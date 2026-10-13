package com.fakeglucosense.core.sim

import com.fakeglucosense.core.model.Trend
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatedSensorTest {
    private val start: Long = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()
    private val oneDayMillis = 24 * 3_600_000L

    private fun sensor(preset: ScenarioPreset, seed: Long = 7L) =
        SimulatedSensor(preset.profile, seed, start)

    @Test
    fun `emits one reading per five minutes with matching time offsets`() {
        val samples = sensor(ScenarioPreset.STEADY).advanceTo(start + 60 * 60_000L)
        assertEquals(13, samples.size) // t = 0, 5, …, 60 minutes
        assertEquals((0..12).map { it * 5L }, samples.map { it.timeOffsetMinutes })
        assertEquals(samples.map { start + it.timeOffsetMinutes * 60_000L }, samples.map { it.reading.epochMillis })
    }

    @Test
    fun `same seed gives identical series`() {
        val a = sensor(ScenarioPreset.MEAL_DAY).advanceTo(start + oneDayMillis).map { it.reading }
        val b = sensor(ScenarioPreset.MEAL_DAY).advanceTo(start + oneDayMillis).map { it.reading }
        assertEquals(a, b)
    }

    @Test
    fun `output does not depend on how advance calls are chunked`() {
        val single = sensor(ScenarioPreset.ACTIVE_DAY).advanceTo(start + oneDayMillis).map { it.reading }
        val chunked = sensor(ScenarioPreset.ACTIVE_DAY).let { s ->
            (1..24).flatMap { hour -> s.advanceTo(start + hour * 3_600_000L) }.map { it.reading }
        }
        assertEquals(single, chunked)
    }

    @Test
    fun `calling advance again at the same time returns nothing new`() {
        val s = sensor(ScenarioPreset.STEADY)
        s.advanceTo(start + 30 * 60_000L)
        assertTrue(s.advanceTo(start + 30 * 60_000L).isEmpty())
    }

    @Test
    fun `every preset stays within the physiological clamp`() {
        for (preset in ScenarioPreset.entries) {
            val values = sensor(preset).advanceTo(start + oneDayMillis).map { it.reading.mgdl }
            assertTrue(values.all { it in 40..400 }, "${preset.name} produced ${values.minOrNull()}..${values.maxOrNull()}")
        }
    }

    @Test
    fun `meal day spikes above target and nocturnal low dips below it`() {
        val meal = sensor(ScenarioPreset.MEAL_DAY).advanceTo(start + oneDayMillis).map { it.reading.mgdl }
        assertTrue(meal.max() > 140, "meal day peak was ${meal.max()}")
        val night = sensor(ScenarioPreset.NOCTURNAL_LOW).advanceTo(start + oneDayMillis).map { it.reading.mgdl }
        assertTrue(night.min() < 75, "nocturnal low minimum was ${night.min()}")
    }

    @Test
    fun `high spike preset exceeds the high threshold`() {
        val values = sensor(ScenarioPreset.HIGH_SPIKE).advanceTo(start + oneDayMillis).map { it.reading.mgdl }
        assertTrue(values.max() > 180, "high spike peak was ${values.max()}")
    }

    @Test
    fun `trend is computed from the sensor's own output`() {
        val samples = sensor(ScenarioPreset.MEAL_DAY).advanceTo(start + 2 * 3_600_000L).map { it.reading }
        assertEquals(Trend.NOT_COMPUTABLE, samples.first().trend)
        assertTrue(samples.drop(1).all { it.trend != Trend.NOT_COMPUTABLE })
    }
}
