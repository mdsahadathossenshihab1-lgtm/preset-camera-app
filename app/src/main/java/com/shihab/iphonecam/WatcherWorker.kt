package com.shihab.iphonecam

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic backstop sweep (every 15 minutes, the shortest WorkManager
 * interval) for the default-camera watcher. Catches photos the foreground
 * service missed — e.g. taken while the service was stopped, the phone
 * rebooted, or the app was killed — and retries deletion of any originals
 * still queued (e.g. after the user grants the one-time delete
 * permission in settings).
 */
class WatcherWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            if (!WatcherStore(applicationContext).enabled) return Result.success()
            WatcherProcessor.processNewCameraPhotos(applicationContext)
            DeleteHelper.deletePendingOriginals(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "watcher-sweep"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatcherWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
