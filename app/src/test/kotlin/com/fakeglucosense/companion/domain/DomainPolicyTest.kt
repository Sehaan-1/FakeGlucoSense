package com.fakeglucosense.companion.domain

import com.fakeglucosense.core.analysis.AlertAction
import com.fakeglucosense.core.analysis.AlertDecision
import com.fakeglucosense.core.model.AlertThresholds
import com.fakeglucosense.core.model.AlertType
import com.fakeglucosense.core.model.GlucoseReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StalenessPolicyTest {

    private val minute = 60_000L

    @Test
    fun `fresh install with no readings is not stale`() {
        assertFalse(StalenessPolicy.isStale(latestMillis = null, nowMillis = 99 * minute))
    }

    @Test
    fun `a reading inside the threshold is not stale`() {
        assertFalse(StalenessPolicy.isStale(latestMillis = 0L, nowMillis = 14 * minute))
    }

    @Test
    fun `three missed five-minute readings are stale`() {
        assertTrue(StalenessPolicy.isStale(latestMillis = 0L, nowMillis = 15 * minute))
        assertEquals(15L, StalenessPolicy.minutesSince(latestMillis = 0L, nowMillis = 15 * minute))
    }
}

class AlertEngineTest {

    private val sent = mutableListOf<AlertDecision>()
    private fun engine(thresholds: AlertThresholds = AlertThresholds()) =
        AlertEngine(thresholds) { sent += it }

    private fun reading(mgdl: Int, minute: Long) =
        GlucoseReading(epochMillis = minute * 60_000L, mgdl = mgdl)

    @Test
    fun `a low reading raises once and repeats do not re-raise`() {
        val engine = engine()
        engine.onReading(reading(65, 0))
        engine.onReading(reading(64, 5))
        val raised = sent.filter { it.action == AlertAction.RAISE && it.type == AlertType.LOW }
        assertEquals(1, raised.size)
    }

    @Test
    fun `returning to range clears the alert`() {
        val engine = engine()
        engine.onReading(reading(65, 0))
        engine.onReading(reading(120, 5))
        assertTrue(sent.any { it.action == AlertAction.CLEAR && it.type == AlertType.LOW })
    }

    @Test
    fun `threshold changes apply to the next reading`() {
        val engine = engine()
        engine.onReading(reading(75, 0))
        assertTrue(sent.none { it.type == AlertType.LOW })
        engine.updateThresholds(AlertThresholds(lowMgdl = 80))
        engine.onReading(reading(75, 5))
        assertTrue(sent.any { it.action == AlertAction.RAISE && it.type == AlertType.LOW })
    }

    @Test
    fun `an urgent low supersedes the plain low alert`() {
        val engine = engine()
        engine.onReading(reading(65, 0))
        engine.onReading(reading(50, 5))
        assertEquals(setOf(AlertType.URGENT_LOW), engine.activeTypes())
    }
}
