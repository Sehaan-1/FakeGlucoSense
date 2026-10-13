package com.fakeglucosense.core.model

/** Chart windows offered in the dashboard (R6: 3 / 6 / 12 / 24 hours). */
enum class TimeRange(val hours: Int) {
    H3(3),
    H6(6),
    H12(12),
    H24(24);

    val millis: Long get() = hours * MILLIS_PER_HOUR

    companion object {
        const val MILLIS_PER_HOUR = 3_600_000L
    }
}
