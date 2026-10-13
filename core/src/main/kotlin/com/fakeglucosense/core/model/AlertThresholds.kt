package com.fakeglucosense.core.model

/**
 * User-configurable glucose thresholds in mg/dL. Ordering is enforced:
 * urgentLow < low < high < urgentHigh. Each bound is also clamped to a safe editing range.
 */
data class AlertThresholds(
    val urgentLowMgdl: Int = DEFAULT_URGENT_LOW,
    val lowMgdl: Int = DEFAULT_LOW,
    val highMgdl: Int = DEFAULT_HIGH,
    val urgentHighMgdl: Int = DEFAULT_URGENT_HIGH,
) {
    init {
        val errors = validationErrors(urgentLowMgdl, lowMgdl, highMgdl, urgentHighMgdl)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }

    companion object {
        const val DEFAULT_URGENT_LOW = 55
        const val DEFAULT_LOW = 70
        const val DEFAULT_HIGH = 180
        const val DEFAULT_URGENT_HIGH = 250

        val URGENT_LOW_RANGE = 40..80
        val LOW_RANGE = 60..100
        val HIGH_RANGE = 120..250
        val URGENT_HIGH_RANGE = 180..400

        /** Returns human-readable problems, or an empty list when the combination is valid. */
        fun validationErrors(urgentLow: Int, low: Int, high: Int, urgentHigh: Int): List<String> {
            val problems = mutableListOf<String>()
            if (urgentLow !in URGENT_LOW_RANGE) problems += "urgent low $urgentLow outside $URGENT_LOW_RANGE"
            if (low !in LOW_RANGE) problems += "low $low outside $LOW_RANGE"
            if (high !in HIGH_RANGE) problems += "high $high outside $HIGH_RANGE"
            if (urgentHigh !in URGENT_HIGH_RANGE) problems += "urgent high $urgentHigh outside $URGENT_HIGH_RANGE"
            if (urgentLow >= low) problems += "urgent low must be below low"
            if (low >= high) problems += "low must be below high"
            if (high >= urgentHigh) problems += "high must be below urgent high"
            return problems
        }
    }
}
