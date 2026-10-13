package com.fakeglucosense.companion.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fakeglucosense.companion.FakeGlucoSenseApp

/** Deletes readings older than [RETENTION_DAYS] so the database stays small on long-running installs. */
class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as FakeGlucoSenseApp).container
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * DAY_MILLIS
        container.repository.purgeOlderThan(cutoff)
        return Result.success()
    }

    companion object {
        const val RETENTION_DAYS = 30L
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
