package com.fakeglucosense.core.ble

import java.util.UUID

/**
 * Bluetooth SIG assigned UUIDs for the Continuous Glucose Monitoring Service (CGMS v1.0.2).
 * Full 128-bit forms are derived from the 16-bit SIG base UUID 0000xxxx-0000-1000-8000-00805F9B34FB.
 */
object CgmUuids {
    private fun sig16(short: Int): UUID = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))

    /** CGM Service (0x181F) — advertised by the sensor and used as the scan filter. */
    val SERVICE: UUID = sig16(0x181F)

    /** CGM Measurement (0x2AA7) — notify. Carries one or more CGM Measurement records. */
    val MEASUREMENT: UUID = sig16(0x2AA7)

    /** CGM Feature (0x2AA8) — read. The sensor's capability bitmap, type, and sample location. */
    val FEATURE: UUID = sig16(0x2AA8)

    /** CGM Status (0x2AA9) — read. Current time offset with the three annunciation octets. */
    val STATUS: UUID = sig16(0x2AA9)

    /** CGM Session Start Time (0x2AAA) — read. Anchors Time Offset to wall-clock time. */
    val SESSION_START_TIME: UUID = sig16(0x2AAA)

    /** CGM Specific Ops Control Point (0x2AAC) — write, indicate. Session and cadence control. */
    val SPECIFIC_OPS_CONTROL_POINT: UUID = sig16(0x2AAC)

    /** Record Access Control Point (0x2A52) — write, indicate. On-demand stored-record access. */
    val RECORD_ACCESS_CONTROL_POINT: UUID = sig16(0x2A52)

    /** Client Characteristic Configuration descriptor (0x2902) — enables notifications/indications. */
    val CLIENT_CONFIG: UUID = sig16(0x2902)
}
