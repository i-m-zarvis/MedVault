package com.medvault.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.Data
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

class DriveDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_FILE_ID = "file_id"
        const val KEY_FILE_NAME = "file_name"

        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"

        private const val CHANNEL_ID = "medvault_downloads"
        private const val NOTIFICATION_ID = 4101
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {

        val fileId = inputData.getString(KEY_FILE_ID)
            ?: return@withContext Result.failure()

        val fileName = inputData.getString(KEY_FILE_NAME)
            ?: "lecture_$fileId.mp4"

        val token = DriveTokenVault.get(applicationContext)
            ?: return@withContext Result.failure(
                Data.Builder()
                    .putString("error", "Reconnect Google Drive and retry.")
                    .build()
            )

        val downloadDirectory = File(
            applicationContext.filesDir,
            "downloads"
        )

        if (!downloadDirectory.exists() && !downloadDirectory.mkdirs()) {
            return@withContext Result.failure()
        }

        val safeName = fileName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "lecture_$fileId.mp4" }

        val finalFile = File(
            downloadDirectory,
            "${fileId}_$safeName"
        )

        val partialFile = File(
            downloadDirectory,
            "${fileId}_$safeName.part"
        )

        fun registerDownload(file: File) {
            DownloadStore.save(
                applicationContext,
                DownloadedLecture(
                    fileId = fileId,
                    name = safeName,
                    localPath = file.absolutePath,
                    downloadId = 0L,
                    downloadedAt = System.currentTimeMillis()
                )
            )
        }

        // Register an already completed file.
        if (finalFile.exists() && finalFile.length() > 0L) {
            registerDownload(finalFile)

            return@withContext Result.success(
                Data.Builder()
                    .putString("file_path", finalFile.absolutePath)
                    .putString("file_name", safeName)
                    .putLong("file_size", finalFile.length())
                    .build()
            )
        }

        createNotificationChannel()

        setForeground(
            createForegroundInfo("Preparing download", 0)
        )

        var connection: HttpURLConnection? = null

        try {
            val existingBytes = partialFile
                .takeIf { it.exists() }
                ?.length() ?: 0L

            val url = URL(
                "https://www.googleapis.com/drive/v3/files/" +
                    "${URLEncoder.encode(fileId, "UTF-8")}?alt=media"
            )

            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 20000
                readTimeout = 30000

                if (existingBytes > 0L) {
                    setRequestProperty(
                        "Range",
                        "bytes=$existingBytes-"
                    )
                }
            }

            val responseCode = connection.responseCode

            if (responseCode == 401) {
                return@withContext Result.failure(
                    Data.Builder()
                        .putString(
                            "error",
                            "Google access expired. Reconnect Drive and retry."
                        )
                        .build()
                )
            }

            if (responseCode == 403 || responseCode == 404) {
                return@withContext Result.failure(
                    Data.Builder()
                        .putString(
                            "error",
                            "Google Drive denied access or the file was not found."
                        )
                        .build()
                )
            }

            if (responseCode !in listOf(200, 206)) {
                return@withContext Result.retry()
            }

            // If the server ignores Range and returns 200,
            // restart the partial file instead of appending.
            val append = responseCode == 206
            val startBytes = if (append) existingBytes else 0L

            val contentRange = connection.getHeaderField("Content-Range")

            val rangeTotal = contentRange
                ?.substringAfterLast("/")
                ?.toLongOrNull()

            val responseLength = connection.contentLengthLong

            val totalBytes = rangeTotal
                ?: if (responseLength > 0L) {
                    startBytes + responseLength
                } else {
                    -1L
                }

            var downloadedBytes = startBytes
            var lastNotificationUpdate = 0L

            connection.inputStream.use { input ->
                FileOutputStream(partialFile, append).use { output ->

                    val buffer = ByteArray(64 * 1024)

                    while (true) {
                        coroutineContext.ensureActive()

                        val count = input.read(buffer)
                        if (count == -1) break

                        output.write(buffer, 0, count)
                        downloadedBytes += count

                        val progress = if (totalBytes > 0L) {
                            ((downloadedBytes * 100L) / totalBytes)
                                .toInt()
                                .coerceIn(0, 100)
                        } else {
                            0
                        }

                        setProgress(
                            Data.Builder()
                                .putInt(KEY_PROGRESS, progress)
                                .putLong(KEY_DOWNLOADED, downloadedBytes)
                                .putLong(KEY_TOTAL, totalBytes)
                                .build()
                        )

                        val now = System.currentTimeMillis()

                        if (now - lastNotificationUpdate >= 1000L) {
                            setForeground(
                                createForegroundInfo(
                                    "Downloading $safeName",
                                    progress
                                )
                            )
                            lastNotificationUpdate = now
                        }
                    }

                    output.fd.sync()
                }
            }

            if (
                totalBytes > 0L &&
                partialFile.length() != totalBytes
            ) {
                return@withContext Result.retry()
            }

            if (!partialFile.renameTo(finalFile)) {
                partialFile.copyTo(finalFile, overwrite = true)
                partialFile.delete()
            }

            // Register only after the complete file is available.
            registerDownload(finalFile)

            setProgress(
                Data.Builder()
                    .putInt(KEY_PROGRESS, 100)
                    .putLong(KEY_DOWNLOADED, finalFile.length())
                    .putLong(KEY_TOTAL, finalFile.length())
                    .build()
            )

            Result.success(
                Data.Builder()
                    .putString("file_path", finalFile.absolutePath)
                    .putString("file_name", safeName)
                    .putLong("file_size", finalFile.length())
                    .build()
            )

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        } finally {
            connection?.disconnect()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            val channel = NotificationChannel(
                CHANNEL_ID,
                "MedVault downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Lecture download progress"
            }

            manager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(
        message: String,
        progress: Int
    ): ForegroundInfo {

        val notification = NotificationCompat.Builder(
            applicationContext,
            CHANNEL_ID
        )
            .setContentTitle("MedVault")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
