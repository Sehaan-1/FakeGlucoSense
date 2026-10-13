package com.fakeglucosense.core.ble

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionStartCodecTest {

    @Test
    fun `round-trips a UTC session start`() {
        val instant = Instant.parse("2026-10-13T05:30:45Z").toEpochMilli()
        val bytes = SessionStartCodec.encode(instant)
        assertEquals(SessionStartCodec.SIZE, bytes.size)
        assertEquals(instant, SessionStartCodec.decode(bytes))
    }

    @Test
    fun `honours time zone and DST offsets when decoding`() {
        // Local 10:00 with zone +1h (4 quarter-hours) and DST +1h (code 4) means UTC 08:00.
        val localTenAm = byteArrayOf(
            0xEA.toByte(), 0x07, // 2026 little endian
            10, 13, 10, 0, 0,    // month, day, hour, minute, second
            4,                   // zone +4 quarter hours = +1h
            4,                   // DST +1h
        )
        val expected = Instant.parse("2026-10-13T08:00:00Z").toEpochMilli()
        assertEquals(expected, SessionStartCodec.decode(localTenAm))
    }

    @Test
    fun `rejects impossible dates`() {
        val badDate = byteArrayOf(0xEA.toByte(), 0x07, 13, 45, 0, 0, 0, 0, 0)
        assertFailsWith<CgmDecodeException> { SessionStartCodec.decode(badDate) }
    }
}
