package com.apkupdater.repository

import com.apkupdater.data.apkmirror.AppExistsResponse
import com.google.gson.Gson
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkMirrorParsingTest {
    private val sampleJson = "{\"data\":[{\"pname\":\"com.sample\",\"exists\":true,\"apks\":[{\"version_code\":\"253074010\"}]}]}"

    @Test
    fun `parses versionCode from string`() {
        val response = Gson().fromJson(sampleJson, AppExistsResponse::class.java)
        val apk = response.data.first().apks.first()
        assertEquals(253074010L, apk.versionCode)
    }

    @Test
    fun `parses variants table and marks bundle and tv capabilities`() {
        val html = """
            <div class="table topmargin variants-table">
              <div class="table-row headerFont">
                <div class="table-cell">Variant</div>
                <div class="table-cell">Arch</div>
                <div class="table-cell">Minimum Version</div>
                <div class="table-cell">DPI</div>
                <div class="table-cell"></div>
              </div>
              <div class="table-row headerFont">
                <div class="table-cell rowheight addseparator expand pad dowrap-break-all">
                  <a class="accent_color" href="/apk/example/app/example-app-release/example-app-release-android-apk-download/">
                    example.app.release
                  </a>
                  <span class="apkm-badge success">BUNDLE</span>
                  <span class="apkm-badge success">3 S</span>
                  <span class="apkm-badge signature front">3891</span>
                  <span class="accent_color" title="Supports Android TV"></span>
                  <br><span class="colorLightBlack">210595523</span>
                  <br><span class="colorLightBlack wrapText">October 20, 2025</span>
                </div>
                <div class="table-cell">armeabi-v7a</div>
                <div class="table-cell">Android 8.0+</div>
                <div class="table-cell">320dpi</div>
                <div class="table-cell">
                  <a class="accent_color" href="/apk/example/app/example-app-release/example-app-release-android-apk-download/"></a>
                </div>
              </div>
            </div>
        """.trimIndent()

        val document = Jsoup.parse(html, "https://www.apkmirror.com")
        val variants = parseApkMirrorVariants(document)
        assertEquals(1, variants.size)
        val variant = variants.first()
        assertEquals(210595523L, variant.versionCode)
        assertEquals(
            "https://www.apkmirror.com/apk/example/app/example-app-release/example-app-release-android-apk-download/",
            variant.link
        )
        assertTrue(variant.isBundle)
        assertTrue(variant.isTv)
        assertFalse(variant.isWear)
        assertEquals("armeabi-v7a", variant.archLabel)
    }

    @Test
    fun `arch filter accepts matching abi and rejects mismatched`() {
        assertTrue(isScrapedVariantArchSupported("arm64-v8a", "arm"))
        assertTrue(isScrapedVariantArchSupported("universal", "arm"))
        assertFalse(isScrapedVariantArchSupported("x86_64", "arm"))
    }

    @Test
    fun `min api filter respects device level`() {
        assertTrue(isScrapedVariantMinApiSupported("Android 8.0+", 30))
        assertFalse(isScrapedVariantMinApiSupported("Android 13+", 31))
        assertTrue(isScrapedVariantMinApiSupported("Varies with device", 21))
    }

    @Test
    fun `parses android version labels into api levels`() {
        assertEquals(22, parseApkMirrorMinApi("Android 5.1+"))
        assertEquals(26, parseApkMirrorMinApi("Android 8.0+"))
        assertEquals(null, parseApkMirrorMinApi("Varies with device"))
    }
}
