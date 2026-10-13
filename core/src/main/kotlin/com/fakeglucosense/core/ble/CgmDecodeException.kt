package com.fakeglucosense.core.ble

/** Thrown when a CGM Measurement payload is structurally invalid (bad size, truncated, etc.). */
class CgmDecodeException(message: String) : IllegalArgumentException(message)
