package com.fakeglucosense.core.model

/** Kinds of alert the evaluator can raise. Urgent kinds override their non-urgent counterparts. */
enum class AlertType(val urgent: Boolean) {
    URGENT_LOW(urgent = true),
    LOW(urgent = false),
    HIGH(urgent = false),
    URGENT_HIGH(urgent = true),
    RAPID_DROP(urgent = false),
    RAPID_RISE(urgent = false),
}
