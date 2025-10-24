package com.tripleu.updater.repository

import android.net.Uri
import android.util.Log
import com.tripleu.updater.BuildConfig
import com.tripleu.updater.R
import com.tripleu.updater.data.ui.AppInstalled
import com.tripleu.updater.data.ui.AppPackaging
import com.tripleu.updater.data.ui.AppUpdate
import com.tripleu.updater.data.ui.Link
import com.tripleu.updater.data.ui.Source
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "CustomFeedRepository"

class CustomFeedRepository(
    private val httpClient: OkHttpClient,
    private val gson: Gson
) {

    private val token = BuildConfig.CUSTOM_FEED_TOKEN
    private val feedUrl = BuildConfig.CUSTOM_FEED_URL
    private val feedRepo = BuildConfig.CUSTOM_FEED_REPO
    private val feedPath = BuildConfig.CUSTOM_FEED_PATH
    private val source = Source(
        BuildConfig.CUSTOM_FEED_NAME.ifBlank { "Custom" },
        R.drawable.ic_safe
    )

    private val logMethod by lazy {
        runCatching {
            Log::class.java.getMethod(
                "println",
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java
            )
        }.getOrNull()
    }

    init {
        log(
            Log.INFO,
            "CustomFeedRepository init: flavor=${BuildConfig.FLAVOR}, url=$feedUrl, repo=$feedRepo, path=$feedPath, name=${source.name}"
        )
    }

    val isEnabled: Boolean
        get() = feedUrl.isNotBlank() || (feedRepo.isNotBlank() && feedPath.isNotBlank())

    fun updates(installed: List<AppInstalled>): Flow<List<AppUpdate>> = flow {
        if (!isEnabled) {
            log(Log.DEBUG, "Custom feed disabled; skipping updates flow.")
            emit(emptyList())
            return@flow
        }

        log(
            Log.DEBUG,
            "Resolving custom feed for ${installed.size} installed app(s); repo=$feedRepo path=$feedPath url=$feedUrl"
        )

        val manifest = fetchManifest() ?: run {
            emit(emptyList())
            return@flow
        }

        val updates = installed.mapNotNull { app ->
            runCatching {
                manifest[app.packageName]?.let { entry ->
                    log(Log.DEBUG, "Entry type for ${app.packageName}: ${entry::class.java.name}")
                    buildUpdateForInstalled(app, entry)
                }
            }.onFailure { error ->
                log(Log.ERROR, "Failed to build custom feed update for ${app.packageName}: ${error.message}", error)
            }.getOrNull()
        }

        log(Log.INFO, "Custom feed produced ${updates.size} candidate updates.")
        emit(updates)
    }.catch { error ->
        log(Log.ERROR, "Error loading custom feed updates: ${error::class.java.name} - ${error.message}", error)
        emit(emptyList())
    }
    private fun resolveFeedUrl(): HttpUrl? {
        feedUrl.takeIf { it.isNotBlank() }?.let { direct ->
            direct.toHttpUrlOrNull()?.let { return it }
            log(Log.WARN, "Invalid custom feed URL: $direct")
        }

        if (feedRepo.isBlank() || feedPath.isBlank()) {
            return null
        }

        val githubUrl = buildGitHubContentsUrl(feedRepo, feedPath)
        if (githubUrl == null) {
            log(Log.WARN, "Invalid GitHub feed configuration: repo=$feedRepo path=$feedPath")
        }
        return githubUrl
    }

    private fun buildGitHubContentsUrl(repo: String, path: String): HttpUrl? {
        val parts = repo.split('/', limit = 2)
        if (parts.size != 2) return null
        val (owner, repoName) = parts
        val builder = HttpUrl.Builder()
            .scheme("https")
            .host("api.github.com")
            .addPathSegment("repos")
            .addPathSegment(owner)
            .addPathSegment(repoName)
            .addPathSegment("contents")
        path.split('/').filter { it.isNotBlank() }.forEach { segment ->
            builder.addPathSegment(segment)
        }
        return runCatching { builder.build() }.getOrNull()
    }

    fun search(query: String): Flow<Result<List<AppUpdate>>> = flow {
        if (!isEnabled) {
            emit(Result.success(emptyList()))
            return@flow
        }

        val manifest = fetchManifest() ?: run {
            emit(Result.success(emptyList()))
            return@flow
        }

        val normalized = query.trim().lowercase()
        val results = manifest.mapNotNull { (packageName, entry) ->
            val displayName = entry.name ?: packageName
            if (normalized.isEmpty() ||
                packageName.lowercase().contains(normalized) ||
                displayName.lowercase().contains(normalized)
            ) {
                buildUpdateForSearch(packageName, entry, displayName)
            } else {
                null
            }
        }.sortedBy { it.name.lowercase() }

        emit(Result.success(results))
    }.catch { error ->
        log(Log.ERROR, "Error searching custom feed: ${error::class.java.name} - ${error.message}", error)
        emit(Result.failure(error))
    }

    private suspend fun fetchManifest(): Map<String, CustomFeedEntry>? = withContext(Dispatchers.IO) {
        val resolvedUrl = resolveFeedUrl()
        if (resolvedUrl == null) {
            log(Log.WARN, "Custom feed disabled; no URL or repository configured.")
            return@withContext null
        }

        log(Log.DEBUG, "Requesting custom feed from $resolvedUrl")

        val requestBuilder = Request.Builder()
            .url(resolvedUrl)
            .header("Accept", "application/vnd.github.raw")
            .header("User-Agent", "APKUpdater-${BuildConfig.VERSION_NAME}")

        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "token $token")
        }

        val request = requestBuilder.build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log(Log.WARN, "Custom feed HTTP ${response.code}")
                    return@use null
                }
                val body = response.body?.string().orEmpty()
                if (body.isEmpty()) {
                    log(Log.WARN, "Custom feed returned empty body.")
                    return@use null
                }
                val jsonObject = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                if (jsonObject == null) {
                    log(Log.WARN, "Custom feed JSON was null after parsing.")
                    return@use null
                }

                val manifest = mutableMapOf<String, CustomFeedEntry>()
                jsonObject.entrySet().forEach { entry ->
                    val pkg = entry.key
                    val obj = entry.value?.asJsonObject
                    if (obj == null) {
                        log(Log.WARN, "Custom feed entry for $pkg was not a JSON object; skipping.")
                        return@forEach
                    }

                    val version = obj.get("version")?.asString.orEmpty()
                    val versionCode = obj.get("versionCode")?.let { value ->
                        runCatching { value.asLong }.getOrElse {
                            log(Log.WARN, "Invalid versionCode for $pkg: $value")
                            0L
                        }
                    } ?: 0L
                    val url = obj.get("url")?.asString.orEmpty()
                    val changelog = obj.get("changelog")?.asString
                    val name = obj.get("name")?.asString
                    val icon = obj.get("icon")?.asString
                    val packaging = obj.get("packaging")?.asString

                    manifest[pkg] = CustomFeedEntry(
                        version = version,
                        versionCode = versionCode,
                        url = url,
                        changelog = changelog,
                        name = name,
                        icon = icon,
                        packaging = packaging
                    )
                }
                log(Log.INFO, "Loaded ${manifest.size} entries from custom feed.")
                manifest
            }
        }.onFailure {
            log(Log.ERROR, "Failed to download custom feed manifest: ${it.message}", it)
        }.getOrNull()
    }

    private fun buildUpdateForInstalled(app: AppInstalled, entry: CustomFeedEntry): AppUpdate? {
        if (entry.versionCode <= app.versionCode) return null
        val packaging = resolvePackaging(entry)
        val link = buildLink(entry, packaging) ?: return null
        return AppUpdate(
            name = app.name,
            packageName = app.packageName,
            version = entry.version,
            oldVersion = app.version,
            versionCode = entry.versionCode,
            oldVersionCode = app.versionCode,
            source = source,
            iconUri = entry.iconUri(),
            link = link,
            whatsNew = entry.changelog.orEmpty(),
            packaging = packaging
        )
    }

    private fun buildUpdateForSearch(
        packageName: String,
        entry: CustomFeedEntry,
        displayName: String
    ): AppUpdate? {
        val packaging = resolvePackaging(entry)
        val link = buildLink(entry, packaging) ?: return null
        return AppUpdate(
            name = displayName,
            packageName = packageName,
            version = entry.version,
            oldVersion = "",
            versionCode = entry.versionCode,
            oldVersionCode = 0L,
            source = source,
            iconUri = entry.iconUri(),
            link = link,
            whatsNew = entry.changelog.orEmpty(),
            packaging = packaging
        )
    }

    private fun resolvePackaging(entry: CustomFeedEntry): AppPackaging =
        when (entry.packaging?.lowercase()) {
            "bundle", "apkm", "xapk" -> AppPackaging.Bundle
            else -> AppPackaging.Apk
        }

    private fun buildLink(entry: CustomFeedEntry, packaging: AppPackaging): Link? {
        val url = entry.url.takeIf { it.isNotBlank() } ?: return null
        return if (packaging == AppPackaging.Bundle) Link.Xapk(url) else Link.Url(url)
    }

    private fun CustomFeedEntry.iconUri(): Uri =
        icon?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: Uri.EMPTY

    private fun log(priority: Int, message: String, throwable: Throwable? = null) {
        val method = logMethod
        if (method != null) {
            val stack = throwable?.let { Log.getStackTraceString(it) }
            runCatching { method.invoke(null, priority, TAG, message) }
                .onFailure { Log.println(priority, TAG, message) }
            stack?.let {
                runCatching { method.invoke(null, priority, TAG, it) }
                    .onFailure { failure -> Log.println(priority, TAG, failure.message ?: failure.toString()) }
            }
        } else {
            Log.println(priority, TAG, message)
            throwable?.let { Log.println(priority, TAG, Log.getStackTraceString(it)) }
        }
    }
}

data class CustomFeedEntry(
    val version: String,
    val versionCode: Long,
    val url: String,
    val changelog: String? = null,
    val name: String? = null,
    val icon: String? = null,
    @SerializedName("packaging") val packaging: String? = null
)
