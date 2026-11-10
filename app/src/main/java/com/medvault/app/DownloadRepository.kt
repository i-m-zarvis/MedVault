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

        fun displayState(state: WorkInfo.State): String {
            return when (state) {
                WorkInfo.State.ENQUEUED -> "Queued"
                WorkInfo.State.RUNNING -> "Downloading"
                WorkInfo.State.SUCCEEDED -> "Downloaded"
                WorkInfo.State.FAILED -> "Failed"
                WorkInfo.State.BLOCKED -> "Waiting"
                WorkInfo.State.CANCELLED -> "Cancelled"
            }
        }
    }

    /**
     * Add a lecture to the download queue.
     * Existing unfinished downloads are not duplicated.
     */
    fun enqueue(file: DriveItem): UUID {
        val input = Data.Builder()
            .putString(DriveDownloadWorker.KEY_FILE_ID, file.id)
            .putString(DriveDownloadWorker.KEY_FILE_NAME, file.name)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DriveDownloadWorker>()
            .setInputData(input)
            .setConstraints(constraints)
            .addTag(DOWNLOAD_TAG)
            .addTag("$UNIQUE_WORK_PREFIX${file.id}")
            .build()

        workManager.enqueueUniqueWork(
            "$UNIQUE_WORK_PREFIX${file.id}",
            ExistingWorkPolicy.KEEP,
            request
        )

        return request.id
    }

    /**
     * Observe all download tasks.
     */
    fun observeAll() =
        workManager.getWorkInfosByTagLiveData(DOWNLOAD_TAG)

    /**
     * Observe a particular download.
     */
    fun observe(id: UUID) =
        workManager.getWorkInfoByIdLiveData(id)

    /**
     * Pause by stopping the worker.
     * The worker's partial file is retained.
     */
    fun pause(id: UUID) {
        workManager.cancelWorkById(id)
    }

    /**
     * Resume by enqueueing the same lecture again.
     * The worker can continue from its partial file
     * when the Drive server supports HTTP Range requests.
     */
    fun resume(file: DriveItem): UUID {
        return enqueue(file)
    }

    /**
     * Cancel a download.
     * This stops the worker but does not delete its partial file.
     */
    fun cancel(id: UUID) {
        workManager.cancelWorkById(id)
    }

    /**
     * Cancel every MedVault download task.
     */
    fun cancelAll() {
        workManager.cancelAllWorkByTag(DOWNLOAD_TAG)
    }

    /**
     * Retrieve the WorkManager task ID for a lecture.
     */
    fun findDownload(fileId: String) =
        workManager.getWorkInfosByTagLiveData(
            "$UNIQUE_WORK_PREFIX$fileId"
        )

    /**
     * Remove completed WorkManager records when safe.
     * This does not delete downloaded lecture files.
     */
    fun pruneWork() {
        workManager.pruneWork()
    }
}
