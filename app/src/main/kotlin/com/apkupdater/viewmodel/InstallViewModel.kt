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
import com.apkupdater.util.SessionInstaller
import com.apkupdater.util.SessionInstaller.RootInstallResult
import com.apkupdater.util.SnackBar
import com.apkupdater.util.Stringer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.min
import java.io.File
import java.util.zip.ZipFile


abstract class InstallViewModel(
    private val downloader: Downloader,
    private val installer: SessionInstaller,
    private val prefs: Prefs,
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
                if (prefs.rootInstall.get() && update.link !is Link.Empty) {
                    Log.d("InstallViewModel", "Starting root install (link type: ${update.link::class.simpleName})")
                    downloadAndRootInstall(update)
                } else {
                    Log.d("InstallViewModel", "Starting normal install (link type: ${update.link::class.simpleName})")
                    downloadAndInstall(update)
                }
            }
        }
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
                val (file, _) = downloadFile(id, link.link, total, 0L)
                val extracted = runCatching { extractApksFromArchive(file) }.getOrDefault(emptyList())
                if (extracted.isNotEmpty()) {
                    try {
                        performRootInstall(id, packageName, extracted) { file.delete() }
                    } finally {
                        extracted.forEach { it.delete() }
                    }
                } else {
                    performRootInstall(id, packageName, listOf(file))
                }
            }
            is Link.Xapk -> {
                val total = downloader.getContentLength(link.link).takeIf { it > 0 }
                startProgress(id, total)
                val (archive, _) = downloadFile(id, link.link, total, 0L)
                val extracted = mutableListOf<File>()
                try {
                    extracted += extractApksFromArchive(archive)
                    performRootInstall(id, packageName, extracted) { archive.delete() }
                } finally {
                    archive.delete()
                    extracted.forEach { it.delete() }
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
                        val length = downloader.getContentLength(file.url)
                        if (length > 0) acc + length else acc
                    }.takeIf { it > 0 }
                    startProgress(id, total)
                    val localFiles = mutableListOf<File>()
                    var offset = 0L
                    try {
                        remoteFiles.forEach { remote ->
                            val (file, bytes) = downloadFile(id, remote.url, total, offset)
                            offset += bytes
                            localFiles += file
                        }
                        performRootInstall(id, packageName, localFiles)
                    } catch (error: Throwable) {
                        localFiles.forEach { it.delete() }
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
                    !log.message.isNullOrBlank() -> log.message!!
                    else -> stringer.get(R.string.install_failure, app.name)
                }
                snackBar.snackBar(viewModelScope, TextSnack(text))
            }
        }
    }

    private fun startProgress(id: Int, total: Long?) =
        installLog.emitProgress(AppInstallProgress(id, 0L, total, InstallStage.Downloading))

    private fun downloadFile(id: Int, url: String, total: Long?, offset: Long): Pair<File, Long> {
        var bytesCopied = 0L
        val file = downloader.download(url) { bytes ->
            bytesCopied = bytes
            installLog.emitProgress(AppInstallProgress(id, progressValue(offset + bytes, total), total))
        }
        if (bytesCopied == 0L) {
            val length = file.length()
            if (length > 0) {
                bytesCopied = length
                installLog.emitProgress(AppInstallProgress(id, progressValue(offset + length, total), total))
            }
        }
        return file to bytesCopied
    }

    private fun extractApksFromArchive(archive: File): List<File> {
        val extracted = mutableListOf<Pair<String, File>>()
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.endsWith(".apk", true)) {
                        val file = downloader.createTempFile(".apk")
                        zip.getInputStream(entry).use { input ->
                            file.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        extracted += entry.name to file
                    }
                }
            }
        } catch (error: Throwable) {
            extracted.forEach { it.second.delete() }
            throw error
        }
        return extracted
            .sortedBy { (name, _) -> if (name.contains("base", ignoreCase = true)) 0 else 1 }
            .map { it.second }
    }

    private suspend fun performRootInstall(
        id: Int,
        packageName: String,
        files: List<File>,
        cleanup: (() -> Unit)? = null
    ) {
        if (files.isEmpty()) {
            Log.e("InstallViewModel", "performRootInstall called with no files.")
            installLog.emitStage(id, InstallStage.Idle)
            cancelInstall(id)
            cleanup?.invoke()
            return
        }
        try {
            installLog.emitStage(id, InstallStage.Installing)
            val result = installer.rootInstall(files)
            if (result.success) {
                finishInstall(id)
            } else {
                val handled = handleRootInstallFailure(id, packageName, files, result)
                if (!handled) {
                    installLog.emitStage(id, InstallStage.Idle)
                    cancelInstall(id)
                }
            }
        } finally {
            files.forEach { file ->
                runCatching { if (file.exists()) file.delete() }
                    .onFailure { Log.w("InstallViewModel", "Failed to delete temp file ${file.absolutePath}", it) }
            }
            cleanup?.invoke()
        }
    }

    private suspend fun handleRootInstallFailure(
        id: Int,
        packageName: String,
        files: List<File>,
        result: SessionInstaller.RootInstallResult
    ): Boolean {
        val reason = result.humanReadableMessage()
        Log.e("InstallViewModel", "Root install failed for $packageName: $reason")
        snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.root_install_failed_with_reason, reason)))
        return fallbackToPackageInstaller(id, packageName, files, reason)
    }

    private suspend fun fallbackToPackageInstaller(
        id: Int,
        packageName: String,
        files: List<File>,
        reason: String
    ): Boolean = runCatching {
        installer.installFromFiles(id, packageName, files)
        true
    }.getOrElse { error ->
        val message = error.message?.ifBlank { error::class.simpleName.orEmpty() }.orEmpty()
        Log.e("InstallViewModel", "Fallback install failed for $packageName: $message", error)
        snackBar.snackBar(
            viewModelScope,
            TextSnack(stringer.get(R.string.root_install_fallback_failed, reason, message))
        )
        false
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

    protected abstract fun downloadAndInstall(update: AppUpdate): Job
    protected abstract fun downloadAndRootInstall(update: AppUpdate): Job
    protected abstract fun cancelInstall(id: Int): Job
    protected abstract fun finishInstall(id: Int): Job
}
