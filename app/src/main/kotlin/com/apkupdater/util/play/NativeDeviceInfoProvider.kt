package com.apkupdater.util.play

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.text.TextUtils
import android.util.Log
import java.util.Locale
import java.util.Properties
import kotlin.math.abs


class NativeDeviceInfoProvider(context: Context) : ContextWrapper(context) {

    fun getNativeDeviceProperties(): Properties {
        val properties = Properties().apply {
            //Build Props
            setProperty("UserReadableName", "${Build.DEVICE}-default")
            setProperty("Build.HARDWARE", Build.HARDWARE)
            setProperty(
                "Build.RADIO",
                if (Build.getRadioVersion() != null)
                    Build.getRadioVersion()
                else
                    "unknown"
            )
            setProperty("Build.FINGERPRINT", Build.FINGERPRINT)
            setProperty("Build.BRAND", Build.BRAND)
            setProperty("Build.DEVICE", Build.DEVICE)
            setProperty("Build.VERSION.SDK_INT", "${Build.VERSION.SDK_INT}")
            setProperty("Build.VERSION.RELEASE", Build.VERSION.RELEASE)
            setProperty("Build.MODEL", Build.MODEL)
            setProperty("Build.MANUFACTURER", Build.MANUFACTURER)
            setProperty("Build.PRODUCT", Build.PRODUCT)
            setProperty("Build.ID", Build.ID)
            setProperty("Build.BOOTLOADER", Build.BOOTLOADER)

            val config = applicationContext.resources.configuration
            setProperty("TouchScreen", "${config.touchscreen}")
            setProperty("Keyboard", "${config.keyboard}")
            setProperty("Navigation", "${config.navigation}")
            setProperty("ScreenLayout", "${config.screenLayout and 15}")
            setProperty("HasHardKeyboard", "${config.keyboard == Configuration.KEYBOARD_QWERTY}")
            setProperty(
                "HasFiveWayNavigation",
                "${config.navigation == Configuration.NAVIGATIONHIDDEN_YES}"
            )

            //Display Metrics
            val metrics = applicationContext.resources.displayMetrics
            setProperty("Screen.Density", "${metrics.densityDpi}")
            setProperty("Screen.Width", "${metrics.widthPixels}")
            setProperty("Screen.Height", "${metrics.heightPixels}")


            //Supported Platforms
            setProperty("Platforms", Build.SUPPORTED_ABIS.joinToString(separator = ","))
            //Supported Features
            setProperty("Features", getFeatures().joinToString(separator = ","))
            //Shared Locales
            setProperty("Locales", getLocales().joinToString(separator = ","))
            //Shared Libraries
            setProperty("SharedLibraries", getSharedLibraries().joinToString(separator = ","))
            //GL Extensions
            val activityManager =
                applicationContext.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            setProperty(
                "GL.Version",
                activityManager.deviceConfigurationInfo.reqGlEsVersion.toString()
            )
            setProperty(
                "GL.Extensions",
                EglExtensionProvider.eglExtensions.joinToString(separator = ",")
            )

            //Google Related Props
            setProperty("Client", "android-google")
            setProperty("GSF.version", "203615037")
            setProperty("Vending.version", "82201710")
            setProperty("Vending.versionString", "22.0.17-21 [0] [PR] 332555730")

            //MISC
            setProperty("Roaming", "mobile-notroaming")
            setProperty("TimeZone", "UTC-10")

            //Telephony (USA 3650 AT&T)
            setProperty("CellOperator", "310")
            setProperty("SimOperator", "38")
        }

        applyPlayProfile(properties)

        return properties
    }

    private fun getFeatures(): List<String> {
        val featureStringList: MutableList<String> = ArrayList()
        try {
            val availableFeatures = applicationContext.packageManager.systemAvailableFeatures
            for (feature in availableFeatures) {
                if (feature.name.isNotEmpty()) {
                    featureStringList.add(feature.name)
                }
            }
        } catch (_: Exception) {}
        return featureStringList
    }

