package com.tripleu.updater.util

import com.tripleu.updater.util.NoOpLog as Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.io.buffered

private const val MAX_DOWNLOAD_RETRIES = 3


class Downloader(
    private val client: OkHttpClient,
    private val apkPureClient: OkHttpClient,
    private val auroraClient: OkHttpClient,
    private val dir: File
) {

    init {
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }

    fun download(url: String, onProgress: ((Long, Long) -> Unit)? = null): File {
        val file = File(dir, randomUUID())
        var attempt = 0
        var downloaded = 0L
        var totalBytes = -1L

        try {
            while (true) {
                val requestBuilder = Request.Builder().url(url)
                if (downloaded > 0L) {
                    requestBuilder.header("Range", "bytes=$downloaded-")
                }
                val response = clientFor(url).newCall(requestBuilder.build()).execute()
                try {
                    val isPartial = response.code == 206
                    val isSuccessful = response.isSuccessful || isPartial
                    if (!isSuccessful) {
                        throw IllegalStateException("Download failed with code ${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty response body")
                    response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let {
                        totalBytes = it
                    }
                    val bodyLength = body.contentLength()
                    if (bodyLength > 0L) {
                        totalBytes = when {
                            totalBytes > 0L -> totalBytes
                            isPartial && downloaded > 0L -> downloaded + bodyLength
                            else -> bodyLength
                        }
                    }

                    val append = downloaded > 0L && isPartial
                    if (!append && downloaded > 0L) {
                        downloaded = 0L
                        file.delete()
                    }

                    val output = FileOutputStream(file, append).buffered()
                    try {
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                val reportedTotal = if (totalBytes > 0L) totalBytes else 0L
                                onProgress?.invoke(downloaded, reportedTotal)
                            }
                        }
                        output.flush()
                    } finally {
                        output.close()
                    }

                    val expectedTotal = if (totalBytes > 0L) totalBytes else downloaded
                    if (downloaded >= expectedTotal) {
                        break
                    }
                    attempt += 1
                    if (attempt > MAX_DOWNLOAD_RETRIES) {
                        file.delete()
                        throw IOException("Download incomplete after $MAX_DOWNLOAD_RETRIES attempts")
                    } else {
                        Log.w("Downloader", "Download incomplete (attempt $attempt/$MAX_DOWNLOAD_RETRIES); retrying")
                    }
                    continue
                } catch (exception: IOException) {
                    attempt += 1
                    if (attempt > MAX_DOWNLOAD_RETRIES) {
                        file.delete()
                        Log.e("Downloader", "Download failed permanently", exception)
                        throw exception
                    } else {
                        Log.w("Downloader", "Download interrupted (attempt $attempt/$MAX_DOWNLOAD_RETRIES)", exception)
                    }
                    continue
                } finally {
                    response.close()
                }
            }
            return file
        } catch (throwable: Throwable) {
            file.delete()
            Log.e("Downloader", "Error downloading", throwable)
            throw throwable
        }
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

    fun clearStaleDownloads() {
        dir.listFiles()?.forEach { file ->
            runCatching {
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            }.onFailure {
                Log.w("Downloader", "Failed to delete stale download ${file.absolutePath}", it)
            }
        }
    }

}
