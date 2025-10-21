package com.apkupdater.viewmodel

import android.util.Log
import androidx.compose.ui.platform.UriHandler
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apkupdater.R
import com.apkupdater.data.snack.TextSnack
import com.apkupdater.data.ui.ApkMirrorSource
import com.apkupdater.data.ui.AppInstallProgress
import com.apkupdater.data.ui.AppInstallStatus
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.InstallStage
import com.apkupdater.data.ui.Link
import com.apkupdater.prefs.Prefs
import com.apkupdater.util.Downloader
import com.apkupdater.util.InstallLog
import com.apkupdater.util.InstallModePrompt
import com.apkupdater.util.SessionInstaller
import com.apkupdater.util.SessionInstaller.RootInstallResult
import com.apkupdater.util.SessionInstaller.RootFile
import com.apkupdater.util.SnackBar
import com.apkupdater.util.Stringer
import com.topjohnwu.superuser.Shell
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.min


abstract class InstallViewModel(
    private val downloader: Downloader,
    private val installer: SessionInstaller,
    protected val prefs: Prefs,
    private val snackBar: SnackBar,
    private val stringer: Stringer,
    private val installLog: InstallLog
): ViewModel() {

    fun install(update: AppUpdate, uriHandler: UriHandler) {
        Log.d("InstallViewModel", "install() called for: ${update.name}, source: ${update.source}, link: ${update.link}")
        when (update.source) {
            ApkMirrorSource -> {
                Log.d("InstallViewModel", "ApkMirror source - opening URI in browser")
                uriHandler.openUri((update.link as Link.Url).link)
            }
            else -> {
                val usePackageInstaller = prefs.packageInstallerInstall.get()
                val useRootInstall = prefs.rootInstall.get() && update.link !is Link.Empty
                when {
                    usePackageInstaller -> {
                        Log.d("InstallViewModel", "Starting package installer install (link type: ${update.link::class.simpleName})")
                        downloadAndInstall(update)
                    }
                    useRootInstall -> {
                        Log.d("InstallViewModel", "Starting root install (link type: ${update.link::class.simpleName})")
                        downloadAndRootInstall(update)
                    }
                    else -> {
                        Log.d("InstallViewModel", "Starting normal install (link type: ${update.link::class.simpleName})")
                        downloadAndInstall(update)
                    }
                }
            }
        }
    }

    private val activeInstalls = ConcurrentHashMap<Int, AppUpdate>()
    private val blockedInstallIds = ConcurrentHashMap.newKeySet<Int>()

    protected fun registerInstall(update: AppUpdate) {
        activeInstalls[update.id] = update
    }

    protected fun clearTrackedInstall(id: Int) {
        activeInstalls.remove(id)
        blockedInstallIds.remove(id)
    }

    protected fun consumeBlockedInstall(id: Int): Boolean = blockedInstallIds.remove(id)

    protected fun trackedInstall(id: Int): AppUpdate? = activeInstalls[id]

    private fun markInstallBlocked(id: Int) {
        blockedInstallIds.add(id)
    }

    protected fun subscribeToInstallStatus(updates: List<AppUpdate>) = installLog.status().onEach {
        sendInstallSnack(updates, it)
        if (it.success) {
            finishInstall(it.id).join()
        } else {
            installLog.emitProgress(AppInstallProgress(it.id, 0L, 0L, InstallStage.Idle))
            cancelInstall(it.id).join()
        }
    }.launchIn(viewModelScope)

    protected fun subscribeToInstallProgress(
        block: (AppInstallProgress) -> Unit
    ) = installLog.progress().onEach {
        block(it)
    }.launchIn(viewModelScope)

    protected suspend fun downloadAndRootInstall(id: Int, packageName: String, link: Link) = runCatching {
        installLog.emitStage(id, InstallStage.Pending)
        when (link) {
            Link.Empty -> {
                snackBar.snackBar(
                    viewModelScope,
                    TextSnack(stringer.get(R.string.root_install_not_supported))
                )
                installLog.emitStage(id, InstallStage.Idle)
                cancelInstall(id)
            }
            is Link.Url -> {
                val total = link.size.takeIf { it > 0 } ?: downloader.getContentLength(link.link).takeIf { it > 0 }
                startProgress(id, total)
                val (downloaded, _) = downloadFile(
                    id = id,
                    url = link.link,
                    total = total,
                    offset = 0L,
                    sessionName = sessionNameFromUrl(link.link)
                )
                val extracted = runCatching { extractApksFromArchive(id, downloaded.file) }.getOrDefault(emptyList())
                if (extracted.isNotEmpty()) {
                    try {
                        performRootInstall(id, packageName, extracted) { downloaded.file.delete() }
                    } finally {
                        extracted.forEach { it.file.delete() }
                    }
                } else {
                    performRootInstall(id, packageName, listOf(downloaded))
                }
            }
            is Link.Xapk -> {
                val total = downloader.getContentLength(link.link).takeIf { it > 0 }
                startProgress(id, total)
                val (archive, _) = downloadFile(
                    id = id,
                    url = link.link,
                    total = total,
                    offset = 0L,
                    sessionName = "package.xapk"
                )
                val extracted = mutableListOf<RootFile>()
                try {
                    extracted += extractApksFromArchive(id, archive.file)
                    performRootInstall(id, packageName, extracted) { archive.file.delete() }
                } finally {
                    archive.file.delete()
                    extracted.forEach { it.file.delete() }
                }
            }
            is Link.Play -> {
                val remoteFiles = link.getInstallFiles()
                if (remoteFiles.isEmpty()) {
                    Log.w("InstallViewModel", "No files returned for Play install.")
                    installLog.emitStage(id, InstallStage.Idle)
                    cancelInstall(id)
                } else {
                    val total = remoteFiles.fold(0L) { acc, file ->
                        val length = if (file.size > 0) file.size else downloader.getContentLength(file.url)
                        if (length > 0) acc + length else acc
                    }.takeIf { it > 0 }
                    startProgress(id, total)
                    val localFiles = mutableListOf<RootFile>()
                    var offset = 0L
                    try {
                        remoteFiles.forEach { remote ->
                            val (file, bytes) = downloadFile(
                                id = id,
                                url = remote.url,
                                total = total,
                                offset = offset,
                                sessionName = remote.name.takeIf { it.isNotBlank() }
                            )
                            offset += bytes
                            localFiles += file
                        }
                        performRootInstall(id, packageName, localFiles)
                    } catch (error: Throwable) {
                        localFiles.forEach { it.file.delete() }
                        throw error
                    }
                }
            }
        }
    }.getOrElse {
        Log.e("InstallViewModel", "Error in downloadAndRootInstall.", it)
        cancelInstall(id)
    }

    protected suspend fun downloadAndInstall(id: Int, packageName: String, link: Link) = runCatching {
        installLog.emitStage(id, InstallStage.Pending)
        when (link) {
            Link.Empty -> { Log.e("InstallViewModel", "downloadAndInstall: Unsupported.")}
            is Link.Play -> {
                val files = link.getInstallFiles()
                installLog.emitProgress(AppInstallProgress(id, 0L, files.sumOf { it.size }, InstallStage.Downloading))
                installer.playInstall(id, packageName, files.map { downloader.downloadStream(it.url)!! })
                installLog.emitStage(id, InstallStage.Installing)
            }
            is Link.Url -> {
                val size = if (link.size > 0) link.size else downloader.getContentLength(link.link)
                installLog.emitProgress(AppInstallProgress(id, 0L, size, InstallStage.Downloading))
                installer.install(id, packageName, downloader.downloadStream(link.link)!!)
                installLog.emitStage(id, InstallStage.Installing)
            }
            is Link.Xapk -> {
                installLog.emitStage(id, InstallStage.Downloading)
                installer.installXapk(id, packageName, downloader.downloadStream(link.link)!!)
                installLog.emitStage(id, InstallStage.Installing)
            }
        }
    }.getOrElse {
        Log.e("InstallViewModel", "Error in downloadAndInstall.", it)
        cancelInstall(id)
    }

    private fun sendInstallSnack(updates: List<AppUpdate>, log: AppInstallStatus) {
        if (log.snack) {
            updates.find { log.id == it.id }?.let { app ->
                val text = when {
                    log.success -> stringer.get(R.string.install_success, app.name)
                    !log.message.isNullOrBlank() -> log.message
                    else -> stringer.get(R.string.install_failure, app.name)
                }
                snackBar.snackBar(viewModelScope, TextSnack(text))
            }
        }
    }

    private fun startProgress(id: Int, total: Long?) =
        installLog.emitProgress(AppInstallProgress(id, 0L, total, InstallStage.Downloading))

    private fun downloadFile(
        id: Int,
        url: String,
        total: Long?,
        offset: Long,
        sessionName: String? = null
    ): Pair<RootFile, Long> {
        var bytesCopied = 0L
        val file = downloader.download(url) { bytes ->
            bytesCopied = bytes
            installLog.emitProgress(
                AppInstallProgress(
                    id = id,
                    progress = progressValue(offset + bytes, total),
                    total = total,
                    stage = InstallStage.Downloading
                )
            )
        }
        if (bytesCopied == 0L) {
            val length = file.length()
            if (length > 0) {
                bytesCopied = length
                installLog.emitProgress(
                    AppInstallProgress(
                        id = id,
                        progress = progressValue(offset + length, total),
                        total = total,
                        stage = InstallStage.Downloading
                    )
                )
            }
        }
        val resolvedName = sessionName?.takeIf { it.isNotBlank() }
            ?: sessionNameFromUrl(url)
            ?: file.name
        return RootFile(file, resolvedName) to bytesCopied
    }

    private fun extractApksFromArchive(id: Int, archive: File): List<RootFile> {
        val extracted = mutableListOf<Pair<String, RootFile>>()
        try {
            ZipFile(archive).use { zip ->
                val apkEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .toList()
                if (apkEntries.isEmpty()) return emptyList()

                val total = apkEntries.size.toLong()
                installLog.emitProgress(
                    AppInstallProgress(
                        id = id,
                        progress = 0L,
                        total = total,
                        stage = InstallStage.Installing
                    )
                )

                var processed = 0L
                apkEntries.forEach { entry ->
                    val tempFile = downloader.createTempFile(".apk")
                    val entryName = entry.name.substringAfterLast('/').ifBlank { tempFile.name }
                    zip.getInputStream(entry).use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    processed += 1
                    installLog.emitProgress(
                        AppInstallProgress(
                            id = id,
                            progress = processed,
                            total = total,
                            stage = InstallStage.Installing
                        )
                    )
                    extracted += entryName to RootFile(tempFile, entryName)
                }
            }
        } catch (error: Throwable) {
            extracted.forEach { runCatching { it.second.file.delete() } }
            throw error
        }
        return extracted
            .sortedBy { (name, _) -> if (name.contains("base", ignoreCase = true)) 0 else 1 }
            .map { it.second }
    }

    private suspend fun performRootInstall(
        id: Int,
        packageName: String,
        files: List<RootFile>,
        cleanup: (() -> Unit)? = null
    ) {
        if (files.isEmpty()) {
            Log.e("InstallViewModel", "performRootInstall called with no files.")
            installLog.emitStage(id, InstallStage.Idle)
            cancelInstall(id)
            cleanup?.invoke()
            return
        }
        val syncPrivApp = shouldSyncPrivApp(packageName)
        try {
            installLog.emitStage(id, InstallStage.Installing)
            val result = installer.rootInstall(files)
            if (result.success) {
                if (syncPrivApp) {
                    val privResult = installer.installAsPrivApp(packageName, files)
                    if (!privResult.success) {
                        val reason = privResult.humanReadableMessage()
                        Log.w("InstallViewModel", "Priv-app sync failed for $packageName: $reason")
                        snackBar.snackBar(
                            viewModelScope,
                            TextSnack(stringer.get(R.string.priv_app_install_failed_with_reason, reason))
                        )
                    }
                }
                clearTrackedInstall(id)
                finishInstall(id)
            } else {
                handleRootInstallFailure(id, packageName, result)
            }
        } finally {
            files.forEach { rootFile ->
                runCatching { if (rootFile.file.exists()) rootFile.file.delete() }
                    .onFailure { Log.w("InstallViewModel", "Failed to delete temp file ${rootFile.file.absolutePath}", it) }
            }
            cleanup?.invoke()
        }
    }

    private suspend fun handleRootInstallFailure(
        id: Int,
        packageName: String,
        result: RootInstallResult
    ) {
        val reason = result.humanReadableMessage()
        trackedInstall(id)?.let { update ->
            if (isCertificateFailure(reason)) {
                blockSourceDueToCertificates(update, reason)
                markInstallBlocked(id)
            }
        }
        Log.e("InstallViewModel", "Root install failed for $packageName: $reason")
        snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.root_install_failed_with_reason, reason)))
        maybePromptInstallModeChange(packageName)
        installLog.emitStage(id, InstallStage.Idle)
        cancelInstall(id).join()
    }

    private fun shouldSyncPrivApp(packageName: String): Boolean =
        prefs.privAppInstall.get() && installer.isPrivApp(packageName)

    private fun maybePromptInstallModeChange(
        packageName: String
    ) {
        if (!prefs.rootInstall.get()) return
        if (prefs.packageInstallerInstall.get()) return
        if (prefs.suppressInstallModePrompt.get()) return
        val hasRoot = runCatching { Shell.getShell().isRoot }.getOrDefault(true)
        if (!hasRoot) {
            installLog.emitInstallModePrompt(InstallModePrompt(packageName))
        }
    }

    private fun RootInstallResult.humanReadableMessage(): String {
        val output = (stderr + stdout).map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            output.isNotEmpty() -> output.joinToString("; ")
            code != 0 -> "exit code $code"
            else -> "unknown error"
        }
    }

    private fun progressValue(progress: Long, total: Long?) = if (total != null) min(progress, total) else progress

    private fun sessionNameFromUrl(url: String): String? {
        val candidate = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        return candidate.takeIf { it.isNotBlank() }
    }

    protected abstract fun downloadAndInstall(update: AppUpdate): Job
    protected abstract fun downloadAndRootInstall(update: AppUpdate): Job
    protected abstract fun cancelInstall(id: Int): Job
    protected abstract fun finishInstall(id: Int): Job

    private fun isCertificateFailure(reason: String): Boolean {
        val normalized = reason.lowercase()
        return normalized.contains("failed to collect certificates") ||
            normalized.contains("install_parse_failed_no_certificates") ||
            normalized.contains("failed verification of meta-inf") ||
            normalized.contains("no verified signerinfos") ||
            normalized.contains("failed to verify signature")
    }

    private fun blockSourceDueToCertificates(update: AppUpdate, reason: String) {
        val key = blockKey(update)
        val blocked = prefs.blockedCertificateSources.get().toMutableSet()
        if (blocked.add(key)) {
            prefs.blockedCertificateSources.put(blocked.toList())
            Log.w(
                "InstallViewModel",
                "Blocking ${update.source.name} for ${update.packageName} due to certificate failure: $reason"
            )
        }
    }

    private fun blockKey(update: AppUpdate) = "${update.source.name}|${update.packageName}"
}
