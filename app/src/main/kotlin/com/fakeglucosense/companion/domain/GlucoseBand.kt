package com.fakeglucosense.companion.domain

import com.fakeglucosense.core.model.AlertThresholds

/** Which colour band a reading sits in. Edges match [com.fakeglucosense.core.analysis.AlertEvaluator]. */
enum class GlucoseBand { URGENT_LOW, LOW, IN_RANGE, HIGH, URGENT_HIGH }

/**
 * Classifies [mgdl] with the same comparison operators the alert evaluator uses. The screen colour
 * and the alert can therefore never disagree about whether a value is low.
 */
fun AlertThresholds.bandOf(mgdl: Int): GlucoseBand = when {
    mgdl <= urgentLowMgdl -> GlucoseBand.URGENT_LOW
    mgdl < lowMgdl -> GlucoseBand.LOW
    mgdl >= urgentHighMgdl -> GlucoseBand.URGENT_HIGH
    mgdl > highMgdl -> GlucoseBand.HIGH
    else -> GlucoseBand.IN_RANGE
}
