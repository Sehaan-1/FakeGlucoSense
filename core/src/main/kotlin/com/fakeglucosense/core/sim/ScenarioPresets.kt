package com.fakeglucosense.core.sim

/**
 * Named demo scenarios (ADR-0004). Each one is built so a demo shows a particular behavior:
 * alerts, spikes, or a trend arrow.
 */
enum class ScenarioPreset(val description: String, val profile: SimulationProfile) {
    STEADY(
        description = "Quiet day, no meals. Mostly flat trend.",
        profile = SimulationProfile(baselineMgdl = 110.0, sensorNoiseMgdl = 1.5, processNoiseMgdl = 0.15),
    ),
    MEAL_DAY(
        description = "Breakfast, lunch, snack, and dinner with boluses. Typical spikes and recoveries.",
        profile = SimulationProfile(
            baselineMgdl = 105.0,
            meals = listOf(
                MealTemplate(hourOfDay = 7.5, minCarbs = 30, maxCarbs = 45),
                MealTemplate(hourOfDay = 12.5, minCarbs = 50, maxCarbs = 70),
                MealTemplate(hourOfDay = 15.5, minCarbs = 15, maxCarbs = 25, bolusProbability = 0.3),
                MealTemplate(hourOfDay = 18.5, minCarbs = 55, maxCarbs = 75),
            ),
        ),
    ),
    NOCTURNAL_LOW(
        description = "Overnight drift toward low after an over-bolused dinner. Triggers LOW and URGENT_LOW.",
        profile = SimulationProfile(
            baselineMgdl = 100.0,
            meals = listOf(
                MealTemplate(
                    hourOfDay = 19.0,
                    minCarbs = 40,
                    maxCarbs = 60,
                    bolusProbability = 1.0,
                    bolusFactorRange = 1.3..1.5,
                ),
            ),
            nightDriftPerMin = -0.4,
            dawnRisePerMin = 0.25,
        ),
    ),
    HIGH_SPIKE(
        description = "Large meals without insulin. Triggers HIGH, URGENT_HIGH, and RAPID_RISE.",
        profile = SimulationProfile(
            baselineMgdl = 120.0,
            meals = listOf(
                MealTemplate(hourOfDay = 8.0, minCarbs = 60, maxCarbs = 80, bolusProbability = 0.0),
                MealTemplate(hourOfDay = 12.0, minCarbs = 80, maxCarbs = 100, bolusProbability = 0.0),
            ),
        ),
    ),
    ACTIVE_DAY(
        description = "Exercise sessions lower glucose during the day. Triggers RAPID_DROP after a hard session.",
        profile = SimulationProfile(
            baselineMgdl = 105.0,
            meals = listOf(
                MealTemplate(hourOfDay = 7.5, minCarbs = 35, maxCarbs = 50),
                MealTemplate(hourOfDay = 19.0, minCarbs = 60, maxCarbs = 80),
            ),
            exerciseWindows = listOf(HourWindow(7.0, 8.0), HourWindow(17.0, 18.5)),
            exerciseDropPerMin = 0.5,
        ),
    ),
}
