package com.fakeglucosense.sensor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.fakeglucosense.core.ble.CgmFeatureCodec
import com.fakeglucosense.core.ble.CgmMeasurementCodec
import com.fakeglucosense.core.ble.CgmOpsCodec
import com.fakeglucosense.core.ble.CgmRecord
import com.fakeglucosense.core.ble.CgmStatusCodec
import com.fakeglucosense.core.ble.CgmUuids
import com.fakeglucosense.core.ble.CgmDecodeException
import com.fakeglucosense.core.ble.StatusAnnunciation
import com.fakeglucosense.core.ble.Racp
import com.fakeglucosense.core.ble.RacpFilter
import com.fakeglucosense.core.ble.SessionStartCodec

/**
 * The GATT server that serves the CGM Service (ADR-0013, ADR-0014).
 *
 * Serves Measurement (notify, E2E-CRC on every value), Feature, Status, and Session Start Time
 * (read), plus the Record Access Control Point and the CGM Specific Ops Control Point
 * (write | indicate). The record logic — validation, filtering, response codes — lives in the
 * core codecs; this class is plumbing between them and the Android stack.
 *
 * One control frame or notification is in flight at a time. Everything else waits in a shared,
 * bounded outbox, so a slow central cannot stall or exhaust the sensor. When a central's MTU
 * grows, its records are batched into fewer, larger frames.
 */
