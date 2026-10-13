package com.fakeglucosense.core.ble

/**
 * The CGM Feature characteristic (0x2AA8): a 24-bit feature bitmap plus one octet that carries
 * the CGM Type (high nibble) and the CGM Sample Location (low nibble). See docs/ble-protocol.md.
 *
 * The simulated sensor reports what it actually does: patient high/low, hypo, hyper, and rate
 * alerts via the Warning annunciation octet; E2E-CRC on every measurement; and trend
 * information. Calibration, bonds, and multiple sessions are reported as unsupported.
 */
object CgmFeatureCodec {
    const val FEAT_CALIBRATION_SUPPORTED = 0x000001
    const val FEAT_PATIENT_HIGH_LOW_ALERTS = 0x000002
    const val FEAT_HYPO_ALERTS = 0x000004
    const val FEAT_HYPER_ALERTS = 0x000008
    const val FEAT_RATE_OF_CHANGE_ALERTS = 0x000010
    const val FEAT_E2E_CRC_SUPPORTED = 0x001000
    const val FEAT_MULTIPLE_BOND_SUPPORTED = 0x002000
    const val FEAT_MULTIPLE_SESSIONS_SUPPORTED = 0x004000
    const val FEAT_TREND_INFORMATION_SUPPORTED = 0x008000
    const val FEAT_QUALITY_SUPPORTED = 0x010000

    const val TYPE_INTERSTITIAL_FLUID = 0x09
    const val SAMPLE_LOCATION_SUBCUTANEOUS = 0x05
    const val SAMPLE_LOCATION_NOT_AVAILABLE = 0x0F

    const val SIZE = 4

    /** The feature value the FakeGlucoSense sensor and ESP32 firmware advertise. */
    const val SENSOR_FEATURES: Int =
        FEAT_PATIENT_HIGH_LOW_ALERTS or FEAT_HYPO_ALERTS or FEAT_HYPER_ALERTS or
            FEAT_RATE_OF_CHANGE_ALERTS or FEAT_E2E_CRC_SUPPORTED or FEAT_TREND_INFORMATION_SUPPORTED

    /** Serializes [features] with the type and sample location nibbles. */
    fun encode(features: Int = SENSOR_FEATURES, type: Int = TYPE_INTERSTITIAL_FLUID, sampleLocation: Int = SAMPLE_LOCATION_SUBCUTANEOUS): ByteArray {
        require(features in 0..0xFFFFFF) { "feature bitmap out of range" }
        require(type in 0..0xF && sampleLocation in 0..0xF) { "type/location nibbles out of range" }
        val out = ByteArray(SIZE)
        out[0] = (features and 0xFF).toByte()
        out[1] = ((features shr 8) and 0xFF).toByte()
        out[2] = ((features shr 16) and 0xFF).toByte()
        out[3] = ((type shl 4) or sampleLocation).toByte()
        return out
    }

    /** Parses a CGM Feature value. Throws [CgmDecodeException] on a short value. */
    fun decode(bytes: ByteArray): FeatureValue {
        if (bytes.size < SIZE) throw CgmDecodeException("feature value needs $SIZE bytes, got ${bytes.size}")
        val features = (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16)
        return FeatureValue(
            features = features,
            type = (bytes[3].toInt() shr 4) and 0xF,
            sampleLocation = bytes[3].toInt() and 0xF,
        )
    }

    data class FeatureValue(val features: Int, val type: Int, val sampleLocation: Int) {
        val e2eCrcSupported: Boolean get() = features and FEAT_E2E_CRC_SUPPORTED != 0
    }
}
