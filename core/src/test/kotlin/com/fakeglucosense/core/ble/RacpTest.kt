package com.fakeglucosense.core.ble

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RacpTest {

    // --- request decoding ---------------------------------------------------

    @Test
    fun `decodes report-all from two bytes`() {
        val request = Racp.decodeRequest(byteArrayOf(0x01, 0x01))
        assertEquals(Racp.OP_REPORT_STORED_RECORDS, request.opCode)
        assertEquals(Racp.OPERATOR_ALL_RECORDS, request.operator)
        assertNull(request.minOffsetMinutes)
        assertNull(request.filterType)
    }

    @Test
    fun `decodes greater-or-equal with a filter type and a UINT16 bound`() {
        // op 0x01, operator >=, filter 0x01 (time offset), bound 300 LE
        val request = Racp.decodeRequest(byteArrayOf(0x01, 0x03, 0x01, 0x2C, 0x01))
        assertEquals(300, request.minOffsetMinutes)
        assertEquals(Racp.FILTER_TYPE_TIME_OFFSET, request.filterType)
    }

    @Test
    fun `decodes within-range with one filter byte and two bounds`() {
        val request = Racp.decodeRequest(byteArrayOf(0x04, 0x04, 0x01, 0x64, 0x00, 0xC8.toByte(), 0x00))
        assertEquals(100, request.minOffsetMinutes)
        assertEquals(200, request.maxOffsetMinutes)
    }

    @Test
    fun `rejects truncated operands`() {
        assertFailsWith<CgmDecodeException> { Racp.decodeRequest(byteArrayOf(0x01)) }
        assertFailsWith<CgmDecodeException> { Racp.decodeRequest(byteArrayOf(0x01, 0x03, 0x01, 0x2C)) }
    }

    @Test
    fun `request encoders round-trip through the decoder`() {
        val original = Racp.requestStoredRecords(Racp.OPERATOR_WITHIN_RANGE, minOffsetMinutes = 5, maxOffsetMinutes = 995)
        val decoded = Racp.decodeRequest(original)
        assertEquals(5, decoded.minOffsetMinutes)
        assertEquals(995, decoded.maxOffsetMinutes)
        assertEquals(Racp.FILTER_TYPE_TIME_OFFSET, decoded.filterType)

        val count = Racp.decodeRequest(Racp.requestNumberOfRecords(Racp.OPERATOR_GREATER_THAN_OR_EQUAL, minOffsetMinutes = 60))
        assertEquals(Racp.OP_REPORT_NUMBER_OF_RECORDS, count.opCode)
        assertEquals(60, count.minOffsetMinutes)
    }

    // --- response encoders --------------------------------------------------

    @Test
    fun `response code frame carries the request op and the value`() {
        assertContentEquals(
            byteArrayOf(0x06, 0x00, 0x01, 0x01),
            Racp.encodeResponseCode(Racp.OP_REPORT_STORED_RECORDS, Racp.RESPONSE_SUCCESS),
        )
    }

    @Test
    fun `number-of-records frame carries a UINT16 count`() {
        assertContentEquals(byteArrayOf(0x05, 0x00, 0x2C, 0x01), Racp.encodeNumberOfRecords(300))
        assertFailsWith<IllegalArgumentException> { Racp.encodeNumberOfRecords(70_000) }
    }

    // --- validation ----------------------------------------------------------

    @Test
    fun `delete is answered op-code-not-supported on the fake sensor`() {
        val request = Racp.decodeRequest(byteArrayOf(Racp.OP_DELETE_STORED_RECORDS.toByte(), 0x01))
        assertEquals(Racp.RESPONSE_OP_CODE_NOT_SUPPORTED, RacpFilter.validate(request))
    }

    @Test
    fun `abort requires the null operator`() {
        assertEquals(
            Racp.RESPONSE_INVALID_OPERATOR,
            RacpFilter.validate(Racp.Request(Racp.OP_ABORT_OPERATION, Racp.OPERATOR_ALL_RECORDS)),
        )
        assertNull(RacpFilter.validate(Racp.Request(Racp.OP_ABORT_OPERATION, Racp.OPERATOR_NULL)))
    }

    @Test
    fun `bounded operators demand the time-offset filter`() {
        val wrongFilter = Racp.Request(
            Racp.OP_REPORT_STORED_RECORDS,
            Racp.OPERATOR_GREATER_THAN_OR_EQUAL,
            minOffsetMinutes = 10,
            filterType = 0x02,
        )
        assertEquals(Racp.RESPONSE_OPERAND_NOT_SUPPORTED, RacpFilter.validate(wrongFilter))

        val inverted = Racp.Request(
            Racp.OP_REPORT_STORED_RECORDS,
            Racp.OPERATOR_WITHIN_RANGE,
            minOffsetMinutes = 100,
            maxOffsetMinutes = 10,
            filterType = Racp.FILTER_TYPE_TIME_OFFSET,
        )
        assertEquals(Racp.RESPONSE_INVALID_OPERAND, RacpFilter.validate(inverted))

        val unknownOperator = Racp.Request(Racp.OP_REPORT_STORED_RECORDS, 0x42)
        assertEquals(Racp.RESPONSE_OPERATOR_NOT_SUPPORTED, RacpFilter.validate(unknownOperator))
    }

    // --- selection -----------------------------------------------------------

    private val history = (0..500 step 5).map { CgmRecord(it, 100.0 + it % 10) }

    @Test
    fun `select picks first, last, all, and windows by offset`() {
        fun request(operator: Int, min: Int? = null, max: Int? = null) =
            Racp.Request(Racp.OP_REPORT_STORED_RECORDS, operator, minOffsetMinutes = min, maxOffsetMinutes = max)

        assertEquals(history, RacpFilter.select(request(Racp.OPERATOR_ALL_RECORDS), history))
        assertEquals(listOf(0), RacpFilter.select(request(Racp.OPERATOR_FIRST_RECORD), history).map { it.timeOffsetMinutes })
        assertEquals(listOf(500), RacpFilter.select(request(Racp.OPERATOR_LAST_RECORD), history).map { it.timeOffsetMinutes })
        assertEquals(
            (100..500 step 5).toList(),
            RacpFilter.select(request(Racp.OPERATOR_GREATER_THAN_OR_EQUAL, min = 100), history).map { it.timeOffsetMinutes },
        )
        assertEquals(
            (0..100 step 5).toList(),
            RacpFilter.select(request(Racp.OPERATOR_LESS_THAN_OR_EQUAL, max = 100), history).map { it.timeOffsetMinutes },
        )
        assertEquals(
            (95..205 step 5).toList(),
            RacpFilter.select(request(Racp.OPERATOR_WITHIN_RANGE, min = 95, max = 205), history).map { it.timeOffsetMinutes },
        )
    }
}
