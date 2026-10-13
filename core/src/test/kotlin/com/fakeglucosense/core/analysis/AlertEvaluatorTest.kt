package com.fakeglucosense.core.analysis

import com.fakeglucosense.core.model.AlertThresholds
import com.fakeglucosense.core.model.AlertType
import com.fakeglucosense.core.model.GlucoseReading
import com.fakeglucosense.core.model.Trend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlertEvaluatorTest {
    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private val evaluator = AlertEvaluator(AlertThresholds())

    private fun reading(minutes: Long, mgdl: Int, trend: Trend = Trend.FLAT) =
        GlucoseReading(t0 + minutes * minute, mgdl, trend)

    private fun List<AlertDecision>.summary() = map { it.type to it.action }

    @Test
    fun `low raises once and clears only past the hysteresis margin`() {
        val raised = evaluator.evaluate(AlertState(), reading(0, 68))
        assertEquals(listOf(AlertType.LOW to AlertAction.RAISE), raised.second.summary())

        // 72 is above 70 but inside the 5 mg/dL hysteresis band, so the alert stays active silently.
        val held = evaluator.evaluate(raised.first, reading(5, 72))
        assertTrue(held.second.isEmpty())
        assertTrue(held.first.isActive(AlertType.LOW))

        // 75 clears it.
        val cleared = evaluator.evaluate(held.first, reading(10, 75))
        assertEquals(listOf(AlertType.LOW to AlertAction.CLEAR), cleared.second.summary())
        assertFalse(cleared.first.isActive(AlertType.LOW))
    }

    @Test
    fun `active alert is reminded only after the snooze period`() {
        val first = evaluator.evaluate(AlertState(), reading(0, 65))
        val tenMinutes = evaluator.evaluate(first.first, reading(10, 64)).second
        assertTrue(tenMinutes.isEmpty(), "no reminder inside snooze")
        val thirtyMinutes = evaluator.evaluate(first.first, reading(30, 64)).second
        assertEquals(listOf(AlertType.LOW to AlertAction.REMIND), thirtyMinutes.summary())
    }

    @Test
    fun `urgent low suppresses low and supersedes an active low`() {
        val lowState = evaluator.evaluate(AlertState(), reading(0, 65)).first
        val (state, decisions) = evaluator.evaluate(lowState, reading(5, 50))
        assertEquals(
            listOf(
                AlertType.URGENT_LOW to AlertAction.RAISE,
                AlertType.LOW to AlertAction.CLEAR,
            ),
            decisions.summary(),
        )
        assertTrue(state.isActive(AlertType.URGENT_LOW))
        assertFalse(state.isActive(AlertType.LOW))
    }

    @Test
    fun `fresh urgent low does not also raise low`() {
        val decisions = evaluator.evaluate(AlertState(), reading(0, 50)).second
        assertEquals(listOf(AlertType.URGENT_LOW to AlertAction.RAISE), decisions.summary())
    }

    @Test
    fun `high and urgent high follow the same rules`() {
        val high = evaluator.evaluate(AlertState(), reading(0, 200)).second
        assertEquals(listOf(AlertType.HIGH to AlertAction.RAISE), high.summary())
        val urgent = evaluator.evaluate(AlertState(), reading(0, 260)).second
        assertEquals(listOf(AlertType.URGENT_HIGH to AlertAction.RAISE), urgent.summary())
    }

    @Test
    fun `double down trend raises rapid drop and clears when the trend changes`() {
        val raised = evaluator.evaluate(AlertState(), reading(0, 120, Trend.DOUBLE_DOWN))
        assertEquals(listOf(AlertType.RAPID_DROP to AlertAction.RAISE), raised.second.summary())
        val cleared = evaluator.evaluate(raised.first, reading(5, 115, Trend.SINGLE_DOWN))
        assertEquals(listOf(AlertType.RAPID_DROP to AlertAction.CLEAR), cleared.second.summary())
    }

    @Test
    fun `in-range readings produce no decisions`() {
        val decisions = evaluator.evaluate(AlertState(), reading(0, 110, Trend.FLAT)).second
        assertTrue(decisions.isEmpty())
    }
}
