package com.tripleu.updater.viewmodel

import com.tripleu.updater.util.NoOpLog as Log
import androidx.compose.ui.platform.UriHandler
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tripleu.updater.R
import com.tripleu.updater.data.snack.TextSnack
import com.tripleu.updater.data.ui.AppInstallProgress
import com.tripleu.updater.data.ui.AppInstallStatus
import com.tripleu.updater.data.ui.AppUpdate
import com.tripleu.updater.data.ui.InstallStage
import com.tripleu.updater.data.ui.Link
import com.tripleu.updater.data.ui.blockKey
import com.tripleu.updater.prefs.Prefs
import com.tripleu.updater.util.Downloader
import com.tripleu.updater.util.InstallLog
import com.tripleu.updater.util.InstallModePrompt
import com.tripleu.updater.util.SessionInstaller
import com.tripleu.updater.util.SessionInstaller.RootInstallResult
import com.tripleu.updater.util.SessionInstaller.RootFile
import com.tripleu.updater.util.SnackBar
import com.tripleu.updater.util.Stringer
import com.tripleu.updater.util.XapkInstaller
import com.topjohnwu.superuser.Shell
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.io.buffered
import kotlin.math.min
private const val LOG_ENABLED = false
private const val LOG_TAG = "InstallViewModel"

private inline fun logDebug(message: () -> String) {
    if (LOG_ENABLED) Log.d(LOG_TAG, message())
}

private inline fun logWarn(message: () -> String) {
    if (LOG_ENABLED) Log.w(LOG_TAG, message())
}

private inline fun logWarn(throwable: Throwable, message: () -> String) {
    if (LOG_ENABLED) Log.w(LOG_TAG, message(), throwable)
}

private inline fun logError(message: () -> String) {
    if (LOG_ENABLED) Log.e(LOG_TAG, message())
}

private inline fun logError(throwable: Throwable, message: () -> String) {
    if (LOG_ENABLED) Log.e(LOG_TAG, message(), throwable)
}

