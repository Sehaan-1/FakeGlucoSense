package com.fakeglucosense.core.ble

/**
 * Encodes and decodes the CGM Measurement characteristic (0x2AA7). See docs/ble-protocol.md.
 *
 * Record layout (little-endian), in the order the fields are sent:
 * ```
 * Size (u8) | Flags (u8) | Glucose SFLOAT (2) | Time Offset u16 (2)
 *   [Status octet if Flags bit 7] [Cal/Temp octet if bit 6] [Warning octet if bit 5]
 *   [Trend SFLOAT (2) if Flags bit 0]
 *   [E2E-CRC u16, when the characteristic value carries one]
 * ```
 * Size includes itself and every optional field sent. The shortest record is 6 bytes; with the
 * three annunciation octets, trend, and CRC it is 13.
 *
 * The E2E-CRC has no flag bit: its presence is implied when a record is two bytes longer than
 * its declared fields need, which is how Nordic's and STM32's collectors parse it. When a value
 * carries a CRC, the CRC is computed once over the whole value with the CRC fields excluded and
 * stamped at the end of every record. The decoder recomputes it the same way and throws on a
 * mismatch.
 *
 * Decoding is strict about sizes and conservative about flags. A record whose Flags set the
 * Quality bit or a reserved bit is skipped whole, using its Size octet, so the rest of the
 * notification stays in sync.
 */
object CgmMeasurementCodec {
    const val FLAG_TREND_PRESENT = 0x01
    const val FLAG_WARNING_OCTET_PRESENT = 0x20
    const val FLAG_CALTEMP_OCTET_PRESENT = 0x40
    const val FLAG_STATUS_OCTET_PRESENT = 0x80

    /** Quality (bit 1) and reserved bits we do not parse: skip the record via its Size octet. */
    private const val UNSUPPORTED_FLAGS_MASK = 0x1E
    private const val BASE_SIZE = 6
    private const val TREND_SIZE = 2
    private const val CRC_SIZE = 2

    /** SFLOAT infinities map to the physiological clamp, so a saturated sensor still raises alerts. */
    const val ABOVE_RANGE_MGDL = 400.0
    const val BELOW_RANGE_MGDL = 40.0

    /** Encodes one record without a CRC. Convenience for single-record framing. */
    fun encode(record: CgmRecord): ByteArray = encodeValue(listOf(record), withCrc = false)

    /**
     * Encodes [records] as one characteristic value. When [withCrc] is set, an E2E-CRC over the
     * whole value (CRC fields excluded) is appended to every record and counted in each Size.
     */
    fun encodeValue(records: List<CgmRecord>, withCrc: Boolean = false): ByteArray {
        val prefixes = records.map { encodePrefix(it, withCrc) }
        val body = concat(prefixes)
        if (!withCrc) return body
        val crc = CgmCrc16.compute(body)
        val out = ByteArray(body.size + prefixes.size * CRC_SIZE)
        var position = 0
        for (prefix in prefixes) {
            prefix.copyInto(out, position)
            position += prefix.size
            writeU16(out, position, crc)
            position += CRC_SIZE
        }
        return out
    }

    /** Back-compatible name for [encodeValue] without a CRC. */
    fun encodeAll(records: List<CgmRecord>): ByteArray = encodeValue(records, withCrc = false)

    /**
     * Splits [records] into characteristic values no larger than [maxPayloadBytes], each a
     * complete, self-verifying value (the CRC, when requested, covers its own frame). Use the
     * negotiated ATT MTU minus 3 bytes of overhead. Each value holds whole records only.
     */
    fun batch(records: List<CgmRecord>, maxPayloadBytes: Int, withCrc: Boolean = false): List<ByteArray> {
        val sizes = records.map { recordSize(it, withCrc) }
        require(sizes.all { it <= maxPayloadBytes }) { "payload limit $maxPayloadBytes is too small for one record" }
        val batches = mutableListOf<ByteArray>()
        val current = mutableListOf<CgmRecord>()
        var currentSize = 0
        for ((index, record) in records.withIndex()) {
            if (currentSize + sizes[index] > maxPayloadBytes && current.isNotEmpty()) {
                batches += encodeValue(current, withCrc)
                current.clear()
                currentSize = 0
            }
            current += record
            currentSize += sizes[index]
        }
        if (current.isNotEmpty()) batches += encodeValue(current, withCrc)
        return batches
    }

