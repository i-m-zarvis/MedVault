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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import org.drinkless.tdlib.TdApi

/**
 * Bridges TDLib's persistent message-file download queue into MedVault's
 * offline library. The TDLib cache is copied to app-private storage so deleting
 * an offline copy never deletes Telegram's own cached media.
 */
class TelegramDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_STABLE_ID = "telegram_stable_id"
        const val KEY_TITLE = "telegram_title"
        const val KEY_FILE_NAME = "telegram_file_name"
        const val KEY_MIME_TYPE = "telegram_mime_type"
        const val KEY_FILE_ID = "telegram_file_id"
        const val KEY_CHAT_ID = "telegram_chat_id"
        const val KEY_MESSAGE_ID = "telegram_message_id"
        const val KEY_TOPIC_ID = "telegram_topic_id"
        const val KEY_DURATION = "telegram_duration"
        const val KEY_FILE_SIZE = "telegram_file_size"
        private const val CHANNEL_ID = "medvault_telegram_downloads"
        private const val BUFFER_SIZE = 256 * 1024
    }

    override suspend fun doWork(): Result {
        val fileId = inputData.getInt(KEY_FILE_ID, 0)
        val chatId = inputData.getLong(KEY_CHAT_ID, 0L)
        val messageId = inputData.getLong(KEY_MESSAGE_ID, 0L)
        if (fileId <= 0 || chatId == 0L || messageId == 0L) return Result.failure()

        val auth = TelegramAuthManager.getInstance(applicationContext) { }
        auth.start()
        var ready = false
        repeat(90) {
            if (auth.currentState is TelegramAuthManager.State.Ready) {
                ready = true
                return@repeat
            }
            delay(1000)
        }
        if (!ready && auth.currentState !is TelegramAuthManager.State.Ready) return Result.retry()

        createNotificationChannel()
        setForeground(createForegroundInfo("Preparing offline lecture", 0))

        val latestFile = AtomicReference<TdApi.File?>()
        val completion = CompletableDeferred<TdApi.File>()
        val updateListener: (TdApi.Object) -> Unit = { update ->
            if (update is TdApi.UpdateFile && update.file.id == fileId) {
                latestFile.set(update.file)
                if (update.file.local.isDownloadingCompleted && update.file.local.path.isNotBlank()) {
                    completion.complete(update.file)
                }
                val total = update.file.size.takeIf { it > 0L } ?: inputData.getLong(KEY_FILE_SIZE, 0L)
                val downloaded = update.file.local.downloadedSize.coerceAtLeast(0L)
                val progress = if (total > 0L) (downloaded * 100L / total).toInt().coerceIn(0, 100) else 0
                setProgressAsyncCompat(progress, downloaded, total)
            }
        }
        auth.addUpdateListener(updateListener)
        try {
            auth.sendRequest(TdApi.GetFile(fileId)) { result ->
                val file = result as? TdApi.File ?: return@sendRequest
                latestFile.set(file)
                if (file.local.isDownloadingCompleted && file.local.path.isNotBlank()) completion.complete(file)
            }
            auth.sendRequest(TdApi.AddFileToDownloads(fileId, chatId, messageId, 32)) { result ->
                val file = result as? TdApi.File ?: return@sendRequest
                latestFile.set(file)
                if (file.local.isDownloadingCompleted && file.local.path.isNotBlank()) completion.complete(file)
            }

            val completedFile = withTimeout(6 * 60 * 60 * 1000L) {
                while (true) {
                    coroutineContextEnsureActive()
                    val snapshot = latestFile.get()
                    if (snapshot?.local?.isDownloadingCompleted == true && snapshot.local.path.isNotBlank()) {
                        return@withTimeout snapshot
                    }
                    val update = completion
                    if (update.isCompleted) return@withTimeout update.await()
                    val total = snapshot?.size?.takeIf { it > 0L } ?: inputData.getLong(KEY_FILE_SIZE, 0L)
                    val downloaded = snapshot?.local?.downloadedSize?.coerceAtLeast(0L) ?: 0L
                    val percent = if (total > 0L) (downloaded * 100L / total).toInt().coerceIn(0, 99) else 0
                    setForeground(createForegroundInfo("Downloading " + inputData.getString(KEY_TITLE).orEmpty(), percent))
                    delay(700)
                }
                throw IllegalStateException("Unreachable")
            }

            val source = File(completedFile.local.path)
            if (!source.isFile || source.length() <= 0L) return Result.retry()
            val directory = File(applicationContext.filesDir, "downloads")
            if (!directory.exists() && !directory.mkdirs()) return Result.failure()
            val safeName = (inputData.getString(KEY_FILE_NAME) ?: "lecture_$messageId.mp4")
                .replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "lecture_$messageId.mp4" }
            val destination = File(directory, "telegram_${chatId}_${messageId}_$safeName")
            if (source.canonicalPath != destination.canonicalPath) {
                source.copyTo(destination, overwrite = true)
                FileOutputStream(destination, true).use { it.fd.sync() }
            }
            val record = DownloadedLecture(
                fileId = inputData.getString(KEY_STABLE_ID) ?: "$chatId:$messageId",
                name = inputData.getString(KEY_TITLE) ?: safeName,
                localPath = destination.absolutePath,
                downloadId = id.hashCode().toLong(),
                downloadedAt = System.currentTimeMillis(),
                accountId = "telegram",
                source = "telegram",
                chatId = chatId,
                topicId = inputData.getInt(KEY_TOPIC_ID, 0),
                messageId = messageId,
                telegramFileId = fileId,
                mimeType = inputData.getString(KEY_MIME_TYPE) ?: "video/mp4",
                durationSeconds = inputData.getInt(KEY_DURATION, 0)
            )
            DownloadStore.save(applicationContext, record)
            setProgressAsyncCompat(100, destination.length(), destination.length())
            Result.success(Data.Builder()
                .putString("file_name", record.name)
                .putString("file_path", destination.absolutePath)
                .putLong("file_size", destination.length())
                .putString("source", "telegram")
                .putString(KEY_STABLE_ID, record.fileId)
                .build())
        } catch (cancelled: CancellationException) {
            runCatching { auth.sendRequest(TdApi.CancelDownloadFile(fileId, false)) { } }
            throw cancelled
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            Result.retry()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            auth.removeUpdateListener(updateListener)
        }
    }

    private suspend fun setProgressAsyncCompat(progress: Int, downloaded: Long, total: Long) {
        setProgress(Data.Builder()
            .putString(DriveDownloadWorker.KEY_FILE_NAME, inputData.getString(KEY_TITLE) ?: "Telegram lecture")
            .putInt("progress", progress)
            .putLong(DriveDownloadWorker.KEY_DOWNLOADED, downloaded)
            .putLong(DriveDownloadWorker.KEY_TOTAL, total)
            .putString("source", "telegram")
            .build())
    }

    private suspend fun coroutineContextEnsureActive() {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Telegram lecture downloads", NotificationManager.IMPORTANCE_LOW
            ))
        }
    }

    private fun createForegroundInfo(message: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("MedVault")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id.hashCode().and(0x7fffffff), notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(id.hashCode().and(0x7fffffff), notification)
    }
}
