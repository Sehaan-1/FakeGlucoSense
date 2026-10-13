package com.fakeglucosense.simulator

import com.fakeglucosense.core.sim.ScenarioPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SimulatorConfigTest {

    private fun ok(vararg args: String): SimulatorConfig {
        val result = SimulatorConfig.parse(arrayOf(*args))
        assertIs<SimulatorConfig.ParseResult.Ok>(result, "expected success for ${args.toList()}")
        return result.config
    }

    private fun error(vararg args: String): String {
        val result = SimulatorConfig.parse(arrayOf(*args))
        assertIs<SimulatorConfig.ParseResult.Error>(result, "expected error for ${args.toList()}")
        return result.message
    }

    @Test
    fun `no arguments yields defaults`() {
        val config = ok()
        assertEquals(SimulatorConfig.DEFAULT_PORT, config.port)
        assertEquals(ScenarioPreset.MEAL_DAY, config.scenario)
        assertEquals(5, config.intervalMinutes)
    }

    @Test
    fun `accepts space and equals forms`() {
        val config = ok("--port", "9000", "--scenario=nocturnal_low", "--speed=60", "--seed", "7")
        assertEquals(9000, config.port)
        assertEquals(ScenarioPreset.NOCTURNAL_LOW, config.scenario)
        assertEquals(60.0, config.speed)
        assertEquals(7L, config.seed)
    }

    @Test
    fun `help is recognized`() {
        assertIs<SimulatorConfig.ParseResult.Help>(SimulatorConfig.parse(arrayOf("--help")))
    }

    @Test
    fun `rejects unknown options and bad values with a message`() {
        assertTrue(error("--bogus", "1").contains("unknown option"))
        assertTrue(error("--port", "abc").contains("port"))
        assertTrue(error("--port", "70000").contains("0..65535"))
        assertTrue(error("--scenario", "NOPE").contains("unknown scenario"))
        assertTrue(error("--interval-minutes", "0").contains("1..60"))
        assertTrue(error("--speed", "0").contains("speed"))
        assertTrue(error("--zone", "Mars/Olympus").contains("time zone"))
        assertTrue(error("--port").contains("missing value"))
    }

    @Test
    fun `history limit covers the requested backfill plus a margin`() {
        val config = ok("--backfill-hours", "2", "--interval-minutes", "5")
        assertEquals(2 * 60 / 5 + SimulatorConfig.HISTORY_MARGIN, config.historyLimit)
    }
}
