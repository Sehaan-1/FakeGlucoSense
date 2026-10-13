package com.fakeglucosense.core.ble

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * CGM Session Start Time (0x2AAA): a 7-byte Date Time (year u16 LE, month, day, hour, minute,
 * second), then Time Zone (s8, 15-minute units) and DST Offset (u8). We always write UTC
 * (zone 0, DST 0). Decoding honours the zone and DST fields when present.
 */
object SessionStartCodec {
    const val SIZE = 9
    private const val DATE_TIME_SIZE = 7
    private const val DST_STANDARD = 0
    private const val DST_HALF_HOUR = 2
    private const val DST_ONE_HOUR = 4
    private const val DST_TWO_HOUR = 8

    /** Encodes [epochMillis] as a UTC session start. Sub-second precision is dropped. */
    fun encode(epochMillis: Long): ByteArray {
        val utc = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)
        require(utc.year in 1..0xFFFF) { "year ${utc.year} cannot be encoded" }
        val out = ByteArray(SIZE)
        CgmMeasurementCodec.writeU16(out, 0, utc.year)
        out[2] = utc.monthValue.toByte()
        out[3] = utc.dayOfMonth.toByte()
        out[4] = utc.hour.toByte()
        out[5] = utc.minute.toByte()
        out[6] = utc.second.toByte()
        out[7] = 0 // UTC
        out[8] = DST_STANDARD.toByte()
        return out
    }

    /** Decodes a Session Start Time value to UTC epoch milliseconds. */
    fun decode(bytes: ByteArray): Long {
        if (bytes.size < DATE_TIME_SIZE) throw CgmDecodeException("session start needs at least 7 bytes")
        val year = CgmMeasurementCodec.readU16(bytes, 0)
        val local = try {
            LocalDateTime.of(
                year,
                bytes[2].toInt(),
                bytes[3].toInt(),
                bytes[4].toInt(),
                bytes[5].toInt(),
                bytes[6].toInt(),
            )
        } catch (e: java.time.DateTimeException) {
            throw CgmDecodeException("invalid session start date: ${e.message}")
        }
        val zoneQuarterHours = if (bytes.size >= SIZE) bytes[7].toInt() else 0
        val dstSeconds = if (bytes.size >= SIZE) dstSeconds(bytes[8].toInt() and 0xFF) else 0
        // Zone values of -128 mean "unknown"; treat as UTC.
        val zoneSeconds = if (zoneQuarterHours == -128) 0 else zoneQuarterHours * 15 * 60
        val offset = ZoneOffset.ofTotalSeconds(zoneSeconds + dstSeconds)
        return ZonedDateTime.of(local, offset).toInstant().toEpochMilli()
    }

    private fun dstSeconds(code: Int): Int = when (code) {
        DST_HALF_HOUR -> 30 * 60
        DST_ONE_HOUR -> 60 * 60
        DST_TWO_HOUR -> 120 * 60
        else -> 0 // 0 = standard time; 255 = unknown, treated as standard
    }
}