    /** Wire length of one record. A CRC counts in the Size field when present. */
    fun recordSize(record: CgmRecord, withCrc: Boolean): Int =
        BASE_SIZE + annunciationSize(record.statusAnnunciation) +
            (if (record.trendMgdlPerMin != null) TREND_SIZE else 0) +
            (if (withCrc) CRC_SIZE else 0)

    /** Decodes a characteristic value. See the class comment for the skip and CRC rules. */
    fun decodeAll(payload: ByteArray): DecodeResult {
        val parsed = parseRecords(payload)
        verifyCrcIfPresent(payload, parsed)
        return DecodeResult(parsed.mapNotNull { it.record }, parsed.count { it.skipped })
    }

    /** Result of [decodeAll]: usable records plus the count of records skipped on purpose. */
    data class DecodeResult(val records: List<CgmRecord>, val skipped: Int)

    /**
     * One span of the parsed value. [prefixStart]..[prefixEnd) covers the bytes the CRC sees:
     * the record without its CRC field, or the full span of a skipped record (its own CRC
     * layout is unknowable, so encoders that mix unsupported flags with CRC values are not
     * verifiable — ours never does).
     */
    private data class Parsed(
        val record: CgmRecord?,
        val skipped: Boolean,
        val prefixStart: Int,
        val prefixEnd: Int,
        val crc: Int?,
    )

    private fun parseRecords(payload: ByteArray): List<Parsed> {
        val out = mutableListOf<Parsed>()
        var position = 0
        while (position < payload.size) {
            val size = payload[position].toInt() and 0xFF
            if (size < BASE_SIZE || position + size > payload.size) {
                throw CgmDecodeException("record at $position has invalid size $size for ${payload.size}-byte payload")
            }
            val flags = payload[position + 1].toInt() and 0xFF
            if (flags and UNSUPPORTED_FLAGS_MASK != 0) {
                out += Parsed(null, skipped = true, prefixStart = position, prefixEnd = position + size, crc = null)
                position += size
                continue
            }
            val declared = BASE_SIZE + annunciationSize(flags) +
                if (flags and FLAG_TREND_PRESENT != 0) TREND_SIZE else 0
            val hasCrc = size == declared + CRC_SIZE
            if (size != declared && !hasCrc) {
                throw CgmDecodeException("record at $position has size $size, expected $declared for flags $flags")
            }
            val prefixEnd = position + declared
            val crc = if (hasCrc) readU16(payload, position + size - CRC_SIZE) else null
            val record = readRecord(payload, position, flags)
            // An SFLOAT NaN glucose value yields no record; the slot counts as skipped.
            out += Parsed(record, skipped = record == null, prefixStart = position, prefixEnd = prefixEnd, crc = crc)
            position += size
        }
        return out
    }

    /**
     * Reads the declared fields of one record. Returns null when the glucose value is an SFLOAT
     * special that maps to nothing (NaN), which the caller counts as skipped.
     */
    private fun readRecord(payload: ByteArray, position: Int, flags: Int): CgmRecord? {
        val glucose = SFloat.decode(readU16(payload, position + 2))
        val offset = readU16(payload, position + 4)
        var cursor = position + BASE_SIZE
        val annunciation = readAnnunciation(payload, cursor, flags)
        cursor += annunciationSize(flags)
        val trend = if (flags and FLAG_TREND_PRESENT != 0) SFloat.decode(readU16(payload, cursor)) else null
        val mgdl = mapGlucose(glucose) ?: return null
        return CgmRecord(
            timeOffsetMinutes = offset,
            mgdl = mgdl,
            trendMgdlPerMin = trend?.takeIf { it.isFinite() },
            statusAnnunciation = annunciation.takeIf { !it.isEmpty },
        )
    }

    /** Reads whichever annunciation octets the flags mark as present, in wire order. */
    private fun readAnnunciation(payload: ByteArray, position: Int, flags: Int): StatusAnnunciation {
        var cursor = position
        var status = 0
        var calTemp = 0
        var warning = 0
        if (flags and FLAG_STATUS_OCTET_PRESENT != 0) {
            status = payload[cursor].toInt() and 0xFF
            cursor++
        }
        if (flags and FLAG_CALTEMP_OCTET_PRESENT != 0) {
            calTemp = payload[cursor].toInt() and 0xFF
            cursor++
        }
        if (flags and FLAG_WARNING_OCTET_PRESENT != 0) {
            warning = payload[cursor].toInt() and 0xFF
        }
        return StatusAnnunciation(status = status, calTemp = calTemp, warning = warning)
    }

