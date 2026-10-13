package com.fakeglucosense.companion.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fakeglucosense.companion.data.ble.ScannedDevice
import com.fakeglucosense.companion.data.settings.AppSettings
import com.fakeglucosense.companion.data.settings.DisplayUnit
import com.fakeglucosense.companion.data.settings.SourceKind
import com.fakeglucosense.companion.di.AppContainer
import com.fakeglucosense.core.model.AlertThresholds
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

data class ScanState(
    val scanning: Boolean = false,
    val devices: List<ScannedDevice> = emptyList(),
    val error: String? = null,
)

/**
 * Edits settings. Each change is written straight to DataStore, so a restart keeps it. Changes
 * that affect a running connection (source, host, device) are applied by the screen restarting the
 * service. Threshold changes take effect live without a restart.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppSettings())

    private val scanFlow = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = scanFlow.asStateFlow()
    private var scanJob: Job? = null

    fun setSource(kind: SourceKind) = save { it.copy(sourceKind = kind) }

    fun setConnection(host: String, port: Int) = save { it.copy(tcpHost = host.trim(), tcpPort = port) }

    fun setUnit(unit: DisplayUnit) = save { it.copy(displayUnit = unit) }

    fun setThresholds(thresholds: AlertThresholds) = save { it.copy(thresholds = thresholds) }

    fun selectDevice(device: ScannedDevice) {
        stopScan()
        save {
            it.copy(
                sourceKind = SourceKind.BLE_CGM,
                bleAddress = device.address,
                bleName = device.name ?: device.address,
            )
        }
    }

    /** Requires the BLE permissions to be granted. The screen checks before calling. */
    fun startScan() {
        scanJob?.cancel()
        scanFlow.value = ScanState(scanning = true)
        scanJob = viewModelScope.launch {
            try {
                container.bleScanner.scan().collect { device ->
                    scanFlow.value = scanFlow.value.let { current ->
                        current.copy(devices = (current.devices.filterNot { it.address == device.address } + device)
                            .sortedByDescending { it.rssi })
                    }
                }
                scanFlow.value = scanFlow.value.copy(scanning = false)
            } catch (e: IOException) {
                scanFlow.value = ScanState(scanning = false, error = e.message ?: "Scan failed")
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanFlow.value = scanFlow.value.copy(scanning = false)
    }

    private fun save(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settingsStore.update(transform) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
