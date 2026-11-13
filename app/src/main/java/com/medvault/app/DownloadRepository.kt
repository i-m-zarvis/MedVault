package com.medvault.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class DownloadRepository(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    companion object {
        private const val DOWNLOAD_TAG = "medvault_download"
        private const val SOURCE_TAG_PREFIX = "medvault_source_"
        private const val UNIQUE_WORK_PREFIX = "medvault_download_"
        private const val ACCOUNT_TAG_PREFIX = "medvault_account_"
        private const val PREFS = "medvault_download_controls"
        private const val PAUSED_IDS = "paused_file_ids"

        fun accountKey(accountId: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(accountId.toByteArray(Charsets.UTF_8))
            return bytes.take(8).joinToString("") { "%02x".format(it) }
        }

        private fun workName(file: DriveItem) =
            "$UNIQUE_WORK_PREFIX" + accountKey(file.accountId) + "_" + file.id

        private fun pausedKey(fileId: String, accountId: String) =
            accountKey(accountId) + ":" + fileId

        fun pausedIds(context: Context): Set<String> =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(PAUSED_IDS, emptySet())?.toSet() ?: emptySet()

        fun isPaused(context: Context, fileId: String, accountId: String = "default"): Boolean =
            pausedKey(fileId, accountId) in pausedIds(context)

        private fun setPaused(context: Context, fileId: String, accountId: String, paused: Boolean) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val ids = prefs.getStringSet(PAUSED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
            val key = pausedKey(fileId, accountId)
            if (paused) ids.add(key) else ids.remove(key)
            prefs.edit().putStringSet(PAUSED_IDS, ids).apply()
        }

        fun displayState(state: WorkInfo.State): String = when (state) {
            WorkInfo.State.ENQUEUED -> "Queued"
            WorkInfo.State.RUNNING -> "Downloading"
            WorkInfo.State.SUCCEEDED -> "Downloaded"
            WorkInfo.State.FAILED -> "Failed"
            WorkInfo.State.BLOCKED -> "Waiting"
            WorkInfo.State.CANCELLED -> "Cancelled"
        }
    }

    private fun request(file: DriveItem) =
        OneTimeWorkRequestBuilder<DriveDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(DriveDownloadWorker.KEY_FILE_ID, file.id)
                    .putString(DriveDownloadWorker.KEY_FILE_NAME, file.name)
                    .putString(DriveDownloadWorker.KEY_ACCOUNT_ID, file.accountId)
                    .build()
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(DOWNLOAD_TAG)
            .addTag("$UNIQUE_WORK_PREFIX" + file.id)
            .addTag(ACCOUNT_TAG_PREFIX + accountKey(file.accountId))
            .build()

    fun enqueue(file: DriveItem): UUID {
        setPaused(appContext, file.id, file.accountId, false)
        val request = request(file)
        workManager.enqueueUniqueWork(workName(file), ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    fun enqueueTelegram(lecture: TelegramLecture): UUID {
        val uniqueKey = "telegram_" + lecture.chatId + "_" + lecture.messageId
        val request = OneTimeWorkRequestBuilder<TelegramDownloadWorker>()
            .setInputData(Data.Builder()
                .putString(TelegramDownloadWorker.KEY_STABLE_ID, lecture.stableId)
                .putString(TelegramDownloadWorker.KEY_TITLE, lecture.title)
                .putString(TelegramDownloadWorker.KEY_FILE_NAME, lecture.fileName)
                .putString(TelegramDownloadWorker.KEY_MIME_TYPE, lecture.mimeType)
                .putInt(TelegramDownloadWorker.KEY_FILE_ID, lecture.fileId)
                .putLong(TelegramDownloadWorker.KEY_CHAT_ID, lecture.chatId)
                .putLong(TelegramDownloadWorker.KEY_MESSAGE_ID, lecture.messageId)
                .putInt(TelegramDownloadWorker.KEY_TOPIC_ID, lecture.topicId)
                .putInt(TelegramDownloadWorker.KEY_DURATION, lecture.durationSeconds)
                .putLong(TelegramDownloadWorker.KEY_FILE_SIZE, lecture.fileSizeBytes)
                .build())
            .addTag(DOWNLOAD_TAG)
            .addTag(SOURCE_TAG_PREFIX + "telegram")
            .addTag("medvault_telegram_id_" + lecture.stableId)
            .addTag(UNIQUE_WORK_PREFIX + uniqueKey)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_PREFIX + uniqueKey, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    fun observeAll() = workManager.getWorkInfosByTagLiveData(DOWNLOAD_TAG)
    fun observe(id: UUID) = workManager.getWorkInfoByIdLiveData(id)

    fun pause(id: UUID, fileId: String, accountId: String = "default") {
        setPaused(appContext, fileId, accountId, true)
        workManager.cancelWorkById(id)
    }

    fun resume(file: DriveItem): UUID {
        setPaused(appContext, file.id, file.accountId, false)
        val request = request(file)
        workManager.enqueueUniqueWork(workName(file), ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    fun cancel(id: UUID, fileId: String, accountId: String = "default") {
        setPaused(appContext, fileId, accountId, false)
        workManager.cancelWorkById(id)
        val prefix = accountKey(accountId) + "_" + fileId + "_"
        File(appContext.filesDir, "downloads").listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".part") }
            ?.forEach { it.delete() }
    }

    fun cancelAll() {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PAUSED_IDS).apply()
        workManager.cancelAllWorkByTag(DOWNLOAD_TAG)
    }

    fun pruneWork() = workManager.pruneWork()
}
