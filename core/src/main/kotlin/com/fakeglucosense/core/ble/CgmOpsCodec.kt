package com.fakeglucosense.core.ble

/**
 * The CGM Specific Ops Control Point characteristic (0x2AAC, write | indicate), op codes from
 * CGMS v1.0.2 Table 3.17 as implemented by Nordic's CGMS service.
 *
 * The fake sensor implements the session and cadence ops: Set/Get Communication Interval,
 * Start the Session, and Stop the Session. Calibration and alert-level ops are answered
 * "op code not supported": the fake sensor has no calibration to set, and its alert levels
 * live in the companion app.
 *
 * Responses are indications on the same characteristic: a dedicated response op for Get
 * Communication Interval, or the generic [OP_RESPONSE_CODE] for everything else.
 */
object CgmOpsCodec {
    const val OP_SET_COMMUNICATION_INTERVAL = 0x01
    const val OP_GET_COMMUNICATION_INTERVAL = 0x02
    const val OP_COMMUNICATION_INTERVAL_RESPONSE = 0x03
    const val OP_START_SESSION = 0x1A
    const val OP_STOP_SESSION = 0x1B
    const val OP_RESPONSE_CODE = 0x1C

    const val RESPONSE_SUCCESS = 0x01
    const val RESPONSE_OP_CODE_NOT_SUPPORTED = 0x02
    const val RESPONSE_INVALID_OPERAND = 0x03
    const val RESPONSE_PROCEDURE_NOT_COMPLETED = 0x04
    const val RESPONSE_OUT_OF_RANGE = 0x05

    /** Ranges the sensor accepts for the communication interval, in whole minutes. */
    const val MIN_COMMUNICATION_INTERVAL_MINUTES = 1
    const val MAX_COMMUNICATION_INTERVAL_MINUTES = 255

    /** A decoded ops request. [intervalMinutes] is set only for Set Communication Interval. */
    data class Request(val opCode: Int, val intervalMinutes: Int? = null)

    /** Parses an ops CP write. Throws [CgmDecodeException] on a structurally truncated value. */
    fun decodeRequest(bytes: ByteArray): Request {
        if (bytes.isEmpty()) throw CgmDecodeException("ops request needs at least 1 byte")
        val opCode = bytes[0].toInt() and 0xFF
        return if (opCode == OP_SET_COMMUNICATION_INTERVAL) {
            if (bytes.size < 2) throw CgmDecodeException("set interval needs an operand")
            Request(opCode, intervalMinutes = bytes[1].toInt() and 0xFF)
        } else {
            Request(opCode)
        }
    }

    /** The "communication interval response" indication value. */
    fun encodeCommunicationInterval(intervalMinutes: Int): ByteArray {
        require(intervalMinutes in 0..0xFF) { "interval out of range" }
        return byteArrayOf(OP_COMMUNICATION_INTERVAL_RESPONSE.toByte(), intervalMinutes.toByte())
    }

    /** The generic "response code" indication value. */
    fun encodeResponseCode(requestOpCode: Int, responseValue: Int): ByteArray =
        byteArrayOf(OP_RESPONSE_CODE.toByte(), requestOpCode.toByte(), responseValue.toByte())
}
