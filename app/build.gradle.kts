@file:Suppress("DEPRECATION", "UnstableApiUsage")

import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.tripleu.updater"
    compileSdk = 36

    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
            buildConfigField("String", "CUSTOM_FEED_URL", "\"\"")
            buildConfigField("String", "CUSTOM_FEED_TOKEN", "\"REDACTED_TOKEN\"")
            buildConfigField("String", "CUSTOM_FEED_NAME", "\"TripleU\"")
            buildConfigField("String", "CUSTOM_FEED_REPO", "\"TripleU613/uuupdater_public\"")
            buildConfigField("String", "CUSTOM_FEED_PATH", "\"uuupdate.json\"")
        }
        create("techeaz") {
            dimension = "distribution"
            buildConfigField("String", "CUSTOM_FEED_URL", "\"\"")
            buildConfigField(
                "String",
                "CUSTOM_FEED_TOKEN",
                "\"REDACTED_TOKEN\""
            )
            buildConfigField("String", "CUSTOM_FEED_NAME", "\"Techeaz\"")
            buildConfigField("String", "CUSTOM_FEED_REPO", "\"TripleU613/kstore_json_techeaz\"")
            buildConfigField("String", "CUSTOM_FEED_PATH", "\"uuupdate.json\"")
            buildConfigField("Boolean", "TECHEAZ_PRIVILEGED_INSTALLER", "true")
            buildConfigField("Boolean", "ENABLE_PLAY_DEBUG_LOGS", "true")
        }
    }

    val versionNameOverride = System.getenv("VERSION_NAME").orEmpty()
    val buildNumber = System.getenv("BUILD_NUMBER").orEmpty()
    defaultConfig {
        applicationId = "com.tripleu.updater" + System.getenv("BUILD_TAG").orEmpty()
        minSdk = 23
        //noinspection OldTargetApi
        targetSdk = 35
        versionCode = 52
        versionName = when {
            versionNameOverride.isNotEmpty() -> versionNameOverride
            buildNumber.isNotEmpty() -> "0.0."
            else -> "3.0.3"
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        multiDexEnabled = true
        buildConfigField("Boolean", "TECHEAZ_PRIVILEGED_INSTALLER", "false")
        buildConfigField("Boolean", "ENABLE_PLAY_DEBUG_LOGS", "false")
    }

    signingConfigs {
        create("release") {
            try {
                val props = Properties()
                props.load(FileInputStream(file("../local.properties")))
                storeFile = file(props.getProperty("keystore.file"))
                storePassword = props.getProperty("keystore.password")
                keyAlias = props.getProperty("keystore.keyalias")
                keyPassword = props.getProperty("keystore.keypassword")
            } catch (_: Exception) {
                val config = signingConfigs.getByName("debug")
                storeFile = config.storeFile
                storePassword = config.storePassword
                keyAlias = config.keyAlias
                keyPassword = config.keyPassword
                println("Signing config not found. Using debug settings.")
            }
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    applicationVariants.configureEach {
        outputs.configureEach {
            val variant = this as BaseVariantOutputImpl
            val flavorSuffix = flavorName.takeIf { !it.isNullOrBlank() }?.let { "-$it" } ?: ""
            variant.outputFileName = defaultConfig.applicationId + flavorSuffix + "-" + buildType.name + ".apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        warning.addAll(arrayOf("ExtraTranslation", "MissingTranslation", "MissingQuantity"))
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.navigation:navigation-runtime-ktx:2.8.4")
    implementation("androidx.tv:tv-foundation:1.0.0-alpha10")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.github.rumboalla.KryptoPrefs:kryptoprefs-gson:0.4.3")
    implementation("com.github.rumboalla.KryptoPrefs:kryptoprefs:0.4.3")
    implementation("com.github.topjohnwu.libsu:core:5.2.1")
    implementation("com.aurora:gplayapi:3.2.11")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.github.g00fy2:versioncompare:1.5.0")
    implementation("io.insert-koin:koin-android:3.5.3")
    implementation("io.insert-koin:koin-androidx-compose:3.5.3")
    implementation("org.jsoup:jsoup:1.16.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")

    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-common-ktx")
    implementation("com.google.firebase:firebase-database-ktx")

    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.firebase:firebase-auth:24.0.1")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")

    debugImplementation(platform("androidx.compose:compose-bom:2024.10.00"))
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

