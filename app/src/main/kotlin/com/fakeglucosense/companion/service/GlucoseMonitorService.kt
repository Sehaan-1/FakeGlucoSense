package com.fakeglucosense.companion.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fakeglucosense.companion.FakeGlucoSenseApp
import com.fakeglucosense.companion.R
import com.fakeglucosense.companion.data.source.LinkPhase
import com.fakeglucosense.companion.data.source.LinkState
import com.fakeglucosense.companion.data.source.SourceEvent
import com.fakeglucosense.companion.di.AppContainer
import com.fakeglucosense.companion.domain.AlertEngine
import com.fakeglucosense.companion.notify.AppNotifications
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps monitoring alive while the UI is closed (ADR-0007).
 *
 * Runs as a `connectedDevice` foreground service with a persistent notification. It reads from
 * the selected source, stores new readings, and runs the alert engine. START_STICKY lets the
 * system restart it after process death, and the persisted `monitoringEnabled` flag decides
 * whether that restart should keep running.
 */
class GlucoseMonitorService : LifecycleService() {

    private val container: AppContainer get() = (application as FakeGlucoSenseApp).container
    private var monitorJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action

        if (action == ACTION_STOP) {
            lifecycleScope.launch {
                container.settingsStore.update { it.copy(monitoringEnabled = false) }
                shutDown()
            }
            return START_NOT_STICKY
        }

        // Promote before any slow work. Android kills a foreground start that takes too long.
        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START -> lifecycleScope.launch {
                container.settingsStore.update { it.copy(monitoringEnabled = true) }
                startMonitoring()
            }
            // Re-reads settings by cancelling the current connection first. Stop and start sent
            // back to back would race inside this service.
            ACTION_RESTART -> lifecycleScope.launch {
                cancelMonitoring()
                container.settingsStore.update { it.copy(monitoringEnabled = true) }
                startMonitoring()
            }
            else -> lifecycleScope.launch {
                // A sticky restart carries no intent. Honour the last user choice.
                if (container.settingsStore.settings.first().monitoringEnabled) startMonitoring() else shutDown()
            }
        }
        return START_STICKY
    }

    private fun promoteToForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            AppNotifications.ID_MONITORING,
            container.notifications.monitoring(getString(R.string.notif_monitoring_waiting)),
            foregroundType(),
        )
        true
    } catch (e: RuntimeException) {
        // On Android 12+ a background start can be refused (ForegroundServiceStartNotAllowedException).
        // Stopping cleanly beats crashing the process.
        false
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = lifecycleScope.launch { runMonitoring() }
    }

    private fun cancelMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        container.live.setLink(null)
    }

    private suspend fun runMonitoring() {
        val settings = container.settingsStore.settings.first()
        container.live.setRunning(true)

        val source = container.sourceFactory.create(settings)
        if (source == null) {
            // Monitoring is on, but no sensor is chosen yet. Stay up and say so. Settings restarts us.
            val idle = getString(R.string.notif_idle_text)
            container.live.setLink(LinkState(LinkPhase.IDLE, idle))
            updateNotification(idle)
            awaitCancellation()
        }

        val engine = AlertEngine(settings.thresholds) { decision -> container.notifications.post(decision) }
        coroutineScope {
            // Threshold edits take effect without restarting the source connection.
            launch {
                container.settingsStore.settings
                    .map { it.thresholds }
                    .distinctUntilChanged()
                    .collect { engine.updateThresholds(it) }
            }
            source.events().collect { event ->
                when (event) {
                    is SourceEvent.Link -> {
                        container.live.setLink(event.state)
                        updateNotification(describe(event.state))
                    }
                    is SourceEvent.Reading -> {
                        // Only new readings reach the engine, so a replay never re-alerts.
                        val fresh = container.repository.ingest(listOf(event.reading), source.sourceName)
                        fresh.forEach { engine.onReading(it) }
                        val shown = fresh.lastOrNull() ?: event.reading
                        container.live.setLink(LinkState(LinkPhase.CONNECTED, source.sourceName))
                        updateNotification(
                            "${container.notifications.formatReading(shown.mgdl)} · ${shown.trend.label}",
                        )
                    }
                }
            }
        }
    }

    private fun describe(state: LinkState): String = when (state.phase) {
        LinkPhase.IDLE -> state.detail
        LinkPhase.CONNECTING -> "Connecting to ${state.detail}…"
        LinkPhase.CONNECTED -> "Connected · ${state.detail}"
        LinkPhase.RETRYING -> "Reconnecting in ${state.retryInSeconds ?: 0}s"
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(text: String) {
        val manager = NotificationManagerCompat.from(this)
        // Without POST_NOTIFICATIONS the foreground notification is hidden. Updating it is then pointless.
        if (!manager.areNotificationsEnabled()) return
        manager.notify(AppNotifications.ID_MONITORING, container.notifications.monitoring(text))
    }

    private fun shutDown() {
        cancelMonitoring()
        container.live.setRunning(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        container.live.setRunning(false)
        container.live.setLink(null)
        super.onDestroy()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        else 0

    companion object {
        const val ACTION_START = "com.fakeglucosense.companion.action.START"
        const val ACTION_STOP = "com.fakeglucosense.companion.action.STOP"
        const val ACTION_RESTART = "com.fakeglucosense.companion.action.RESTART"

        private fun intent(context: Context, action: String) =
            Intent(context, GlucoseMonitorService::class.java).setAction(action)

        /** Call from the foreground only. A background start is refused on Android 12+. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(intent(context, ACTION_STOP))
        }

        /** Applies changed source settings to a running service. Safe to call only while it runs. */
        fun restart(context: Context) {
            context.startService(intent(context, ACTION_RESTART))
        }
    }
}
