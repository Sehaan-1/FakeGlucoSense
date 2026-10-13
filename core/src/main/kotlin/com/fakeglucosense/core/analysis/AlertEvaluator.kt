package com.fakeglucosense.core.analysis

import com.fakeglucosense.core.model.AlertThresholds
import com.fakeglucosense.core.model.AlertType
import com.fakeglucosense.core.model.GlucoseReading
import com.fakeglucosense.core.model.Trend

/**
 * Pure alert state machine (ADR-0010).
 *
 * - **Hysteresis:** an alert clears only once the value crosses back past its threshold by
 *   [hysteresisMgdl], so values bouncing around a line don't flap.
 * - **Snooze:** an active alert is re-announced as [AlertAction.REMIND] only after [snoozeMillis].
 * - **Supersession:** an urgent alert suppresses its non-urgent counterpart (`URGENT_LOW` over `LOW`).
 */
class AlertEvaluator(
    private val thresholds: AlertThresholds,
    private val snoozeMillis: Long = DEFAULT_SNOOZE_MILLIS,
    private val hysteresisMgdl: Int = DEFAULT_HYSTERESIS_MGDL,
) {
    init {
        require(snoozeMillis >= 0) { "snooze must be non-negative" }
        require(hysteresisMgdl >= 0) { "hysteresis must be non-negative" }
    }

    /** Evaluates [reading] against [state]. Returns the new state and the decisions to act on. */
    fun evaluate(state: AlertState, reading: GlucoseReading): Pair<AlertState, List<AlertDecision>> {
        val now = reading.epochMillis
        val active = state.active.toMutableMap()
        val decisions = mutableListOf<AlertDecision>()

        for (type in EVALUATION_ORDER) {
            val previous = active[type]
            val clearing = previous != null && isCleared(type, reading)
            val triggered = previous == null && isTriggered(type, reading)
            val overridden = previous != null && isOverriddenByUrgent(type, active)

            when {
                overridden -> {
                    active.remove(type)
                    decisions += decision(type, AlertAction.CLEAR, reading)
                }
                clearing -> {
                    active.remove(type)
                    decisions += decision(type, AlertAction.CLEAR, reading)
                }
                triggered && !isOverriddenByUrgent(type, active) -> {
                    active[type] = ActiveAlert(type, sinceMillis = now, lastNotifiedMillis = now)
                    decisions += decision(type, AlertAction.RAISE, reading)
                }
                previous != null && now - previous.lastNotifiedMillis >= snoozeMillis -> {
                    active[type] = previous.copy(lastNotifiedMillis = now)
                    decisions += decision(type, AlertAction.REMIND, reading)
                }
            }
        }
        return AlertState(active.toMap()) to decisions
    }

    /** Condition that starts the alert, measured against the plain thresholds. */
    internal fun isTriggered(type: AlertType, reading: GlucoseReading): Boolean {
        val v = reading.mgdl
        return when (type) {
            AlertType.URGENT_LOW -> v <= thresholds.urgentLowMgdl
            AlertType.LOW -> v < thresholds.lowMgdl
            AlertType.HIGH -> v > thresholds.highMgdl
            AlertType.URGENT_HIGH -> v >= thresholds.urgentHighMgdl
            AlertType.RAPID_DROP -> reading.trend == Trend.DOUBLE_DOWN
            AlertType.RAPID_RISE -> reading.trend == Trend.DOUBLE_UP
        }
    }

    /** Condition that clears an active alert. Includes the hysteresis margin. */
    internal fun isCleared(type: AlertType, reading: GlucoseReading): Boolean {
        val v = reading.mgdl
        return when (type) {
            AlertType.URGENT_LOW -> v > thresholds.urgentLowMgdl + hysteresisMgdl
            AlertType.LOW -> v >= thresholds.lowMgdl + hysteresisMgdl
            AlertType.HIGH -> v <= thresholds.highMgdl - hysteresisMgdl
            AlertType.URGENT_HIGH -> v < thresholds.urgentHighMgdl - hysteresisMgdl
            AlertType.RAPID_DROP -> reading.trend != Trend.DOUBLE_DOWN
            AlertType.RAPID_RISE -> reading.trend != Trend.DOUBLE_UP
        }
    }

    /**
     * True when a non-urgent alert is overridden by its urgent counterpart being active. Used both
     * to block a new non-urgent raise and to clear one that is already showing.
     */
    private fun isOverriddenByUrgent(type: AlertType, active: Map<AlertType, ActiveAlert>): Boolean = when (type) {
        AlertType.LOW -> AlertType.URGENT_LOW in active
        AlertType.HIGH -> AlertType.URGENT_HIGH in active
        else -> false
    }

    private fun decision(type: AlertType, action: AlertAction, reading: GlucoseReading) =
        AlertDecision(type, action, reading.mgdl, reading.epochMillis)

    companion object {
        const val DEFAULT_SNOOZE_MILLIS = 30 * 60_000L
        const val DEFAULT_HYSTERESIS_MGDL = 5

        /** Urgent alerts are evaluated first so that suppression sees their new state. */
        private val EVALUATION_ORDER = listOf(
            AlertType.URGENT_LOW,
            AlertType.URGENT_HIGH,
            AlertType.LOW,
            AlertType.HIGH,
            AlertType.RAPID_DROP,
            AlertType.RAPID_RISE,
        )
    }
}
