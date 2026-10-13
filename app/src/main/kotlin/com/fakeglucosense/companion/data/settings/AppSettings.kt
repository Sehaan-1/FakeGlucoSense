package com.fakeglucosense.companion.data.settings

import com.fakeglucosense.core.model.AlertThresholds

/** Where readings come from. The simulator path is the default so the app works without hardware. */
enum class SourceKind { TCP_SIMULATOR, BLE_CGM }

/** Display only. Storage and alerting always use mg/dL (ADR-0004). */
enum class DisplayUnit { MGDL, MMOL }

data class AppSettings(
    val thresholds: AlertThresholds = AlertThresholds(),
    val sourceKind: SourceKind = SourceKind.TCP_SIMULATOR,
    /** Emulator default reaches the host machine. On a phone, enter the simulator's LAN address. */
    val tcpHost: String = DEFAULT_TCP_HOST,
    val tcpPort: Int = DEFAULT_TCP_PORT,
    val bleAddress: String? = null,
    val bleName: String? = null,
    val displayUnit: DisplayUnit = DisplayUnit.MGDL,
    /** Persisted intent. The service is restarted at launch when this is true. */
    val monitoringEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_TCP_HOST = "10.0.2.2"
        const val DEFAULT_TCP_PORT = 8765
    }
}
