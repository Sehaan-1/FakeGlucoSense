package com.fakeglucosense.simulator

import com.fakeglucosense.core.sim.ScenarioPreset
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Runtime options for the simulator CLI. Defaults match the README quick start. */
data class SimulatorConfig(
    val bindAddress: String = "0.0.0.0",
    val port: Int = DEFAULT_PORT,
    val scenario: ScenarioPreset = ScenarioPreset.MEAL_DAY,
    val seed: Long = DEFAULT_SEED,
    val speed: Double = 1.0,
    val intervalMinutes: Int = 5,
    val backfillHours: Int = 24,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Number of readings held for replay to new clients. */
    val historyLimit: Int get() = backfillHours * 60 / intervalMinutes + HISTORY_MARGIN

    companion object {
        const val DEFAULT_PORT = 8765
        const val DEFAULT_SEED = 42L
        const val HISTORY_MARGIN = 12

        val USAGE = """
            FakeGlucoSense simulator — emits synthetic CGM readings as NDJSON over TCP.

            Usage: simulator [options]

              --port <n>               TCP port to listen on (default $DEFAULT_PORT; 0 = any free port)
              --bind <addr>            Bind address (default 0.0.0.0, reachable from the LAN and emulator host)
              --scenario <name>        ${ScenarioPreset.entries.joinToString(" | ")} (default MEAL_DAY)
              --seed <n>               RNG seed; same seed = same data (default $DEFAULT_SEED)
              --speed <x>              Time acceleration; 60 = one reading every 5 s (default 1)
              --interval-minutes <n>   Minutes between readings, 1..60 (default 5)
              --backfill-hours <n>     History replayed to each new client, 0..168 (default 24)
              --zone <id>              Time zone for meal times, e.g. Europe/Berlin (default system)
              --help                   Show this message

            Data is synthetic and for education only. It is not a medical device.
        """.trimIndent()

        /** Parses CLI arguments. Accepts `--flag value` and `--flag=value`. */
        fun parse(args: Array<String>): ParseResult {
            val values = mutableMapOf<String, String>()
            var index = 0
            while (index < args.size) {
                val raw = args[index]
                if (raw == "--help" || raw == "-h") return ParseResult.Help
                if (!raw.startsWith("--")) return ParseResult.Error("unexpected argument '$raw'")
                val eq = raw.indexOf('=')
                val key: String
                val value: String
                if (eq > 0) {
                    key = raw.substring(2, eq)
                    value = raw.substring(eq + 1)
                } else {
                    key = raw.substring(2)
                    if (index + 1 >= args.size) return ParseResult.Error("missing value for --$key")
                    value = args[++index]
                }
                if (key !in KNOWN_FLAGS) return ParseResult.Error("unknown option --$key")
                values[key] = value
                index++
            }
            return build(values)
        }

        private val KNOWN_FLAGS = setOf(
            "port", "bind", "scenario", "seed", "speed", "interval-minutes", "backfill-hours", "zone",
        )

        private fun build(values: Map<String, String>): ParseResult {
            val defaults = SimulatorConfig()
            val port = values["port"]?.toIntOrNull() ?: if (values.containsKey("port")) return bad("port") else defaults.port
            if (port !in 0..65535) return ParseResult.Error("port must be 0..65535")

            val scenario = values["scenario"]?.let { name ->
                ScenarioPreset.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: return ParseResult.Error("unknown scenario '$name'")
            } ?: defaults.scenario

            val seed = values["seed"]?.toLongOrNull() ?: if (values.containsKey("seed")) return bad("seed") else defaults.seed

            val speed = values["speed"]?.toDoubleOrNull() ?: if (values.containsKey("speed")) return bad("speed") else defaults.speed
            if (!speed.isFinite() || speed !in 0.1..3600.0) return ParseResult.Error("speed must be 0.1..3600")

            val interval = values["interval-minutes"]?.toIntOrNull()
                ?: if (values.containsKey("interval-minutes")) return bad("interval-minutes") else defaults.intervalMinutes
            if (interval !in 1..60) return ParseResult.Error("interval-minutes must be 1..60")

            val backfill = values["backfill-hours"]?.toIntOrNull()
                ?: if (values.containsKey("backfill-hours")) return bad("backfill-hours") else defaults.backfillHours
            if (backfill !in 0..168) return ParseResult.Error("backfill-hours must be 0..168")

            val zone = values["zone"]?.let {
                try {
                    ZoneId.of(it)
                } catch (e: DateTimeParseException) {
                    return ParseResult.Error("unknown time zone '$it'")
                } catch (e: java.time.DateTimeException) {
                    return ParseResult.Error("unknown time zone '$it'")
                }
            } ?: defaults.zone

            val bind = values["bind"]?.takeIf { it.isNotBlank() } ?: defaults.bindAddress
            return ParseResult.Ok(
                SimulatorConfig(
                    bindAddress = bind,
                    port = port,
                    scenario = scenario,
                    seed = seed,
                    speed = speed,
                    intervalMinutes = interval,
                    backfillHours = backfill,
                    zone = zone,
                ),
            )
        }

        private fun bad(flag: String): ParseResult = ParseResult.Error("--$flag needs a valid number")
    }

    /** Outcome of [parse]. */
    sealed interface ParseResult {
        data class Ok(val config: SimulatorConfig) : ParseResult
        data class Error(val message: String) : ParseResult
        data object Help : ParseResult
    }
}
