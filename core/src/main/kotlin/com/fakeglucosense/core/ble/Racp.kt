package com.fakeglucosense.core.ble

/**
 * The Record Access Control Point characteristic (0x2A52), as the CGM Service uses it
 * (CGMS v1.0.2 §3.7; op, operator, and response tables from the Bluetooth assigned numbers,
 * `org.bluetooth.characteristic.record_access_control_point`).
 *
 * A central writes a request (write with response). The sensor sends the requested records as
 * CGM Measurement notifications, then indicates the outcome on the RACP characteristic:
 * [OP_NUMBER_OF_RECORDS_RESPONSE] for count requests, [OP_RESPONSE_CODE] otherwise.
 *
 * Filters compare the CGM Time Offset (UINT16 minutes), announced by [FILTER_TYPE_TIME_OFFSET].
 */
object Racp {
    // Op codes.
    const val OP_REPORT_STORED_RECORDS = 0x01
    const val OP_DELETE_STORED_RECORDS = 0x02
    const val OP_ABORT_OPERATION = 0x03
    const val OP_REPORT_NUMBER_OF_RECORDS = 0x04
    const val OP_NUMBER_OF_RECORDS_RESPONSE = 0x05
    const val OP_RESPONSE_CODE = 0x06

    // Operators.
    const val OPERATOR_NULL = 0x00
    const val OPERATOR_ALL_RECORDS = 0x01
    const val OPERATOR_LESS_THAN_OR_EQUAL = 0x02
    const val OPERATOR_GREATER_THAN_OR_EQUAL = 0x03
    const val OPERATOR_WITHIN_RANGE = 0x04
    const val OPERATOR_FIRST_RECORD = 0x05
    const val OPERATOR_LAST_RECORD = 0x06

    // Response code values.
    const val RESPONSE_SUCCESS = 0x01
    const val RESPONSE_OP_CODE_NOT_SUPPORTED = 0x02
    const val RESPONSE_INVALID_OPERATOR = 0x03
    const val RESPONSE_OPERATOR_NOT_SUPPORTED = 0x04
    const val RESPONSE_INVALID_OPERAND = 0x05
    const val RESPONSE_NO_RECORDS_FOUND = 0x06
    const val RESPONSE_ABORT_UNSUCCESSFUL = 0x07
    const val RESPONSE_PROCEDURE_NOT_COMPLETED = 0x08
    const val RESPONSE_OPERAND_NOT_SUPPORTED = 0x09

    /** The only operand filter the CGM Service defines: by Time Offset. */
    const val FILTER_TYPE_TIME_OFFSET = 0x01

    /**
     * A decoded RACP request. [minOffsetMinutes] and [maxOffsetMinutes] are set for the bounded
     * operators: <= fills max, >= fills min, within-range fills both. [filterType] keeps the
     * operand's declared filter so the sensor can answer "operand not supported" instead of
     * rejecting the write.
     */
    data class Request(
        val opCode: Int,
        val operator: Int,
        val minOffsetMinutes: Int? = null,
        val maxOffsetMinutes: Int? = null,
        val filterType: Int? = null,
    )

    /**
     * Parses a request written to the RACP characteristic. Throws [CgmDecodeException] only on a
     * structurally truncated value; unsupported-but-readable values are the sensor's to answer.
     */
    fun decodeRequest(bytes: ByteArray): Request {
        if (bytes.size < 2) throw CgmDecodeException("RACP request needs at least 2 bytes")
        val opCode = bytes[0].toInt() and 0xFF
        val operator = bytes[1].toInt() and 0xFF
        var cursor = 2

        fun readByte(): Int {
            if (cursor >= bytes.size) throw CgmDecodeException("RACP operand truncated")
            val value = bytes[cursor].toInt() and 0xFF
            cursor++
            return value
        }

        fun readU16(): Int {
            val low = readByte()
            val high = readByte()
            return low or (high shl 8)
        }

        fun readBound(): Pair<Int, Int> {
            val filter = readByte()
            return filter to readU16()
        }

        return when (operator) {
            OPERATOR_WITHIN_RANGE -> {
                val (filter, min) = readBound()
                val max = readU16()
                Request(opCode, operator, minOffsetMinutes = min, maxOffsetMinutes = max, filterType = filter)
            }
            OPERATOR_GREATER_THAN_OR_EQUAL -> {
                val (filter, min) = readBound()
                Request(opCode, operator, minOffsetMinutes = min, filterType = filter)
            }
            OPERATOR_LESS_THAN_OR_EQUAL -> {
                val (filter, max) = readBound()
                Request(opCode, operator, maxOffsetMinutes = max, filterType = filter)
            }
            else -> Request(opCode, operator)
        }
    }

    /** The "response code" indication value for a request that has no response operand. */
    fun encodeResponseCode(requestOpCode: Int, responseValue: Int): ByteArray =
        byteArrayOf(OP_RESPONSE_CODE.toByte(), OPERATOR_NULL.toByte(), requestOpCode.toByte(), responseValue.toByte())

    /** The "number of stored records response" indication value for a count request. */
    fun encodeNumberOfRecords(count: Int): ByteArray {
        require(count in 0..0xFFFF) { "record count out of range" }
        return byteArrayOf(
            OP_NUMBER_OF_RECORDS_RESPONSE.toByte(),
            OPERATOR_NULL.toByte(),
            (count and 0xFF).toByte(),
            ((count shr 8) and 0xFF).toByte(),
        )
    }

    // Central-side request encoders, used by the companion app.

    fun requestStoredRecords(operator: Int, minOffsetMinutes: Int? = null, maxOffsetMinutes: Int? = null): ByteArray =
        encodeSelective(OP_REPORT_STORED_RECORDS, operator, minOffsetMinutes, maxOffsetMinutes)

    fun requestNumberOfRecords(operator: Int, minOffsetMinutes: Int? = null, maxOffsetMinutes: Int? = null): ByteArray =
        encodeSelective(OP_REPORT_NUMBER_OF_RECORDS, operator, minOffsetMinutes, maxOffsetMinutes)

    fun requestAbort(): ByteArray = byteArrayOf(OP_ABORT_OPERATION.toByte(), OPERATOR_NULL.toByte())

    private fun encodeSelective(opCode: Int, operator: Int, minOffsetMinutes: Int?, maxOffsetMinutes: Int?): ByteArray {
        val out = mutableListOf(opCode.toByte(), operator.toByte())
        fun filterTag() {
            out += FILTER_TYPE_TIME_OFFSET.toByte()
        }
        fun bound(bound: Int?) {
            val value = bound ?: throw CgmDecodeException("operator $operator needs a bound")
            require(value in 0..0xFFFF) { "time offset out of range" }
            out += (value and 0xFF).toByte()
            out += ((value shr 8) and 0xFF).toByte()
        }
        when (operator) {
            OPERATOR_WITHIN_RANGE -> {
                filterTag()
                bound(minOffsetMinutes)
                bound(maxOffsetMinutes)
            }
            OPERATOR_GREATER_THAN_OR_EQUAL -> {
                filterTag()
                bound(minOffsetMinutes)
            }
            OPERATOR_LESS_THAN_OR_EQUAL -> {
                filterTag()
                bound(maxOffsetMinutes)
            }
        }
        return out.toByteArray()
    }
}
