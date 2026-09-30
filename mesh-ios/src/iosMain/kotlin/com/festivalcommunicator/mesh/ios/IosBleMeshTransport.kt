package com.festivalcommunicator.mesh.ios

import com.festivalcommunicator.mesh.BleDiscoveryCodec
import com.festivalcommunicator.mesh.NodeId
import com.festivalcommunicator.mesh.ParticipationPolicy
import com.festivalcommunicator.mesh.ProtocolEnvelope
import com.festivalcommunicator.mesh.ProtocolEnvelopeCodec
import com.festivalcommunicator.mesh.transport.MeshAdvertisement
import com.festivalcommunicator.mesh.transport.MeshTransport
import com.festivalcommunicator.mesh.transport.MeshTransportSession
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataServiceDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBPeripheralStateConnecting
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBleMeshTransport(
    private val serviceUuid: CBUUID = CBUUID.UUIDWithString("8B6D27A4-9D5F-430B-9FD9-F2494D9BAF10"),
    private val characteristicUuid: CBUUID = CBUUID.UUIDWithString("BA0F49F4-E7BE-44D6-9581-F50F1F18CF6E"),
) : MeshTransport {

    private val bluetoothQueue = dispatch_queue_create("festival-mesh-ios.bluetooth", null)
    private val delegateBridge = DelegateBridge(this)
    private val centralManager = CBCentralManager(delegateBridge, bluetoothQueue, null)
    private val peripheralManager = CBPeripheralManager(delegateBridge, bluetoothQueue, null)

    private var session: MeshTransportSession? = null
    private var participationPolicy: ParticipationPolicy = ParticipationPolicy.NORMAL
    private var writeCharacteristic: CBMutableCharacteristic? = null
    private val knownPeripherals = linkedMapOf<NodeId, CBPeripheral>()
    private val recentDiscoveries = linkedMapOf<NodeId, Double>()
    private val inboundAssemblers = linkedMapOf<String, FramedEnvelopeAssembler>()
    private val outboundConnections = linkedMapOf<NodeId, OutboundConnection>()

    override fun start(session: MeshTransportSession) {
        dispatch_async(bluetoothQueue) {
            stopLocked()
            this.session = session
            configurePeripheralIfReady()
            startScanningIfReady()
        }
    }

    override fun stop() {
        dispatch_async(bluetoothQueue) {
            stopLocked()
        }
    }

    override fun send(peerId: NodeId, message: ProtocolEnvelope) {
        dispatch_async(bluetoothQueue) {
            val peripheral = knownPeripherals[peerId] ?: error("Peer ${peerId.value} has not been discovered yet")
            val payload = ProtocolEnvelopeCodec.encode(message)
            val connection = outboundConnections.getOrPut(peerId) {
                OutboundConnection(peerId = peerId, peripheral = peripheral)
            }

            connection.pendingChunks.addAll(framePayload(payload))
            when (peripheral.state) {
                CBPeripheralStateConnected -> {
                    if (connection.characteristic != null) {
                        writeNextChunk(connection)
                    } else {
                        peripheral.delegate = delegateBridge
                        peripheral.discoverServices(listOf(serviceUuid))
                    }
                }
                CBPeripheralStateConnecting -> Unit
                else -> {
                    peripheral.delegate = delegateBridge
                    centralManager.connectPeripheral(peripheral, null)
                }
            }
        }
    }

    override fun updateParticipation(policy: ParticipationPolicy) {
        dispatch_async(bluetoothQueue) {
            participationPolicy = policy
            if (session != null) {
                stopAdvertisingLocked()
                stopScanningLocked()
                configurePeripheralIfReady()
                startScanningIfReady()
            }
        }
    }

    internal fun onCentralStateUpdated() {
        if (centralManager.state == CBManagerStatePoweredOn) {
            startScanningIfReady()
        } else {
            stopScanningLocked()
        }
    }

    internal fun onPeripheralManagerStateUpdated() {
        if (peripheralManager.state == CBManagerStatePoweredOn) {
            configurePeripheralIfReady()
        } else {
            stopAdvertisingLocked()
        }
    }

    internal fun onPeripheralDiscovered(
        peripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
    ) {
        val activeSession = session ?: return
        val serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? Map<*, *> ?: return
        val payload = serviceData[serviceUuid] as? NSData ?: return
        val record = try {
            BleDiscoveryCodec.decode(payload.toByteArray())
        } catch (_: IllegalArgumentException) {
            return
        }

        if (record.protocolVersion != activeSession.protocolVersion ||
            !BleDiscoveryCodec.matchesEvent(record, activeSession.eventId)
        ) {
            return
        }

        knownPeripherals[record.nodeId] = peripheral
        val now = NSDate().timeIntervalSinceReferenceDate
        val lastSeen = recentDiscoveries[record.nodeId]
        recentDiscoveries[record.nodeId] = now
        if (lastSeen != null && now - lastSeen < 5.0) {
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

    internal fun onPeripheralConnected(peripheral: CBPeripheral) {
        peripheral.delegate = delegateBridge
        peripheral.discoverServices(listOf(serviceUuid))
    }

    internal fun onPeripheralFailedToConnect(peripheral: CBPeripheral) {
        outboundConnections.entries.firstOrNull { it.value.peripheral.identifier == peripheral.identifier }
            ?.key
            ?.let(::closeConnection)
    }

    internal fun onPeripheralDisconnected(peripheral: CBPeripheral) {
        val match = outboundConnections.entries.firstOrNull { it.value.peripheral.identifier == peripheral.identifier }
            ?: return
        closeConnection(match.key)
        session?.listener?.onPeerDisconnected(match.key)
    }

    internal fun onDidDiscoverServices(peripheral: CBPeripheral, error: NSError?) {
        if (error != null) {
            return
        }
        val service = peripheral.services?.firstOrNull { (it as? CBService)?.UUID == serviceUuid } as? CBService ?: return
        peripheral.discoverCharacteristics(listOf(characteristicUuid), service)
    }

    internal fun onDidDiscoverCharacteristics(peripheral: CBPeripheral, service: CBService, error: NSError?) {
        if (error != null) {
            return
        }
        val characteristic = service.characteristics
            ?.firstOrNull { (it as? CBCharacteristic)?.UUID == characteristicUuid } as? CBCharacteristic
            ?: return
        val connection = outboundConnections.values.firstOrNull { it.peripheral.identifier == peripheral.identifier } ?: return
        connection.characteristic = characteristic
        writeNextChunk(connection)
    }

    internal fun onDidWriteValue(peripheral: CBPeripheral, error: NSError?) {
        val connection = outboundConnections.values.firstOrNull { it.peripheral.identifier == peripheral.identifier } ?: return
        connection.isWriting = false
        if (error != null) {
            closeConnection(connection.peerId)
            return
        }
        writeNextChunk(connection)
    }

    internal fun onDidReceiveWriteRequests(requests: List<CBATTRequest>) {
        val validRequests = requests.filter {
            it.characteristic.UUID == characteristicUuid && it.offset.toInt() == 0 && it.value != null
        }
        if (validRequests.isEmpty()) {
            requests.firstOrNull()?.let { peripheralManager.respondToRequest(it, CBATTErrorSuccess) }
            return
        }

        val chunks = validRequests.mapNotNull { it.value?.toByteArray() }
        handleReceivedChunks(validRequests.first().central.identifier.UUIDString, chunks)
        validRequests.forEach { peripheralManager.respondToRequest(it, CBATTErrorSuccess) }
    }

    private fun stopLocked() {
        stopScanningLocked()
        stopAdvertisingLocked()
        outboundConnections.values.forEach { connection ->
            if (connection.peripheral.state == CBPeripheralStateConnected ||
                connection.peripheral.state == CBPeripheralStateConnecting
            ) {
                centralManager.cancelPeripheralConnection(connection.peripheral)
            }
        }
        outboundConnections.clear()
        knownPeripherals.clear()
        recentDiscoveries.clear()
        inboundAssemblers.clear()
        peripheralManager.removeAllServices()
        writeCharacteristic = null
        session = null
    }

    private fun configurePeripheralIfReady() {
        val activeSession = session ?: return
        if (peripheralManager.state != CBManagerStatePoweredOn) {
            return
        }

        val characteristic = CBMutableCharacteristic(
            type = characteristicUuid,
            properties = (CBCharacteristicPropertyWrite or CBCharacteristicPropertyWriteWithoutResponse),
            value = null,
            permissions = CBAttributePermissionsWriteable,
        )
        val service = CBMutableService(type = serviceUuid, primary = true)
        service.setCharacteristics(listOf(characteristic))
        peripheralManager.removeAllServices()
        peripheralManager.addService(service)
        writeCharacteristic = characteristic

        peripheralManager.startAdvertising(
            mapOf(
                CBAdvertisementDataServiceUUIDsKey to listOf(serviceUuid),
                CBAdvertisementDataServiceDataKey to mapOf(
                    serviceUuid to BleDiscoveryCodec.encode(
                        eventId = activeSession.eventId,
                        protocolVersion = activeSession.protocolVersion,
                        nodeId = activeSession.localNodeId,
                    ).toNSData(),
                ),
            ),
        )
    }

    private fun startScanningIfReady() {
        if (centralManager.state != CBManagerStatePoweredOn) {
            return
        }
        centralManager.scanForPeripheralsWithServices(
            serviceUUIDs = listOf(serviceUuid),
            options = mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true),
        )
    }

    private fun stopAdvertisingLocked() {
        peripheralManager.stopAdvertising()
    }

    private fun stopScanningLocked() {
        centralManager.stopScan()
    }

    private fun writeNextChunk(connection: OutboundConnection) {
        if (connection.isWriting) {
            return
        }
        val characteristic = connection.characteristic ?: return
        val nextChunk = connection.pendingChunks.removeFirstOrNull()
        if (nextChunk == null) {
            closeConnection(connection.peerId)
            return
        }

        connection.isWriting = true
        connection.peripheral.writeValue(nextChunk.toNSData(), characteristic, CBCharacteristicWriteWithResponse)
    }

    private fun closeConnection(peerId: NodeId) {
        val connection = outboundConnections.remove(peerId) ?: return
        if (connection.peripheral.state == CBPeripheralStateConnected ||
            connection.peripheral.state == CBPeripheralStateConnecting
        ) {
            centralManager.cancelPeripheralConnection(connection.peripheral)
        }
    }

    private fun handleReceivedChunks(deviceId: String, chunks: List<ByteArray>) {
        val assembler = inboundAssemblers.getOrPut(deviceId) { FramedEnvelopeAssembler() }
        chunks.flatMap(assembler::append).forEach { envelope ->
            session?.listener?.onMessageReceived(envelope.sourceNodeId, envelope)
        }
    }

    private fun framePayload(payload: ByteArray): List<ByteArray> {
        val framed = ByteArray(4 + payload.size)
        val size = payload.size
        framed[0] = ((size ushr 24) and 0xFF).toByte()
        framed[1] = ((size ushr 16) and 0xFF).toByte()
        framed[2] = ((size ushr 8) and 0xFF).toByte()
        framed[3] = (size and 0xFF).toByte()
        payload.copyInto(framed, destinationOffset = 4)

        return buildList {
            var offset = 0
            while (offset < framed.size) {
                val end = minOf(offset + 20, framed.size)
                add(framed.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    private data class OutboundConnection(
        val peerId: NodeId,
        val peripheral: CBPeripheral,
        var characteristic: CBCharacteristic? = null,
        val pendingChunks: ArrayDeque<ByteArray> = ArrayDeque(),
        var isWriting: Boolean = false,
    )

    private class FramedEnvelopeAssembler {
        private var buffer = ByteArray(0)

        fun append(chunk: ByteArray): List<ProtocolEnvelope> {
            buffer += chunk
            val envelopes = mutableListOf<ProtocolEnvelope>()
            var offset = 0
            while (buffer.size - offset >= 4) {
                val frameLength = ((buffer[offset].toInt() and 0xFF) shl 24) or
                    ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                    ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                    (buffer[offset + 3].toInt() and 0xFF)
                if (frameLength < 0 || buffer.size - offset - 4 < frameLength) {
                    break
                }
                offset += 4
                val frame = buffer.copyOfRange(offset, offset + frameLength)
                envelopes += ProtocolEnvelopeCodec.decode(frame)
                offset += frameLength
            }
            buffer = buffer.copyOfRange(offset, buffer.size)
            return envelopes
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class DelegateBridge(
    private val owner: IosBleMeshTransport,
) : NSObject(),
    CBCentralManagerDelegateProtocol,
    CBPeripheralDelegateProtocol,
    CBPeripheralManagerDelegateProtocol {

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        owner.onCentralStateUpdated()
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        owner.onPeripheralDiscovered(didDiscoverPeripheral, advertisementData)
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        owner.onPeripheralConnected(didConnectPeripheral)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        owner.onPeripheralFailedToConnect(didFailToConnectPeripheral)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        owner.onPeripheralDisconnected(didDisconnectPeripheral)
    }

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
        owner.onDidDiscoverServices(peripheral, didDiscoverServices)
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?,
    ) {
        owner.onDidDiscoverCharacteristics(peripheral, didDiscoverCharacteristicsForService, error)
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didWriteValueForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        owner.onDidWriteValue(peripheral, error)
    }

    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        owner.onPeripheralManagerStateUpdated()
    }

    override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveWriteRequests: List<*>) {
        owner.onDidReceiveWriteRequests(didReceiveWriteRequests.filterIsInstance<CBATTRequest>())
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.convert())
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val result = ByteArray(length.toInt())
    if (result.isEmpty()) {
        return result
    }
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return result
}
