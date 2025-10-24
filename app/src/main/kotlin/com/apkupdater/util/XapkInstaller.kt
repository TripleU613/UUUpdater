package com.apkupdater.util

import android.util.Log
import com.apkupdater.data.ui.AppInstallProgress
import com.apkupdater.data.ui.InstallStage
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

object XapkInstaller {
    private const val TAG = "XapkInstaller"
    private val OBB_PATH_REGEX = Regex("android/obb/([^/]+)/(.+)", RegexOption.IGNORE_CASE)

    data class ParsedArchive(
        val splits: List<SessionInstaller.RootFile>,
        val obbEntries: List<ObbEntry>,
        val hasBaseSplit: Boolean
    )

    data class ObbEntry(
        val file: File,
        val relativePath: String
    )

    fun parseArchive(
        id: Int,
        archive: File,
        packageName: String,
        downloader: Downloader,
        installLog: InstallLog
    ): ParsedArchive {
        return parseInternal(id, archive, packageName, downloader, installLog)
            ?: throw IllegalArgumentException("Archive ${archive.name} does not contain APK splits.")
    }

    fun parseArchiveOrNull(
        id: Int,
        archive: File,
        packageName: String,
        downloader: Downloader,
        installLog: InstallLog
    ): ParsedArchive? = try {
        parseInternal(id, archive, packageName, downloader, installLog)
    } catch (error: ZipException) {
        Log.d(TAG, "Skipping archive parse for ${archive.name}: ${error.message}")
        null
    } catch (error: IllegalArgumentException) {
        Log.d(TAG, "Archive ${archive.name} does not contain APK splits: ${error.message}")
        null
    } catch (error: IOException) {
        Log.w(TAG, "Failed to parse archive ${archive.name}", error)
        null
    }

    private fun parseInternal(
        id: Int,
        archive: File,
        packageName: String,
        downloader: Downloader,
        installLog: InstallLog
    ): ParsedArchive? {
        val extractedSplits = mutableListOf<Pair<String, SessionInstaller.RootFile>>()
        val extractedObb = mutableListOf<ObbEntry>()
        var processedApks = 0L

        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().filterNot(ZipEntry::isDirectory).toList()
            val apkEntries = entries.filter { it.name.lowercase(Locale.US).endsWith(".apk") }
            val totalApkEntries = apkEntries.size

            if (totalApkEntries > 0) {
                installLog.emitProgress(
                    AppInstallProgress(
                        id = id,
                        progress = 0L,
                        total = totalApkEntries.toLong(),
                        stage = InstallStage.Installing
                    )
                )
            }

            apkEntries.forEach { entry ->
                val tempFile = downloader.createTempFile(".apk")
                zip.getInputStream(entry).use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                processedApks += 1
                installLog.emitProgress(
                    AppInstallProgress(
                        id = id,
                        progress = processedApks,
                        total = totalApkEntries.toLong(),
                        stage = InstallStage.Installing
                    )
                )
                val entryName = entry.name.substringAfterLast('/').ifBlank { tempFile.name }
                extractedSplits += entryName to SessionInstaller.RootFile(tempFile, entryName)
            }

            entries.filter { it.name.lowercase(Locale.US).endsWith(".obb") }.forEach { entry ->
                val tempFile = downloader.createTempFile(".obb")
                zip.getInputStream(entry).use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val normalized = entry.name.replace('\\', '/')
                val match = OBB_PATH_REGEX.find(normalized)
                val relative = when {
                    match != null -> "${match.groupValues[1]}/${match.groupValues[2]}"
                    normalized.contains('/') -> normalized.substringAfterLast("Android/obb/", "")
                        .takeIf { it.isNotBlank() }
                        ?: "${packageName}/${normalized.substringAfterLast('/')}"
                    else -> "${packageName}/${normalized}"
                }
                extractedObb += ObbEntry(tempFile, relative)
            }
        }

