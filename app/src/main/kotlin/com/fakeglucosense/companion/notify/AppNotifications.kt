package com.fakeglucosense.companion.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fakeglucosense.companion.R
import com.fakeglucosense.companion.domain.GlucoseUnits
import com.fakeglucosense.companion.service.GlucoseMonitorService
import com.fakeglucosense.companion.ui.MainActivity
import com.fakeglucosense.core.analysis.AlertAction
import com.fakeglucosense.core.analysis.AlertDecision
import com.fakeglucosense.core.model.AlertType

/**
 * Every notification the app posts, in one place. Channels are created at launch. Urgent glucose
 * alerts get their own channel so users can tune them separately in system settings (ADR-0010).
 */
class AppNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(CHANNEL_URGENT, context.getString(R.string.channel_alerts_urgent), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_alerts_urgent_desc) },
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_alerts_desc) },
            NotificationChannel(CHANNEL_MONITORING, context.getString(R.string.channel_monitoring), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_monitoring_desc) },
            NotificationChannel(CHANNEL_STALE, context.getString(R.string.channel_stale), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_stale_desc) },
        )
        system.createNotificationChannels(channels)
    }

    /** The persistent notification the foreground service must show. Also used for updates. */
    fun monitoring(text: String): Notification {
        val stop = PendingIntent.getService(
            context,
            REQUEST_STOP,
            Intent(context, GlucoseMonitorService::class.java).setAction(GlucoseMonitorService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_monitoring_title))
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, context.getString(R.string.notif_stop_action), stop)
            .build()
    }

    /** Posts or updates the alert for [decision]. CLEAR removes it. */
    @SuppressLint("MissingPermission")
    fun post(decision: AlertDecision) {
        if (decision.action == AlertAction.CLEAR) {
            manager.cancel(alertId(decision.type))
            return
        }
        if (!manager.areNotificationsEnabled()) return
        val urgent = decision.type.urgent
        val text = formatReading(decision.mgdl)
        val notification = NotificationCompat.Builder(context, if (urgent) CHANNEL_URGENT else CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(titleFor(decision.type)))
            .setContentText(text)
            .setContentIntent(openApp())
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            // Repeat reminders should not re-buzz the device; the first alert already did.
            .setOnlyAlertOnce(decision.action == AlertAction.REMIND)
            .build()
        manager.notify(alertId(decision.type), notification)
    }

    @SuppressLint("MissingPermission")
    fun showStale(minutes: Long) {
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_STALE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_stale_title))
            .setContentText(context.getString(R.string.notif_stale_text, minutes.toInt()))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        manager.notify(ID_STALE, notification)
    }

    fun cancelStale() = manager.cancel(ID_STALE)

    /** Plain-text reading line used in both the monitoring and alert notifications. */
    fun formatReading(mgdl: Int): String =
        context.getString(R.string.notif_reading_format, mgdl, GlucoseUnits.toMmol(mgdl))

    private fun titleFor(type: AlertType): Int = when (type) {
        AlertType.URGENT_LOW -> R.string.alert_urgent_low_title
        AlertType.LOW -> R.string.alert_low_title
        AlertType.HIGH -> R.string.alert_high_title
        AlertType.URGENT_HIGH -> R.string.alert_urgent_high_title
        AlertType.RAPID_DROP -> R.string.alert_rapid_drop_title
        AlertType.RAPID_RISE -> R.string.alert_rapid_rise_title
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_URGENT = "glucose_urgent"
        const val CHANNEL_ALERTS = "glucose_alerts"
        const val CHANNEL_MONITORING = "monitoring"
        const val CHANNEL_STALE = "data_gaps"

        const val ID_MONITORING = 1
        const val ID_STALE = 2
        private const val ID_ALERT_BASE = 100
        private const val REQUEST_OPEN = 10
        private const val REQUEST_STOP = 11

        fun alertId(type: AlertType): Int = ID_ALERT_BASE + type.ordinal
    }
}
