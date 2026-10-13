package com.fakeglucosense.companion.domain

import com.fakeglucosense.core.analysis.AlertDecision
import com.fakeglucosense.core.analysis.AlertEvaluator
import com.fakeglucosense.core.analysis.AlertState
import com.fakeglucosense.core.model.AlertThresholds
import com.fakeglucosense.core.model.AlertType
import com.fakeglucosense.core.model.GlucoseReading

/**
 * Runs the core [AlertEvaluator] over new readings and hands each decision to [sink].
 *
 * Holds state, so it must be used from one coroutine. The monitoring service does this on its
 * lifecycle scope. Kept free of Android types so it can be tested on the JVM.
 */
class AlertEngine(
    initialThresholds: AlertThresholds,
    private val sink: (AlertDecision) -> Unit,
) {
    private var thresholds = initialThresholds
    private var evaluator = AlertEvaluator(initialThresholds)
    private var state = AlertState()

    /** Changing thresholds keeps active alerts. They clear on the next reading that is back in range. */
    fun updateThresholds(next: AlertThresholds) {
        if (next == thresholds) return
        thresholds = next
        evaluator = AlertEvaluator(next)
    }

    fun onReading(reading: GlucoseReading): List<AlertDecision> {
        val (nextState, decisions) = evaluator.evaluate(state, reading)
        state = nextState
        decisions.forEach(sink)
        return decisions
    }

    /** Alert types currently active. Used by tests and the status UI. */
    fun activeTypes(): Set<AlertType> = state.active.keys.toSet()
}
