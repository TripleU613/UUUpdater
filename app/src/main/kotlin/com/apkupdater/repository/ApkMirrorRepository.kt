package com.apkupdater.repository

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.apkupdater.BuildConfig
import com.apkupdater.data.apkmirror.AppExistsRequest
import com.apkupdater.data.apkmirror.AppExistsResponseApk
import com.apkupdater.data.apkmirror.AppExistsResponseData
import com.apkupdater.data.apkmirror.toAppUpdate
import com.apkupdater.data.ui.ApkMirrorSource
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppPackaging
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.Link
import com.apkupdater.data.ui.getApp
import com.apkupdater.data.ui.getPackageNames
import com.apkupdater.data.ui.getSignature
import com.apkupdater.data.ui.getVersionCode
import com.apkupdater.prefs.Prefs
import com.apkupdater.service.ApkMirrorService
import com.apkupdater.util.combine
import com.apkupdater.util.isAndroidTv
import com.apkupdater.util.orFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

private const val TAG = "ApkMirrorRepository"
private const val BASE_URL = "https://www.apkmirror.com"
private const val SCRAPE_TIMEOUT_MS = 15000
private val ANDROID_VERSION_TO_API = mapOf(
    "15" to 35,
    "14" to 34,
    "13" to 33,
    "12.1" to 32,
    "12" to 31,
    "11" to 30,
    "10" to 29,
    "9" to 28,
    "8.1" to 27,
    "8.0" to 26,
    "7.1" to 25,
    "7.0" to 24,
    "6.0" to 23,
    "5.1" to 22,
    "5.0" to 21,
    "4.4" to 19,
    "4.3" to 18,
    "4.2" to 17,
    "4.1" to 16,
    "4.0.3" to 15,
    "4.0" to 14
)