        if (extractedSplits.isEmpty()) {
            extractedObb.forEach { it.file.delete() }
            return null
        }

        Log.d(TAG, "Extracted ${extractedSplits.size} split candidates: ${extractedSplits.joinToString { it.first }}")
        val selected = BundleSplitSelector.select(extractedSplits)
        val selectedFiles = selected.map { it.second }.toSet()
        extractedSplits
            .filter { it.second !in selectedFiles }
            .forEach { runCatching { it.second.file.delete() } }

        val ordered = selected.sortedBy { (name, _) ->
            if (isBaseSplit(name, packageName)) 0 else 1
        }
        val nameCounts = mutableMapOf<String, Int>()
        val uniqueSplits = ordered.map { (_, rootFile) ->
            val baseName = rootFile.sessionName.ifBlank { rootFile.file.name }
            val count = nameCounts.getOrDefault(baseName, 0)
            val uniqueName = if (count == 0) baseName else "${baseName}_${count + 1}"
            nameCounts[baseName] = count + 1
            SessionInstaller.RootFile(rootFile.file, uniqueName)
        }

        if (nameCounts.any { it.value > 1 }) {
            val duplicates = nameCounts.filter { it.value > 1 }.keys.joinToString()
            Log.d(TAG, "Adjusted duplicate split session names for: $duplicates")
        }

        val prunedSplits = pruneExtraBaseSplits(packageName, uniqueSplits)
        Log.d(TAG, "Selected ${prunedSplits.size} split APK(s): ${prunedSplits.joinToString { it.sessionName }}")

        val hasBase = prunedSplits.any { isBaseSplit(it.sessionName, packageName) }
        return ParsedArchive(
            splits = prunedSplits,
            obbEntries = extractedObb,
            hasBaseSplit = hasBase
        )
    }

    fun installObb(packageName: String, entries: List<ObbEntry>) {
        if (entries.isEmpty()) return
        val commands = mutableListOf<String>()
        entries.forEach { entry ->
            val relative = entry.relativePath.ifBlank { "$packageName/${entry.file.name}" }
            val dest = "/sdcard/Android/obb/$relative"
            val destDir = dest.substringBeforeLast('/', "/sdcard/Android/obb/$packageName")
            commands += "mkdir -p ${File(destDir).quote()}"
            commands += "cp ${entry.file.quote()} ${File(dest).quote()}"
            commands += "chmod 0644 ${File(dest).quote()} || true"
            commands += "chown media_rw:media_rw ${File(dest).quote()} || true"
            commands += "restorecon ${File(dest).quote()} || true"
        }
        val result = Shell.cmd(*commands.toTypedArray()).exec()
        if (!result.isSuccess) {
            Log.w(TAG, "Failed to install OBB files (code=${result.code}) for $packageName: ${result.err}")
        } else {
            Log.i(TAG, "Installed ${entries.size} OBB file(s) for $packageName.")
        }
    }

    private fun File.quote(): String = "\"${absolutePath}\""
    private fun pruneExtraBaseSplits(
        packageName: String,
        splits: List<SessionInstaller.RootFile>
    ): List<SessionInstaller.RootFile> {
        var baseSeen = false
        return splits.filter { rootFile ->
            val isBase = isBaseSplit(rootFile.sessionName, packageName)
            if (isBase) {
                if (baseSeen) {
                    Log.d(TAG, "Discarding extra base split ${rootFile.sessionName}")
                    false
                } else {
                    baseSeen = true
                    true
                }
            } else {
                true
            }
        }
    }

    private fun isBaseSplit(name: String, packageName: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.startsWith("base") ||
            lower == "${packageName.lowercase(Locale.US)}.apk" ||
            (!lower.startsWith("config.") && !lower.contains("split_config") && lower == name.lowercase(Locale.US) && lower.endsWith(".apk") && !lower.contains("dynamite"))
    }
}
