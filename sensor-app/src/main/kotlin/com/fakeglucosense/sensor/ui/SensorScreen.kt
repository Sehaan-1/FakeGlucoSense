package com.fakeglucosense.sensor.ui

import android.Manifest
import android.os.Build
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fakeglucosense.core.ble.CgmRecord
import com.fakeglucosense.core.sim.ScenarioPreset
import com.fakeglucosense.sensor.R
import com.fakeglucosense.sensor.SensorApp
import com.fakeglucosense.sensor.service.SensorService
import com.fakeglucosense.sensor.service.SensorUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val REAL_TIME = 1.0
private const val FAST_DEMO = 60.0

@Composable
fun SensorScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as SensorApp
    val state by app.status.state.collectAsStateWithLifecycle()
    var scenario by remember { mutableStateOf(ScenarioPreset.MEAL_DAY) }
    var speed by remember { mutableStateOf(REAL_TIME) }

    val permissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Start even if notifications were refused. The service still runs; only its notice is hidden.
        if (result[Manifest.permission.BLUETOOTH_ADVERTISE] != false) {
            SensorService.start(context, scenario, speed)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Simulated CGM sensor", style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.disclaimer_simulated),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Advertises the CGM service so the FakeGlucoSense companion can connect over real Bluetooth.",
            style = MaterialTheme.typography.bodyMedium,
        )

        StatusCard(state)

        Text("Scenario", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScenarioPreset.entries.take(3).forEach { preset ->
                FilterChip(
                    selected = scenario == preset,
                    onClick = { if (!state.running) scenario = preset },
                    label = { Text(preset.name.lowercase().replace('_', ' ')) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScenarioPreset.entries.drop(3).forEach { preset ->
                FilterChip(
                    selected = scenario == preset,
                    onClick = { if (!state.running) scenario = preset },
                    label = { Text(preset.name.lowercase().replace('_', ' ')) },
                )
            }
        }

        Text("Speed", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = speed == REAL_TIME,
                onClick = { if (!state.running) speed = REAL_TIME },
                label = { Text("Real time") },
            )
            FilterChip(
                selected = speed == FAST_DEMO,
                onClick = { if (!state.running) speed = FAST_DEMO },
                label = { Text("Fast demo (60×)") },
            )
        }
        Text(
            "Fast demo timestamps run ahead of the phone clock. Use real time for alert and staleness testing.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (state.running) {
            OutlinedButton(onClick = { SensorService.stop(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Stop sensor")
            }
        } else {
            Button(onClick = { launcher.launch(permissions) }, modifier = Modifier.fillMaxWidth()) {
                Text("Start sensor")
            }
        }

        Text("Recent readings", style = MaterialTheme.typography.titleSmall)
        state.recent.forEach { record -> RecordRow(record, state) }
    }
}

@Composable
private fun StatusCard(state: SensorUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status: ${if (state.running) state.advertising else "Stopped"}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Session: ${if (state.sessionActive) "running" else "stopped"} · interval ${state.intervalMinutes} min",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Companion connections: ${state.subscribers}", style = MaterialTheme.typography.bodyMedium)
            state.sessionStartMillis?.let { start ->
                Text("Session start: ${timeOf(start)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RecordRow(record: CgmRecord, state: SensorUiState) {
    val at = (state.sessionStartMillis ?: 0L) + record.timeOffsetMinutes * 60_000L
    Text(
        "${timeOf(at)}   ${record.mgdl.toInt()} mg/dL   offset ${record.timeOffsetMinutes} min",
        style = MaterialTheme.typography.bodyMedium,
    )
}

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private fun timeOf(epochMillis: Long): String = clock.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
