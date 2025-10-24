package com.tripleu.updater.util

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
import com.tripleu.updater.util.NoOpLog as Log
import com.tripleu.updater.BuildConfig
import com.tripleu.updater.data.ui.AppInstallProgress
import com.tripleu.updater.ui.activity.MainActivity
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.jvm.JvmName


class SessionInstaller(
    private val context: Context,
    private val installLog: InstallLog
) {

    companion object {
        const val INSTALL_ACTION = "installAction"
    }

    private val installMutex = AtomicBoolean(false)
    private val pmHelpLines: List<String>? by lazy {
        val result = Shell.cmd("pm help").exec()
        if (!result.isSuccess) {
            Log.w(SESSION_INSTALLER_TAG, "Failed to query pm help (code=${result.code}); using conservative feature detection.")
            null
        } else {
            result.out
        }
    }
    private val installMultipleSupported: Boolean by lazy {
        pmHelpLines?.any { it.contains("install-multiple") } ?: false
    }
    private val verificationFlags: String by lazy {
        val help = pmHelpLines ?: return@lazy ""
        buildList {
            if (help.any { it.contains("--skip-verification") }) add("--skip-verification")
            if (help.any { it.contains("--skip-install-verification") }) add("--skip-install-verification")
        }.joinToString(" ")
    }
    private val allowDuplicatePermissionsFlag: String? by lazy {
        val help = pmHelpLines
        when {
            help == null -> null
            help.any { it.contains("--allow-duplicate-permissions") } -> "--allow-duplicate-permissions"
            else -> null
        }
    }

    suspend fun install(id: Int, packageName: String, stream: InputStream) =
        install(id, packageName, listOf(stream))

    private suspend fun install(id: Int, packageName: String, streams: List<InputStream>) {
        val packageInstaller: PackageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)

        if (Build.VERSION.SDK_INT > 24) {
            params.setOriginatingUid(android.os.Process.myUid())
        }

        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }

        val sessionId = packageInstaller.createSession(params)
        var bytes = 0L
        packageInstaller.openSession(sessionId).use { session ->
            streams.forEach {
                session.openWrite("$packageName.${randomUUID()}", 0, -1).use { output ->
                    bytes += it.copyToAndNotify(output, id, installLog, bytes)
                    it.close()
                    session.fsync(output)
                }
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                action = "$INSTALL_ACTION.$id"
            }

            installMutex.lock()
            val pending = PendingIntent.getActivity(context, 0, intent, FLAG_MUTABLE)
            session.commit(pending.intentSender)
            session.close()
        }
    }

    data class RootInstallResult(
        val success: Boolean,
        val stdout: List<String>,
        val stderr: List<String>,
        val code: Int
    )

    data class RootFile(
        val file: File,
        val sessionName: String = file.name
    )

    fun attachInstalledBase(packageName: String, splits: List<RootFile>): List<RootFile>? {
        if (splits.any { it.sessionName.lowercase(Locale.US).startsWith("base") }) return splits
        val pathResult = Shell.cmd("pm path $packageName").exec()
        if (!pathResult.isSuccess && pathResult.out.isEmpty()) {
            Log.w(SESSION_INSTALLER_TAG, "Unable to resolve installed base for $packageName (code=${pathResult.code}).")
            return null
        }
        val basePath = pathResult.out.asSequence()
            .map { it.substringAfter("package:", "").trim() }
            .firstOrNull { it.isNotEmpty() && !it.contains("split", ignoreCase = true) }
            ?: run {
                Log.w(SESSION_INSTALLER_TAG, "No base path found for $packageName via pm path.")
                return null
            }
        val temp = File(context.cacheDir, "${packageName.replace('.', '_')}_base_${randomUUID()}.apk")
        val copyResult = Shell.cmd("cat ${File(basePath).quotedPath()} > ${temp.quotedPath()}").exec()
        if (!copyResult.isSuccess) {
            Log.w(SESSION_INSTALLER_TAG, "Failed to copy base APK from $basePath (code=${copyResult.code}).")
            temp.delete()
            return null
        }
        val pm = context.packageManager
        val baseInfo = pm.getPackageArchiveInfo(temp.absolutePath, 0)
        val targetVersion = baseInfo?.longVersionCode ?: -1
        val splitVersion = splits.firstNotNullOfOrNull { root ->
            pm.getPackageArchiveInfo(root.file.absolutePath, 0)?.longVersionCode
        } ?: targetVersion
        if (targetVersion > 0 && splitVersion > 0 && targetVersion != splitVersion) {
            Log.w(
                SESSION_INSTALLER_TAG,
                "Installed base version ($targetVersion) mismatches split version ($splitVersion); discarding attached base."
            )
            temp.delete()
            return null
        }
        Log.d(SESSION_INSTALLER_TAG, "Attached installed base split from $basePath for $packageName (version=$targetVersion).")
        val result = mutableListOf<RootFile>()
        result += RootFile(temp, "base.apk")
        result.addAll(splits)
        return result
    }

    fun rootInstall(file: File): RootInstallResult = rootInstall(
        packageName = null,
        files = listOf(RootFile(file)),
        allowDuplicateResolution = true,
        allowDuplicatePermission = allowDuplicatePermissionsFlag != null
    )

    fun rootInstall(files: List<File>): RootInstallResult =
        rootInstall(
            packageName = null,
            files = files.map { RootFile(it) },
            allowDuplicateResolution = true,
            allowDuplicatePermission = allowDuplicatePermissionsFlag != null
        )

    @JvmName("rootInstallWithMetadata")
    fun rootInstall(files: List<RootFile>): RootInstallResult =
        rootInstall(
            packageName = null,
            files = files,
            allowDuplicateResolution = true,
            allowDuplicatePermission = allowDuplicatePermissionsFlag != null
        )

    fun rootInstall(packageName: String, files: List<RootFile>): RootInstallResult =
        rootInstall(
            packageName,
            files,
            allowDuplicateResolution = true,
            allowDuplicatePermission = allowDuplicatePermissionsFlag != null
        )

    private fun rootInstall(
        packageName: String?,
        files: List<RootFile>,
        allowDuplicateResolution: Boolean = true,
        allowDuplicatePermission: Boolean = false,
        duplicateRetryCount: Int = 0
    ): RootInstallResult {
        if (duplicateRetryCount > MAX_DUPLICATE_RETRIES) {
            Log.w(
                SESSION_INSTALLER_TAG,
                "Duplicate-permission retry limit reached for ${packageName ?: "unknown"}."
            )
            return RootInstallResult(false, emptyList(), emptyList(), -1)
        }
        if (files.isEmpty()) return RootInstallResult(false, emptyList(), emptyList(), -1)
        if (files.size > 1 && !installMultipleSupported) {
            Log.w(SESSION_INSTALLER_TAG, "pm install-multiple unavailable; using session-based fallback.")
            return rootInstallViaSession(
                packageName = packageName,
                files = files,
                allowDuplicateResolution = allowDuplicateResolution,
                allowDuplicatePermission = allowDuplicatePermission,
                duplicateRetryCount = duplicateRetryCount
            )
        }

        val includeVerificationFlags = verificationFlags.isNotEmpty()
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        Log.d(
            SESSION_INSTALLER_TAG,
            "Initiating root install for ${packageName ?: "unknown"} with ${files.size} split(s): ${
                files.joinToString { it.sessionName }
            } (allowDuplicatePermission=$allowDuplicatePermission)"
        )
        val command = buildInstallCommand(
            files = files,
            includeVerificationFlags = includeVerificationFlags,
            allowDuplicatePermission = allowDuplicatePermission
        )
        var result = Shell.cmd(command).exec()
        stdout += result.out
        stderr += result.err

        if (!result.isSuccess && includeVerificationFlags && hasUnknownOption(result.out, result.err)) {
            Log.w(SESSION_INSTALLER_TAG, "Skip-verification flags unsupported; retrying without them.")
            val retryCommand = buildInstallCommand(
                files = files,
                includeVerificationFlags = false,
                allowDuplicatePermission = allowDuplicatePermission
            )
            result = Shell.cmd(retryCommand).exec()
            stdout += result.out
            stderr += result.err
        }

        if (!result.isSuccess) {
            if (allowDuplicatePermission && hasUnknownOption(result.out, result.err)) {
                Log.w(
                    SESSION_INSTALLER_TAG,
                    "allow-duplicate-permissions flag unsupported; retrying without it."
                )
                return rootInstall(
                    packageName = packageName,
                    files = files,
                    allowDuplicateResolution = allowDuplicateResolution,
                    allowDuplicatePermission = false,
                    duplicateRetryCount = duplicateRetryCount + 1
                )
            }

            if (!allowDuplicatePermission &&
                allowDuplicatePermissionsFlag != null &&
                hasDuplicatePermissionError(stdout, stderr)
            ) {
                Log.w(
                    SESSION_INSTALLER_TAG,
                    "Duplicate permission detected; retrying install with allow-duplicate-permissions."
                )
                return rootInstall(
                    packageName = packageName,
                    files = files,
                    allowDuplicateResolution = allowDuplicateResolution,
                    allowDuplicatePermission = true,
                    duplicateRetryCount = duplicateRetryCount + 1
                )
            }

            if (allowDuplicatePermission && result.code != 0) {
                Log.w(
                    SESSION_INSTALLER_TAG,
                    "Install command failed with allow-duplicate-permissions (code=${result.code}); aborting duplicate retry."
                )
                return RootInstallResult(false, stdout, stderr, result.code)
            }

            if (allowDuplicateResolution &&
                maybeResolveDuplicatePermission(packageName, stdout, stderr)
            ) {
                Log.i(SESSION_INSTALLER_TAG, "Duplicate permission resolved; retrying install command.")
                return rootInstall(
                    packageName = packageName,
                    files = files,
                    allowDuplicateResolution = false,
                    allowDuplicatePermission = allowDuplicatePermission,
                    duplicateRetryCount = duplicateRetryCount + 1
                )
            }

            if (files.size > 1) {
                Log.w(
                    SESSION_INSTALLER_TAG,
                    "pm install-multiple failed (code=${result.code}); using session-based fallback."
                )
                return rootInstallViaSession(
                    packageName = packageName,
                    files = files,
                    prefetchedStdout = stdout,
                    prefetchedStderr = stderr,
                    allowDuplicateResolution = allowDuplicateResolution,
                    allowDuplicatePermission = allowDuplicatePermission,
                    duplicateRetryCount = duplicateRetryCount
                )
            }
        }

        return RootInstallResult(result.isSuccess, stdout, stderr, result.code)
    }

    private fun removeSystemPackage(
        packageName: String,
        stdout: MutableList<String>,
        stderr: MutableList<String>
    ): Boolean {
        val pathResult = Shell.cmd("pm path $packageName").exec()
        stdout += pathResult.out
        stderr += pathResult.err
        val paths = pathResult.out.mapNotNull { line ->
            val path = line.substringAfter("package:", "").trim()
            path.takeIf { it.isNotEmpty() }
        }
        if (paths.isEmpty()) {
            Log.w(SESSION_INSTALLER_TAG, "pm path returned no locations for $packageName; using directory search fallback.")
        } else {
            Log.i(SESSION_INSTALLER_TAG, "pm path for $packageName -> $paths")
        }
        val removablePaths = paths.filter { path -> REMOVABLE_PATH_PREFIXES.any { prefix -> path.startsWith(prefix) } }
        val directories = removablePaths.mapNotNull { path ->
            runCatching { File(path).parentFile?.absolutePath }.getOrNull()
        }.filterNotNull().distinct()

        val commands = mutableListOf<String>()
        commands += REMOUNT_RW_COMMANDS
        directories.forEach { directory ->
            commands += "rm -rf ${File(directory).quotedPath()}"
        }
        removablePaths.forEach { path ->
            commands += "rm -f ${File(path).quotedPath()}"
        }
        val baseToken = packageName.substringAfterLast('.')
        commands += SEARCH_PATH_COMMAND.replace("%TOKEN%", baseToken)
        commands += "pm uninstall $packageName"
        commands += "pm uninstall --user all $packageName"
        commands += "pm disable-user --user 0 $packageName"
        commands += REMOUNT_RO_COMMANDS

        val removalResult = Shell.cmd(*commands.toTypedArray()).exec()
        stdout += removalResult.out
        stderr += removalResult.err
        return !packageExists(packageName)
    }

    private fun packageExists(packageName: String): Boolean {
        val result = Shell.cmd("pm path $packageName").exec()
        if (result.out.any { it.startsWith("package:") }) return true
        if (!result.isSuccess) return false
        return result.out.any { it.contains("package:") }
    }

    private fun buildInstallCommand(
        files: List<RootFile>,
        includeVerificationFlags: Boolean,
        allowDuplicatePermission: Boolean
    ): String {
        val verification = if (includeVerificationFlags) verificationFlags else ""
        val duplicate = if (allowDuplicatePermission) allowDuplicatePermissionsFlag.orEmpty() else ""
        fun StringBuilder.appendFlags() {
            if (verification.isNotEmpty()) {
                append(verification)
                append(' ')
            }
            if (duplicate.isNotEmpty()) {
                append(duplicate)
                append(' ')
            }
        }
        return if (files.size == 1) {
            buildString {
                append("pm install -r ")
                appendFlags()
                append(files.single().file.quotedPath())
            }
        } else {
            val paths = files.joinToString(" ") { it.file.quotedPath() }
            buildString {
                append("pm install-multiple -r ")
                appendFlags()
                append(paths)
            }
        }
    }

    private fun rootInstallViaSession(
        packageName: String?,
        files: List<RootFile>,
        prefetchedStdout: List<String> = emptyList(),
        prefetchedStderr: List<String> = emptyList(),
        allowDuplicateResolution: Boolean = true,
        allowDuplicatePermission: Boolean = false,
        duplicateRetryCount: Int = 0
    ): RootInstallResult {
        val stdout = mutableListOf<String>().apply { addAll(prefetchedStdout) }
        val stderr = mutableListOf<String>().apply { addAll(prefetchedStderr) }

        val includeVerificationFlags = verificationFlags.isNotEmpty()
        val baseCreateCommand = "cmd package install-create -r"
        val duplicateFlag = if (allowDuplicatePermission) allowDuplicatePermissionsFlag else null
        val createCommand = buildString {
            append(baseCreateCommand)
            if (includeVerificationFlags && verificationFlags.isNotEmpty()) {
                append(' ')
                append(verificationFlags)
            }
            if (!duplicateFlag.isNullOrEmpty()) {
                append(' ')
                append(duplicateFlag)
            }
        }.trimEnd()
        var createResult = Shell.cmd(createCommand).exec()
        stdout += createResult.out
        stderr += createResult.err
        if (!createResult.isSuccess) {
            if (allowDuplicatePermission && hasUnknownOption(createResult.out, createResult.err) && !duplicateFlag.isNullOrEmpty()) {
                Log.w(
                    SESSION_INSTALLER_TAG,
                    "allow-duplicate-permissions unsupported for install-create; retrying without it."
                )
                return rootInstallViaSession(
                    packageName = packageName,
                    files = files,
                    prefetchedStdout = stdout,
                    prefetchedStderr = stderr,
                    allowDuplicateResolution = allowDuplicateResolution,
                    allowDuplicatePermission = false,
                    duplicateRetryCount = duplicateRetryCount + 1
                )
            }

            if (includeVerificationFlags && hasUnknownOption(createResult.out, createResult.err)) {
                Log.w(SESSION_INSTALLER_TAG, "install-create skip-verification flags unsupported; retrying without them.")
                val retryCommand = buildString {
                    append(baseCreateCommand)
                    if (!duplicateFlag.isNullOrEmpty()) {
                        append(' ')
                        append(duplicateFlag)
                    }
                }.trimEnd()
                createResult = Shell.cmd(retryCommand).exec()
                stdout += createResult.out
                stderr += createResult.err
                if (!createResult.isSuccess) {
                    if (allowDuplicatePermission) {
                        Log.w(
                            SESSION_INSTALLER_TAG,
                            "install-create failed with allow-duplicate-permissions (code=${createResult.code}); retrying without the flag."
                        )
                        return rootInstallViaSession(
                            packageName = packageName,
                            files = files,
                            prefetchedStdout = stdout,
                            prefetchedStderr = stderr,
                            allowDuplicateResolution = allowDuplicateResolution,
                            allowDuplicatePermission = false,
                            duplicateRetryCount = duplicateRetryCount + 1
                        )
                    }
                    Log.e(SESSION_INSTALLER_TAG, "install-create failed (code=${createResult.code})")
                    return RootInstallResult(false, stdout, stderr, createResult.code)
                }
            } else {
                if (allowDuplicatePermission) {
                    Log.w(
                        SESSION_INSTALLER_TAG,
                        "install-create failed with allow-duplicate-permissions (code=${createResult.code}); retrying without the flag."
                    )
                    return rootInstallViaSession(
                        packageName = packageName,
                        files = files,
                        prefetchedStdout = stdout,
                        prefetchedStderr = stderr,
                        allowDuplicateResolution = allowDuplicateResolution,
                        allowDuplicatePermission = false,
                        duplicateRetryCount = duplicateRetryCount + 1
                    )
                }
                Log.e(SESSION_INSTALLER_TAG, "install-create failed (code=${createResult.code})")
                return RootInstallResult(false, stdout, stderr, createResult.code)
            }
        }
        val sessionId = createResult.out.firstNotNullOfOrNull { line ->
            INSTALL_SESSION_REGEX.find(line)?.groupValues?.getOrNull(1)
        } ?: run {
            Log.e(SESSION_INSTALLER_TAG, "Unable to parse session id from install-create output: ${createResult.out}")
            return RootInstallResult(false, stdout, stderr, createResult.code)
        }

        files.forEach { rootFile ->
            val writeCmd = "cmd package install-write -S ${rootFile.file.length()} $sessionId ${rootFile.sessionName} ${rootFile.file.quotedPath()}"
            val writeResult = Shell.cmd(writeCmd).exec()
            stdout += writeResult.out
            stderr += writeResult.err
            if (!writeResult.isSuccess) {
                Log.e(SESSION_INSTALLER_TAG, "install-write failed for ${rootFile.sessionName} (code=${writeResult.code})")
                val abandonResult = Shell.cmd("cmd package install-abandon $sessionId").exec()
                stdout += abandonResult.out
                stderr += abandonResult.err
                return RootInstallResult(false, stdout, stderr, writeResult.code)
            }
        }

        val commitResult = Shell.cmd("cmd package install-commit $sessionId").exec()
        stdout += commitResult.out
        stderr += commitResult.err

        if (!commitResult.isSuccess) {
            if (allowDuplicatePermission && hasUnknownOption(commitResult.out, commitResult.err)) {
                Log.w(
                    SESSION_INSTALLER_TAG,
                    "allow-duplicate-permissions unsupported during install-commit; aborting duplicate retry."
                )
                return RootInstallResult(false, stdout, stderr, commitResult.code)
            }

            if (!allowDuplicatePermission &&
                allowDuplicatePermissionsFlag != null &&
                hasDuplicatePermissionError(stdout, stderr)
            ) {
                Log.w(
                    SESSION_INSTALLER_TAG,
                    "Duplicate permission detected during session commit; retrying with allow-duplicate-permissions."
                )
                return rootInstall(
                    packageName = packageName,
                    files = files,
                    allowDuplicateResolution = allowDuplicateResolution,
                    allowDuplicatePermission = true,
                    duplicateRetryCount = duplicateRetryCount + 1
                )
            }

            if (allowDuplicatePermission && commitResult.code != 0) {
                Log.w(
                    SESSION_INSTALLER_TAG,
                    "Install commit failed with allow-duplicate-permissions (code=${commitResult.code}); aborting duplicate retry."
                )
                return RootInstallResult(false, stdout, stderr, commitResult.code)
            }

            if (allowDuplicateResolution &&
                maybeResolveDuplicatePermission(packageName, stdout, stderr)
            ) {
                Log.i(SESSION_INSTALLER_TAG, "Duplicate permission resolved during session commit; retrying install.")
                return rootInstall(
                    packageName = packageName,
                    files = files,
                    allowDuplicateResolution = false,
                    allowDuplicatePermission = allowDuplicatePermission,
                    duplicateRetryCount = duplicateRetryCount + 1
                )
            }
        }

        return RootInstallResult(commitResult.isSuccess, stdout, stderr, commitResult.code)
    }

    private fun maybeResolveDuplicatePermission(
        packageName: String?,
        stdout: MutableList<String>,
        stderr: MutableList<String>
    ): Boolean {
        val lines = stdout + stderr
        val match = lines.firstNotNullOfOrNull { line -> DUPLICATE_PERMISSION_REGEX.find(line) } ?: return false

        val permission = match.groupValues.getOrNull(1).orEmpty()
        val owner = match.groupValues.getOrNull(2).orEmpty()
        if (owner.isBlank()) return false
        if (packageName != null && owner == packageName) return false
        if (owner == "android") {
            Log.w(SESSION_INSTALLER_TAG, "Duplicate permission owned by android; cannot resolve automatically.")
            return false
        }

        val commands = listOf(
            "pm uninstall --user 0 $owner",
            "pm uninstall --user all $owner",
            "pm uninstall $owner",
            "pm disable-user --user 0 $owner"
        )
        commands.forEach { command ->
            val result = Shell.cmd(command).exec()
            stdout += result.out
            stderr += result.err
            if (!packageExists(owner)) {
                Log.i(
                    SESSION_INSTALLER_TAG,
                    "Resolved duplicate permission $permission owned by $owner via `$command`."
                )
                return true
            }
        }

        if (removeSystemPackage(owner, stdout, stderr)) {
            Log.i(
                SESSION_INSTALLER_TAG,
                "Removed system package $owner to resolve duplicate permission $permission."
            )
            return true
        }

        Log.w(
            SESSION_INSTALLER_TAG,
            "Unable to resolve duplicate permission $permission owned by $owner automatically."
        )
        return false
    }

    suspend fun installFromFiles(id: Int, packageName: String, files: List<File>) {
        val streams = files.map { it.inputStream() }
        try {
            install(id, packageName, streams)
        } finally {
            streams.forEach { runCatching { it.close() } }
        }
    }

    fun isPrivApp(packageName: String): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        val source = info.sourceDir.orEmpty()
        source.contains("priv-app")
    }.getOrDefault(false)

    fun installAsPrivApp(packageName: String, files: List<RootFile>): RootInstallResult {
        if (files.isEmpty()) return RootInstallResult(false, emptyList(), listOf("No files to install"), -1)
        val appInfo = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return RootInstallResult(false, emptyList(), listOf("Package $packageName not found"), -1)
        val sourceDir = appInfo.sourceDir ?: return RootInstallResult(false, emptyList(), listOf("No sourceDir for $packageName"), -1)
        if (!sourceDir.contains("/priv-app/")) {
            return RootInstallResult(true, listOf("Package is not priv-app; skipping priv sync"), emptyList(), 0)
        }
        val destinationDirectory = File(sourceDir).parentFile
            ?: return RootInstallResult(false, emptyList(), listOf("Unable to resolve destination directory for $packageName"), -1)
        val existing = mutableMapOf<String, File>()
        existing[File(sourceDir).name] = File(sourceDir)
        appInfo.splitSourceDirs?.forEach { path ->
            existing[File(path).name] = File(path)
        }

        val commands = mutableListOf<String>()
        commands += "mount -o rw,remount /system || mount -o rw,remount /"
        files.forEach { rootFile ->
            val destination = existing[rootFile.sessionName] ?: File(destinationDirectory, rootFile.sessionName)
            destination.parentFile?.let { parent ->
                commands += "mkdir -p ${parent.quotedPath()}"
            }
            commands += "rm -f ${destination.quotedPath()}"
            commands += "cp ${rootFile.file.quotedPath()} ${destination.quotedPath()}"
            commands += "chmod 0644 ${destination.quotedPath()}"
            commands += "chown root:root ${destination.quotedPath()}"
            commands += "restorecon ${destination.quotedPath()}"
        }
        commands += "mount -o ro,remount /system || mount -o ro,remount /"

        val result = Shell.cmd(*commands.toTypedArray()).exec()
        return RootInstallResult(result.isSuccess, result.out, result.err, result.code)
    }

    fun finish() = installMutex.unlock()

    fun checkPermission(): Boolean {
        if (BuildConfig.TECHEAZ_PRIVILEGED_INSTALLER) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val uri = Uri.parse("package:${BuildConfig.APPLICATION_ID}")
                val intent = Intent(ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return false
            }
        }
        return true
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun installXapk(id: Int, packageName: String, stream: InputStream) {
        // Copy file to disk.
        // TODO: Find a way to do this without saving file
        val file = File(context.cacheDir, randomUUID())
        stream.copyTo(file.outputStream())

        // Get entries
        val zip = ZipFile(file)
        val entries = zip.entries().toList()

        // Install all the apks
        // TODO: Try to install only needed apks
        val apks = entries.filter { it.name.contains(".apk") }.map { zip.getInputStream(it) }
        install(id, packageName, apks)

        // Cleanup
        zip.close()
        file.delete()
    }

    suspend fun playInstall(id: Int, packageName: String, streams: List<InputStream>) =
        install(id, packageName, streams)

}