    private fun getLocales(): List<String> {
        val localeList: MutableList<String> = ArrayList()
        localeList.addAll(listOf(*applicationContext.assets.locales))
        val locales: MutableList<String> = ArrayList()
        for (locale in localeList) {
            if (TextUtils.isEmpty(locale)) {
                continue
            }
            locales.add(locale.replace("-", "_"))
        }
        return locales
    }

    private fun getSharedLibraries(): List<String> {
        val systemSharedLibraryNames = applicationContext.packageManager.systemSharedLibraryNames
        val libraries: MutableList<String> = ArrayList()
        try {
            if (systemSharedLibraryNames != null) {
                libraries.addAll(listOf(*systemSharedLibraryNames))
            }
        } catch (_: Exception) {}
        return libraries
    }

    private fun applyPlayProfile(properties: Properties) {
        val alreadyPixel = Build.MANUFACTURER.equals("google", true) && Build.BRAND.equals("google", true)
        val needsSpoof = isHuawei() || !alreadyPixel
        if (!needsSpoof) {
            properties.setProperty("Platforms", Build.SUPPORTED_ABIS.joinToString(","))
            return
        }

        val profile = selectPlayDeviceProfile()
        if (profile != null) {
            Log.i("NativeDeviceInfoProvider", "Using Play spoof profile: ${profile.name}")
            profile.applyTo(properties)
        } else {
            Log.w("NativeDeviceInfoProvider", "No matching spoof profile found; using device defaults.")
            properties.setProperty("Platforms", Build.SUPPORTED_ABIS.joinToString(","))
        }
    }

    private fun selectPlayDeviceProfile(): PlayDeviceProfile? {
        val deviceSdk = Build.VERSION.SDK_INT
        val deviceAbis = Build.SUPPORTED_ABIS.map { it.lowercase(Locale.US) }
        val candidates = PLAY_DEVICE_PROFILES.filter { profile ->
            profile.supportedAbis.any { it in deviceAbis }
        }
        val pool = if (candidates.isNotEmpty()) candidates else PLAY_DEVICE_PROFILES
        return pool.minByOrNull { profile -> abs(profile.sdkInt - deviceSdk) }
    }
}

fun isHuawei(): Boolean {
    return Build.MANUFACTURER.lowercase(Locale.getDefault()).contains("huawei")
            || Build.HARDWARE.lowercase(Locale.getDefault()).contains("kirin")
            || Build.HARDWARE.lowercase(Locale.getDefault()).contains("hi3")
}

private data class PlayDeviceProfile(
    val name: String,
    val brand: String,
    val manufacturer: String,
    val device: String,
    val model: String,
    val product: String,
    val hardware: String,
    val bootloader: String,
    val fingerprint: String,
    val radio: String,
    val buildId: String,
    val sdkInt: Int,
    val release: String,
    val gsfVersion: String,
    val vendingVersion: String,
    val vendingVersionString: String,
    val supportedAbis: Set<String>
) {
    fun applyTo(properties: Properties) {
        properties.setProperty("UserReadableName", "$model-default")
        properties.setProperty("Build.HARDWARE", hardware)
        properties.setProperty("Build.BOOTLOADER", bootloader)
        properties.setProperty("Build.BRAND", brand)
        properties.setProperty("Build.DEVICE", device)
        properties.setProperty("Build.VERSION.SDK_INT", sdkInt.toString())
        properties.setProperty("Build.VERSION.RELEASE", release)
        properties.setProperty("Build.MODEL", model)
        properties.setProperty("Build.MANUFACTURER", manufacturer)
        properties.setProperty("Build.PRODUCT", product)
        properties.setProperty("Build.ID", buildId)
        properties.setProperty("Build.FINGERPRINT", fingerprint)
        properties.setProperty("Build.RADIO", radio)
        properties.setProperty("Client", "android-google")
        properties.setProperty("GSF.version", gsfVersion)
        properties.setProperty("Vending.version", vendingVersion)
        properties.setProperty("Vending.versionString", vendingVersionString)
        properties.setProperty("Platforms", supportedAbis.joinToString(","))
    }
}