@SuppressLint("MissingPermission")
class SensorGattServer(
    private val context: Context,
    private val onReady: () -> Unit,
    private val onSubscribersChanged: (Int) -> Unit,
) : BluetoothGattServerCallback() {

    /** Session and cadence control surfaced by [com.fakeglucosense.sensor.service.SensorService]. */
    interface SessionControl {
        val communicationIntervalMinutes: Int
        fun setCommunicationInterval(minutes: Int): Boolean
        fun startSession(): Boolean
        fun stopSession(): Boolean
    }

    /** One queued frame for one central. Report frames belong to an RACP transfer (abortable). */
    private data class QueuedFrame(
        val device: BluetoothDevice,
        val characteristic: BluetoothGattCharacteristic?,
        val value: ByteArray,
        val confirm: Boolean,
        val report: Boolean = false,
    )

    private val lock = Any()
    private var server: BluetoothGattServer? = null
    private var measurement: BluetoothGattCharacteristic? = null
    private var racp: BluetoothGattCharacteristic? = null
    private var opsControlPoint: BluetoothGattCharacteristic? = null
    private var sessionStartMillis: Long = 0L

    private val measurementSubscribers = LinkedHashSet<BluetoothDevice>()
    private val indicating = mutableMapOf<java.util.UUID, MutableSet<BluetoothDevice>>()
    private val mtus = mutableMapOf<BluetoothDevice, Int>()
    private val outbox = ArrayDeque<QueuedFrame>()
    private var inFlight: QueuedFrame? = null

    /** History source for RACP reports, and the current-session snapshot for CGM Status reads. */
    var recordsProvider: (() -> List<CgmRecord>)? = null
    var statusProvider: (() -> Pair<Int, StatusAnnunciation>)? = null
    var sessionControl: SessionControl? = null

    /** Opens the server and registers the service. [onReady] runs when the stack confirms it. */
    fun open(sessionStart: Long): Boolean {
        sessionStartMillis = sessionStart
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val opened = try {
            manager.openGattServer(context, this)
        } catch (e: SecurityException) {
            null
        }
        if (opened == null) return false
        server = opened

        val service = BluetoothGattService(CgmUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val measure = BluetoothGattCharacteristic(
            CgmUuids.MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        ).apply { addDescriptor(clientConfig()) }
        val feature = BluetoothGattCharacteristic(
            CgmUuids.FEATURE,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val status = BluetoothGattCharacteristic(
            CgmUuids.STATUS,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val sessionStart = BluetoothGattCharacteristic(
            CgmUuids.SESSION_START_TIME,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val racpChar = BluetoothGattCharacteristic(
            CgmUuids.RECORD_ACCESS_CONTROL_POINT,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        ).apply { addDescriptor(clientConfig()) }
        val opsChar = BluetoothGattCharacteristic(
            CgmUuids.SPECIFIC_OPS_CONTROL_POINT,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        ).apply { addDescriptor(clientConfig()) }
        service.addCharacteristic(measure)
        service.addCharacteristic(feature)
        service.addCharacteristic(status)
        service.addCharacteristic(sessionStart)
        service.addCharacteristic(racpChar)
        service.addCharacteristic(opsChar)
        measurement = measure
        racp = racpChar
        opsControlPoint = opsChar
        return try {
            opened.addService(service)
        } catch (e: SecurityException) {
            false
        }
    }

    private fun clientConfig() = BluetoothGattDescriptor(
        CgmUuids.CLIENT_CONFIG,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
    )

    /** Re-anchors the served Session Start Time after a collector started a new session. */
    fun updateSessionStart(sessionStart: Long) {
        sessionStartMillis = sessionStart
    }

    /** Queues live records for every subscribed central. A no-op when nobody is listening. */
    fun publish(records: List<CgmRecord>) {
        if (records.isEmpty()) return
        val devices = synchronized(lock) { measurementSubscribers.toList() }
        for (device in devices) enqueueFrames(device, records, report = false)
        pump()
    }

    fun subscriberCount(): Int = synchronized(lock) { measurementSubscribers.size }

    fun close() {
        synchronized(lock) {
            measurementSubscribers.clear()
            indicating.clear()
            outbox.clear()
            inFlight = null
        }
        try {
            server?.close()
        } catch (_: SecurityException) {
            // Permission revoked while running. The stack has already dropped the server.
        }
        server = null
        onSubscribersChanged(0)
    }

    // --- framing and flow control -------------------------------------------------------------

    private fun payloadLimit(device: BluetoothDevice): Int =
        (synchronized(lock) { mtus[device] } ?: DEFAULT_ATT_MTU) - NOTIFICATION_HEADER_BYTES

    /** Splits records into CRC-stamped frames sized for this central's MTU and queues them. */
    private fun enqueueFrames(device: BluetoothDevice, records: List<CgmRecord>, report: Boolean) {
        val frames = CgmMeasurementCodec.batch(records, payloadLimit(device), withCrc = true)
        val char = measurement ?: return
        synchronized(lock) {
            for (frame in frames) outbox.addLast(QueuedFrame(device, char, frame, confirm = false, report = report))
            while (outbox.size > MAX_QUEUED_FRAMES) outbox.removeFirst()
        }
    }

    /** Queues an indication at the tail of the device's stream. Indications are protocol
     *  responses the central awaits; overflow drops report frames ahead of them, never the
     *  indication itself. */
    private fun enqueueIndication(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic?, value: ByteArray) {
        val char = characteristic ?: return
        synchronized(lock) {
            outbox.addLast(QueuedFrame(device, char, value, confirm = true))
            while (outbox.size > MAX_QUEUED_FRAMES) {
                val shielded = outbox.last()
                outbox.removeFirst()
                if (outbox.last() !== shielded) { outbox.removeLast(); outbox.addLast(shielded) }
            }
        }
        pump()
    }

    private fun pump() {
        val next = synchronized(lock) {
            if (inFlight != null) return
            outbox.removeFirstOrNull()?.also { inFlight = it }
        } ?: return
        if (!send(next)) {
            // The stack refused the frame. Drop it and move on rather than stalling the queue.
            synchronized(lock) { inFlight = null }
            pump()
        }
    }

    @Suppress("DEPRECATION")
    private fun send(frame: QueuedFrame): Boolean {
        val gattServer = server ?: return false
        val characteristic = frame.characteristic ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer.notifyCharacteristicChanged(
                    frame.device,
                    characteristic,
                    frame.confirm,
                    frame.value,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = frame.value
                gattServer.notifyCharacteristicChanged(frame.device, characteristic, frame.confirm)
            }
        } catch (e: SecurityException) {
            false
        }
    }

    // --- stack callbacks ----------------------------------------------------------------------

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            val count = synchronized(lock) {
                measurementSubscribers.remove(device)
                indicating.values.forEach { it.remove(device) }
                outbox.removeAll { it.device == device }
                if (inFlight?.device == device) inFlight = null
                mtus.remove(device)
                measurementSubscribers.size
            }
            onSubscribersChanged(count)
            pump()
        }
    }

    override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
        synchronized(lock) { mtus[device] = mtu }
    }

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == CgmUuids.SERVICE) onReady()
    }

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic,
    ) {
        val gattServer = server ?: return
        val value: ByteArray? = when (characteristic.uuid) {
            CgmUuids.SESSION_START_TIME -> SessionStartCodec.encode(sessionStartMillis)
            CgmUuids.FEATURE -> CgmFeatureCodec.encode()
            CgmUuids.STATUS -> statusProvider?.invoke()?.let { (offset, annunciation) ->
                CgmStatusCodec.encode(offset, annunciation)
            }
            else -> null
        }
        if (value == null) {
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            return
        }
        val slice = if (offset >= value.size) ByteArray(0) else value.copyOfRange(offset, value.size)
        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
    }

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
    ) {
        val gattServer = server ?: return
        val gattStatus = try {
            when (characteristic.uuid) {
                CgmUuids.RECORD_ACCESS_CONTROL_POINT -> handleRacpWrite(device, value)
                CgmUuids.SPECIFIC_OPS_CONTROL_POINT -> handleOpsWrite(device, value)
                else -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
            }
        } catch (e: CgmDecodeException) {
            BluetoothGatt.GATT_FAILURE
        } catch (e: SecurityException) {
            BluetoothGatt.GATT_FAILURE
        }
        // Control-point procedures always answer, so the central's write never hangs.
        if (responseNeeded || gattStatus != BluetoothGatt.GATT_SUCCESS) {
            gattServer.sendResponse(device, requestId, gattStatus, offset, null)
        }
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
    ) {
        val gattServer = server ?: return
        if (descriptor.uuid != CgmUuids.CLIENT_CONFIG) {
            if (responseNeeded) gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            return
        }
        val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
            value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        val parent = descriptor.characteristic?.uuid
        val count = synchronized(lock) {
            when (parent) {
                CgmUuids.MEASUREMENT -> {
                    if (enable) measurementSubscribers.add(device) else measurementSubscribers.remove(device)
                }
                CgmUuids.RECORD_ACCESS_CONTROL_POINT, CgmUuids.SPECIFIC_OPS_CONTROL_POINT -> {
                    val set = indicating.getOrPut(parent) { mutableSetOf() }
                    if (enable) set.add(device) else set.remove(device)
                }
                else -> Unit
            }
            measurementSubscribers.size
        }
        if (responseNeeded) gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        if (parent == CgmUuids.MEASUREMENT) onSubscribersChanged(count)
    }

    override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
        val parent = descriptor.characteristic?.uuid
        val enabled = synchronized(lock) {
            when (parent) {
                CgmUuids.MEASUREMENT -> device in measurementSubscribers
                null -> false
                else -> indicating[parent]?.contains(device) == true
            }
        }
        val value = if (enabled) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
    }

    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
        synchronized(lock) { if (inFlight?.device == device) inFlight = null }
        pump()
    }

    // --- Record Access Control Point ----------------------------------------------------------

    private fun handleRacpWrite(device: BluetoothDevice, value: ByteArray): Int {
        val request = Racp.decodeRequest(value)
        val error = RacpFilter.validate(request)
        if (error != null) {
            enqueueIndication(device, racp, Racp.encodeResponseCode(request.opCode, error))
            return BluetoothGatt.GATT_SUCCESS
        }
        return when (request.opCode) {
            Racp.OP_ABORT_OPERATION -> handleAbort(device)
            Racp.OP_REPORT_NUMBER_OF_RECORDS -> {
                val selected = RacpFilter.select(request, recordsProvider?.invoke().orEmpty())
                enqueueIndication(device, racp, Racp.encodeNumberOfRecords(selected.size))
                BluetoothGatt.GATT_SUCCESS
            }
            Racp.OP_REPORT_STORED_RECORDS -> {
                val selected = RacpFilter.select(request, recordsProvider?.invoke().orEmpty())
                if (selected.isEmpty()) {
                    enqueueIndication(device, racp, Racp.encodeResponseCode(request.opCode, Racp.RESPONSE_NO_RECORDS_FOUND))
                } else {
                    enqueueFrames(device, selected, report = true)
                    enqueueIndication(device, racp, Racp.encodeResponseCode(request.opCode, Racp.RESPONSE_SUCCESS))
                }
                BluetoothGatt.GATT_SUCCESS
            }
            else -> BluetoothGatt.GATT_SUCCESS // validate() already rejected unknown op codes.
        }
    }

    /** Drops the device's queued report frames; succeeds only when a report was in progress. */
    private fun handleAbort(device: BluetoothDevice): Int {
        val removed = synchronized(lock) {
            var removedAny = outbox.removeAll { it.device == device && it.report }
            if (inFlight?.device == device && inFlight?.report == true) removedAny = true
            removedAny
        }
        val outcome = if (removed) Racp.RESPONSE_SUCCESS else Racp.RESPONSE_ABORT_UNSUCCESSFUL
        enqueueIndication(device, racp, Racp.encodeResponseCode(Racp.OP_ABORT_OPERATION, outcome))
        return BluetoothGatt.GATT_SUCCESS
    }

    // --- CGM Specific Ops Control Point --------------------------------------------------------

    private fun handleOpsWrite(device: BluetoothDevice, value: ByteArray): Int {
        val request = CgmOpsCodec.decodeRequest(value)
        val control = sessionControl
        val response: ByteArray = when (request.opCode) {
            CgmOpsCodec.OP_GET_COMMUNICATION_INTERVAL ->
                CgmOpsCodec.encodeCommunicationInterval(control?.communicationIntervalMinutes ?: DEFAULT_INTERVAL_MINUTES)
            CgmOpsCodec.OP_SET_COMMUNICATION_INTERVAL -> {
                val minutes = request.intervalMinutes ?: 0
                val applied = minutes in CgmOpsCodec.MIN_COMMUNICATION_INTERVAL_MINUTES..CgmOpsCodec.MAX_COMMUNICATION_INTERVAL_MINUTES &&
                    control?.setCommunicationInterval(minutes) == true
                CgmOpsCodec.encodeResponseCode(
                    request.opCode,
                    if (applied) CgmOpsCodec.RESPONSE_SUCCESS else CgmOpsCodec.RESPONSE_OUT_OF_RANGE,
                )
            }
            CgmOpsCodec.OP_START_SESSION -> CgmOpsCodec.encodeResponseCode(
                request.opCode,
                if (control?.startSession() == true) CgmOpsCodec.RESPONSE_SUCCESS else CgmOpsCodec.RESPONSE_PROCEDURE_NOT_COMPLETED,
            )
            CgmOpsCodec.OP_STOP_SESSION -> CgmOpsCodec.encodeResponseCode(
                request.opCode,
                if (control?.stopSession() == true) CgmOpsCodec.RESPONSE_SUCCESS else CgmOpsCodec.RESPONSE_PROCEDURE_NOT_COMPLETED,
            )
            else -> CgmOpsCodec.encodeResponseCode(request.opCode, CgmOpsCodec.RESPONSE_OP_CODE_NOT_SUPPORTED)
        }
        enqueueIndication(device, opsControlPoint, response)
        return BluetoothGatt.GATT_SUCCESS
    }

    private companion object {
        const val DEFAULT_ATT_MTU = 23
        const val NOTIFICATION_HEADER_BYTES = 3
        const val DEFAULT_INTERVAL_MINUTES = 5

        /** Bounding memory: more than an hour of backlog per subscriber at 5-minute cadence. */
        const val MAX_QUEUED_FRAMES = 2_000
    }
}