fun InputStream.copyToAndNotify(out: OutputStream, id: Int, installLog: InstallLog, total: Long, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
    var bytesCopied: Long = 0
    val buffer = ByteArray(bufferSize)
    var bytes = read(buffer)
    while (bytes >= 0) {
        out.write(buffer, 0, bytes)
        bytesCopied += bytes
        val currentProgress = total + bytesCopied
        installLog.emitProgress(AppInstallProgress(id, progress = currentProgress))
        bytes = read(buffer)
    }
    return bytesCopied
}

private fun File.quotedPath() = "\"${absolutePath}\""

private val DUPLICATE_PERMISSION_REGEX =
    Regex("INSTALL_FAILED_DUPLICATE_PERMISSION:.*?permission\\s+([\\w\\._]+)\\s+already owned by\\s+([\\w\\._]+)", RegexOption.IGNORE_CASE)

private val INSTALL_SESSION_REGEX = Regex(".*\\[(\\d+)]")
private const val SESSION_INSTALLER_TAG = "SessionInstaller"

private val REMOVABLE_PATH_PREFIXES = listOf(
    "/data/app/",
    "/system/",
    "/system_ext/",
    "/product/",
    "/vendor/"
)

private val REMOUNT_RW_COMMANDS = listOf(
    "mount -o remount,rw / || true",
    "mount -o rw,remount / || true",
    "mount -o rw,remount /system || true",
    "mount -o rw,remount /system_root || true",
    "mount -o rw,remount /system_ext || true",
    "mount -o rw,remount /product || true",
    "mount -o rw,remount /vendor || true"
)

private val REMOUNT_RO_COMMANDS = listOf(
    "mount -o remount,ro / || true",
    "mount -o ro,remount / || true",
    "mount -o ro,remount /system || true",
    "mount -o ro,remount /system_root || true",
    "mount -o ro,remount /system_ext || true",
    "mount -o ro,remount /product || true",
    "mount -o ro,remount /vendor || true"
)

private const val SEARCH_PATH_COMMAND =
    "for dir in /system /system_ext /product /vendor; do if [ -d \"${'$'}dir\" ]; then find \"${'$'}dir\" -maxdepth 5 -type f -iname '*%TOKEN%*.apk' -delete 2>/dev/null; fi; done"

private fun hasUnknownOption(stdout: List<String>, stderr: List<String>): Boolean =
    (stdout + stderr).any { line ->
        line.contains("Unknown option", ignoreCase = true) ||
            line.contains("Unknown argument", ignoreCase = true)
    }

private fun hasDuplicatePermissionError(stdout: List<String>, stderr: List<String>): Boolean =
    (stdout + stderr).any { line ->
        line.contains("INSTALL_FAILED_DUPLICATE_PERMISSION", ignoreCase = true)
    }

private const val MAX_DUPLICATE_RETRIES = 1
