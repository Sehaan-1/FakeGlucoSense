package com.fakeglucosense.core.ble

/**
 * Pure selection logic behind the Record Access Control Point: validates a request and picks
 * the matching records. Shared by the sensor-app GATT server and the ESP32 firmware's behavior
 * contract, and pinned by unit tests, so the three implementations agree byte for byte.
 */
object RacpFilter {

    /**
     * Validates [request] the way the fake sensor answers it. Returns null when the request can
     * be served, or the RACP response value that explains why not.
     *
     * - Delete is rejected as a whole: the simulated sensor's memory is append-only.
     * - Abort is never "invalid" here; whether it succeeds depends on a report in flight, which
     *   is transport state, so the sensor answers that separately.
     * - A bounded operator needs the filter type [Racp.FILTER_TYPE_TIME_OFFSET]; anything else
     *   is answered "operand not supported".
     */
    fun validate(request: Racp.Request): Int? = when (request.opCode) {
        Racp.OP_DELETE_STORED_RECORDS -> Racp.RESPONSE_OP_CODE_NOT_SUPPORTED
        Racp.OP_REPORT_STORED_RECORDS, Racp.OP_REPORT_NUMBER_OF_RECORDS -> validateSelective(request)
        Racp.OP_ABORT_OPERATION ->
            if (request.operator != Racp.OPERATOR_NULL) Racp.RESPONSE_INVALID_OPERATOR else null
        else -> Racp.RESPONSE_OP_CODE_NOT_SUPPORTED
    }

    private fun validateSelective(request: Racp.Request): Int? =
        when (request.operator) {
            Racp.OPERATOR_ALL_RECORDS, Racp.OPERATOR_FIRST_RECORD, Racp.OPERATOR_LAST_RECORD -> {
                if (request.filterType != null || request.minOffsetMinutes != null || request.maxOffsetMinutes != null) {
                    Racp.RESPONSE_INVALID_OPERAND
                } else {
                    null
                }
            }
            Racp.OPERATOR_GREATER_THAN_OR_EQUAL, Racp.OPERATOR_LESS_THAN_OR_EQUAL, Racp.OPERATOR_WITHIN_RANGE ->
                when {
                    request.filterType != Racp.FILTER_TYPE_TIME_OFFSET -> Racp.RESPONSE_OPERAND_NOT_SUPPORTED
                    request.operator == Racp.OPERATOR_WITHIN_RANGE && request.minOffsetMinutes == null ->
                        Racp.RESPONSE_INVALID_OPERAND
                    request.operator == Racp.OPERATOR_GREATER_THAN_OR_EQUAL && request.minOffsetMinutes == null ->
                        Racp.RESPONSE_INVALID_OPERAND
                    request.operator == Racp.OPERATOR_LESS_THAN_OR_EQUAL && request.maxOffsetMinutes == null ->
                        Racp.RESPONSE_INVALID_OPERAND
                    request.operator == Racp.OPERATOR_WITHIN_RANGE &&
                        request.minOffsetMinutes != null && request.maxOffsetMinutes != null &&
                        request.minOffsetMinutes > request.maxOffsetMinutes -> Racp.RESPONSE_INVALID_OPERAND
                    else -> null
                }
            Racp.OPERATOR_NULL -> Racp.RESPONSE_INVALID_OPERATOR
            else -> Racp.RESPONSE_OPERATOR_NOT_SUPPORTED
        }

    /**
     * Picks the records a valid request selects, oldest first. First/last mean oldest/newest by
     * Time Offset, per the assigned-numbers operator table.
     */
    fun select(request: Racp.Request, records: List<CgmRecord>): List<CgmRecord> = when (request.operator) {
        Racp.OPERATOR_ALL_RECORDS -> records
        Racp.OPERATOR_FIRST_RECORD -> records.take(1)
        Racp.OPERATOR_LAST_RECORD -> records.takeLast(1)
        Racp.OPERATOR_GREATER_THAN_OR_EQUAL ->
            records.filter { it.timeOffsetMinutes >= (request.minOffsetMinutes ?: 0) }
        Racp.OPERATOR_LESS_THAN_OR_EQUAL ->
            records.filter { it.timeOffsetMinutes <= (request.maxOffsetMinutes ?: Int.MAX_VALUE) }
        Racp.OPERATOR_WITHIN_RANGE ->
            records.filter {
                it.timeOffsetMinutes >= (request.minOffsetMinutes ?: 0) &&
                    it.timeOffsetMinutes <= (request.maxOffsetMinutes ?: Int.MAX_VALUE)
            }
        else -> emptyList()
    }
}
