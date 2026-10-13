package com.fakeglucosense.core.analysis

import com.fakeglucosense.core.model.AlertType

/** An alert that is currently active. [lastNotifiedMillis] drives snooze reminders. */
data class ActiveAlert(
    val type: AlertType,
    val sinceMillis: Long,
    val lastNotifiedMillis: Long,
)

/** The evaluator's memory between readings. Immutable; each evaluation returns a new state. */
data class AlertState(val active: Map<AlertType, ActiveAlert> = emptyMap()) {
    fun isActive(type: AlertType): Boolean = type in active
}

/** What the app should do about an alert after a reading. */
enum class AlertAction {
    /** The alert just started. Post a notification. */
    RAISE,

    /** Still active after the snooze period. Post a reminder. */
    REMIND,

    /** Condition cleared, or superseded by a more urgent alert. Dismiss its notification. */
    CLEAR,
}

/** One decision produced by [AlertEvaluator]. */
data class AlertDecision(
    val type: AlertType,
    val action: AlertAction,
    val mgdl: Int,
    val epochMillis: Long,
)
