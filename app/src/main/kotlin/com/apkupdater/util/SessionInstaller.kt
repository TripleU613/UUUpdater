package com.apkupdater.util

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
import android.util.Log
import androidx.core.content.ContextCompat.startActivity
import com.apkupdater.BuildConfig
import com.apkupdater.data.ui.AppInstallProgress
import com.apkupdater.ui.activity.MainActivity
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.InputStream
import java.io.OutputStream
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
        pmHelpLines?.any { it.contains("install-multiple") } ?: true
    }
    private val verificationFlags: String by lazy {
        val help = pmHelpLines ?: return@lazy ""
        buildList {
            if (help.any { it.contains("--skip-verification") }) add("--skip-verification")
            if (help.any { it.contains("--skip-install-verification") }) add("--skip-install-verification")
        }.joinToString(" ")
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

    fun rootInstall(file: File): RootInstallResult = rootInstall(listOf(file))

    fun rootInstall(files: List<File>): RootInstallResult =
        rootInstall(files.map { RootFile(it) })

    @JvmName("rootInstallWithMetadata")
    fun rootInstall(files: List<RootFile>): RootInstallResult {
        if (files.isEmpty()) return RootInstallResult(false, emptyList(), emptyList(), -1)
        if (files.size > 1 && !installMultipleSupported) {
            Log.w(SESSION_INSTALLER_TAG, "pm install-multiple unavailable; using session-based fallback.")
            return rootInstallViaSession(files)
        }

        val includeVerificationFlags = verificationFlags.isNotEmpty()
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val command = buildInstallCommand(files, includeVerificationFlags)
        var result = Shell.cmd(command).exec()
        stdout += result.out
        stderr += result.err

        if (!result.isSuccess && includeVerificationFlags && hasUnknownOption(result.out, result.err)) {
            Log.w(SESSION_INSTALLER_TAG, "Skip-verification flags unsupported; retrying without them.")
            val retryCommand = buildInstallCommand(files, includeVerificationFlags = false)
            result = Shell.cmd(retryCommand).exec()
            stdout += result.out
            stderr += result.err
        }

        if (!result.isSuccess && files.size > 1 && (result.err + result.out).any { it.contains("Unknown command: install-multiple") }) {
            Log.w(SESSION_INSTALLER_TAG, "pm install-multiple rejected by pm; using session-based fallback.")
            return rootInstallViaSession(files, prefetchedStdout = stdout, prefetchedStderr = stderr)
        }
        return RootInstallResult(result.isSuccess, stdout, stderr, result.code)
    }

    private fun buildInstallCommand(files: List<RootFile>, includeVerificationFlags: Boolean): String {
        val verification = if (includeVerificationFlags) verificationFlags else ""
        return if (files.size == 1) {
            buildString {
                append("pm install -r ")
                if (verification.isNotEmpty()) {
                    append(verification)
                    append(' ')
                }
                append(files.single().file.quotedPath())
            }
        } else {
            val paths = files.joinToString(" ") { it.file.quotedPath() }
            buildString {
                append("pm install-multiple -r ")
                if (verification.isNotEmpty()) {
                    append(verification)
                    append(' ')
                }
                append(paths)
            }
        }
    }

    private fun rootInstallViaSession(
        files: List<RootFile>,
        prefetchedStdout: List<String> = emptyList(),
        prefetchedStderr: List<String> = emptyList()
    ): RootInstallResult {
        val stdout = mutableListOf<String>().apply { addAll(prefetchedStdout) }
        val stderr = mutableListOf<String>().apply { addAll(prefetchedStderr) }

        val includeVerificationFlags = verificationFlags.isNotEmpty()
        val baseCreateCommand = "cmd package install-create -r"
        val createCommand = if (includeVerificationFlags) "$baseCreateCommand $verificationFlags" else baseCreateCommand
        var createResult = Shell.cmd(createCommand).exec()
        stdout += createResult.out
        stderr += createResult.err
        if (!createResult.isSuccess && includeVerificationFlags && hasUnknownOption(createResult.out, createResult.err)) {
            Log.w(SESSION_INSTALLER_TAG, "install-create skip-verification flags unsupported; retrying without them.")
            createResult = Shell.cmd(baseCreateCommand).exec()
            stdout += createResult.out
            stderr += createResult.err
        }
        if (!createResult.isSuccess) {
            Log.e(SESSION_INSTALLER_TAG, "install-create failed (code=${createResult.code})")
            return RootInstallResult(false, stdout, stderr, createResult.code)
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
        return RootInstallResult(commitResult.isSuccess, stdout, stderr, commitResult.code)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if(!context.packageManager.canRequestPackageInstalls()) {
                val uri = Uri.parse("package:${BuildConfig.APPLICATION_ID}")
                val intent = Intent(ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(context, intent, null)
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
        Log.d("SessionInstaller", "Emitting progress: id=$id, progress=$currentProgress, bytesCopied=$bytesCopied, total=$total")
        installLog.emitProgress(AppInstallProgress(id, progress = currentProgress))
        bytes = read(buffer)
    }
    Log.d("SessionInstaller", "Finished copying: id=$id, totalBytesCopied=$bytesCopied")
    return bytesCopied
}

private fun File.quotedPath() = "\"${absolutePath}\""

private val INSTALL_SESSION_REGEX = Regex(".*\\[(\\d+)]")
private const val SESSION_INSTALLER_TAG = "SessionInstaller"

private fun hasUnknownOption(stdout: List<String>, stderr: List<String>): Boolean =
    (stdout + stderr).any { line ->
        line.contains("Unknown option", ignoreCase = true) ||
            line.contains("Unknown argument", ignoreCase = true)
    }
