package com.example.youyou_album.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/** Silently fills missing local hashes; Room then recomputes the merged timeline. */
@HiltWorker
class ContentHashBackfillWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mediaScanService: MediaScanService,
) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "content_hash_backfill"

        fun enqueue(context: Context) {
            // KEEP can lose a scan that finishes after a running worker has read its last batch.
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ContentHashBackfillWorker>().build(),
            )
        }
    }

    override suspend fun doWork(): Result = try {
        mediaScanService.backfillContentHash()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
