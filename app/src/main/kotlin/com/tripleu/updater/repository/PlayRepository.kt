package com.tripleu.updater.repository

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.tripleu.updater.BuildConfig
import com.tripleu.updater.util.NoOpLog as Log
import com.tripleu.updater.data.ui.AppInstalled
import com.tripleu.updater.data.ui.AppUpdate
import com.tripleu.updater.data.ui.Link
import com.tripleu.updater.data.ui.PlaySource
import com.tripleu.updater.data.ui.getPackageNames
import com.tripleu.updater.data.ui.getVersion
import com.tripleu.updater.data.ui.getVersionCode
import com.tripleu.updater.prefs.Prefs
import com.tripleu.updater.util.play.PlayDeviceSpoofManager
import com.tripleu.updater.util.play.PlayHttpClient
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.File
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.gplayapi.helpers.SearchHelper
import com.google.gson.Gson
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow


class PlayRepository(
    private val context: Context,
    private val playHttpClient: PlayHttpClient,
    private val gson: Gson,
    private val prefs: Prefs
) {
    companion object {
        private const val TAG = "PlayRepository"
        const val AUTH_URL = "https://auroraoss.com/api/auth"
    }

    private fun refreshAuth(): AuthData {
        Log.i(TAG, "Refreshing token.")
        val properties = PlayDeviceSpoofManager.properties(context)
        val profileName = properties.getProperty("Spoof.Profile").orEmpty()
        val model = properties.getProperty("Build.MODEL").orEmpty()
        val sdk = properties.getProperty("Build.VERSION.SDK_INT").orEmpty()
        val fingerprint = properties.getProperty("Build.FINGERPRINT").orEmpty()
        val abis = properties.getProperty("Platforms").orEmpty()
        val timeZone = properties.getProperty("TimeZone").orEmpty()
        val cellOperator = properties.getProperty("CellOperator").orEmpty()
        val simOperator = properties.getProperty("SimOperator").orEmpty()
        val deviceCountry = properties.getProperty("DeviceCountry").orEmpty()
        val simCountry = properties.getProperty("SimCountry").orEmpty()
        Log.i(
            TAG,
            "Auth payload profile=$profileName model=$model sdk=$sdk abis=$abis tz=$timeZone " +
                "cell=$cellOperator sim=$simOperator country=$deviceCountry/$simCountry " +
                "fingerprint=${fingerprint.takeLast(12)}"
        )

        val payload = gson.toJson(properties).toByteArray()
        Log.d(TAG, "Auth payload size=${payload.size} bytes")

        val start = SystemClock.elapsedRealtime()
        val playResponse = playHttpClient.postAuth(AUTH_URL, payload)
        val duration = SystemClock.elapsedRealtime() - start
        Log.i(
            TAG,
            "Auth response code=${playResponse.code} success=${playResponse.isSuccessful} " +
                "duration=${duration}ms"
        )

        if (playResponse.isSuccessful) {
            val authData = gson.fromJson(String(playResponse.responseBytes), AuthData::class.java)
            prefs.playAuthData.put(authData)
            PlayDeviceSpoofManager.markAuthSuccess()
            if (BuildConfig.TECHEAZ_PRIVILEGED_INSTALLER) {
                prefs.playSpoofRebootPending.put(true)
                prefs.playSpoofRebootHandled.put(false)
            } else {
                prefs.playSpoofRebootPending.put(false)
                if (!prefs.playSpoofRebootHandled.get()) {
                    prefs.playSpoofRebootHandled.put(true)
                }
            }
            return authData
        }
        Log.w(TAG, "Auth not successful. code=${playResponse.code} message=${playResponse.errorString}")
        PlayDeviceSpoofManager.markAuthFailure(playResponse.code)
        PlayDeviceSpoofManager.refresh(context, reason = "auth_failure_${playResponse.code}")
        throw IllegalStateException("Auth not successful.")
    }

    private fun auth(): AuthData {
        val savedData = prefs.playAuthData.get()
        if (savedData.email.isEmpty()) {
            return refreshAuth()
        }
        if (System.currentTimeMillis() - prefs.lastPlayCheck.get() > 60 * 60 * 1_000) {
            // Update check time
            prefs.lastPlayCheck.put(System.currentTimeMillis())
            Log.i(TAG, "Checking token validity.")

            // 1h has passed check if token still works
            val app = runCatching {
                AppDetailsHelper(savedData)
                    .using(playHttpClient)
                    .getAppByPackageName("com.google.android.gm")
            }.getOrElse {
                return refreshAuth()
            }

            if (app.packageName.isEmpty()) {
                return refreshAuth()
            }
            Log.i(TAG, "Token still valid.")
        }
        return savedData
    }

    suspend fun search(text: String) = flow {
        if (text.contains(" ") || !text.contains(".")) {
            // Normal Search
            val authData = auth()
            val updates = SearchHelper(authData)
                .using(playHttpClient)
                .searchResults(text)
                .appList
                .take(10)
                .map { it.toAppUpdate(::getInstallFiles) }
            emit(Result.success(updates))
        } else {
            // Package Name Search
            val authData = auth()
            val update = AppDetailsHelper(authData)
                .using(playHttpClient)
                .getAppByPackageName(text)
                .toAppUpdate(::getInstallFiles)
            emit(Result.success(listOf(update)))
        }
    }.catch {
        emit(Result.failure(it))
        Log.e(TAG, "Error searching for $text.", it)
    }

    suspend fun updates(apps: List<AppInstalled>) = flow {
        val authData = auth()
        val details = AppDetailsHelper(authData)
            .using(playHttpClient)
            .getAppByPackageName(apps.getPackageNames())
        val updates = details
            .filter { it.versionCode > apps.getVersionCode(it.packageName) }
            .map {
                it.toAppUpdate(
                    ::getInstallFiles,
                    apps.getVersion(it.packageName),
                    apps.getVersionCode(it.packageName)
                )
            }
        emit(updates)
    }.catch {
        emit(emptyList())
        Log.e(TAG, "Error looking for updates.", it)
    }

    private fun getInstallFiles(app: App) = PurchaseHelper(auth())
        .using(playHttpClient)
        .purchase(app.packageName, app.versionCode, app.offerType)
        .filter { it.type == File.FileType.BASE || it.type == File.FileType.SPLIT }

}

fun App.toAppUpdate(
    getInstallFiles: (App) -> List<File>,
    oldVersion: String = "",
    oldVersionCode: Long = 0L
) = AppUpdate(
    displayName,
    packageName,
    versionName,
    oldVersion,
    versionCode.toLong(),
    oldVersionCode,
    PlaySource,
    Uri.parse(iconArtwork.url),
    Link.Play { getInstallFiles(this) },
    whatsNew = changes
)
