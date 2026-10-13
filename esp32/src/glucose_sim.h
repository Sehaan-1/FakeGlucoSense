#ifndef GLUCOSE_SIM_H
#define GLUCOSE_SIM_H

#include <stdint.h>

#include "cgm_codec.h"

// A compact C port of the core/sim physiology (ADR-0004): mean reversion to a
// baseline, meal absorption and insulin action with per-person jitter, optional
// dawn/night drift and exercise, then a first-order sensor lag plus measurement
// noise. It is not a byte-for-byte port of the Kotlin model (it uses its own
// seeded RNG), but the scenarios and output shape match. Teaching approximations,
// not a validated physiological model.

namespace sim {

enum Scenario {
    STEADY = 0,
    MEAL_DAY = 1,
    NOCTURNAL_LOW = 2,
    HIGH_SPIKE = 3,
    ACTIVE_DAY = 4,
    SCENARIO_COUNT = 5,
};

const char* scenarioName(Scenario s);

class GlucoseSim {
public:
    // Starts a session: deterministic for a given (scenario, seed, sessionStart).
    void begin(Scenario scenario, uint64_t seed, uint64_t sessionStartSimMillis, int intervalMinutes);

    // Advances simulated time and emits every reading due at or before simNow.
    // Returns the number of records written into out (at most maxOut).
    size_t advanceTo(uint64_t simNowMillis, cgm::Record* out, size_t maxOut);

    Scenario scenario() const { return scenario_; }
    int lastMgdl() const { return lastMgdl_; }

private:
    struct Meal {
        uint32_t minuteOfDay;  // planned minute within the simulated day
        double carbsGrams;
        double bolusUnits;
        bool started;
    };

    uint64_t nextRandom();        // xorshift64*
    double nextUniform();         // in [0, 1)
    double nextGaussian();        // Box-Muller, standard normal

    void stepOneMinute(uint64_t minuteMillis);
    void planDay(uint32_t dayIndex);
    double trendPerMinute();      // least-squares slope over the last 15 simulated minutes

    Scenario scenario_ = MEAL_DAY;
    uint64_t rngState_ = 88172645463325252ULL;

    // Physiology parameters (see SimulationProfile.kt for the Kotlin originals).
    double baseline_ = 105.0;
    double reversionPerMin_ = 0.01;
    double mgdlPerGram_ = 4.0;
    double carbAbsorptionPerMin_ = 1.0 / 35.0;
    double insulinSensitivity_ = 50.0;
    double insulinActionPerMin_ = 1.0 / 70.0;
    double carbRatio_ = 12.0;
    double nightDriftPerMin_ = 0.0;
    double dawnRisePerMin_ = 0.0;
    double exerciseDropPerMin_ = 0.0;
    double sensorNoise_ = 2.0;
    double processNoise_ = 0.2;
    double sensorLagMinutes_ = 10.0;

    // Meals: at most 4 templates, each plan-able once per simulated day.
    uint8_t mealCount_ = 0;
    double mealHourOfDay_[4]{};
    int mealMinCarbs_[4]{};
    int mealMaxCarbs_[4]{};
    double mealBolusProbability_[4]{};
    double mealBolusFactorMin_[4]{};
    double mealBolusFactorMax_[4]{};
    bool exerciseMorning_ = false;
    bool exerciseEvening_ = false;

    // Integrator state.
    double glucose_ = 105.0;
    double sensor_ = 105.0;
    uint64_t cursorMillis_ = 0;
    uint64_t sessionStartMillis_ = 0;
    int intervalMinutes_ = 5;
    uint64_t nextSequence_ = 0;

    double carbPools_[4]{};
    int carbPoolCount_ = 0;
    double insulinPools_[4]{};
    int insulinPoolCount_ = 0;

    Meal meals_[4]{};
    int32_t plannedDay_ = -1;

    // Trend window: the last three offsets and values (15 minutes at 5-minute cadence).
    double trendMinutes_[4]{};
    double trendValues_[4]{};
    uint8_t trendCount_ = 0;
    int lastMgdl_ = 105;
};

// The Warning annunciation octet for one reading, from the thresholds in
// AlertThresholds (companion defaults: 55/70/180/250 mg/dL).
uint8_t warningFor(int mgdl, double rateMgdlPerMin);

}  // namespace sim

#endif  // GLUCOSE_SIM_H
