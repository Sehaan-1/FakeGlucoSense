package com.fakeglucosense.companion.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fakeglucosense.companion.data.settings.AppSettings
import com.fakeglucosense.companion.data.settings.DisplayUnit
import com.fakeglucosense.companion.data.settings.SourceKind
import com.fakeglucosense.companion.data.source.LinkState
import com.fakeglucosense.companion.di.AppContainer
import com.fakeglucosense.core.analysis.Downsampler
import com.fakeglucosense.core.analysis.GlucoseStats
import com.fakeglucosense.core.analysis.GlucoseSummary
import com.fakeglucosense.core.model.AlertThresholds
import com.fakeglucosense.core.model.GlucoseReading
import com.fakeglucosense.core.model.TimeRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val latest: GlucoseReading? = null,
    val range: TimeRange = TimeRange.H3,
    val points: List<GlucoseReading> = emptyList(),
    val summary: GlucoseSummary? = null,
    val thresholds: AlertThresholds = AlertThresholds(),
    val unit: DisplayUnit = DisplayUnit.MGDL,
    val link: LinkState? = null,
    val running: Boolean = false,
    val sourceLabel: String = "",
)

/**
 * Combines stored readings, settings, and service status into one state. The chart window is
 * re-queried each time a new latest reading arrives, so a 3-hour view slides forward instead of
 * keeping the start time from when it was first opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val rangeFlow = MutableStateFlow(TimeRange.H3)

    private val chartFlow: Flow<ChartData> = rangeFlow.flatMapLatest { range ->
        container.repository.observeLatest().flatMapLatest { latest ->
            container.repository.observeWindow(range).map { window ->
                ChartData(range = range, latest = latest, window = window)
            }
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        chartFlow,
        container.settingsStore.settings,
        container.live.link,
        container.live.running,
    ) { chart, settings, link, running ->
        chart.toUiState(settings, link, running)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DashboardUiState())

    fun selectRange(range: TimeRange) {
        rangeFlow.value = range
    }

    private data class ChartData(
        val range: TimeRange,
        val latest: GlucoseReading?,
        val window: List<GlucoseReading>,
    ) {
        fun toUiState(settings: AppSettings, link: LinkState?, running: Boolean) = DashboardUiState(
            latest = latest,
            range = range,
            points = Downsampler.minMax(window, MAX_CHART_POINTS),
            summary = GlucoseStats.summarize(
                window,
                targetLow = settings.thresholds.lowMgdl,
                targetHigh = settings.thresholds.highMgdl,
            ),
            thresholds = settings.thresholds,
            unit = settings.displayUnit,
            link = link,
            running = running,
            sourceLabel = settings.sourceKind.label(),
        )
    }

    private companion object {
        const val MAX_CHART_POINTS = 300
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

private fun SourceKind.label(): String = when (this) {
    SourceKind.TCP_SIMULATOR -> "Simulator"
    SourceKind.BLE_CGM -> "BLE sensor"
}
