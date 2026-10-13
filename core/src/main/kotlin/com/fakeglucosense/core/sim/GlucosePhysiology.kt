package com.fakeglucosense.core.sim

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.exp
import kotlin.random.Random

/**
 * Minute-by-minute glucose model (ADR-0004). Integrates homeostasis, meal absorption,
 * insulin action, exercise, dawn/night drift, and process noise, then passes the true value through
 * a first-order sensor lag.
 *
 * Call [advanceTo] with monotonically increasing times. The step grid is anchored to
 * [startMillis], so output doesn't depend on how the calls are chunked.
 */
internal class GlucosePhysiology(
    private val profile: SimulationProfile,
    private val random: Random,
    private val startMillis: Long,
    private val zone: ZoneId,
    private val planner: MealPlanner,
) {
    private var glucose = profile.startMgdl
    private var sensor = profile.startMgdl
    private var cursorMillis = startMillis
    private val carbPools = mutableListOf<Double>()
    private val insulinPools = mutableListOf<Double>()
    private var plannedDate: LocalDate? = null
    private var plannedMeals: List<PlannedMeal> = emptyList()

    /** Advances the model to [untilMillis], one minute at a time. */
    fun advanceTo(untilMillis: Long) {
        require(untilMillis >= cursorMillis) { "time cannot move backwards" }
        while (cursorMillis < untilMillis) {
            stepOneMinute(cursorMillis)
            cursorMillis += MILLIS_PER_MINUTE
        }
    }

    /** Sensor reading at the current model time: lagged glucose plus measurement noise (not clamped). */
    fun sensorValue(): Double = sensor + random.nextGaussian() * profile.sensorNoiseMgdl

    private fun stepOneMinute(minuteStart: Long) {
        startMealsIn(minuteStart)
        val local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(minuteStart), zone)
        val hour = local.hour + local.minute / 60.0
        val exercising = profile.exerciseWindows.any { it.contains(hour) }

        var delta = -profile.reversionPerMin * (if (exercising) 2.0 else 1.0) * (glucose - profile.baselineMgdl)
        if (hour < 5.0) delta += profile.nightDriftPerMin
        if (hour >= 4.0 && hour < 8.0) delta += profile.dawnRisePerMin
        if (exercising) delta -= profile.exerciseDropPerMin

        for (i in carbPools.indices) {
            val absorbed = carbPools[i] * (1 - exp(-profile.carbAbsorptionPerMin))
            carbPools[i] -= absorbed
            delta += absorbed * profile.mgdlPerGram
        }
        for (i in insulinPools.indices) {
            val acted = insulinPools[i] * (1 - exp(-profile.insulinActionPerMin))
            insulinPools[i] -= acted
            delta -= acted * profile.insulinSensitivityMgdlPerUnit
        }
        carbPools.removeAll { it < DEPLETED }
        insulinPools.removeAll { it < DEPLETED }

        delta += random.nextGaussian() * profile.processNoiseMgdl
        glucose = (glucose + delta).coerceIn(GLUCOSE_FLOOR, GLUCOSE_CEILING)

        val alpha = 1 - exp(-1.0 / profile.sensorLagMinutes)
        sensor += (glucose - sensor) * alpha
    }

    /** Starts any meal that falls in `[minuteStart, minuteStart + 1 min)`, planning the local day when needed. */
    private fun startMealsIn(minuteStart: Long) {
        if (profile.meals.isEmpty()) return
        val date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(minuteStart), zone).toLocalDate()
        if (date != plannedDate) {
            plannedDate = date
            plannedMeals = planner.mealsFor(date)
        }
        for (meal in plannedMeals) {
            if (meal.epochMillis in minuteStart until minuteStart + MILLIS_PER_MINUTE) {
                if (meal.carbsGrams > 0) carbPools += meal.carbsGrams
                if (meal.bolusUnits > 0) insulinPools += meal.bolusUnits
            }
        }
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val DEPLETED = 0.01
        const val GLUCOSE_FLOOR = 40.0
        const val GLUCOSE_CEILING = 400.0
    }
}