    /** The record body without any CRC. Size and Flags already reflect the with-CRC layout. */
    private fun encodePrefix(record: CgmRecord, withCrc: Boolean): ByteArray {
        val size = recordSize(record, withCrc)
        val out = ByteArray(size - if (withCrc) CRC_SIZE else 0)
        out[0] = size.toByte()
        out[1] = flagsOf(record).toByte()
        writeU16(out, 2, SFloat.encode(record.mgdl))
        writeU16(out, 4, record.timeOffsetMinutes)
        var cursor = BASE_SIZE
        val annunciation = record.statusAnnunciation
        if (annunciation != null) {
            // Wire order is status, cal/temp, warning; zero octets are not sent.
            if (annunciation.status != 0) out[cursor++] = annunciation.status.toByte()
            if (annunciation.calTemp != 0) out[cursor++] = annunciation.calTemp.toByte()
            if (annunciation.warning != 0) out[cursor++] = annunciation.warning.toByte()
        }
        record.trendMgdlPerMin?.let { writeU16(out, cursor, SFloat.encode(it)) }
        return out
    }

    private fun flagsOf(record: CgmRecord): Int {
        var flags = 0
        if (record.trendMgdlPerMin != null) flags = flags or FLAG_TREND_PRESENT
        val annunciation = record.statusAnnunciation
        if (annunciation != null) {
            if (annunciation.warning != 0) flags = flags or FLAG_WARNING_OCTET_PRESENT
            if (annunciation.calTemp != 0) flags = flags or FLAG_CALTEMP_OCTET_PRESENT
            if (annunciation.status != 0) flags = flags or FLAG_STATUS_OCTET_PRESENT
        }
        return flags
    }

    private fun annunciationSize(annunciation: StatusAnnunciation?): Int {
        if (annunciation == null) return 0
        var size = 0
        if (annunciation.status != 0) size++
        if (annunciation.calTemp != 0) size++
        if (annunciation.warning != 0) size++
        return size
    }

    private fun annunciationSize(flags: Int): Int {
        var size = 0
        if (flags and FLAG_STATUS_OCTET_PRESENT != 0) size++
        if (flags and FLAG_CALTEMP_OCTET_PRESENT != 0) size++
        if (flags and FLAG_WARNING_OCTET_PRESENT != 0) size++
        return size
    }

    /**
     * When any record carried an E2E-CRC, recomputes it over the whole value with the CRC
     * fields left out, and demands that every CRC field agrees. A mismatch makes the whole
     * value malformed: partial data is worse than none.
     */
    private fun verifyCrcIfPresent(payload: ByteArray, parsed: List<Parsed>) {
        if (parsed.none { it.crc != null }) return
        var total = 0
        for (part in parsed) total += part.prefixEnd - part.prefixStart
        val body = ByteArray(total)
        var position = 0
        for (part in parsed) {
            payload.copyInto(body, position, part.prefixStart, part.prefixEnd)
            position += part.prefixEnd - part.prefixStart
        }
        val expected = CgmCrc16.compute(body)
        if (parsed.any { it.crc != null && it.crc != expected }) {
            throw CgmDecodeException("E2E-CRC mismatch over ${payload.size}-byte value")
        }
    }

    private fun mapGlucose(value: Double): Double? = when {
        value.isNaN() -> null
        value == Double.POSITIVE_INFINITY -> ABOVE_RANGE_MGDL
        value == Double.NEGATIVE_INFINITY -> BELOW_RANGE_MGDL
        else -> value
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var position = 0
        for (part in parts) {
            part.copyInto(out, position)
            position += part.size
        }
        return out
    }

    internal fun writeU16(target: ByteArray, index: Int, value: Int) {
        target[index] = (value and 0xFF).toByte()
        target[index + 1] = ((value shr 8) and 0xFF).toByte()
    }

    internal fun readU16(source: ByteArray, index: Int): Int {
        if (index + 1 >= source.size) throw CgmDecodeException("truncated field at $index")
        return (source[index].toInt() and 0xFF) or ((source[index + 1].toInt() and 0xFF) shl 8)
    }
}
