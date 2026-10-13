package com.fakeglucosense.core.ble

/**
 * CRC-16-CCITT as used by the CGM Service E2E-CRC fields: polynomial 0x1021, initial value
 * 0xFFFF, no input or output reflection, no final XOR. The check value for the ASCII string
 * "123456789" is 0x29B1 (see `CgmCrc16Test`).
 *
 * The ESP32 firmware carries a line-for-line C++ port in `esp32/src/cgm_codec.cpp`.
 */
object CgmCrc16 {
    private const val POLYNOMIAL = 0x1021

    /** CRC-16-CCITT of `bytes[start, end)`. */
    fun compute(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Int {
        var crc = 0xFFFF
        for (i in start until end) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor POLYNOMIAL else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}
