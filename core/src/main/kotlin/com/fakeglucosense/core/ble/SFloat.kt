package com.fakeglucosense.core.ble

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * IEEE-11073 16-bit SFLOAT: a signed 4-bit exponent (high nibble) and a signed 12-bit mantissa.
 * Value = mantissa × 10^exponent. Used for every glucose and trend field in the CGM Service.
 *
 * Special raw values (exponent 0) are decoded to non-finite doubles:
 * 0x07FE → +∞, 0x0802 → −∞, 0x07FF → NaN (not a number), 0x0800 → NaN (NRes), 0x0801 → NaN (reserved).
 */
object SFloat {
    const val RAW_POSITIVE_INFINITY = 0x07FE
    const val RAW_NAN = 0x07FF
    const val RAW_NRES = 0x0800
    const val RAW_RESERVED = 0x0801
    const val RAW_NEGATIVE_INFINITY = 0x0802

    /** Largest mantissa magnitude we emit; keeps clear of the special-value range. */
    const val MAX_MANTISSA = 2045

    /** Candidate exponents, nearest zero first, so exact ties resolve to the most natural form. */
    private val EXPONENT_ORDER = listOf(0, -1, 1, -2, 2, -3, 3, 4, 5, 6, 7)
    private const val ERROR_EPSILON = 1e-9

    /**
     * Encodes [value] with the exponent whose mantissa fits and reproduces the value most closely.
     * Exact ties prefer the exponent nearest zero, so 120 encodes as 120 × 10^0 (raw 0x0078).
     *
     * @throws IllegalArgumentException if [value] is not finite or no exponent can represent it.
     */
    fun encode(value: Double): Int {
        require(value.isFinite()) { "SFLOAT cannot encode non-finite value $value" }
        var best = -1
        var bestError = Double.MAX_VALUE
        for (exponent in EXPONENT_ORDER) {
            val scale = 10.0.pow(exponent)
            val mantissa = (value / scale).roundToLong()
            if (mantissa !in -MAX_MANTISSA..MAX_MANTISSA) continue
            val error = abs(mantissa * scale - value)
            if (best < 0 || error < bestError - ERROR_EPSILON) {
                bestError = error
                best = pack(exponent, mantissa.toInt())
            }
        }
        require(best >= 0) { "value $value is not representable as SFLOAT" }
        return best
    }

    /** Decodes a 16-bit raw SFLOAT. Returns NaN or ±∞ for the special values listed above. */
    fun decode(raw: Int): Double {
        val bits = raw and 0xFFFF
        when (bits) {
            RAW_POSITIVE_INFINITY -> return Double.POSITIVE_INFINITY
            RAW_NEGATIVE_INFINITY -> return Double.NEGATIVE_INFINITY
            RAW_NAN, RAW_NRES, RAW_RESERVED -> return Double.NaN
        }
        var exponent = (bits shr 12) and 0xF
        if (exponent >= 8) exponent -= 16
        var mantissa = bits and 0xFFF
        if (mantissa >= 0x800) mantissa -= 0x1000
        return mantissa * 10.0.pow(exponent)
    }

    /** Packs exponent and mantissa into the little-endian-agnostic 16-bit value. */
    internal fun pack(exponent: Int, mantissa: Int): Int =
        ((exponent and 0xF) shl 12) or (mantissa and 0xFFF)
}
