package com.fakeglucosense.simulator

import com.fakeglucosense.core.sim.ScenarioPreset
import com.fakeglucosense.core.sim.SimulatedSensor
import com.fakeglucosense.core.wire.ReadingJsonCodec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SimulationRunnerTest {
    private val fiveMinutes = 5 * 60_000L
    private val start = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()

    private fun runner(published: MutableList<String>): SimulationRunner {
        val sensor = SimulatedSensor(ScenarioPreset.STEADY.profile, seed = 3L, sessionStartMillis = start)
        return SimulationRunner(sensor, publish = { published += it })
    }

    @Test
    fun `backfill publishes every reading from session start to the given time`() {
        val published = mutableListOf<String>()
        val emitted = runner(published).backfill(untilMillis = start + 2 * 60 * 60_000L)
        assertEquals(25, emitted) // 0, 5, … 120 minutes
        assertEquals(25, published.size)
        val seqs = published.map { ReadingJsonCodec.decode(it)!!.sequence }
        assertEquals((0L..24L).toList(), seqs)
    }

    @Test
    fun `each tick advances the timeline by one interval`() {
        val published = mutableListOf<String>()
        val runner = runner(published)
        runner.backfill(untilMillis = start)
        assertEquals(1, runner.tick())
        assertEquals(1, runner.tick())
        val decoded = published.map { ReadingJsonCodec.decode(it)!!.reading.epochMillis }
        assertEquals(listOf(start, start + fiveMinutes, start + 2 * fiveMinutes), decoded)
    }

    @Test
    fun `session start aligns to the interval grid minus the backfill`() {
        val now = start + 7 * 60_000L + 13_000L // 00:07:13
        val aligned = SimulationRunner.alignedSessionStart(now, fiveMinutes, backfillMillis = 3_600_000L)
        assertEquals(start + 5 * 60_000L - 3_600_000L, aligned)
    }
}
