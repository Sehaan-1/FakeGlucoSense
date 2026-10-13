package com.fakeglucosense.sensor.sim

import com.fakeglucosense.core.ble.CgmRecord
import com.fakeglucosense.core.sim.ScenarioPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimulatedCgmSessionTest {

    private val minute = 60_000L
    private val now = 1_700_000_000_000L - (1_700_000_000_000L % (5 * minute)) // on the 5-minute grid

    @Test
    fun `a 24 hour backfill is 289 readings, from offset 0 to offset 1440`() {
        val start = SimulatedCgmSession.alignedStart(now, backfillHours = 24)
        val session = SimulatedCgmSession(ScenarioPreset.STEADY, seed = 1L, sessionStartMillis = start)
        val records = session.advanceTo(now)
        assertEquals(289, records.size)
        assertEquals(0, records.first().timeOffsetMinutes)
        assertEquals(1440, records.last().timeOffsetMinutes)
    }

    @Test
    fun `offsets advance in five minute steps`() {
        val start = SimulatedCgmSession.alignedStart(now, backfillHours = 1)
        val records = SimulatedCgmSession(ScenarioPreset.MEAL_DAY, 7L, start).advanceTo(now)
        assertTrue(records.zipWithNext().all { (a, b) -> b.timeOffsetMinutes - a.timeOffsetMinutes == 5 })
    }

    @Test
    fun `history keeps only the newest records`() {
        val start = SimulatedCgmSession.alignedStart(now, backfillHours = 24)
        val session = SimulatedCgmSession(ScenarioPreset.STEADY, 1L, start, historyLimit = 10)
        session.advanceTo(now)
        val history = session.backfill()
        assertEquals(10, history.size)
        assertEquals(1440, history.last().timeOffsetMinutes)
    }

    @Test
    fun `advancing to an earlier time emits nothing new`() {
        val start = SimulatedCgmSession.alignedStart(now, backfillHours = 1)
        val session = SimulatedCgmSession(ScenarioPreset.STEADY, 1L, start)
        session.advanceTo(now)
        assertTrue(session.advanceTo(now).isEmpty())
    }

    @Test
    fun `the session expires once its offset would exceed a UINT16`() {
        val start = 0L
        val session = SimulatedCgmSession(ScenarioPreset.STEADY, 1L, start)
        assertFalse(session.isExpired(CgmRecord.MAX_TIME_OFFSET * minute))
        assertTrue(session.isExpired((CgmRecord.MAX_TIME_OFFSET + 1L) * minute))
    }

    @Test
    fun `wall time scales to simulated time without integer overflow`() {
        assertEquals(60_000L, SimulatedCgmSession.scaledMillis(1_000L, 60.0))
        // 30 days of wall time at 60x: must not wrap around.
        assertEquals(30L * 24 * 3600 * 1000 * 60, SimulatedCgmSession.scaledMillis(30L * 24 * 3600 * 1000, 60.0))
    }
}
