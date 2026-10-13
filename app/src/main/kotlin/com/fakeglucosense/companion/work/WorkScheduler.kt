package com.fakeglucosense.companion.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Background checks that must survive app death (ADR-0007). Periodic work has a 15-minute floor,
 * and Doze may delay it, so these are backstops, not the real-time path.
 */
object WorkScheduler {
    const val STALE_WORK = "stale-signal-check"
    const val RETENTION_WORK = "retention-purge"

    fun ensureScheduled(context: Context) {
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(
            STALE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<StaleSignalWorker>(15, TimeUnit.MINUTES).build(),
        )
        work.enqueueUniquePeriodicWork(
            RETENTION_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}
