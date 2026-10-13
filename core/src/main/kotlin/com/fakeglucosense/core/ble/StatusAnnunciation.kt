package com.fakeglucosense.core.ble

/**
 * The three Sensor Status Annunciation octets of a CGM record, as one value. Each octet is
 * optional on the wire and is flagged on its own (Flags bits 5, 6, 7); an octet that is zero
 * is not sent at all, matching Nordic's CGMS service (see docs/adr/0014). In the CGM Status
 * characteristic all three octets are always present in the order status, cal/temp, warning.
 */
data class StatusAnnunciation(
    val status: Int = 0,
    val calTemp: Int = 0,
    val warning: Int = 0,
) {
    init {
        require(status in 0..0xFF) { "status octet out of range: $status" }
        require(calTemp in 0..0xFF) { "cal/temp octet out of range: $calTemp" }
        require(warning in 0..0xFF) { "warning octet out of range: $warning" }
    }

    val isEmpty: Boolean get() = status == 0 && calTemp == 0 && warning == 0
}
