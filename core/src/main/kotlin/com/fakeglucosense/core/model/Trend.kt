package com.fakeglucosense.core.model

/**
 * Glucose trend direction. Names match Nightscout's `direction` vocabulary so exports stay
 * interoperable. Bands follow the Dexcom-style rate table in ADR-0005.
 */
enum class Trend(
    val symbol: String,
    val label: String,
    val nightscoutName: String,
) {
    DOUBLE_UP("↑↑", "Rising fast", "DoubleUp"),
    SINGLE_UP("↑", "Rising", "SingleUp"),
    FORTY_FIVE_UP("↗", "Rising slowly", "FortyFiveUp"),
    FLAT("→", "Steady", "Flat"),
    FORTY_FIVE_DOWN("↘", "Falling slowly", "FortyFiveDown"),
    SINGLE_DOWN("↓", "Falling", "SingleDown"),
    DOUBLE_DOWN("↓↓", "Falling fast", "DoubleDown"),
    NOT_COMPUTABLE("?", "Not enough data", "NOT COMPUTABLE"),
    NONE("–", "No trend", "NONE");

    companion object {
        /**
         * Classifies a rate of change (mg/dL per minute).
         *
         * Bands: > 3 DoubleUp · (2, 3] SingleUp · [1, 2] FortyFiveUp · (-1, 1) Flat ·
         * [-2, -1] FortyFiveDown · [-3, -2) SingleDown · < -3 DoubleDown.
         */
        fun fromRate(mgdlPerMin: Double): Trend {
            require(mgdlPerMin.isFinite()) { "rate must be finite" }
            return when {
                mgdlPerMin > 3.0 -> DOUBLE_UP
                mgdlPerMin > 2.0 -> SINGLE_UP
                mgdlPerMin >= 1.0 -> FORTY_FIVE_UP
                mgdlPerMin > -1.0 -> FLAT
                mgdlPerMin >= -2.0 -> FORTY_FIVE_DOWN
                mgdlPerMin >= -3.0 -> SINGLE_DOWN
                else -> DOUBLE_DOWN
            }
        }

        fun fromNightscoutName(name: String): Trend? = entries.firstOrNull { it.nightscoutName == name }
    }
}
