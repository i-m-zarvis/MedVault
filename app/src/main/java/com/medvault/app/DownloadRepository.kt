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
import java.util.UUID

class DownloadRepository(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    companion object {
        private const val DOWNLOAD_TAG = "medvault_download"
        private const val UNIQUE_WORK_PREFIX = "medvault_download_"
        private const val PREFS = "medvault_download_controls"
        private const val PAUSED_IDS = "paused_file_ids"

        fun pausedIds(context: Context): Set<String> =
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(PAUSED_IDS, emptySet())
                ?.toSet() ?: emptySet()

        fun isPaused(context: Context, fileId: String): Boolean =
            fileId in pausedIds(context)

        private fun setPaused(context: Context, fileId: String, paused: Boolean) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val ids = prefs.getStringSet(PAUSED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (paused) ids.add(fileId) else ids.remove(fileId)
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
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(DOWNLOAD_TAG)
            .addTag("$UNIQUE_WORK_PREFIX${file.id}")
            .build()

    fun enqueue(file: DriveItem): UUID {
        setPaused(appContext, file.id, false)
        val request = request(file)
        workManager.enqueueUniqueWork(
            "$UNIQUE_WORK_PREFIX${file.id}",
            ExistingWorkPolicy.KEEP,
            request
        )
        return request.id
    }

    fun observeAll() = workManager.getWorkInfosByTagLiveData(DOWNLOAD_TAG)
    fun observe(id: UUID) = workManager.getWorkInfoByIdLiveData(id)

    fun pause(id: UUID, fileId: String) {
        setPaused(appContext, fileId, true)
        workManager.cancelWorkById(id)
    }

    fun resume(file: DriveItem): UUID {
        setPaused(appContext, file.id, false)
        val request = request(file)
        workManager.enqueueUniqueWork(
            "$UNIQUE_WORK_PREFIX${file.id}",
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    fun cancel(id: UUID, fileId: String) {
        setPaused(appContext, fileId, false)
        workManager.cancelWorkById(id)
        File(appContext.filesDir, "downloads").listFiles()
            ?.filter { it.name.startsWith("${fileId}_") && it.name.endsWith(".part") }
            ?.forEach { it.delete() }
    }

    fun cancelAll() {
        pausedIds(appContext).forEach { setPaused(appContext, it, false) }
        workManager.cancelAllWorkByTag(DOWNLOAD_TAG)
    }

    fun findDownload(fileId: String) =
        workManager.getWorkInfosByTagLiveData("$UNIQUE_WORK_PREFIX$fileId")

    fun pruneWork() = workManager.pruneWork()
}