class ApkMirrorRepository(
    private val service: ApkMirrorService,
    private val prefs: Prefs,
    packageManager: PackageManager
) {

    private val arch = when {
        Build.SUPPORTED_ABIS.contains("x86") -> "x86"
        Build.SUPPORTED_ABIS.contains("x86_64") -> "x86"
        Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "arm"
        Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "arm"
        else -> "arm"
    }

    private val isAndroidTV = packageManager.isAndroidTv()
    private val api = Build.VERSION.SDK_INT
    private val userAgent = "APKUpdater/${BuildConfig.VERSION_NAME}"

    suspend fun updates(apps: List<AppInstalled>) = flow {
        apps.chunked(100)
            .map { appExists(it.getPackageNames()) }
            .combine { all -> emit(parseUpdates(all.flatMap { it }, apps)) }
            .collect()
    }

    suspend fun search(text: String) = flow {
        val searchQuery = "/?post_type=app_release&searchtype=app&s="
        val doc = Jsoup.connect("$BASE_URL$searchQuery$text")
            .userAgent(userAgent)
            .timeout(SCRAPE_TIMEOUT_MS)
            .get()
        val row = doc.select("div.appRow")
        val a = row.select("a.byDeveloper")
        val h5 = row.select("h5.appRowTitle").take(a.size)
        val img = row.select("img")
        a.removeAt(0)
        img.removeAt(0)
        val result = (0 until a.size).map {
            AppUpdate(
                name = h5[it].attr("title"),
                link = Link.Url("$BASE_URL${h5[it].selectFirst("a")?.attr("href")}"),
                iconUri = Uri.parse("$BASE_URL${img[it].attr("src")}".replace("=32", "=128")),
                version = "?",
                oldVersion = "?",
                versionCode = 0L,
                oldVersionCode = 0L,
                source = ApkMirrorSource,
                packageName = a[it].text() // Developer name in this case
            )
        }
        emit(Result.success(result))
    }.catch {
        emit(Result.failure(it))
        Log.e("ApkMirrorRepository", "Error searching.", it)
    }

    private fun appExists(apps: List<String>) = flow {
        emit(service.appExists(AppExistsRequest(apps, buildIgnoreList())).data)
    }.catch {
        emit(emptyList())
        Log.e("ApkMirrorRepository", "Error getting updates.", it)
    }

    private suspend fun parseUpdates(
        updates: List<AppExistsResponseData>,
        apps: List<AppInstalled>
    ): List<AppUpdate> {
        val results = mutableListOf<AppUpdate>()
        val fallbackTargets = mutableListOf<Pair<AppInstalled, AppExistsResponseData>>()

        updates
            .filter { it.exists == true }
            .forEach { data ->
                val installed = apps.getApp(data.pname) ?: return@forEach
                val signature = apps.getSignature(data.pname)
                val filteredApks = data.apks
                    .filter { filterSignature(it, signature) }
                    .filter { filterArch(it) }
                    .filter { filterMinApi(it) }

                if (filteredApks.isEmpty()) {
                    fallbackTargets += installed to data
                    return@forEach
                }

                val handsetCandidates = filteredApks.filterNot { apk ->
                    apk.isWearVariant() || (!isAndroidTV && apk.isTvVariant())
                }
                val selected = handsetCandidates.maxByOrNull { it.versionCode }

                if (selected != null && selected.versionCode > installed.versionCode) {
                    results += selected.toAppUpdate(installed, data.release)
                } else {
                    fallbackTargets += installed to data
                }
            }

        if (fallbackTargets.isNotEmpty()) {
            fallbackTargets.forEach { (app, data) ->
                val fallback = runCatching { fetchScrapedUpdate(app, data) }
                    .onFailure { Log.w(TAG, "Fallback scrape failed for ${app.packageName}", it) }
                    .getOrNull()
                if (fallback != null) {
                    results += fallback
                }
            }
        }

        return results
    }

    private fun filterSignature(apk: AppExistsResponseApk, signature: String?) = when {
        apk.signaturesSha1.isNullOrEmpty() -> true
        apk.signaturesSha1.contains(signature) -> true
        else -> false
    }

    private fun filterArch(app: AppExistsResponseApk) = when {
        app.arches.isEmpty() -> true
        app.arches.contains("universal") || app.arches.contains("noarch") -> true
        app.arches.find { a -> Build.SUPPORTED_ABIS.contains(a) } != null -> true
        app.arches.find { a -> a.contains(arch) } != null -> true
        else -> false
    }

    private fun AppExistsResponseApk.isTvVariant(): Boolean {
        val caps = capabilities.orEmpty()
        return caps.any { it.contains("leanback", ignoreCase = true) }
    }

    private fun AppExistsResponseApk.isWearVariant(): Boolean {
        val caps = capabilities.orEmpty()
        return caps.any { it.contains("wear", ignoreCase = true) }
    }

    private fun filterMinApi(apk: AppExistsResponseApk) = runCatching {
        when {
            apk.minapi.toInt() > api -> false
            else -> true
        }
    }.getOrDefault(true)

    private suspend fun fetchScrapedUpdate(
        app: AppInstalled,
        data: AppExistsResponseData
    ): AppUpdate? {
        val releaseLink = data.release.link ?: return null
        val releaseUrl = if (releaseLink.startsWith("http")) releaseLink else "$BASE_URL$releaseLink"
        val variants = scrapeVariants(releaseUrl)

        val matching = variants.asSequence()
            .filterNot { it.isWear || (!isAndroidTV && it.isTv) }
            .filter { isScrapedVariantArchSupported(it.archLabel, arch) }
            .filter { isScrapedVariantMinApiSupported(it.minAndroidLabel, api) }
            .filter { it.versionCode > app.versionCode }
            .maxByOrNull { it.versionCode }
            ?: return null

        return AppUpdate(
            name = app.name,
            packageName = app.packageName,
            version = data.release.version,
            oldVersion = app.version,
            versionCode = matching.versionCode,
            oldVersionCode = app.versionCode,
            source = ApkMirrorSource,
            iconUri = app.iconUri,
            link = Link.Url(matching.link),
            whatsNew = data.release.whatsNew.orEmpty(),
            packaging = if (matching.isBundle) AppPackaging.Bundle else AppPackaging.Apk
        )
    }

    private suspend fun scrapeVariants(url: String): List<ApkMirrorScrapedVariant> = withContext(Dispatchers.IO) {
        runCatching {
            Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(SCRAPE_TIMEOUT_MS)
                .get()
        }.onFailure { Log.w(TAG, "Unable to download APKMirror release page $url", it) }
            .getOrNull()
            ?.let(::parseApkMirrorVariants)
            .orEmpty()
    }

    private fun buildIgnoreList() = mutableListOf<String>().apply {
        if (prefs.ignoreAlpha.get()) add("alpha")
        if (prefs.ignoreBeta.get()) add("beta")
    }
}

