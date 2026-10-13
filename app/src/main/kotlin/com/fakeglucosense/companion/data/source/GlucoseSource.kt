package com.fakeglucosense.companion.data.source

import com.fakeglucosense.core.model.GlucoseReading
import kotlinx.coroutines.flow.Flow

/** IDLE: monitoring is on but there is nothing to connect to yet (for example, no sensor chosen). */
enum class LinkPhase { IDLE, CONNECTING, CONNECTED, RETRYING }

/** Connection status for the UI. [retryInSeconds] is set only while [phase] is RETRYING. */
data class LinkState(
    val phase: LinkPhase,
    val detail: String = "",
    val retryInSeconds: Int? = null,
)

sealed interface SourceEvent {
    data class Link(val state: LinkState) : SourceEvent
    data class Reading(val reading: GlucoseReading) : SourceEvent
}

/**
 * A producer of readings. Implementations own their connection and reconnection; callers only
 * collect. Cancelling the collector closes the connection (ADR-0007, ADR-0011).
 */
interface GlucoseSource {
    /** Stored with each reading so the database shows where it came from. */
    val sourceName: String

    fun events(): Flow<SourceEvent>
}
