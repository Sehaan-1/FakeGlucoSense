package com.fakeglucosense.companion.ui.format

import com.fakeglucosense.companion.data.settings.DisplayUnit
import com.fakeglucosense.companion.domain.GlucoseUnits
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Text for glucose values and times. Pure functions, so they can be tested without Android. */
object GlucoseFormat {

    fun unitLabel(unit: DisplayUnit): String = when (unit) {
        DisplayUnit.MGDL -> "mg/dL"
        DisplayUnit.MMOL -> "mmol/L"
    }

    /** A value in the chosen unit. mmol/L uses one decimal place. */
    fun value(mgdl: Int, unit: DisplayUnit): String = when (unit) {
        DisplayUnit.MGDL -> mgdl.toString()
        DisplayUnit.MMOL -> String.format(Locale.getDefault(), "%.1f", GlucoseUnits.toMmol(mgdl))
    }

    /** A threshold or axis number in the chosen unit, without a unit suffix. */
    fun number(mgdl: Double, unit: DisplayUnit): String = when (unit) {
        DisplayUnit.MGDL -> mgdl.toInt().toString()
        DisplayUnit.MMOL -> String.format(Locale.getDefault(), "%.1f", mgdl / GlucoseUnits.MGDL_PER_MMOL)
    }

    /** "just now", "7 min ago", or "1 h 5 min ago". */
    fun ago(readingMillis: Long, nowMillis: Long): String {
        val minutes = ((nowMillis - readingMillis).coerceAtLeast(0)) / 60_000L
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            else -> "${minutes / 60} h ${minutes % 60} min ago"
        }
    }

    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun clock(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clockFormat.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
}
