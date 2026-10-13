package com.fakeglucosense.companion.domain

import com.fakeglucosense.core.model.AlertThresholds
import kotlin.test.Test
import kotlin.test.assertEquals

class GlucoseBandTest {

    private val thresholds = AlertThresholds(urgentLowMgdl = 55, lowMgdl = 70, highMgdl = 180, urgentHighMgdl = 250)

    @Test
    fun `edges follow the same operators as the alert evaluator`() {
        assertEquals(GlucoseBand.URGENT_LOW, thresholds.bandOf(55))   // urgent low fires at <= 55
        assertEquals(GlucoseBand.LOW, thresholds.bandOf(56))
        assertEquals(GlucoseBand.LOW, thresholds.bandOf(69))
        assertEquals(GlucoseBand.IN_RANGE, thresholds.bandOf(70))     // low fires at < 70
        assertEquals(GlucoseBand.IN_RANGE, thresholds.bandOf(180))    // high fires at > 180
        assertEquals(GlucoseBand.HIGH, thresholds.bandOf(181))
        assertEquals(GlucoseBand.HIGH, thresholds.bandOf(249))
        assertEquals(GlucoseBand.URGENT_HIGH, thresholds.bandOf(250)) // urgent high fires at >= 250
    }

    @Test
    fun `extreme values stay in the outer bands`() {
        assertEquals(GlucoseBand.URGENT_LOW, thresholds.bandOf(20))
        assertEquals(GlucoseBand.URGENT_HIGH, thresholds.bandOf(600))
    }
}
