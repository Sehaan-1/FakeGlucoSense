package com.fakeglucosense.companion.data.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.fakeglucosense.companion.data.source.SourceEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException

/**
 * Opens a GATT connection to a CGM and emits its link state and readings. The flow completes
 * when the link drops and fails with an IOException when setup fails. Reconnecting is the
 * caller's job (see [com.fakeglucosense.companion.data.source.reconnecting]).
 */
class BleCgmClient(private val context: Context) {

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun connect(address: String): Flow<SourceEvent> = callbackFlow {
        val bluetooth = adapter ?: throw IOException("This device has no Bluetooth")
        if (!bluetooth.isEnabled) throw IOException("Bluetooth is off")
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw IOException("Invalid sensor address")
        val device = bluetooth.getRemoteDevice(address)
        val session = BleCgmSession(device, context, this, this)
        session.start()
        awaitClose { session.finish() }
    }
}
