package com.fakeglucosense.sensor.service

import com.fakeglucosense.core.ble.CgmRecord
import com.fakeglucosense.core.sim.ScenarioPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SensorUiState(
    val running: Boolean = false,
    val scenario: ScenarioPreset = ScenarioPreset.MEAL_DAY,
    val speed: Double = 1.0,
    val advertising: String = "Stopped",
    val subscribers: Int = 0,
    /** False when a collector stopped the session over the ops control point. */
    val sessionActive: Boolean = true,
    /** Live-record cadence negotiated over the ops control point, in minutes. */
    val intervalMinutes: Int = 5,
    /** Newest first, capped. */
    val recent: List<CgmRecord> = emptyList(),
    val sessionStartMillis: Long? = null,
)

/** Shared between [SensorService] (writer) and the screen (reader). */
class SensorStatus {
    private val flow = MutableStateFlow(SensorUiState())
    val state: StateFlow<SensorUiState> = flow.asStateFlow()

    fun update(transform: (SensorUiState) -> SensorUiState) {
        // update() is atomic: the service loop and the GATT callbacks both write here.
        flow.update(transform)
    }

    companion object {
        const val RECENT_LIMIT = 12
    }
}
