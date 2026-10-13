package com.fakeglucosense.companion.ui.format

import com.fakeglucosense.companion.data.settings.DisplayUnit
import com.fakeglucosense.companion.domain.GlucoseUnits
import kotlin.test.Test
import kotlin.test.assertEquals

class GlucoseFormatTest {

    private val minute = 60_000L

    @Test
    fun `ago reads naturally at each scale`() {
        val now = 10 * 60 * minute
        assertEquals("just now", GlucoseFormat.ago(now - 20_000L, now))
        assertEquals("7 min ago", GlucoseFormat.ago(now - 7 * minute, now))
        assertEquals("1 h 5 min ago", GlucoseFormat.ago(now - 65 * minute, now))
    }

    @Test
    fun `ago never goes negative for a reading slightly in the future`() {
        assertEquals("just now", GlucoseFormat.ago(1_000L, 0L))
    }

    @Test
    fun `mg per dl is shown as a whole number`() {
        assertEquals("112", GlucoseFormat.value(112, DisplayUnit.MGDL))
        assertEquals("mg/dL", GlucoseFormat.unitLabel(DisplayUnit.MGDL))
    }

    @Test
    fun `mmol conversion uses the glucose molar mass`() {
        assertEquals(99, GlucoseUnits.toMgdl(5.5))
        assertEquals(5.5, GlucoseUnits.toMmol(99), 0.01)
    }
}
