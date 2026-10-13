package com.fakeglucosense.simulator

import com.fakeglucosense.core.model.GlucoseReading
import com.fakeglucosense.core.sim.SensorSample
import com.fakeglucosense.core.sim.SimulatedSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Wires the simulator together: sensor model, replay hub, TCP server, and pacing loop.
 * The entry point (`Main.kt`) only parses arguments and calls [run].
 */
class SimulatorApp(
    private val config: SimulatorConfig,
    private val clock: Clock = Clock.SYSTEM,
    private val console: (String) -> Unit = ::println,
) {
    private val intervalMillis = config.intervalMinutes * 60_000L

    /** Runs until the scope is cancelled. Returns the bound port once the server is listening. */
    suspend fun run(scope: CoroutineScope, onListening: (Int) -> Unit = {}) {
        val now = clock.nowMillis()
        val sessionStart = SimulationRunner.alignedSessionStart(
            nowMillis = now,
            intervalMillis = intervalMillis,
            backfillMillis = config.backfillHours * 3_600_000L,
        )
        val sensor = SimulatedSensor(
            profile = config.scenario.profile,
            seed = config.seed,
            sessionStartMillis = sessionStart,
            intervalMinutes = config.intervalMinutes,
            zone = config.zone,
        )
        val hub = ReadingHub(historyLimit = config.historyLimit)
        val server = NdjsonTcpServer(hub, scope, onLog = { console("[server] $it") })
        val runner = SimulationRunner(
            sensor = sensor,
            publish = hub::publish,
            onSample = { console(formatLine(it)) },
        )

        console(banner())
        val port = server.start(config.bindAddress, config.port)
        onListening(port)

        val backfilled = runner.backfill(untilMillis = alignedNow(clock.nowMillis()))
        console("[sim] backfilled $backfilled readings (${config.backfillHours} h)")

        val tickDelayMillis = (intervalMillis / config.speed).toLong().coerceAtLeast(1L)
        try {
            while (scope.isActive) {
                delay(tickDelayMillis)
                runner.tick()
            }
        } finally {
            server.stop()
        }
    }

    internal fun banner(): String = buildString {
        appendLine("FakeGlucoSense simulator — SYNTHETIC DATA, NOT A MEDICAL DEVICE")
        appendLine("  scenario : ${config.scenario.name} — ${config.scenario.description}")
        appendLine("  seed     : ${config.seed}")
        appendLine("  interval : ${config.intervalMinutes} min   speed: ${config.speed}x")
        append("  zone     : ${config.zone}")
    }

    private fun alignedNow(nowMillis: Long): Long =
        Math.floorDiv(nowMillis, intervalMillis) * intervalMillis

    private fun formatLine(sample: SensorSample): String {
        val reading: GlucoseReading = sample.reading
        val time = TIME_FORMAT.format(Instant.ofEpochMilli(reading.epochMillis).atZone(config.zone))
        return "$time  ${reading.mgdl.toString().padStart(3)} mg/dL  ${reading.trend.symbol.padEnd(2)}  (seq ${sample.sequence})"
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ROOT)
    }
}
