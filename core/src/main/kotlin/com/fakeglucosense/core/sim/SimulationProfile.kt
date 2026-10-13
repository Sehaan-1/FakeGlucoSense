package com.fakeglucosense.core.sim

/**
 * Parameters for [GlucosePhysiology]. Every value has a physical meaning; see ADR-0004.
 * These are teaching approximations, not a validated physiological model.
 *
 * @param baselineMgdl resting glucose the body returns to.
 * @param reversionPerMin fraction of the gap to baseline closed each minute (homeostasis).
 * @param mgdlPerGram glucose rise per gram of carbohydrate absorbed.
 * @param carbAbsorptionPerMin fraction of the remaining carbohydrate absorbed each minute.
 * @param insulinSensitivityMgdlPerUnit glucose drop per unit of insulin, fully acted.
 * @param insulinActionPerMin fraction of the remaining insulin acting each minute.
 * @param carbRatioGramsPerUnit carbohydrate covered by one unit of bolus insulin.
 * @param meals scheduled meals; each is planned per local day with jitter.
 * @param exerciseWindows local hour ranges with increased uptake and glucose drop.
 * @param exerciseDropPerMin glucose drop per minute while exercising.
 * @param dawnRisePerMin extra glucose rise per minute between 04:00 and 08:00 (dawn phenomenon).
 * @param nightDriftPerMin extra glucose change per minute between 00:00 and 05:00 (negative = drift down).
 * @param sensorLagMinutes first-order lag between blood glucose and the interstitial sensor reading.
 * @param sensorNoiseMgdl standard deviation of sensor measurement noise, in mg/dL.
 * @param processNoiseMgdl standard deviation of per-minute physiological variation.
 */
data class SimulationProfile(
    val baselineMgdl: Double = 110.0,
    val startMgdl: Double = baselineMgdl,
    val reversionPerMin: Double = 0.01,
    val mgdlPerGram: Double = 4.0,
    val carbAbsorptionPerMin: Double = 1.0 / 35.0,
    val insulinSensitivityMgdlPerUnit: Double = 50.0,
    val insulinActionPerMin: Double = 1.0 / 70.0,
    val carbRatioGramsPerUnit: Double = 12.0,
    val meals: List<MealTemplate> = emptyList(),
    val exerciseWindows: List<HourWindow> = emptyList(),
    val exerciseDropPerMin: Double = 0.4,
    val dawnRisePerMin: Double = 0.0,
    val nightDriftPerMin: Double = 0.0,
    val sensorLagMinutes: Double = 10.0,
    val sensorNoiseMgdl: Double = 2.0,
    val processNoiseMgdl: Double = 0.2,
) {
    init {
        require(baselineMgdl in 40.0..300.0) { "baseline out of range" }
        require(reversionPerMin in 0.0..1.0) { "reversion must be a fraction" }
        require(carbRatioGramsPerUnit > 0) { "carb ratio must be positive" }
        require(sensorLagMinutes > 0) { "sensor lag must be positive" }
    }
}

/**
 * A recurring meal. [hourOfDay] is local time (0.0–24.0). The planner adds ±[jitterMinutes] and draws
 * carbohydrate in [minCarbs]..[maxCarbs]. [bolusProbability] of meals get a bolus; the bolus size is
 * scaled by a random factor in [bolusFactorRange] to model imperfect dosing.
 */
data class MealTemplate(
    val hourOfDay: Double,
    val minCarbs: Int,
    val maxCarbs: Int,
    val jitterMinutes: Int = 30,
    val bolusProbability: Double = 0.9,
    val bolusFactorRange: ClosedFloatingPointRange<Double> = 0.8..1.2,
) {
    init {
        require(hourOfDay in 0.0..24.0) { "hour of day out of range" }
        require(minCarbs in 0..maxCarbs) { "carb range invalid" }
    }
}

/** A local-time window, e.g., `HourWindow(7.0, 8.0)`. Start is inclusive, end is exclusive. */
data class HourWindow(val startHour: Double, val endHour: Double) {
    init {
        require(startHour < endHour) { "window start must be before end" }
    }

    fun contains(hour: Double): Boolean = hour >= startHour && hour < endHour
}