private val PLAY_DEVICE_PROFILES = listOf(
    PlayDeviceProfile(
        name = "Pixel 8 Pro (Android 14)",
        brand = "google",
        manufacturer = "Google",
        device = "husky",
        model = "Pixel 8 Pro",
        product = "husky",
        hardware = "husky",
        bootloader = "husky-1.0-12385700",
        fingerprint = "google/husky/husky:14/UQ1A.240205.004/11624333:user/release-keys",
        radio = "g5300g-240104-230926-B-10792369",
        buildId = "UQ1A.240205.004",
        sdkInt = 34,
        release = "14",
        gsfVersion = "240604045",
        vendingVersion = "83821610",
        vendingVersionString = "38.9.16-21 [0] [PR] 609195242",
        supportedAbis = setOf("arm64-v8a")
    ),
    PlayDeviceProfile(
        name = "Pixel 7a (Android 13)",
        brand = "google",
        manufacturer = "Google",
        device = "lynx",
        model = "Pixel 7a",
        product = "lynx",
        hardware = "lynx",
        bootloader = "lynx-1.0-12346121",
        fingerprint = "google/lynx/lynx:13/TQ3A.230901.001/10472828:user/release-keys",
        radio = "g5300g-230829-230112-B-10387210",
        buildId = "TQ3A.230901.001",
        sdkInt = 33,
        release = "13",
        gsfVersion = "230915046",
        vendingVersion = "82241710",
        vendingVersionString = "22.4.17-21 [0] [PR] 345620102",
        supportedAbis = setOf("arm64-v8a", "armeabi-v7a")
    ),
    PlayDeviceProfile(
        name = "Pixel 5 (Android 12)",
        brand = "google",
        manufacturer = "Google",
        device = "redfin",
        model = "Pixel 5",
        product = "redfin",
        hardware = "redfin",
        bootloader = "redfin-1.0-10209595",
        fingerprint = "google/redfin/redfin:12/SQ3A.220705.003.A1/8819327:user/release-keys",
        radio = "g7250-220216-200123-B-8581309",
        buildId = "SQ3A.220705.003.A1",
        sdkInt = 31,
        release = "12",
        gsfVersion = "230310040",
        vendingVersion = "81551710",
        vendingVersionString = "21.5.17-21 [0] [PR] 337464975",
        supportedAbis = setOf("arm64-v8a", "armeabi-v7a")
    ),
    PlayDeviceProfile(
        name = "Pixel 3 (Android 11)",
        brand = "google",
        manufacturer = "Google",
        device = "blueline",
        model = "Pixel 3",
        product = "blueline",
        hardware = "blueline",
        bootloader = "blueline-1.0-6413031",
        fingerprint = "google/blueline/blueline:11/RQ3A.210905.001/7511028:user/release-keys",
        radio = "g845-00029-210812-B-7628869",
        buildId = "RQ3A.210905.001",
        sdkInt = 30,
        release = "11",
        gsfVersion = "210390105",
        vendingVersion = "81721710",
        vendingVersionString = "21.7.21-21 [0] [PR] 345627434",
        supportedAbis = setOf("arm64-v8a", "armeabi-v7a")
    ),
    PlayDeviceProfile(
        name = "Pixel 4 XL Emulator (Android 13)",
        brand = "google",
        manufacturer = "Google",
        device = "emulator_x86_64",
        model = "Pixel 4 XL",
        product = "sdk_phone_x86_64",
        hardware = "ranchu",
        bootloader = "unknown",
        fingerprint = "google/sdk_phone_x86_64/emulator_x86_64:13/TP1A.220624.021/10035358:userdebug/dev-keys",
        radio = "unknown",
        buildId = "TP1A.220624.021",
        sdkInt = 33,
        release = "13",
        gsfVersion = "230915046",
        vendingVersion = "82241710",
        vendingVersionString = "22.4.17-21 [0] [PR] 345620102",
        supportedAbis = setOf("x86_64", "x86")
    )
)
