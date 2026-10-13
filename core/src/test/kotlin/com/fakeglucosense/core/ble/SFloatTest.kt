package com.fakeglucosense.core.ble

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SFloatTest {

    @Test
    fun `encodes integers with exponent zero`() {
        assertEquals(0x0078, SFloat.encode(120.0))
        assertEquals(120.0, SFloat.decode(SFloat.encode(120.0)))
    }

    @Test
    fun `decodes spec example with negative exponent`() {
        // exponent nibble 0xF = -1, mantissa 0x00A = 10 → 1.0
        assertEquals(1.0, SFloat.decode(0xF00A), 1e-12)
    }

    @Test
    fun `round-trips fractional and negative values within a thousandth`() {
        for (value in listOf(1.5, -3.25, 0.5, 0.123, 25.5, 300.0, -2045.0, 2045.0)) {
            val decoded = SFloat.decode(SFloat.encode(value))
            assertTrue(abs(decoded - value) < 0.001, "round trip of $value gave $decoded")
        }
    }

    @Test
    fun `special raw values decode to non-finite doubles`() {
        assertEquals(Double.POSITIVE_INFINITY, SFloat.decode(SFloat.RAW_POSITIVE_INFINITY))
        assertEquals(Double.NEGATIVE_INFINITY, SFloat.decode(SFloat.RAW_NEGATIVE_INFINITY))
        assertTrue(SFloat.decode(SFloat.RAW_NAN).isNaN())
        assertTrue(SFloat.decode(SFloat.RAW_NRES).isNaN())
        assertTrue(SFloat.decode(SFloat.RAW_RESERVED).isNaN())
    }

    @Test
    fun `rejects non-finite and unrepresentable input`() {
        assertFailsWith<IllegalArgumentException> { SFloat.encode(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { SFloat.encode(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { SFloat.encode(1e12) }
    }

    @Test
    fun `encoded values never collide with special raw values`() {
        for (value in listOf(2045.0, -2045.0, 2000.0)) {
            val raw = SFloat.encode(value) and 0xFFF
            assertTrue(raw !in listOf(0x7FE, 0x7FF, 0x800, 0x801, 0x802), "raw $raw collides with a special value")
        }
    }
}
