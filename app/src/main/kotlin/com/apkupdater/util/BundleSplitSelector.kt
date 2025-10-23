package com.apkupdater.util

import android.content.res.Resources
import android.os.Build
import android.util.Log
import com.apkupdater.util.SessionInstaller.RootFile
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.math.abs

object BundleSplitSelector {
    private const val TAG = "BundleSplitSelector"

    fun select(entries: List<Pair<String, RootFile>>): List<Pair<String, RootFile>> {
        if (entries.size <= 1) return entries

        val candidates = entries.map { (name, file) -> SplitCandidate.from(name, file) }
        val baseCandidates = candidates.filter { it.type == SplitType.BASE }
        if (baseCandidates.isEmpty()) return entries

        val selected = LinkedHashSet<SplitCandidate>()
        val base = selectBase(baseCandidates) ?: return entries
        selected += base

        val configCandidates = candidates.filter { it.type == SplitType.CONFIG }
        val groupedConfigs = configCandidates.groupBy { it.axis }
        groupedConfigs.forEach { (axis, group) ->
            when (axis) {
                SplitAxis.ABI -> pickAbi(group)?.let { selected += it }
                SplitAxis.DENSITY -> pickDensity(group)?.let { selected += it }
                SplitAxis.SDK -> pickSdk(group)?.let { selected += it }
                SplitAxis.LANGUAGE -> selected += selectLanguages(group)
                SplitAxis.BASE, SplitAxis.UNKNOWN, null -> selected += group
            }
        }

        candidates.filter { it.type == SplitType.UNKNOWN }.forEach { selected += it }

        val selectedFiles = selected.map { it.rootFile }.toSet()
        val filtered = entries.filter { (_, file) -> selectedFiles.contains(file) }
        if (filtered.isEmpty()) {
            Log.d(TAG, "Split filtering produced empty selection; falling back to original set.")
            return entries
        }

        if (filtered.size != entries.size) {
            val keptNames = filtered.joinToString { it.first.substringAfterLast('/') }
            Log.d(TAG, "Selected ${filtered.size}/${entries.size} split APK(s) for install: $keptNames")
        } else {
            Log.d(TAG, "No split filtering required; installing all ${entries.size} APK(s).")
        }

        return filtered
    }

