package com.fakeglucosense.companion.ui.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fakeglucosense.companion.data.ble.BlePermissions
import com.fakeglucosense.companion.data.source.LinkPhase
import com.fakeglucosense.companion.data.source.LinkState
import com.fakeglucosense.companion.service.GlucoseMonitorService
import com.fakeglucosense.companion.ui.appContainer
import com.fakeglucosense.companion.ui.chart.GlucoseChart
import com.fakeglucosense.core.model.TimeRange
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    val container = appContainer()
    val viewModel: DashboardViewModel = viewModel(
        factory = viewModelFactory { initializer { DashboardViewModel(container) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Start only when granted. Without the permission the service would refuse to go foreground.
        if (result.values.all { it }) GlucoseMonitorService.start(context)
    }
    fun startWithPermission() {
        val missing = BlePermissions.missingForMonitoring(context)
        if (missing.isEmpty()) GlucoseMonitorService.start(context) else permissionLauncher.launch(missing.toTypedArray())
    }

    // Ages in the hero card and chart labels advance even when no reading arrives.
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(TICK_MILLIS)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LinkStatusLine(state.link, state.running, state.sourceLabel)

        GlucoseHeroCard(
            latest = state.latest,
            thresholds = state.thresholds,
            unit = state.unit,
            nowMillis = now,
        )

        if (state.running) {
            OutlinedButton(onClick = { GlucoseMonitorService.stop(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Stop monitoring")
            }
        } else {
            Button(onClick = { startWithPermission() }, modifier = Modifier.fillMaxWidth()) {
                Text("Start monitoring")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeRange.entries.forEach { range ->
                FilterChip(
                    selected = state.range == range,
                    onClick = { viewModel.selectRange(range) },
                    label = { Text("${range.hours} h") },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            GlucoseChart(
                points = state.points,
                thresholds = state.thresholds,
                range = state.range,
                nowMillis = now,
                unit = state.unit,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        GlucoseStatsCard(summary = state.summary, unit = state.unit)
    }
}

@Composable
private fun LinkStatusLine(link: LinkState?, running: Boolean, source: String) {
    val text = when {
        !running -> "Monitoring is off"
        link == null -> "Starting…"
        else -> when (link.phase) {
            LinkPhase.IDLE -> link.detail
            LinkPhase.CONNECTING -> "Connecting to $source…"
            LinkPhase.CONNECTED -> "Connected to $source"
            LinkPhase.RETRYING ->
                "Reconnecting in ${link.retryInSeconds ?: 0} s"
        }
    }
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val TICK_MILLIS = 15_000L
