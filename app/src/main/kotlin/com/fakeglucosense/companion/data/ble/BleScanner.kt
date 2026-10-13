package com.fakeglucosense.companion.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.fakeglucosense.core.ble.CgmUuids
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.IOException

data class ScannedDevice(val address: String, val name: String?, val rssi: Int)

/**
 * Finds CGM peripherals that advertise the CGM service UUID. Results are emitted once per
 * address, and the scan stops after [timeoutMillis]. The filter keeps the list to CGM-like
 * devices.
 */
class BleScanner(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun scan(timeoutMillis: Long = DEFAULT_SCAN_MILLIS): Flow<ScannedDevice> = callbackFlow {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: throw IOException("Bluetooth LE scanning is unavailable")
        val seen = HashSet<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (seen.add(result.device.address)) {
                    trySend(
                        ScannedDevice(
                            address = result.device.address,
                            // scanRecord avoids BLUETOOTH_CONNECT, which device.name would need on API 31+.
                            name = result.scanRecord?.deviceName,
                            rssi = result.rssi,
                        ),
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IOException("BLE scan failed (code $errorCode)"))
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(CgmUuids.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: SecurityException) {
            throw IOException("Bluetooth scan permission missing")
        }
        val timer = launch {
            delay(timeoutMillis)
            close()
        }
        awaitClose {
            timer.cancel()
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // Permission revoked while scanning. The scan is already dead.
            }
        }
    }

    companion object {
        const val DEFAULT_SCAN_MILLIS = 10_000L
    }
}
