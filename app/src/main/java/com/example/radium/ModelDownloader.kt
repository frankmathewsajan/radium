package com.example.radium

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloader {

    private const val MIN_VALID_MODEL_BYTES = 50L * 1024L * 1024L
    private const val BUFFER_SIZE = 32 * 1024

    fun downloadModelOta(
        targetUrl: String,
        destFile: File,
        onProgress: (Int, String) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val tempFile = File(destFile.parentFile, "${destFile.name}.part")
        var connection: HttpURLConnection? = null

        try {
            if (destFile.exists() && destFile.length() >= MIN_VALID_MODEL_BYTES) {
                onProgress(100, "Model already available")
                onSuccess()
                return
            }

            connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                connect()
            }

            if (connection.responseCode !in 200..299) {
                onError("Server returned HTTP ${connection.responseCode}")
                return
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L
            var lastUiUpdate = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var count: Int

                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        downloadedBytes += count

                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate >= 200L) {
                            val progress = if (totalBytes > 0) {
                                ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            val downloadedMb = downloadedBytes / (1024L * 1024L)
                            val totalMb = if (totalBytes > 0) totalBytes / (1024L * 1024L) else 0L
                            onProgress(progress, "$downloadedMb MB / $totalMb MB")
                            lastUiUpdate = now
                        }
                    }
                    output.flush()
                }
            }

            if (tempFile.length() < MIN_VALID_MODEL_BYTES) {
                tempFile.delete()
                onError("Downloaded file is too small to be a valid model.")
                return
            }

            if (destFile.exists()) {
                destFile.delete()
            }
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }

            onProgress(100, "Download complete")
            onSuccess()
        } catch (e: Exception) {
            tempFile.delete()
            onError(e.message ?: "Unknown download error")
        } finally {
            connection?.disconnect()
        }
    }
}
