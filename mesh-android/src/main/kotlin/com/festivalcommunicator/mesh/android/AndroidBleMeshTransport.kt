package com.festivalcommunicator.mesh.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.Build
import android.os.SystemClock
import com.festivalcommunicator.mesh.BleDiscoveryCodec
import com.festivalcommunicator.mesh.NodeId
import com.festivalcommunicator.mesh.ParticipationPolicy
import com.festivalcommunicator.mesh.ProtocolEnvelope
import com.festivalcommunicator.mesh.ProtocolEnvelopeCodec
import com.festivalcommunicator.mesh.transport.MeshAdvertisement
import com.festivalcommunicator.mesh.transport.MeshTransport
import com.festivalcommunicator.mesh.transport.MeshTransportSession
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

class AndroidBleMeshTransport(
    context: Context,
    private val bluetoothManager: BluetoothManager =
        requireNotNull(context.applicationContext.getSystemService(BluetoothManager::class.java)) {
            "BluetoothManager is not available"
        },
    private val serviceUuid: UUID = MESH_SERVICE_UUID,
    private val manufacturerId: Int = TEST_MANUFACTURER_ID,
) : MeshTransport {
    private val appContext = context.applicationContext
    private val bluetoothAdapter: BluetoothAdapter =
        requireNotNull(bluetoothManager.adapter) { "Bluetooth is not supported on this device" }

    private var session: MeshTransportSession? = null
    private var participationPolicy: ParticipationPolicy = ParticipationPolicy.NORMAL
    private var gattServer: BluetoothGattServer? = null
    private val knownDevices = linkedMapOf<NodeId, BluetoothDevice>()
    private val recentDiscoveries = linkedMapOf<NodeId, Long>()
    private val inboundAssemblers = linkedMapOf<String, FramedEnvelopeAssembler>()
    private val outboundConnections = linkedMapOf<NodeId, OutboundConnection>()

    private val advertiseCallback = object : AdvertiseCallback() {}

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val activeSession = synchronized(this@AndroidBleMeshTransport) { session } ?: return
            val manufacturerPayload = result.scanRecord
                ?.getManufacturerSpecificData(manufacturerId)
                ?: return

            val record = try {
                BleDiscoveryCodec.decode(manufacturerPayload)
            } catch (_: IllegalArgumentException) {
                return
            }

            if (record.protocolVersion != activeSession.protocolVersion ||
                !BleDiscoveryCodec.matchesEvent(record, activeSession.eventId)
            ) {
                return
            }

            synchronized(this@AndroidBleMeshTransport) {
                knownDevices[record.nodeId] = result.device
                val now = SystemClock.elapsedRealtime()
                val lastSeen = recentDiscoveries[record.nodeId]
                recentDiscoveries[record.nodeId] = now
                if (lastSeen != null && now - lastSeen < DISCOVERY_DEBOUNCE_MS) {
                    return
                }
                activeSession.listener.onPeerDiscovered(
                    MeshAdvertisement(
                        eventId = activeSession.eventId,
                        nodeId = record.nodeId,
                        protocolVersion = record.protocolVersion,
                    ),
                )
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val activeSession = synchronized(this@AndroidBleMeshTransport) { session }
            val isValidWrite = characteristic.uuid == messageCharacteristicUuid && !preparedWrite && offset == 0
            if (!isValidWrite) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
                return
            }

            val envelopes = synchronized(this@AndroidBleMeshTransport) {
                inboundAssemblers
                    .getOrPut(device.address) { FramedEnvelopeAssembler() }
                    .append(value)
            }

            envelopes.forEach { envelope ->
                synchronized(this@AndroidBleMeshTransport) {
                    knownDevices[envelope.sourceNodeId] = device
                    activeSession?.listener?.onMessageReceived(envelope.sourceNodeId, envelope)
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            synchronized(this@AndroidBleMeshTransport) {
                val connection = outboundConnections.values.firstOrNull { it.device.address == gatt.device.address }
                    ?: return
                if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                    closeConnectionLocked(connection.peerId)
                    return
                }
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            synchronized(this@AndroidBleMeshTransport) {
                val connection = outboundConnections.values.firstOrNull { it.device.address == gatt.device.address }
                    ?: return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    closeConnectionLocked(connection.peerId)
                    return
                }
                val service = gatt.getService(serviceUuid) ?: run {
                    closeConnectionLocked(connection.peerId)
                    return
                }
                val characteristic = service.getCharacteristic(messageCharacteristicUuid) ?: run {
                    closeConnectionLocked(connection.peerId)
                    return
                }
                connection.characteristic = characteristic
                writeNextChunkLocked(connection)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            synchronized(this@AndroidBleMeshTransport) {
                val connection = outboundConnections.values.firstOrNull { it.device.address == gatt.device.address }
                    ?: return
                connection.isWriting = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    closeConnectionLocked(connection.peerId)
                    return
                }
                writeNextChunkLocked(connection)
            }
        }
    }

    @Synchronized
    override fun start(session: MeshTransportSession) {
        stop()
        this.session = session
        openGattServerLocked()
        startAdvertisingLocked()
        startScanningLocked()
    }

    @Synchronized
    override fun stop() {
        stopScanningLocked()
        stopAdvertisingLocked()
        outboundConnections.keys.toList().forEach(::closeConnectionLocked)
        inboundAssemblers.clear()
        knownDevices.clear()
        recentDiscoveries.clear()
        gattServer?.close()
        gattServer = null
        session = null
    }

    @Synchronized
    override fun send(peerId: NodeId, message: ProtocolEnvelope) {
        val device = knownDevices[peerId] ?: error("Peer $peerId has not been discovered yet")
        val payload = ProtocolEnvelopeCodec.encode(message)
        val connection = outboundConnections.getOrPut(peerId) {
            val gatt = device.connectGatt(appContext, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
            OutboundConnection(peerId = peerId, device = device, gatt = gatt)
        }
        framePayload(payload).forEach(connection.pendingChunks::addLast)
        if (connection.characteristic != null && !connection.isWriting) {
            writeNextChunkLocked(connection)
        }
    }

    @Synchronized
    override fun updateParticipation(policy: ParticipationPolicy) {
        participationPolicy = policy
        if (session != null) {
            stopScanningLocked()
            stopAdvertisingLocked()
            startAdvertisingLocked()
            startScanningLocked()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openGattServerLocked() {
        val server = requireNotNull(
            bluetoothManager.openGattServer(appContext, gattServerCallback),
        ) { "Unable to open Bluetooth GATT server" }
        server.addService(
            BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(
                    BluetoothGattCharacteristic(
                        messageCharacteristicUuid,
                        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                        BluetoothGattCharacteristic.PERMISSION_WRITE,
                    ),
                )
            },
        )
        gattServer = server
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingLocked() {
        val activeSession = session ?: return
        val advertiser: BluetoothLeAdvertiser =
            requireNotNull(bluetoothAdapter.bluetoothLeAdvertiser) {
                "BLE advertising is not available on this device"
            }

        val settings = AdvertiseSettings.Builder()
            .setConnectable(true)
            .setTxPowerLevel(
                if (participationPolicy == ParticipationPolicy.LOW_BATTERY) {
                    AdvertiseSettings.ADVERTISE_TX_POWER_LOW
                } else {
                    AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
                },
            )
            .setAdvertiseMode(
                if (participationPolicy == ParticipationPolicy.LOW_BATTERY) {
                    AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                } else {
                    AdvertiseSettings.ADVERTISE_MODE_BALANCED
                },
            )
            .build()

        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUuid))
            .setIncludeDeviceName(false)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addManufacturerData(
                manufacturerId,
                BleDiscoveryCodec.encode(
                    eventId = activeSession.eventId,
                    protocolVersion = activeSession.protocolVersion,
                    nodeId = activeSession.localNodeId,
                ),
            )
            .build()

        advertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingLocked() {
        bluetoothAdapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun startScanningLocked() {
        val scanner: BluetoothLeScanner =
            requireNotNull(bluetoothAdapter.bluetoothLeScanner) {
                "BLE scanning is not available on this device"
            }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(serviceUuid))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(
                if (participationPolicy == ParticipationPolicy.LOW_BATTERY) {
                    ScanSettings.SCAN_MODE_LOW_POWER
                } else {
                    ScanSettings.SCAN_MODE_BALANCED
                },
            )
            .build()

        scanner.startScan(filters, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanningLocked() {
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun writeNextChunkLocked(connection: OutboundConnection) {
        if (connection.isWriting) {
            return
        }
        val gatt = connection.gatt ?: run {
            closeConnectionLocked(connection.peerId)
            return
        }
        val characteristic = connection.characteristic ?: return
        val nextChunk = connection.pendingChunks.removeFirstOrNull()
        if (nextChunk == null) {
            closeConnectionLocked(connection.peerId)
            return
        }

        connection.isWriting = true
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                nextChunk,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = nextChunk
                gatt.writeCharacteristic(characteristic)
            }
        }
        if (!started) {
            connection.isWriting = false
            closeConnectionLocked(connection.peerId)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeConnectionLocked(peerId: NodeId) {
        val connection = outboundConnections.remove(peerId) ?: return
        connection.gatt?.disconnect()
        connection.gatt?.close()
        connection.gatt = null
    }

    private data class OutboundConnection(
        val peerId: NodeId,
        val device: BluetoothDevice,
        var gatt: BluetoothGatt?,
        var characteristic: BluetoothGattCharacteristic? = null,
        val pendingChunks: ArrayDeque<ByteArray> = ArrayDeque(),
        var isWriting: Boolean = false,
    )

    private class FramedEnvelopeAssembler {
        private val buffer = ByteArrayOutputStream()

        fun append(chunk: ByteArray): List<ProtocolEnvelope> {
            buffer.write(chunk)
            val accumulated = buffer.toByteArray()
            var offset = 0
            val envelopes = mutableListOf<ProtocolEnvelope>()

            while (accumulated.size - offset >= FRAME_HEADER_BYTES) {
                val frameLength = ByteBuffer.wrap(accumulated, offset, FRAME_HEADER_BYTES).int
                if (frameLength < 0 || accumulated.size - offset - FRAME_HEADER_BYTES < frameLength) {
                    break
                }
                offset += FRAME_HEADER_BYTES
                val frame = accumulated.copyOfRange(offset, offset + frameLength)
                envelopes += ProtocolEnvelopeCodec.decode(frame)
                offset += frameLength
            }

            val remaining = accumulated.copyOfRange(offset, accumulated.size)
            buffer.reset()
            buffer.write(remaining)
            return envelopes
        }
    }

    private companion object {
        private const val TEST_MANUFACTURER_ID = 0xFFFF
        private const val DISCOVERY_DEBOUNCE_MS = 5_000L
        private const val CHUNK_BYTES = 20
        private const val FRAME_HEADER_BYTES = Int.SIZE_BYTES
        private val MESH_SERVICE_UUID: UUID = UUID.fromString("8b6d27a4-9d5f-430b-9fd9-f2494d9baf10")
        private val messageCharacteristicUuid: UUID =
            UUID.fromString("ba0f49f4-e7be-44d6-9581-f50f1f18cf6e")

        private fun framePayload(payload: ByteArray): List<ByteArray> {
            val framed = ByteBuffer.allocate(FRAME_HEADER_BYTES + payload.size)
                .putInt(payload.size)
                .put(payload)
                .array()

            return buildList {
                var offset = 0
                while (offset < framed.size) {
                    val end = minOf(offset + CHUNK_BYTES, framed.size)
                    add(framed.copyOfRange(offset, end))
                    offset = end
                }
            }
        }
    }
}
