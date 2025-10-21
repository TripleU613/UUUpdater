package com.apkupdater.util

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream


class Downloader(
    private val client: OkHttpClient,
    private val apkPureClient: OkHttpClient,
    private val auroraClient: OkHttpClient,
    private val dir: File
) {

    fun download(url: String, onProgress: ((Long) -> Unit)? = null): File {
        val file = File(dir, randomUUID())
        return runCatching {
            val response = clientFor(url).newCall(downloadRequest(url)).execute()
            response.use {
                if (!response.isSuccessful) {
                    throw IllegalStateException("Download failed with code ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Empty response body")
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytesCopied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            bytesCopied += read
                            onProgress?.invoke(bytesCopied)
                        }
                        output.flush()
                    }
                }
            }
            file
        }.onFailure {
            file.delete()
            Log.e("Downloader", "Error downloading", it)
        }.getOrThrow()
    }

    fun downloadStream(url: String): InputStream? = runCatching {
        val response = clientFor(url).newCall(downloadRequest(url)).execute()
        if (response.isSuccessful) {
            response.body?.let { return it.byteStream() }
        } else {
            response.close()
            Log.e("Downloader", "Download failed with error code: ${response.code}")
        }
        null
    }.getOrElse {
        Log.e("Downloader", "Error downloading", it)
        null
    }

    fun getContentLength(url: String): Long = runCatching {
        val request = Request.Builder().url(url).head().build()
        clientFor(url).newCall(request).execute().use { response ->
            response.header("Content-Length")?.toLongOrNull() ?: 0L
        }
    }.getOrElse {
        Log.e("Downloader", "Error getting content length", it)
        0L
    }

    fun createTempFile(suffix: String = ""): File = File(dir, randomUUID() + suffix)

    private fun clientFor(url: String) = when {
        url.contains("apkpure", ignoreCase = true) -> apkPureClient
        url.contains("aurora", ignoreCase = true) -> auroraClient
        else -> client
    }

    private fun downloadRequest(url: String) = Request.Builder().url(url).build()

}
