package com.medvault.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

class DriveDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationId = (id.hashCode() and 0x7fffffff).coerceAtLeast(4101)

    companion object {
        const val KEY_FILE_ID = "file_id"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_ACCOUNT_ID = "account_id"

        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"

        private const val CHANNEL_ID = "medvault_downloads"

        // Avoid flooding WorkManager's database with progress updates.
        private const val PROGRESS_UPDATE_INTERVAL = 1000L
        private const val NOTIFICATION_UPDATE_INTERVAL = 2000L

        private const val BUFFER_SIZE = 128 * 1024
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {

        val fileId = inputData.getString(KEY_FILE_ID)
            ?: return@withContext Result.failure()

        val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: "default"

        val fileName = inputData.getString(KEY_FILE_NAME)
            ?: "lecture_$fileId.mp4"

        val safeName = fileName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "lecture_$fileId.mp4" }

        val token = DriveTokenVault.get(applicationContext, accountId)
            ?: return@withContext Result.failure(
                Data.Builder()
                    .putString(
                        "error",
                        "Reconnect Google Drive and retry."
                    )
                    .putString(KEY_FILE_NAME, safeName)
                    .build()
            )

        val downloadDirectory = File(
            applicationContext.filesDir,
            "downloads"
        )

        if (
            !downloadDirectory.exists() &&
            !downloadDirectory.mkdirs()
        ) {
            return@withContext Result.failure()
        }

        val finalFile = File(
            downloadDirectory,
            "${DownloadRepository.accountKey(accountId)}_${fileId}_$safeName"
        )

        val partialFile = File(
            downloadDirectory,
            "${DownloadRepository.accountKey(accountId)}_${fileId}_$safeName.part"
        )

        fun resultData(file: File): Data {
            return Data.Builder()
                .putString("file_path", file.absolutePath)
                .putString("file_name", safeName)
                .putLong("file_size", file.length())
                .putString(KEY_FILE_NAME, safeName)
                .putInt(KEY_PROGRESS, 100)
                .putLong(KEY_DOWNLOADED, file.length())
                .putLong(KEY_TOTAL, file.length())
                .build()
        }

        fun registerDownload(file: File) {
            DownloadStore.save(
                applicationContext,
                DownloadedLecture(
                    fileId = fileId,
                    name = safeName,
                    localPath = file.absolutePath,
                    downloadId = 0L,
                    downloadedAt = System.currentTimeMillis(),
                    accountId = accountId
                )
            )
        }

        // Already downloaded: register it and finish.
        if (finalFile.isFile && finalFile.length() > 0L) {
            registerDownload(finalFile)

            return@withContext Result.success(
                resultData(finalFile)
            )
        }

        createNotificationChannel()

        setForeground(
            createForegroundInfo(
                "Preparing $safeName",
                0
            )
        )

        var connection: HttpURLConnection? = null

        try {
            val existingBytes = if (partialFile.isFile) {
                partialFile.length()
            } else {
                0L
            }

            val encodedId = URLEncoder.encode(
                fileId,
                "UTF-8"
            )

            val url = URL(
                "https://www.googleapis.com/drive/v3/files/" +
                    "$encodedId?alt=media&supportsAllDrives=true"
            )

            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true

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
                        .putString(KEY_FILE_NAME, safeName)
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
                        .putString(KEY_FILE_NAME, safeName)
                        .build()
                )
            }

            if (responseCode !in listOf(200, 206)) {
                return@withContext Result.retry()
            }

            // Append only when the server confirms the exact requested byte offset.
            val append = responseCode == 206
            val contentRange = connection.getHeaderField("Content-Range")
            if (append) {
                val returnedStart = contentRange
                    ?.substringAfter("bytes ", "")
                    ?.substringBefore("-")
                    ?.toLongOrNull()
                if (returnedStart == null || returnedStart != existingBytes) {
                    return@withContext Result.failure(
                        Data.Builder()
                            .putString("error", "Drive returned an invalid resume range. Retry this download.")
                            .putString(KEY_FILE_NAME, safeName)
                            .build()
                    )
                }
            }
            val startBytes = if (append) existingBytes else 0L

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
            var lastProgressUpdate = System.currentTimeMillis()
            var lastNotificationUpdate = 0L

            // Publish the lecture name immediately.
            setProgress(
                Data.Builder()
                    .putString(KEY_FILE_NAME, safeName)
                    .putInt(KEY_PROGRESS, 0)
                    .putLong(KEY_DOWNLOADED, downloadedBytes)
                    .putLong(KEY_TOTAL, totalBytes)
                    .build()
            )

            connection.inputStream.use { input ->

                FileOutputStream(
                    partialFile,
                    append
                ).use { output ->

                    val buffer = ByteArray(BUFFER_SIZE)

                    while (true) {
                        coroutineContext.ensureActive()

                        val count = input.read(buffer)

                        if (count == -1) break

                        output.write(buffer, 0, count)
                        downloadedBytes += count

                        val now = System.currentTimeMillis()

                        val progress = if (totalBytes > 0L) {
                            (
                                downloadedBytes * 100L /
                                    totalBytes
                                ).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }

                        // Update WorkManager only once per second.
                        if (
                            now - lastProgressUpdate >=
                                PROGRESS_UPDATE_INTERVAL
                        ) {
                            setProgress(
                                Data.Builder()
                                    .putString(
                                        KEY_FILE_NAME,
                                        safeName
                                    )
                                    .putInt(
                                        KEY_PROGRESS,
                                        progress
                                    )
                                    .putLong(
                                        KEY_DOWNLOADED,
                                        downloadedBytes
                                    )
                                    .putLong(
                                        KEY_TOTAL,
                                        totalBytes
                                    )
                                    .build()
                            )

                            lastProgressUpdate = now
                        }

                        // Notifications update less frequently.
                        if (
                            now - lastNotificationUpdate >=
                                NOTIFICATION_UPDATE_INTERVAL
                        ) {
                            setForeground(
                                createForegroundInfo(
                                    "Downloading $safeName",
                                    progress
                                )
                            )

                            lastNotificationUpdate = now
                        }
                    }

                    output.flush()
                    output.fd.sync()
                }
            }

            if (
                totalBytes > 0L &&
                partialFile.length() != totalBytes
            ) {
                return@withContext Result.retry()
            }

            if (
                !partialFile.renameTo(finalFile)
            ) {
                partialFile.copyTo(
                    finalFile,
                    overwrite = true
                )
                partialFile.delete()
            }

            registerDownload(finalFile)

            // Final progress update.
            setProgress(
                Data.Builder()
                    .putString(KEY_FILE_NAME, safeName)
                    .putInt(KEY_PROGRESS, 100)
                    .putLong(
                        KEY_DOWNLOADED,
                        finalFile.length()
                    )
                    .putLong(
                        KEY_TOTAL,
                        finalFile.length()
                    )
                    .build()
            )

            Result.success(resultData(finalFile))

        } catch (e: CancellationException) {
            // Pause keeps the partial file; explicit cancel removes it in the repository.
            if (!DownloadRepository.isPaused(applicationContext, fileId, accountId)) {
                partialFile.delete()
            }
            throw e
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (e is IOException && (message.contains("no space", ignoreCase = true) || message.contains("ENOSPC", ignoreCase = true))) {
                Result.failure(
                    Data.Builder()
                        .putString("error", "Not enough device storage. Free space and retry.")
                        .putString(KEY_FILE_NAME, safeName)
                        .build()
                )
            } else {
                Result.retry()
            }
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
            .setSmallIcon(
                android.R.drawable.stat_sys_download
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(
                100,
                progress,
                progress == 0
            )
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(
                notificationId,
                notification
            )
        }
    }
}
