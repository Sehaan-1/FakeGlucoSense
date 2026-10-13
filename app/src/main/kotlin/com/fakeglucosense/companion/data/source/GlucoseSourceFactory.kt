package com.fakeglucosense.companion.data.source

import com.fakeglucosense.companion.data.ble.BleCgmClient
import com.fakeglucosense.companion.data.settings.AppSettings
import com.fakeglucosense.companion.data.settings.SourceKind

/** Builds the source the user selected. Returns null when BLE is selected but no sensor is chosen. */
class GlucoseSourceFactory(private val bleClient: BleCgmClient) {

    fun create(settings: AppSettings): GlucoseSource? = when (settings.sourceKind) {
        SourceKind.TCP_SIMULATOR -> TcpSimulatorSource(settings.tcpHost, settings.tcpPort)
        SourceKind.BLE_CGM -> settings.bleAddress?.let { BleCgmSource(bleClient, it) }
    }
}

/** BLE source: wraps the GATT client in the shared reconnect loop. */
class BleCgmSource(
    private val client: BleCgmClient,
    private val address: String,
    private val backoff: BackoffPolicy = BackoffPolicy(),
) : GlucoseSource {

    override val sourceName: String = "ble"

    override fun events() = reconnecting("BLE $address", backoff) { markConnected ->
        client.connect(address).collect { event ->
            if (event is SourceEvent.Link && event.state.phase == LinkPhase.CONNECTED) markConnected()
            send(event)
        }
    }
}