abstract class InstallViewModel(
    private val downloader: Downloader,
    private val installer: SessionInstaller,
    protected val prefs: Prefs,
    protected val snackBar: SnackBar,
    protected val stringer: Stringer,
    private val installLog: InstallLog
): ViewModel() {

    fun install(update: AppUpdate, @Suppress("UNUSED_PARAMETER") uriHandler: UriHandler): Job? {
        val blockedKey = update.blockKey()
        if (prefs.blockedInstallFailures.get().contains(blockedKey)) {
            logWarn { "Skipping install for ${update.packageName} from ${update.source.name} (blocked due to previous root failures)." }
            snackBar.snackBar(
                viewModelScope,
                TextSnack(stringer.get(R.string.root_install_blocked_for_version, update.name, update.source.name))
            )
            installLog.emitProgress(AppInstallProgress(update.id, 0L, 0L, InstallStage.Idle))
            return null
        }
        logDebug { "install() called for: ${update.name}, source: ${update.source}, link: ${update.link}" }
        val usePackageInstaller = prefs.packageInstallerInstall.get()
        val useRootInstall = prefs.rootInstall.get() && update.link !is Link.Empty
        emitInitialDownloadProgress(update)
        return when {
            usePackageInstaller -> {
                logDebug { "Starting package installer install (link type: ${update.link::class.simpleName})" }
                downloadAndInstall(update)
            }
            useRootInstall -> {
                logDebug { "Starting root install (link type: ${update.link::class.simpleName})" }
                downloadAndRootInstall(update)
            }
            else -> {
                logDebug { "Starting normal install (link type: ${update.link::class.simpleName})" }
                downloadAndInstall(update)
            }
        }
    }

    private val activeInstalls = ConcurrentHashMap<Int, AppUpdate>()
    private val blockedInstallIds = ConcurrentHashMap.newKeySet<Int>()
    private val installProgress = ConcurrentHashMap<Int, AppInstallProgress>()

    protected fun registerInstall(update: AppUpdate) {
        activeInstalls[update.id] = update
    }

    protected fun clearTrackedInstall(id: Int) {
        activeInstalls.remove(id)
        blockedInstallIds.remove(id)
        installProgress.remove(id)
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
        val existing = installProgress[it.id]
        val merged = AppInstallProgress(
            id = it.id,
            progress = it.progress ?: existing?.progress,
            total = it.total ?: existing?.total,
            stage = it.stage ?: existing?.stage
        )
        if (merged.stage == InstallStage.Idle) {
            installProgress.remove(it.id)
        } else {
            installProgress[it.id] = merged
        }
        block(merged)
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
                val parsed = XapkInstaller.parseArchiveOrNull(
                    id = id,
                    archive = downloaded.file,
                    packageName = packageName,
                    downloader = downloader,
                    installLog = installLog
                )
                if (parsed != null && parsed.splits.isNotEmpty()) {
                    val splits = if (parsed.hasBaseSplit) {
                        parsed.splits
                    } else {
                        installer.attachInstalledBase(packageName, parsed.splits)
                            ?: run {
                                logError { "Archive ${downloaded.file.name} missing base split and installed base is incompatible." }
                                parsed.splits.forEach { runCatching { it.file.delete() } }
                                parsed.obbEntries.forEach { runCatching { it.file.delete() } }
                                snackBar.snackBar(
                                    viewModelScope,
                                    TextSnack(stringer.get(R.string.root_install_missing_base_split))
                                )
                                installLog.emitStage(id, InstallStage.Idle)
                                cancelInstall(id)
                                return@runCatching
                            }
                    }
                    try {
                        performRootInstall(
                            id = id,
                            packageName = packageName,
                            files = splits,
                            cleanup = { downloaded.file.delete() },
                            onSuccess = { XapkInstaller.installObb(packageName, parsed.obbEntries) }
                        )
                    } finally {
                        parsed.obbEntries.forEach { runCatching { it.file.delete() } }
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
                val parsed = XapkInstaller.parseArchive(
                    id = id,
                    archive = archive.file,
                    packageName = packageName,
                    downloader = downloader,
                    installLog = installLog
                )
                try {
                    val splits = if (parsed.hasBaseSplit) {
                        parsed.splits
                    } else {
                        installer.attachInstalledBase(packageName, parsed.splits)
                            ?: throw IllegalStateException("Missing base split and unable to retrieve compatible installed base")
                    }
                    performRootInstall(
                        id = id,
                        packageName = packageName,
                        files = splits,
                        cleanup = { archive.file.delete() },
                        onSuccess = { XapkInstaller.installObb(packageName, parsed.obbEntries) }
                    )
                } finally {
                    archive.file.delete()
                    parsed.obbEntries.forEach { runCatching { it.file.delete() } }
                }
            }
            is Link.Play -> {
                val remoteFiles = link.getInstallFiles()
                if (remoteFiles.isEmpty()) {
                    logWarn { "No files returned for Play install." }
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
        logError(it) { "Error in downloadAndRootInstall." }
        if (it is IllegalStateException) {
            snackBar.snackBar(
                viewModelScope,
                TextSnack(stringer.get(R.string.root_install_missing_base_split))
            )
        }
        cancelInstall(id)
    }

    protected suspend fun downloadAndInstall(id: Int, packageName: String, link: Link) = runCatching {
        installLog.emitStage(id, InstallStage.Pending)
        when (link) {
            Link.Empty -> logError { "downloadAndInstall: Unsupported." }
            is Link.Play -> {
                val files = link.getInstallFiles()
                val total = files.fold(0L) { acc, file ->
                    val size = if (file.size > 0L) file.size else 0L
                    acc + size
                }
                val totalHint = total.takeIf { it > 0L }
                installLog.emitProgress(AppInstallProgress(id, 0L, totalHint, InstallStage.Downloading))
                val downloadedFiles = mutableListOf<RootFile>()
                val streams = mutableListOf<java.io.InputStream>()
                var offset = 0L
                try {
                    files.forEach { remote ->
                        val (downloaded, bytes) = downloadFile(
                            id = id,
                            url = remote.url,
                            total = totalHint,
                            offset = offset,
                            sessionName = remote.name.takeIf { it.isNotBlank() }
                        )
                        offset += bytes
                        downloadedFiles += downloaded
                    }
                    downloadedFiles.forEach { local ->
                        streams += local.file.inputStream().buffered()
                    }
                    installer.playInstall(id, packageName, streams)
                    installLog.emitStage(id, InstallStage.Installing)
                } finally {
                    streams.forEach { runCatching { it.close() } }
                    downloadedFiles.forEach { local ->
                        runCatching { if (local.file.exists()) local.file.delete() }
                    }
                }
            }
            is Link.Url -> {
                val size = if (link.size > 0) link.size else downloader.getContentLength(link.link)
                val totalHint = size.takeIf { it > 0 }
                installLog.emitProgress(AppInstallProgress(id, 0L, totalHint, InstallStage.Downloading))
                val (downloaded, _) = downloadFile(
                    id = id,
                    url = link.link,
                    total = totalHint,
                    offset = 0L,
                    sessionName = sessionNameFromUrl(link.link)
                )
                try {
                    downloaded.file.inputStream().buffered().use { stream ->
                        installer.install(id, packageName, stream)
                    }
                } finally {
                    runCatching { if (downloaded.file.exists()) downloaded.file.delete() }
                }
                installLog.emitStage(id, InstallStage.Installing)
            }
            is Link.Xapk -> {
                installLog.emitStage(id, InstallStage.Downloading)
                installer.installXapk(id, packageName, downloader.downloadStream(link.link)!!)
                installLog.emitStage(id, InstallStage.Installing)
            }
        }
    }.getOrElse {
        logError(it) { "Error in downloadAndInstall." }
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
        installLog.emitProgress(AppInstallProgress(id, 0L, total, InstallStage.Pending))

    private fun downloadFile(
        id: Int,
        url: String,
        total: Long?,
        offset: Long,
        sessionName: String? = null
    ): Pair<RootFile, Long> {
        var bytesCopied = 0L
        var knownTotal = total
        val file = downloader.download(url) { bytes, reportedTotal ->
            bytesCopied = bytes
            if ((knownTotal == null || knownTotal == 0L) && reportedTotal > 0) {
                knownTotal = if (total == null || total == 0L) offset + reportedTotal else reportedTotal
            }
            emitDownloadProgress(id, offset + bytes, knownTotal)
        }
        if (bytesCopied == 0L) {
            val length = file.length()
            if (length > 0) {
                bytesCopied = length
                emitDownloadProgress(id, offset + length, knownTotal
                    ?: length)
            }
        }
        val resolvedName = sessionName?.takeIf { it.isNotBlank() }
            ?: sessionNameFromUrl(url)
            ?: file.name
        return RootFile(file, resolvedName) to bytesCopied
    }

    private suspend fun performRootInstall(
        id: Int,
        packageName: String,
        files: List<RootFile>,
        cleanup: (() -> Unit)? = null,
        onSuccess: (() -> Unit)? = null
    ) {
        if (files.isEmpty()) {
            logError { "performRootInstall called with no files." }
            installLog.emitStage(id, InstallStage.Idle)
            cancelInstall(id)
            cleanup?.invoke()
            return
        }
        try {
            installLog.emitStage(id, InstallStage.Installing)
            val result = installer.rootInstall(packageName, files)
            if (result.success) {
                runCatching { onSuccess?.invoke() }
                    .onFailure { logWarn(it) { "Post-install action failed for $packageName" } }
                clearTrackedInstall(id)
                finishInstall(id)
            } else {
                handleRootInstallFailure(id, packageName, result)
            }
        } finally {
            files.forEach { rootFile ->
                runCatching { if (rootFile.file.exists()) rootFile.file.delete() }
                    .onFailure { logWarn(it) { "Failed to delete temp file ${rootFile.file.absolutePath}" } }
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
        val update = trackedInstall(id)
        update?.let {
            if (isCertificateFailure(reason)) {
                blockSourceDueToCertificates(it, reason)
                markInstallBlocked(id)
            }
        }

        val autoBlock = isAutoBlockFailure(reason)
        if (autoBlock && update != null) {
            recordInstallFailure(update)
            logDebug { "Auto-blocked ${update.packageName} from ${update.source.name} after root failure: $reason" }
            clearTrackedInstall(id)
            installLog.emitProgress(AppInstallProgress(id, 0L, 0L, InstallStage.Idle))
            cancelInstall(id).join()
            return
        }

        logError { "Root install failed for $packageName: $reason" }
        snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.root_install_failed_with_reason, reason)))
        maybePromptInstallModeChange(packageName)
        installLog.emitStage(id, InstallStage.Idle)
        cancelInstall(id).join()
    }

    private fun recordInstallFailure(update: AppUpdate) {
        val key = update.blockKey()
        val blocked = prefs.blockedInstallFailures.get().toMutableSet()
        if (blocked.add(key)) {
            prefs.blockedInstallFailures.put(blocked.toList())
        }
    }

    private fun isAutoBlockFailure(reason: String): Boolean =
        reason.contains("INSTALL_FAILED_INVALID_APK", ignoreCase = true) ||
            reason.contains("INSTALL_FAILED_DUPLICATE_PERMISSION", ignoreCase = true) ||
            reason.contains("INSTALL_PARSE_FAILED", ignoreCase = true) ||
            reason.contains("FAILED COLLECTING CERTIFICATES", ignoreCase = true) ||
            reason.contains("FAILED TO COLLECT CERTIFICATES", ignoreCase = true) ||
            reason.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true)


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

    private fun emitDownloadProgress(id: Int, progress: Long, total: Long?) {
        val cappedTotal = total?.takeIf { it > 0 }
        val cappedProgress = if (cappedTotal != null) min(progress, cappedTotal) else progress
        val stage = if (cappedProgress > 0L) InstallStage.Downloading else InstallStage.Pending
        val state = AppInstallProgress(
            id = id,
            progress = cappedProgress,
            total = cappedTotal,
            stage = stage
        )
        installProgress[id] = state
        installLog.emitProgress(state)
    }

    protected fun emitInitialDownloadProgress(update: AppUpdate) {
        val total = (update.link as? Link.Url)?.size?.takeIf { it > 0L }
        val state = AppInstallProgress(
            id = update.id,
            progress = 0L,
            total = total,
            stage = InstallStage.Pending
        )
        installProgress[update.id] = state
        installLog.emitProgress(state)
    }

    protected fun List<AppUpdate>.applyActiveInstallState(): List<AppUpdate> =
        map { update ->
            val progress = installProgress[update.id]
            when {
                progress != null -> {
                    val stage = progress.stage ?: InstallStage.Pending
                    update.copy(
                        installStage = stage,
                        progress = progress.progress ?: update.progress,
                        total = progress.total ?: update.total
                    )
                }
                activeInstalls.containsKey(update.id) && update.installStage == InstallStage.Idle -> {
                    update.copy(installStage = InstallStage.Pending)
                }
                else -> update
            }
        }

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
