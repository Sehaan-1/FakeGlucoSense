package com.fakeglucosense.companion.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fakeglucosense.companion.FakeGlucoSenseApp
import com.fakeglucosense.companion.domain.StalenessPolicy
import kotlinx.coroutines.flow.first

/**
 * Raises a "no data" notification when readings stop arriving while monitoring is on. It runs
 * independently of the service, so it still fires if the service was killed.
 */
class StaleSignalWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as FakeGlucoSenseApp).container
        val settings = container.settingsStore.settings.first()
        val notifications = container.notifications

        if (!settings.monitoringEnabled) {
            notifications.cancelStale()
            return Result.success()
        }
        val latest = container.repository.latest()?.epochMillis
        val now = System.currentTimeMillis()
        if (latest != null && StalenessPolicy.isStale(latest, now)) {
            notifications.showStale(StalenessPolicy.minutesSince(latest, now))
        } else {
            notifications.cancelStale()
        }
        return Result.success()
    }
}
