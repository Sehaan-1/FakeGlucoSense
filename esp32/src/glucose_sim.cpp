#include "glucose_sim.h"

#include <math.h>
#include <string.h>

namespace sim {

constexpr uint64_t MILLIS_PER_MINUTE = 60000ULL;
constexpr uint64_t MILLIS_PER_DAY = 86400000ULL;
constexpr double GLUCOSE_FLOOR = 40.0;
constexpr double GLUCOSE_CEILING = 400.0;

const char* scenarioName(Scenario s) {
    switch (s) {
        case STEADY: return "STEADY";
        case MEAL_DAY: return "MEAL_DAY";
        case NOCTURNAL_LOW: return "NOCTURNAL_LOW";
        case HIGH_SPIKE: return "HIGH_SPIKE";
        case ACTIVE_DAY: return "ACTIVE_DAY";
        default: return "?";
    }
}

uint64_t GlucoseSim::nextRandom() {
    uint64_t x = rngState_;
    x ^= x >> 12;
    x ^= x << 25;
    x ^= x >> 27;
    rngState_ = x;
    return x * 2685821657736338717ULL;
}

double GlucoseSim::nextUniform() {
    return static_cast<double>(nextRandom() >> 11) * (1.0 / 9007199254740992.0);
}

double GlucoseSim::nextGaussian() {
    const double u1 = 1.0 - nextUniform();  // in (0, 1], avoids log(0)
    const double u2 = nextUniform();
    return sqrt(-2.0 * log(u1)) * cos(2.0 * M_PI * u2);
}

void GlucoseSim::begin(Scenario scenario, uint64_t seed, uint64_t sessionStartSimMillis, int intervalMinutes) {
    scenario_ = scenario;
    rngState_ = seed ? seed : 42;
    // Burn in: decorrelate from the raw seed value.
    for (int i = 0; i < 16; i++) nextRandom();

    baseline_ = 105.0;
    nightDriftPerMin_ = 0.0;
    dawnRisePerMin_ = 0.0;
    exerciseDropPerMin_ = 0.0;
    sensorNoise_ = 2.0;
    processNoise_ = 0.2;
    exerciseMorning_ = false;
    exerciseEvening_ = false;
    mealCount_ = 0;

    switch (scenario) {
        case STEADY:
            baseline_ = 110.0;
            sensorNoise_ = 1.5;
            processNoise_ = 0.15;
            break;
        case MEAL_DAY:
            baseline_ = 105.0;
            mealCount_ = 4;
            mealHourOfDay_[0] = 7.5;  mealMinCarbs_[0] = 30; mealMaxCarbs_[0] = 45;
            mealBolusProbability_[0] = 0.9; mealBolusFactorMin_[0] = 0.8; mealBolusFactorMax_[0] = 1.2;
            mealHourOfDay_[1] = 12.5; mealMinCarbs_[1] = 50; mealMaxCarbs_[1] = 70;
            mealBolusProbability_[1] = 0.9; mealBolusFactorMin_[1] = 0.8; mealBolusFactorMax_[1] = 1.2;
            mealHourOfDay_[2] = 15.5; mealMinCarbs_[2] = 15; mealMaxCarbs_[2] = 25;
            mealBolusProbability_[2] = 0.3; mealBolusFactorMin_[2] = 0.8; mealBolusFactorMax_[2] = 1.2;
            mealHourOfDay_[3] = 18.5; mealMinCarbs_[3] = 55; mealMaxCarbs_[3] = 75;
            mealBolusProbability_[3] = 0.9; mealBolusFactorMin_[3] = 0.8; mealBolusFactorMax_[3] = 1.2;
            break;
        case NOCTURNAL_LOW:
            baseline_ = 100.0;
            mealCount_ = 1;
            mealHourOfDay_[0] = 19.0; mealMinCarbs_[0] = 40; mealMaxCarbs_[0] = 60;
            mealBolusProbability_[0] = 1.0; mealBolusFactorMin_[0] = 1.3; mealBolusFactorMax_[0] = 1.5;
            nightDriftPerMin_ = -0.4;
            dawnRisePerMin_ = 0.25;
            break;
        case HIGH_SPIKE:
            baseline_ = 120.0;
            mealCount_ = 2;
            mealHourOfDay_[0] = 8.0;  mealMinCarbs_[0] = 60; mealMaxCarbs_[0] = 80;
            mealBolusProbability_[0] = 0.0; mealBolusFactorMin_[0] = 1.0; mealBolusFactorMax_[0] = 1.0;
            mealHourOfDay_[1] = 12.0; mealMinCarbs_[1] = 80; mealMaxCarbs_[1] = 100;
            mealBolusProbability_[1] = 0.0; mealBolusFactorMin_[1] = 1.0; mealBolusFactorMax_[1] = 1.0;
            break;
        case ACTIVE_DAY:
            baseline_ = 105.0;
            mealCount_ = 2;
            mealHourOfDay_[0] = 7.5;  mealMinCarbs_[0] = 35; mealMaxCarbs_[0] = 50;
            mealBolusProbability_[0] = 0.9; mealBolusFactorMin_[0] = 0.8; mealBolusFactorMax_[0] = 1.2;
            mealHourOfDay_[1] = 19.0; mealMinCarbs_[1] = 60; mealMaxCarbs_[1] = 80;
            mealBolusProbability_[1] = 0.9; mealBolusFactorMin_[1] = 0.8; mealBolusFactorMax_[1] = 1.2;
            exerciseMorning_ = true;
            exerciseEvening_ = true;
            exerciseDropPerMin_ = 0.5;
            break;
        case SCENARIO_COUNT:
            break;
    }

    glucose_ = baseline_;
    sensor_ = baseline_;
    cursorMillis_ = sessionStartSimMillis;
    sessionStartMillis_ = sessionStartSimMillis;
    intervalMinutes_ = intervalMinutes;
    nextSequence_ = 0;
    carbPoolCount_ = 0;
    insulinPoolCount_ = 0;
    plannedDay_ = -1;
    trendCount_ = 0;
    lastMgdl_ = static_cast<int>(baseline_);
}

void GlucoseSim::planDay(uint32_t dayIndex) {
    // Deterministic per (scenario, day), like MealPlanner.kt: jitter and sizes come from
    // a fresh RNG seeded for this day, so the plan does not depend on planning order.
    uint64_t saved = rngState_;
    rngState_ = 0x9E3779B97F4A7C15ULL ^ (static_cast<uint64_t>(scenario_) << 56) ^ dayIndex;
    for (int i = 0; i < 8; i++) nextRandom();
    for (int i = 0; i < mealCount_; i++) {
        int jitter = static_cast<int>((nextUniform() * 61.0)) - 30;  // ±30 minutes
        meals_[i].minuteOfDay = static_cast<uint32_t>(
            static_cast<int>(mealHourOfDay_[i] * 60.0) + jitter + 1440) % 1440;
        meals_[i].carbsGrams = mealMinCarbs_[i] +
            static_cast<int>(nextUniform() * (mealMaxCarbs_[i] - mealMinCarbs_[i] + 1));
        double factor = mealBolusFactorMin_[i] +
            nextUniform() * (mealBolusFactorMax_[i] - mealBolusFactorMin_[i]);
        meals_[i].bolusUnits = (nextUniform() < mealBolusProbability_[i])
            ? meals_[i].carbsGrams / carbRatio_ * factor
            : 0.0;
        meals_[i].started = false;
    }
    rngState_ = saved;
}

void GlucoseSim::stepOneMinute(uint64_t minuteMillis) {
    const uint64_t dayIndex64 = (minuteMillis - sessionStartMillis_) / MILLIS_PER_DAY;
    if (static_cast<int32_t>(dayIndex64) != plannedDay_) {
        plannedDay_ = static_cast<int32_t>(dayIndex64);
        planDay(static_cast<uint32_t>(dayIndex64));
    }
    const uint32_t minuteOfDay = static_cast<uint32_t>(((minuteMillis - sessionStartMillis_) / MILLIS_PER_MINUTE) % 1440ULL);
    for (int i = 0; i < mealCount_; i++) {
        if (!meals_[i].started && meals_[i].minuteOfDay == minuteOfDay) {
            meals_[i].started = true;
            if (meals_[i].carbsGrams > 0 && carbPoolCount_ < 4) carbPools_[carbPoolCount_++] = meals_[i].carbsGrams;
            if (meals_[i].bolusUnits > 0 && insulinPoolCount_ < 4) insulinPools_[insulinPoolCount_++] = meals_[i].bolusUnits;
        }
    }

    const double hour = minuteOfDay / 60.0;
    const bool exercising = (exerciseMorning_ && hour >= 7.0 && hour < 8.0) ||
        (exerciseEvening_ && hour >= 17.0 && hour < 18.5);

    double delta = -reversionPerMin_ * (exercising ? 2.0 : 1.0) * (glucose_ - baseline_);
    if (hour < 5.0) delta += nightDriftPerMin_;
    if (hour >= 4.0 && hour < 8.0) delta += dawnRisePerMin_;
    if (exercising) delta -= exerciseDropPerMin_;

    for (int i = 0; i < carbPoolCount_; i++) {
        double& pool = carbPools_[i];
        const double absorbed = pool * (1.0 - exp(-carbAbsorptionPerMin_));
        pool -= absorbed;
        delta += absorbed * mgdlPerGram_;
    }
    for (int i = 0; i < insulinPoolCount_; i++) {
        double& pool = insulinPools_[i];
        const double acted = pool * (1.0 - exp(-insulinActionPerMin_));
        pool -= acted;
        delta -= acted * insulinSensitivity_;
    }
    // Depleted pools: compact the arrays.
    int kept = 0;
    for (int i = 0; i < carbPoolCount_; i++) if (carbPools_[i] >= 0.01) carbPools_[kept++] = carbPools_[i];
    carbPoolCount_ = kept;
    kept = 0;
    for (int i = 0; i < insulinPoolCount_; i++) if (insulinPools_[i] >= 0.01) insulinPools_[kept++] = insulinPools_[i];
    insulinPoolCount_ = kept;

    delta += nextGaussian() * processNoise_;
    glucose_ += delta;
    if (glucose_ < GLUCOSE_FLOOR) glucose_ = GLUCOSE_FLOOR;
    if (glucose_ > GLUCOSE_CEILING) glucose_ = GLUCOSE_CEILING;

    const double alpha = 1.0 - exp(-1.0 / sensorLagMinutes_);
    sensor_ += (glucose_ - sensor_) * alpha;
}

double GlucoseSim::trendPerMinute() {
    // Least-squares slope over the kept window, matching TrendCalculator.kt.
    if (trendCount_ < 2) return 0.0;
    const uint8_t n = trendCount_;
    double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
    for (uint8_t i = 0; i < n; i++) {
        sumX += trendMinutes_[i];
        sumY += trendValues_[i];
        sumXY += trendMinutes_[i] * trendValues_[i];
        sumXX += trendMinutes_[i] * trendMinutes_[i];
    }
    const double denominator = n * sumXX - sumX * sumX;
    if (fabs(denominator) < 1e-9) return 0.0;
    return (n * sumXY - sumX * sumY) / denominator;
}

size_t GlucoseSim::advanceTo(uint64_t simNowMillis, cgm::Record* out, size_t maxOut) {
    size_t count = 0;
    while (count < maxOut) {
        const uint64_t due = sessionStartMillis_ + nextSequence_ * intervalMinutes_ * MILLIS_PER_MINUTE;
        if (due > simNowMillis) break;
        while (cursorMillis_ < due) {
            stepOneMinute(cursorMillis_);
            cursorMillis_ += MILLIS_PER_MINUTE;
        }
        double reading = sensor_ + nextGaussian() * sensorNoise_;
        int mgdl = static_cast<int>(lround(reading));
        if (mgdl < 40) mgdl = 40;
        if (mgdl > 400) mgdl = 400;
        lastMgdl_ = mgdl;

        cgm::Record rec;
        rec.timeOffsetMinutes = static_cast<uint16_t>(nextSequence_ * intervalMinutes_);
        rec.mgdl = mgdl;
        rec.status = 0;
        rec.calTemp = 0;

        // Keep the last 15 minutes for the trend slope (the anchor is "now" = 0).
        for (uint8_t i = 0; i < trendCount_; i++) trendMinutes_[i] -= intervalMinutes_;
        if (trendCount_ == 4) memmove(trendMinutes_, trendMinutes_ + 1, 3 * sizeof(double)),
            memmove(trendValues_, trendValues_ + 1, 3 * sizeof(double)), trendCount_ = 3;
        trendMinutes_[trendCount_] = 0.0;
        trendValues_[trendCount_] = mgdl;
        if (trendCount_ < 4) trendCount_++;
        // Drop points older than the 15-minute window.
        while (trendCount_ > 1 && trendMinutes_[0] < -15.0) {
            memmove(trendMinutes_, trendMinutes_ + 1, (trendCount_ - 1) * sizeof(double));
            memmove(trendValues_, trendValues_ + 1, (trendCount_ - 1) * sizeof(double));
            trendCount_--;
        }

        const bool enoughHistory = nextSequence_ > 0;
        rec.hasTrend = enoughHistory;
        rec.trendMgdlPerMin = enoughHistory ? trendPerMinute() : 0.0;
        rec.warning = warningFor(mgdl, rec.trendMgdlPerMin);
        out[count++] = rec;
        nextSequence_++;
        if (nextSequence_ * static_cast<uint64_t>(intervalMinutes_) > 0xFFFFULL) break;  // UINT16 limit
    }
    return count;
}

uint8_t warningFor(int mgdl, double rateMgdlPerMin) {
    uint8_t bits = 0;
    if (mgdl < 70) bits |= cgm::ssa::WARNING_PATIENT_LOW;
    if (mgdl > 180) bits |= cgm::ssa::WARNING_PATIENT_HIGH;
    if (mgdl <= 55) bits |= cgm::ssa::WARNING_HYPO;
    if (mgdl >= 250) bits |= cgm::ssa::WARNING_HYPER;
    if (rateMgdlPerMin <= -3.0) bits |= cgm::ssa::WARNING_RATE_OF_DECREASE;
    if (rateMgdlPerMin > 3.0) bits |= cgm::ssa::WARNING_RATE_OF_INCREASE;
    return bits;
}

}  // namespace sim
