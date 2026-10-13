package com.fakeglucosense.companion.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fakeglucosense.companion.data.ble.BlePermissions
import com.fakeglucosense.companion.data.ble.ScannedDevice
import com.fakeglucosense.companion.data.settings.AppSettings
import com.fakeglucosense.companion.data.settings.DisplayUnit
import com.fakeglucosense.companion.data.settings.SourceKind
import com.fakeglucosense.companion.service.GlucoseMonitorService
import com.fakeglucosense.companion.ui.appContainer

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val container = appContainer()
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(container) } },
    )
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val running by container.live.running.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Source or device changes only take effect on a new connection, so restart if monitoring is on.
    fun applyAndRestartIfRunning() {
        if (running) GlucoseMonitorService.restart(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) viewModel.startScan()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle("Data source")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.sourceKind == SourceKind.TCP_SIMULATOR,
                onClick = {
                    viewModel.setSource(SourceKind.TCP_SIMULATOR)
                    applyAndRestartIfRunning()
                },
                label = { Text("Simulator (TCP)") },
            )
            FilterChip(
                selected = settings.sourceKind == SourceKind.BLE_CGM,
                onClick = {
                    viewModel.setSource(SourceKind.BLE_CGM)
                    applyAndRestartIfRunning()
                },
                label = { Text("BLE sensor") },
            )
        }

        if (settings.sourceKind == SourceKind.TCP_SIMULATOR) {
            SimulatorConnectionEditor(settings) { host, port ->
                viewModel.setConnection(host, port)
                applyAndRestartIfRunning()
            }
        } else {
            BleSensorSection(
                settings = settings,
                scan = scan,
                onScan = {
                    val missing = BlePermissions.missing(context)
                    if (missing.isEmpty()) viewModel.startScan()
                    else permissionLauncher.launch(missing.toTypedArray())
                },
                onStopScan = viewModel::stopScan,
                onPick = { device: ScannedDevice ->
                    viewModel.selectDevice(device)
                    applyAndRestartIfRunning()
                },
            )
        }

        HorizontalDivider()
        SectionTitle("Alert thresholds")
        Text(
            "Changes apply immediately, without restarting the connection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ThresholdEditor(saved = settings.thresholds, unit = settings.displayUnit) { viewModel.setThresholds(it) }

        HorizontalDivider()
        SectionTitle("Units")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.displayUnit == DisplayUnit.MGDL,
                onClick = { viewModel.setUnit(DisplayUnit.MGDL) },
                label = { Text("mg/dL") },
            )
            FilterChip(
                selected = settings.displayUnit == DisplayUnit.MMOL,
                onClick = { viewModel.setUnit(DisplayUnit.MMOL) },
                label = { Text("mmol/L") },
            )
        }
    }
}

@Composable
private fun SimulatorConnectionEditor(settings: AppSettings, onSave: (String, Int) -> Unit) {
    var host by remember(settings.tcpHost) { mutableStateOf(settings.tcpHost) }
    var port by remember(settings.tcpPort) { mutableStateOf(settings.tcpPort.toString()) }
    val parsedPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = host.isNotBlank() && parsedPort != null
    val changed = host.trim() != settings.tcpHost || parsedPort != settings.tcpPort

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Run the simulator on your computer and enter its address. Emulators use 10.0.2.2 for the host machine.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text("Port") },
            singleLine = true,
            isError = parsedPort == null,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { parsedPort?.let { onSave(host, it) } },
            enabled = valid && changed,
        ) { Text("Save connection") }
    }
}

@Composable
private fun BleSensorSection(
    settings: AppSettings,
    scan: ScanState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onPick: (ScannedDevice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Selected sensor: ${settings.bleName ?: "none"}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Scans for devices that advertise the CGM service (0x181F). Scanning needs Bluetooth and nearby-devices permission.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scan.scanning) {
                OutlinedButton(onClick = onStopScan) { Text("Stop scan") }
            } else {
                Button(onClick = onScan) { Text("Scan for sensors") }
            }
        }
        scan.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (scan.scanning && scan.devices.isEmpty()) {
            Text("Scanning…", style = MaterialTheme.typography.bodyMedium)
        }
        scan.devices.forEach { device ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onPick(device) }) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(device.name ?: "Unnamed sensor", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${device.address} · ${device.rssi} dBm",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}
