package com.fakeglucosense.sensor.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fakeglucosense.core.ble.CgmRecord
import com.fakeglucosense.core.ble.SensorStatusAnnunciation
import com.fakeglucosense.core.ble.StatusAnnunciation
import com.fakeglucosense.core.sim.ScenarioPreset
import com.fakeglucosense.sensor.R
import com.fakeglucosense.sensor.SensorApp
import com.fakeglucosense.sensor.ble.SensorAdvertiser
import com.fakeglucosense.sensor.ble.SensorGattServer
import com.fakeglucosense.sensor.sim.SimulatedCgmSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs the simulated sensor and serves it over BLE (ADR-0013).
 *
 * The loop advances simulated time at the chosen speed, publishes new records to subscribed
 * centrals, and keeps a short list for the screen. It runs as a `connectedDevice` foreground service,
 * so Android does not suspend it while a companion is connected.
 */
class SensorService : LifecycleService() {

    private val app: SensorApp get() = application as SensorApp
    private var loop: Job? = null
    private var gatt: SensorGattServer? = null
    private val advertiser by lazy { SensorAdvertiser(this) }

    // Session state, mutated by the ops control point callbacks (see SessionControl below).
    private var cgmSession: SimulatedCgmSession? = null
    private var preset: ScenarioPreset = ScenarioPreset.MEAL_DAY
    private var speed: Double = 1.0
    private var wallStart: Long = 0L
    private var sessionStart: Long = 0L
    private var sessionActive: Boolean = true
    private var intervalMinutes: Int = SimulatedCgmSession.INTERVAL_MINUTES

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val request = intent ?: return START_NOT_STICKY
                if (!promoteToForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val preset = request.getStringExtra(EXTRA_SCENARIO)
                    ?.let { runCatching { ScenarioPreset.valueOf(it) }.getOrNull() }
                    ?: ScenarioPreset.MEAL_DAY
                val speed = request.getDoubleExtra(EXTRA_SPEED, 1.0).coerceIn(0.5, 120.0)
                startSensor(preset, speed)
            }
            ACTION_STOP -> stopSensor()
            else -> stopSelf()
        }
        // The user starts the sensor. Do not restart it on our own after the process dies.
        return START_NOT_STICKY
    }

    private fun startSensor(preset: ScenarioPreset, speed: Double) {
        if (loop?.isActive == true) return
        this.preset = preset
        this.speed = speed
        wallStart = System.currentTimeMillis()
        sessionStart = SimulatedCgmSession.alignedStart(wallStart, BACKFILL_HOURS)
        cgmSession = SimulatedCgmSession(preset, SEED, sessionStart)
        sessionActive = true
        intervalMinutes = SimulatedCgmSession.INTERVAL_MINUTES

        val server = SensorGattServer(
            context = this,
            onReady = { app.status.update { it.copy(advertising = "Advertising") }; advertiser.start { msg -> app.status.update { s -> s.copy(advertising = msg) } } },
            onSubscribersChanged = { count -> app.status.update { it.copy(subscribers = count) } },
        )
        server.recordsProvider = { cgmSession?.backfill().orEmpty() }
        server.statusProvider = { statusSnapshot() }
        server.sessionControl = controlPoint
        gatt = server
        app.status.update {
            SensorUiState(running = true, scenario = preset, speed = speed, advertising = "Starting…", sessionStartMillis = sessionStart)
        }
        if (!server.open(sessionStart)) {
            app.status.update { it.copy(advertising = "Bluetooth GATT server could not open") }
        }

        loop = lifecycleScope.launch(Dispatchers.Default) {
            // The first advance generates the history a collector can pull with RACP on connect.
            val initial = cgmSession?.advanceTo(wallStart).orEmpty()
            app.status.update { it.copy(recent = initial.asReversed().take(SensorStatus.RECENT_LIMIT)) }
            while (isActive) {
                delay(TICK_MILLIS)
                val session = cgmSession ?: break
                val simNow = wallStart + SimulatedCgmSession.scaledMillis(System.currentTimeMillis() - wallStart, speed)
                if (session.isExpired(simNow)) {
                    app.status.update { it.copy(advertising = "Session passed 45 days. Restart the sensor.") }
                    stopSensor()
                    break
                }
                if (!sessionActive) continue // A stopped session produces no measurements (CGMS §3.9).
                val fresh = session.advanceTo(simNow)
                if (fresh.isEmpty()) continue
                val interval = intervalMinutes.coerceAtLeast(SimulatedCgmSession.INTERVAL_MINUTES)
                server.publish(fresh.filter { it.timeOffsetMinutes % interval == 0 })
                app.status.update { it.copy(recent = (fresh.asReversed() + it.recent).take(SensorStatus.RECENT_LIMIT)) }
            }
        }
    }

    /** What a CGM Status read should see right now: the offset and the latest annunciation. */
    private fun statusSnapshot(): Pair<Int, StatusAnnunciation> {
        val simNow = wallStart + SimulatedCgmSession.scaledMillis(System.currentTimeMillis() - wallStart, speed)
        val offset = ((simNow - sessionStart) / MILLIS_PER_MINUTE).coerceIn(0, CgmRecord.MAX_TIME_OFFSET.toLong()).toInt()
        val latestWarning = cgmSession?.backfill()?.lastOrNull()?.statusAnnunciation?.warning ?: 0
        val status = if (sessionActive) 0 else SensorStatusAnnunciation.STATUS_SESSION_STOPPED
        return offset to StatusAnnunciation(status = status, warning = latestWarning)
    }

    /** Handles the CGM Specific Ops Control Point writes from a connected collector. */
    private val controlPoint = object : SensorGattServer.SessionControl {
        override val communicationIntervalMinutes: Int get() = intervalMinutes

        override fun setCommunicationInterval(minutes: Int): Boolean {
            intervalMinutes = minutes
            app.status.update { it.copy(intervalMinutes = minutes) }
            return true
        }

        override fun startSession(): Boolean {
            if (sessionActive) return true // Already running is a successful no-op.
            val now = System.currentTimeMillis()
            wallStart = now
            sessionStart = SimulatedCgmSession.alignedStart(now, backfillHours = 0)
            cgmSession = SimulatedCgmSession(preset, SEED, sessionStart)
            sessionActive = true
            gatt?.updateSessionStart(sessionStart)
            app.status.update {
                it.copy(
                    sessionActive = true,
                    sessionStartMillis = sessionStart,
                    recent = emptyList(),
                    advertising = "Session started by collector",
                )
            }
            return true
        }

        override fun stopSession(): Boolean {
            if (!sessionActive) return true
            sessionActive = false
            app.status.update { it.copy(sessionActive = false, advertising = "Session stopped by collector") }
            return true
        }
    }

    private fun stopSensor() {
        loop?.cancel()
        loop = null
        advertiser.stop()
        gatt?.close()
        gatt = null
        app.status.update { it.copy(running = false, advertising = "Stopped", subscribers = 0) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        loop?.cancel()
        advertiser.stop()
        gatt?.close()
        super.onDestroy()
    }

    private fun promoteToForeground(): Boolean = try {
        ServiceCompat.startForeground(this, SensorApp.NOTIFICATION_ID, notification(), foregroundType())
        true
    } catch (e: RuntimeException) {
        // A background start can be refused on Android 12+. Stopping cleanly beats crashing.
        false
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, SensorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, SensorApp.CHANNEL_SENSOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_sensor_title))
            .setContentText("Serving simulated readings over Bluetooth")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notif_stop_action), stop)
            .build()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        else 0

    companion object {
        const val ACTION_START = "com.fakeglucosense.sensor.action.START"
        const val ACTION_STOP = "com.fakeglucosense.sensor.action.STOP"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_SPEED = "speed"

        private const val BACKFILL_HOURS = 24
        private const val SEED = 42L
        private const val TICK_MILLIS = 1_000L
        private const val REQUEST_STOP = 20
        private const val MILLIS_PER_MINUTE = 60_000L

        fun start(context: Context, scenario: ScenarioPreset, speed: Double) {
            val intent = Intent(context, SensorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SCENARIO, scenario.name)
                .putExtra(EXTRA_SPEED, speed)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SensorService::class.java).setAction(ACTION_STOP))
        }
    }
}
