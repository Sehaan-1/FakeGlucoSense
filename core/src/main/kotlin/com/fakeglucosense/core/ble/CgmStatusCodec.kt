package com.fakeglucosense.core.ble

/**
 * The CGM Status characteristic (0x2AA9, read-only): a snapshot of the session as seen by the
 * sensor, in little-endian order:
 *
 * ```
 * Time Offset u16 (2) | Status octet | Cal/Temp octet | Warning octet | E2E-CRC u16 (2)
 * ```
 *
 * Unlike the Measurement record, all three Sensor Status Annunciation octets are always
 * present and there is no Flags octet. The trailing CRC covers the five bytes before it, as
 * it does in a measurement value. Seven bytes in total.
 */
object CgmStatusCodec {
    const val SIZE = 7

    fun encode(timeOffsetMinutes: Int, annunciation: StatusAnnunciation): ByteArray {
        require(timeOffsetMinutes in 0..CgmRecord.MAX_TIME_OFFSET) { "time offset out of range" }
        val out = ByteArray(SIZE)
        CgmMeasurementCodec.writeU16(out, 0, timeOffsetMinutes)
        out[2] = annunciation.status.toByte()
        out[3] = annunciation.calTemp.toByte()
        out[4] = annunciation.warning.toByte()
        CgmMeasurementCodec.writeU16(out, 5, CgmCrc16.compute(out, 0, 5))
        return out
    }

    /** Parses a CGM Status value and verifies its CRC. */
    fun decode(bytes: ByteArray): StatusValue {
        if (bytes.size < SIZE) throw CgmDecodeException("status value needs $SIZE bytes, got ${bytes.size}")
        val expected = CgmCrc16.compute(bytes, 0, 5)
        val actual = CgmMeasurementCodec.readU16(bytes, 5)
        if (actual != expected) throw CgmDecodeException("status E2E-CRC mismatch")
        return StatusValue(
            timeOffsetMinutes = CgmMeasurementCodec.readU16(bytes, 0),
            annunciation = StatusAnnunciation(
                status = bytes[2].toInt() and 0xFF,
                calTemp = bytes[3].toInt() and 0xFF,
                warning = bytes[4].toInt() and 0xFF,
            ),
        )
    }

    data class StatusValue(val timeOffsetMinutes: Int, val annunciation: StatusAnnunciation) {
        val sessionStopped: Boolean
            get() = annunciation.status and SensorStatusAnnunciation.STATUS_SESSION_STOPPED != 0
    }
}
