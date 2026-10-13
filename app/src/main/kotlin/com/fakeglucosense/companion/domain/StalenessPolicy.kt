package com.fakeglucosense.companion.domain

/**
 * Decides when "no data" should be raised. Readings every 5 minutes, so three missed readings
 * (15 minutes) is a gap worth telling the user about.
 */
object StalenessPolicy {
    const val STALE_AFTER_MILLIS = 15 * 60_000L

    /** Stale only when there is a reading to compare against. A fresh install is not stale. */
    fun isStale(latestMillis: Long?, nowMillis: Long): Boolean =
        latestMillis != null && nowMillis - latestMillis >= STALE_AFTER_MILLIS

    fun minutesSince(latestMillis: Long, nowMillis: Long): Long =
        ((nowMillis - latestMillis).coerceAtLeast(0)) / 60_000L
}
