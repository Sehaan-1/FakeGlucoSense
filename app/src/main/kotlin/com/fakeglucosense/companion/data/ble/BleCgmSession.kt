package com.fakeglucosense.companion.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.fakeglucosense.companion.data.source.LinkPhase
import com.fakeglucosense.companion.data.source.LinkState
import com.fakeglucosense.companion.data.source.SourceEvent
import com.fakeglucosense.core.ble.CgmDecodeException
import com.fakeglucosense.core.ble.CgmMeasurementCodec
import com.fakeglucosense.core.ble.CgmStatusCodec
import com.fakeglucosense.core.ble.CgmUuids
import com.fakeglucosense.core.ble.Racp
import com.fakeglucosense.core.ble.SessionStartCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/**
 * One GATT connection to one CGM. Callbacks arrive on the binder thread.
 *
 * Android allows one outstanding GATT operation at a time. Starting another before the callback
 * for the first one returns can drop the callback silently. So every operation goes through a
 * queue, and the next one starts only after the previous completes (ADR-0011).
 *
 * Sequence (ADR-0014): connect → discover services → request a larger MTU → read Session Start
 * Time → read CGM Status (when served: a stopped session is reported on the link) → enable
 * Measurement notifications → when the sensor serves RACP, enable its indications and ask for
 * every stored record → CONNECTED. Measurement notifications then flow as readings. A sensor
 * without RACP (older builds) is connected right after the subscription; its pushed backfill,
 * if any, is deduplicated by the repository.
 */