    private fun selectBase(candidates: List<SplitCandidate>): SplitCandidate? {
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.value == null }?.let { return it }
        candidates.firstOrNull { it.entryName.contains("universal", ignoreCase = true) }?.let { return it }
        pickAbi(candidates)?.let { return it }
        pickDensity(candidates)?.let { return it }
        return candidates.firstOrNull()
    }

    private fun pickAbi(candidates: List<SplitCandidate>): SplitCandidate? {
        if (candidates.isEmpty()) return null
        val supported = Build.SUPPORTED_ABIS.map { it.replace('-', '_').lowercase(Locale.US) }
        supported.forEach { abi ->
            candidates.firstOrNull { candidate ->
                val candidateValue = candidate.value?.lowercase(Locale.US)
                candidateValue == abi || candidate.entryName.lowercase(Locale.US).contains(abi)
            }?.let { return it }
        }
        return candidates.firstOrNull { it.entryName.contains("universal", ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    private fun pickDensity(candidates: List<SplitCandidate>): SplitCandidate? {
        if (candidates.isEmpty()) return null
        val deviceDensity = Resources.getSystem().displayMetrics.densityDpi
        val mapped = candidates.map { candidate ->
            val value = candidate.value?.let { densityValue(it) }
            val resolved = value ?: deviceDensity
            candidate to resolved
        }
        mapped.firstOrNull { it.second == deviceDensity }?.let { return it.first }
        mapped.firstOrNull { (_, density) -> density == 0 }?.let { return it.first }
        return mapped.minByOrNull { (_, density) -> abs(density - deviceDensity) }?.first
            ?: candidates.firstOrNull()
    }

    private fun pickSdk(candidates: List<SplitCandidate>): SplitCandidate? {
        if (candidates.isEmpty()) return null
        val mapped = candidates.mapNotNull { candidate ->
            val sdkValue = candidate.value?.toIntOrNull()
            sdkValue?.let { candidate to it }
        }
        if (mapped.isEmpty()) return candidates.firstOrNull()
        val deviceSdk = Build.VERSION.SDK_INT
        mapped.filter { it.second <= deviceSdk }
            .maxByOrNull { it.second }
            ?.let { return it.first }
        return mapped.minByOrNull { it.second }?.first ?: candidates.firstOrNull()
    }

    private fun selectLanguages(candidates: List<SplitCandidate>): List<SplitCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val languageTokens = deviceLanguageTokens()
        val matches = candidates.filter { candidate ->
            val normalized = candidate.value?.let { normalizeLanguage(it) } ?: return@filter false
            val language = normalized.substringBefore('-')
            languageTokens.contains(normalized) || languageTokens.contains(language)
        }
        return if (matches.isNotEmpty()) matches else listOf(candidates.first())
    }

    private fun deviceLanguageTokens(): Set<String> {
        val tokens = mutableSetOf<String>()
        val configuration = Resources.getSystem().configuration
        val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val list = configuration.locales
            (0 until list.size()).mapNotNull { list[it] }
        } else {
            listOf(configuration.locale)
        }
        locales.filterNotNull().forEach { locale ->
            val language = locale.language?.lowercase(Locale.US).orEmpty()
            if (language.isNotEmpty()) {
                tokens += language
                val region = locale.country?.lowercase(Locale.US).orEmpty()
                if (region.isNotEmpty()) tokens += "$language-$region"
                tokens += locale.toLanguageTag().lowercase(Locale.US)
            }
        }
        val defaultLocale = Locale.getDefault()
        val defaultLanguage = defaultLocale.language?.lowercase(Locale.US).orEmpty()
        if (defaultLanguage.isNotEmpty()) {
            tokens += defaultLanguage
            val region = defaultLocale.country?.lowercase(Locale.US).orEmpty()
            if (region.isNotEmpty()) tokens += "$defaultLanguage-$region"
            tokens += defaultLocale.toLanguageTag().lowercase(Locale.US)
        }
        tokens += "en"
        return tokens
    }

    private fun densityValue(token: String): Int? {
        val normalized = token.lowercase(Locale.US)
        DENSITY_MAP[normalized]?.let { return it }
        if (normalized.endsWith("dpi")) {
            return normalized.removeSuffix("dpi").toIntOrNull()
        }
        return null
    }

    private fun normalizeLanguage(token: String): String =
        token.lowercase(Locale.US).replace('_', '-')

    private enum class SplitType { BASE, CONFIG, UNKNOWN }

    private enum class SplitAxis { BASE, ABI, DENSITY, LANGUAGE, SDK, UNKNOWN }

    private data class SplitCandidate(
        val entryName: String,
        val rootFile: RootFile,
        val type: SplitType,
        val axis: SplitAxis,
        val value: String?
    ) {
        companion object {
            fun from(entryName: String, rootFile: RootFile): SplitCandidate {
                val fileName = entryName.substringAfterLast('/')
                val nameWithoutExt = fileName.substringBeforeLast('.', fileName)
                val lower = nameWithoutExt.lowercase(Locale.US)
                if (isBaseName(lower)) {
                    val variant = lower.removePrefix("base").trimStart('-', '.')
                    if (variant.isEmpty() || variant == "master") {
                        return SplitCandidate(entryName, rootFile, SplitType.BASE, SplitAxis.BASE, null)
                    }
                    val axisInfo = inferAxis(variant)
                    return SplitCandidate(entryName, rootFile, SplitType.BASE, axisInfo.axis, axisInfo.value)
                }

                val normalized = when {
                    lower.startsWith("split_config.") -> lower.removePrefix("split_config.")
                    lower.startsWith("config.") -> lower.removePrefix("config.")
                    else -> lower
                }
                val axisInfo = inferAxis(normalized)
                val type = if (normalized != lower || axisInfo.axis != SplitAxis.UNKNOWN) {
                    SplitType.CONFIG
                } else {
                    SplitType.UNKNOWN
                }
                return SplitCandidate(entryName, rootFile, type, axisInfo.axis, axisInfo.value)
            }

            private fun isBaseName(name: String): Boolean =
                name == "base" || name.startsWith("base-") || name.startsWith("base.")
        }
    }

    private data class AxisInfo(val axis: SplitAxis, val value: String?)

    private fun inferAxis(token: String): AxisInfo {
        if (token.isBlank()) return AxisInfo(SplitAxis.UNKNOWN, null)
        val normalized = token.lowercase(Locale.US)
        if (normalized == "master") return AxisInfo(SplitAxis.BASE, null)

        val abiToken = normalized.replace('-', '_')
        if (ABI_TOKENS.contains(abiToken)) return AxisInfo(SplitAxis.ABI, abiToken)

        densityValue(normalized)?.let { return AxisInfo(SplitAxis.DENSITY, normalized) }

        val sdkValue = parseSdkValue(normalized)
        if (sdkValue != null) return AxisInfo(SplitAxis.SDK, sdkValue.toString())

        if (isLanguageToken(normalized)) return AxisInfo(SplitAxis.LANGUAGE, normalizeLanguage(normalized))

        if (normalized.contains("universal")) return AxisInfo(SplitAxis.ABI, "universal")

        return AxisInfo(SplitAxis.UNKNOWN, normalized)
    }

    private fun parseSdkValue(token: String): Int? {
        return when {
            token.startsWith("sdk") -> token.removePrefix("sdk").toIntOrNull()
            token.startsWith("v") -> token.removePrefix("v").toIntOrNull()
            else -> token.toIntOrNull()
        }
    }

    private fun isLanguageToken(token: String): Boolean {
        if (token.contains("dpi")) return false
        if (token.startsWith("sdk") || token.startsWith("v") && token.drop(1).all { it.isDigit() }) return false
        val sanitized = token.replace('_', '-')
        val parts = sanitized.split('-')
        if (parts.isEmpty()) return false
        return parts.all { part -> part.all { it.isLetter() } }
    }

    private val ABI_TOKENS = setOf(
        "armeabi",
        "armeabi_v7a",
        "arm64_v8a",
        "x86",
        "x86_64",
        "mips",
        "mips64",
        "universal"
    )

    private val DENSITY_MAP = mapOf(
        "ldpi" to 120,
        "mdpi" to 160,
        "tvdpi" to 213,
        "hdpi" to 240,
        "xhdpi" to 320,
        "xxhdpi" to 480,
        "xxxhdpi" to 640,
        "nodpi" to 0,
        "anydpi" to 0
    )
}
