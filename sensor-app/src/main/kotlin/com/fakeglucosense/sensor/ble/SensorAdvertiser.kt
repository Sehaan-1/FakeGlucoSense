package com.fakeglucosense.sensor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import com.fakeglucosense.core.ble.CgmUuids

/**
 * Advertises the CGM service UUID so the companion's scan filter finds this device. Connectable
 * legacy advertising. The 31-byte payload limit rules out the device name in the primary packet,
 * so the name goes in the scan response (ADR-0013).
 */
@SuppressLint("MissingPermission")
class SensorAdvertiser(private val context: Context) {

    private var callback: AdvertiseCallback? = null

    /** Returns false when the adapter has no advertiser or the permission is missing. */
    fun start(onStatus: (String) -> Unit): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            onStatus("This phone cannot advertise over BLE")
            return false
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(CgmUuids.SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = onStatus("Advertising")
            override fun onStartFailure(errorCode: Int) = onStatus("Advertising failed (code $errorCode)")
        }
        callback = cb
        return try {
            advertiser.startAdvertising(settings, data, scanResponse, cb)
            true
        } catch (e: SecurityException) {
            onStatus("Bluetooth advertise permission missing")
            false
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
        } catch (_: SecurityException) {
            // Permission revoked while advertising. The radio stops with the process.
        }
    }
}
