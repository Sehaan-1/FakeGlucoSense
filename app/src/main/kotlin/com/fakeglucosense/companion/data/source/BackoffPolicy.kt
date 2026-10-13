package com.fakeglucosense.companion.data.source

/**
 * Exponential reconnect delay: initial, 2x, 4x, … capped at [maxMillis].
 * Pure function so it can be tested without a clock.
 */
class BackoffPolicy(
    private val initialMillis: Long = 1_000,
    private val maxMillis: Long = 30_000,
) {
    init {
        require(initialMillis > 0 && maxMillis >= initialMillis) { "invalid backoff bounds" }
    }

    /** [attempt] starts at 1. Values below 1 are treated as 1. */
    fun delayFor(attempt: Int): Long {
        val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(30)
        return minOf(maxMillis, initialMillis shl exponent)
    }
}