@SuppressLint("MissingPermission")
internal class BleCgmSession(
    private val device: BluetoothDevice,
    private val context: Context,
    private val out: SendChannel<SourceEvent>,
    private val scope: CoroutineScope,
) : BluetoothGattCallback() {

    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var finished = false
    private var opInFlight = false
    private val ops = ArrayDeque<() -> Boolean>()
    private var timeoutJob: Job? = null
    private var connectedSent = false

    @Volatile
    private var sessionStartMillis: Long? = null

    /** Opens the connection. A failure here finishes the session with an IOException. */
    fun start() {
        val g = try {
            device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            return finish(IOException("Bluetooth permission missing"))
        }
        if (g == null) return finish(IOException("Could not start the BLE connection"))
        synchronized(lock) { gatt = g }
        timeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            finish(IOException("Timed out waiting for the sensor"))
        }
    }

    /** Idempotent. Closes the GATT handle and the output channel, with an error if given. */
    fun finish(error: IOException? = null) {
        val g: BluetoothGatt?
        synchronized(lock) {
            if (finished) return
            finished = true
            g = gatt
            gatt = null
        }
        timeoutJob?.cancel()
        try {
            g?.disconnect()
            g?.close()
        } catch (_: SecurityException) {
            // Permission was revoked mid-session. Nothing more we can do with the handle.
        }
        if (error != null) out.close(error) else out.close()
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            return finish(IOException("GATT error $status"))
        }
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                out.trySend(SourceEvent.Link(LinkState(LinkPhase.CONNECTING, "discovering services")))
                if (!guarded { gatt.discoverServices() }) finish(IOException("Could not discover services"))
            }
            BluetoothProfile.STATE_DISCONNECTED -> finish(null)
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) return finish(IOException("Service discovery failed ($status)"))
        val service = gatt.getService(CgmUuids.SERVICE)
            ?: return finish(IOException("Device does not expose a CGM service"))
        val measurement = service.getCharacteristic(CgmUuids.MEASUREMENT)
            ?: return finish(IOException("CGM service has no Measurement characteristic"))
        val sessionStart = service.getCharacteristic(CgmUuids.SESSION_START_TIME)
            ?: return finish(IOException("CGM service has no Session Start Time characteristic"))
        val racp = service.getCharacteristic(CgmUuids.RECORD_ACCESS_CONTROL_POINT)
        val statusCharacteristic = service.getCharacteristic(CgmUuids.STATUS)

        enqueue { guarded { gatt.requestMtu(REQUESTED_MTU) } }
        enqueue { guarded { gatt.readCharacteristic(sessionStart) } }
        if (statusCharacteristic != null) enqueue { guarded { gatt.readCharacteristic(statusCharacteristic) } }
        enqueue { subscribe(gatt, measurement, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
        if (racp != null) {
            enqueue { subscribe(gatt, racp, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) }
            enqueue {
                guarded {
                    writeCharacteristic(
                        gatt,
                        racp,
                        Racp.requestStoredRecords(Racp.OPERATOR_ALL_RECORDS),
                    )
                }
            }
        }
    }

    // --- reads --------------------------------------------------------------------------------

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) = handleRead(characteristic.uuid, value, status)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) = handleRead(characteristic.uuid, characteristic.value ?: ByteArray(0), status)

    private fun handleRead(uuid: UUID, value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            // A missing CGM Status read is tolerated; a failed Session Start read is not.
            if (uuid == CgmUuids.STATUS) return opCompleted()
            return finish(IOException("Read of $uuid failed ($status)"))
        }
        when (uuid) {
            CgmUuids.SESSION_START_TIME -> {
                val start = try {
                    SessionStartCodec.decode(value)
                } catch (e: IllegalArgumentException) {
                    return finish(IOException("Malformed Session Start Time"))
                }
                sessionStartMillis = start
            }
            CgmUuids.STATUS -> {
                val snapshot = try {
                    CgmStatusCodec.decode(value)
                } catch (e: CgmDecodeException) {
                    null // A foreign status value that fails CRC is ignored, not fatal.
                }
                if (snapshot != null && snapshot.sessionStopped) {
                    out.trySend(SourceEvent.Link(LinkState(LinkPhase.CONNECTING, "sensor session stopped")))
                }
            }
        }
        opCompleted()
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        // A refused or ignored MTU request is fine: the link just stays at 20-byte payloads.
        opCompleted()
    }

    // --- subscriptions and writes --------------------------------------------------------------

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (descriptor.uuid != CgmUuids.CLIENT_CONFIG) return
        if (status != BluetoothGatt.GATT_SUCCESS) return finish(IOException("Enabling notifications failed ($status)"))
        completeOpAndMaybeConnect()
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        // The RACP procedure result itself arrives later as an indication; a failed write just
        // means there is no on-demand backfill. Live data is unaffected.
        completeOpAndMaybeConnect()
    }

    /** Marks the queued op done, and declares the link CONNECTED once the setup queue is empty. */
    private fun completeOpAndMaybeConnect() {
        val empty = synchronized(lock) {
            opInFlight = false
            ops.isEmpty()
        }
        if (empty && !connectedSent && sessionStartMillis != null) {
            connectedSent = true
            timeoutJob?.cancel()
            out.trySend(SourceEvent.Link(LinkState(LinkPhase.CONNECTED, "receiving measurements")))
        }
        pump()
    }

    private fun opCompleted() {
        synchronized(lock) { opInFlight = false }
        pump()
    }

    // --- characteristic values ------------------------------------------------------------------

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) = handleValue(characteristic.uuid, value)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        // Before API 33 the payload lives on the characteristic instead of being passed in.
        val payload = characteristic.value ?: return
        handleValue(characteristic.uuid, payload)
    }

    private fun handleValue(uuid: UUID, payload: ByteArray) {
        when (uuid) {
            CgmUuids.MEASUREMENT -> handleMeasurement(payload)
            CgmUuids.RECORD_ACCESS_CONTROL_POINT -> handleRacpResponse(payload)
        }
    }

    private fun handleMeasurement(payload: ByteArray) {
        val start = sessionStartMillis ?: return
        val decoded = try {
            CgmMeasurementCodec.decodeAll(payload) // Verifies the E2E-CRC when present.
        } catch (e: CgmDecodeException) {
            return // A malformed or CRC-failed notification is dropped. The next one is independent.
        }
        for (record in decoded.records) {
            out.trySend(SourceEvent.Reading(CgmRecordMapper.toReading(record, start)))
        }
    }

    /** Decodes the outcome indication of the backfill request. Nothing to act on: data flowed. */
    private fun handleRacpResponse(payload: ByteArray) {
        if (payload.size < 2) return
        if (payload[0].toInt() and 0xFF == Racp.OP_RESPONSE_CODE) {
            // Success, no-records-found, or a refusal: the link is already up either way.
            return
        }
    }

    // --- operation queue -----------------------------------------------------------------------

    private fun enqueue(op: () -> Boolean) {
        synchronized(lock) {
            if (finished) return
            ops.addLast(op)
        }
        pump()
    }

    private fun pump() {
        val next = synchronized(lock) {
            if (finished || opInFlight) return
            ops.removeFirstOrNull()?.also { opInFlight = true }
        } ?: return
        if (!next()) finish(IOException("A Bluetooth operation could not start"))
    }

    private fun subscribe(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val cccd = characteristic.getDescriptor(CgmUuids.CLIENT_CONFIG) ?: return false
        return guarded { writeDescriptor(gatt, cccd, value) }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
            BluetoothStatusCodes.SUCCESS
    } else {
        characteristic.value = value
        @Suppress("DEPRECATION")
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(characteristic)
    }

    /** Runs a GATT call. A revoked permission finishes the session rather than crashing the binder thread. */
    private inline fun guarded(block: () -> Boolean): Boolean = try {
        block()
    } catch (e: SecurityException) {
        finish(IOException("Bluetooth permission missing"))
        false
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L

        /** What we ask for; the sensor batches one full reading frame per record at this size. */
        const val REQUESTED_MTU = 185
    }
}