internal data class ApkMirrorScrapedVariant(
    val versionCode: Long,
    val link: String,
    val archLabel: String,
    val minAndroidLabel: String,
    val dpiLabel: String,
    val isBundle: Boolean,
    val isTv: Boolean,
    val isWear: Boolean
)

internal fun parseApkMirrorVariants(document: Document): List<ApkMirrorScrapedVariant> {
    val table = document.selectFirst(".variants-table") ?: return emptyList()
    return table.select("> div.table-row")
        .drop(1)
        .mapNotNull { row ->
            val cells = row.select("> div.table-cell")
            if (cells.size < 5) return@mapNotNull null

            val infoCell = cells[0]
            val downloadAnchor = cells[4].selectFirst("a") ?: return@mapNotNull null
            val versionCodeText = infoCell.select("span.colorLightBlack")
                .firstOrNull { !it.hasClass("wrapText") }
                ?.text()
                ?.trim()
            val versionCode = versionCodeText?.filter { it.isDigit() }?.toLongOrNull()
                ?: return@mapNotNull null

            val badges = infoCell.select("span.apkm-badge")
            val isBundle = badges.any { badge ->
                !badge.classNames().contains("signature") &&
                    badge.text().contains("BUNDLE", ignoreCase = true)
            } ||
                infoCell.text().contains("APKM", ignoreCase = true) ||
                downloadAnchor.attr("href").contains("bundle", ignoreCase = true) ||
                downloadAnchor.attr("href").contains("apkm", ignoreCase = true)

            val capabilityTitles = infoCell.select("span[title]").map { it.attr("title") }
            val isTv = capabilityTitles.any { it.contains("Android TV", ignoreCase = true) || it.contains("Google TV", ignoreCase = true) }
            val isWear = capabilityTitles.any { it.contains("Wear", ignoreCase = true) || it.contains("Watch", ignoreCase = true) }

            ApkMirrorScrapedVariant(
                versionCode = versionCode,
                link = downloadAnchor.attr("abs:href"),
                archLabel = cells[1].text().trim(),
                minAndroidLabel = cells[2].text().trim(),
                dpiLabel = cells[3].text().trim(),
                isBundle = isBundle,
                isTv = isTv,
                isWear = isWear
            )
        }
}

internal fun isScrapedVariantArchSupported(label: String, archHint: String): Boolean {
    val tokens = label
        .lowercase()
        .split(',', '/', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return true
    if (tokens.any { it.contains("universal") || it.contains("noarch") }) return true
    if (tokens.any { candidate -> Build.SUPPORTED_ABIS.any { abi -> abi.equals(candidate, ignoreCase = true) } }) return true
    if (tokens.any { candidate -> candidate.contains(archHint) }) return true
    return false
}

internal fun isScrapedVariantMinApiSupported(label: String, deviceApi: Int): Boolean {
    val minApi = parseApkMirrorMinApi(label) ?: return true
    return minApi <= deviceApi
}

internal fun parseApkMirrorMinApi(label: String): Int? {
    val normalized = label.lowercase().trim()
    if (normalized.isBlank() || normalized.contains("varies")) return null
    val numericMatch = Regex("(\\d+(?:\\.\\d+)?)").find(normalized) ?: return null
    val versionToken = numericMatch.groupValues[1]
    val candidates = buildList {
        add(versionToken)
        add(versionToken.substringBeforeLast('.', versionToken))
    }.distinct().filter { it.isNotEmpty() }

    candidates.forEach { candidate ->
        ANDROID_VERSION_TO_API[candidate]?.let { return it }
    }

    return versionToken.toIntOrNull()
}
