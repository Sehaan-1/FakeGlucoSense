package com.fakeglucosense.companion.domain

/** Unit conversion for display only. Storage and alerting are always mg/dL (ADR-0004). */
object GlucoseUnits {
    /** Molar mass of glucose: 1 mmol/L = 18.0182 mg/dL. */
    const val MGDL_PER_MMOL = 18.0182

    fun toMmol(mgdl: Int): Double = mgdl / MGDL_PER_MMOL

    fun toMgdl(mmol: Double): Int = Math.round(mmol * MGDL_PER_MMOL).toInt()
}
