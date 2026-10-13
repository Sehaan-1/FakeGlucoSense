package com.fakeglucosense.core.sim

import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** A meal scheduled at an absolute time, with its bolus decision already made. */
internal data class PlannedMeal(
    val epochMillis: Long,
    val carbsGrams: Double,
    val bolusUnits: Double,
)

/**
 * Plans meals per local calendar day. Each day uses its own RNG seeded from (seed, date), so a
 * day's plan doesn't depend on which days were planned first. That's what makes the simulator
 * reproducible from any starting point.
 */
internal class MealPlanner(
    private val profile: SimulationProfile,
    private val seed: Long,
    private val zone: ZoneId,
) {
    fun mealsFor(date: LocalDate): List<PlannedMeal> {
        val random = Random(seed xor (date.toEpochDay() * DAY_SALT))
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return profile.meals.map { template ->
            val jitter = if (template.jitterMinutes > 0) random.nextInt(-template.jitterMinutes, template.jitterMinutes + 1) else 0
            val minutesIntoDay = template.hourOfDay * 60.0 + jitter
            val carbs = random.nextInt(template.minCarbs, template.maxCarbs + 1).toDouble()
            val wantsBolus = random.nextDouble() < template.bolusProbability
            val factor = template.bolusFactorRange.start +
                random.nextDouble() * (template.bolusFactorRange.endInclusive - template.bolusFactorRange.start)
            val bolus = if (wantsBolus) carbs / profile.carbRatioGramsPerUnit * factor else 0.0
            PlannedMeal(
                epochMillis = dayStart + (minutesIntoDay * MILLIS_PER_MINUTE).toLong(),
                carbsGrams = carbs,
                bolusUnits = bolus,
            )
        }.sortedBy { it.epochMillis }
    }

    private companion object {
        const val DAY_SALT = -7046029254386353131L // 0x9E3779B97F4A7C15 as a signed Long
        const val MILLIS_PER_MINUTE = 60_000.0
    }
}
