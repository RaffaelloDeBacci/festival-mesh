package com.festivalcommunicator.mesh

import com.festivalcommunicator.mesh.transport.MeshAdvertisement
import com.festivalcommunicator.mesh.transport.MeshTransport
import com.festivalcommunicator.mesh.transport.MeshTransportListener
import com.festivalcommunicator.mesh.transport.MeshTransportSession
import kotlin.time.Duration.Companion.seconds

internal enum class PeerConnectionState {
    DISCOVERED,
    CONNECTED,
    DISCONNECTED,
}

internal data class Peer(
    val nodeId: NodeId,
    val eventId: String,
    val lastSeenMillis: Long,
    val connectionState: PeerConnectionState,
    val protocolVersion: Int,
    val capabilities: Set<String>,
    val participationPolicy: ParticipationPolicy,
)

internal class DefaultMeshNetwork(
    private val identity: NodeIdentity,
    private val transport: MeshTransport,
    private val config: MeshConfig,
) : MeshNetwork, MeshTransportListener {
    private val lock = MeshLock()
    private val messageStore: MessageStore = InMemoryMessageStore(config.maxStoredMessages)
    private val peers = linkedMapOf<NodeId, Peer>()
    private val pendingForwards = mutableSetOf<Pair<MessageId, NodeId>>()
    private val handlers = linkedMapOf<Long, MessageHandler>()
    private var nextSubscriptionId = 0L
    private var activeEventId: String? = null
    private var participationPolicy: ParticipationPolicy = ParticipationPolicy.NORMAL

    private var forwardedMessages = 0L
    private var deliveredMessages = 0L
    private var expiredMessages = 0L
    private var duplicatesSuppressed = 0L
    private var messagesReceived = 0L
    private var messagesDropped = 0L
    private var connectionsEstablished = 0L
    private var bytesSent = 0L
    private var bytesReceived = 0L

    override fun start(eventId: String) = lock.withLock {
        require(eventId.isNotBlank()) { "Event ID must not be blank" }
        pruneExpiredLocked()
        activeEventId = eventId
        transport.start(
            MeshTransportSession(
                localNodeId = identity.nodeId,
                eventId = eventId,
                protocolVersion = config.protocolVersion,
                listener = this,
            ),
        )
        transport.updateParticipation(participationPolicy)
    }

    override fun stop() = lock.withLock {
        transport.stop()
        peers.clear()
        pendingForwards.clear()
        activeEventId = null
    }

    override fun send(destinationNodeId: NodeId, payload: ByteArray): MessageId = lock.withLock {
        val eventId = requireActiveEventId()
        require(payload.size <= config.maxPayloadBytes) {
            "Payload exceeds ${config.maxPayloadBytes} bytes"
        }
        pruneExpiredLocked()

        val now = config.clock.now()
        val message = ProtocolEnvelope(
            version = config.protocolVersion,
            type = ProtocolMessageType.MESSAGE,
            messageId = randomMessageId(),
            sourceNodeId = identity.nodeId,
            destinationNodeId = destinationNodeId,
            eventId = eventId,
            ttl = config.defaultTtl,
            createdAt = now,
            expiresAt = now.plus(config.messageExpiration),
            payload = MessagePayload(payload.copyOf()),
        )

        val stored = StoredMeshMessage(
            envelope = message,
            firstSeenAt = now,
            status = StoredMessageStatus.RECEIVED,
            forwardedTo = emptySet(),
            lastUpdatedAt = now,
        )

        if (!messageStore.store(stored, now)) {
            messagesDropped++
            error("Local message store is full")
        }

        if (destinationNodeId == identity.nodeId) {
            deliverToApplicationLocked(stored, now)
        } else {
            scheduleForwardingLocked(stored, excludePeers = emptySet())
        }

        message.messageId
    }

    override fun observeMessages(handler: MessageHandler): MeshSubscription = lock.withLock {
        val subscriptionId = nextSubscriptionId++
        handlers[subscriptionId] = handler
        MeshSubscription {
            lock.withLock {
                handlers.remove(subscriptionId)
            }
        }
    }

    override fun getStatus(): MeshStatus = lock.withLock {
        pruneExpiredLocked()
        MeshStatus(
            nodeId = identity.nodeId,
            eventId = activeEventId,
            discoveredPeers = peers.values.count { it.connectionState != PeerConnectionState.DISCONNECTED },
            connectedPeers = peers.values.count { it.connectionState == PeerConnectionState.CONNECTED },
            storedMessages = messageStore.size(config.clock.now()),
            forwardedMessages = forwardedMessages,
            deliveredMessages = deliveredMessages,
            expiredMessages = expiredMessages,
            duplicatesSuppressed = duplicatesSuppressed,
            messagesReceived = messagesReceived,
            messagesDropped = messagesDropped,
            connectionsEstablished = connectionsEstablished,
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            participationPolicy = participationPolicy,
        )
    }

    override fun setParticipation(policy: ParticipationPolicy) = lock.withLock {
        participationPolicy = policy
        transport.updateParticipation(policy)
    }

    override fun onPeerDiscovered(advertisement: MeshAdvertisement) = lock.withLock {
        val eventId = activeEventId ?: return@withLock
        if (advertisement.eventId != eventId || advertisement.protocolVersion != config.protocolVersion) {
            return@withLock
        }
        val now = config.clock.now()
        peers[advertisement.nodeId] = Peer(
            nodeId = advertisement.nodeId,
            eventId = advertisement.eventId,
            lastSeenMillis = now.toEpochMilliseconds(),
            connectionState = PeerConnectionState.DISCOVERED,
            protocolVersion = advertisement.protocolVersion,
            capabilities = emptySet(),
            participationPolicy = ParticipationPolicy.NORMAL,
        )
        sendHelloLocked(advertisement.nodeId)
    }

    override fun onPeerDisconnected(nodeId: NodeId) = lock.withLock {
        val existing = peers[nodeId] ?: return@withLock
        peers[nodeId] = existing.copy(
            connectionState = PeerConnectionState.DISCONNECTED,
            lastSeenMillis = config.clock.now().toEpochMilliseconds(),
        )
    }

    override fun onMessageReceived(fromNodeId: NodeId, message: ProtocolEnvelope) = lock.withLock {
        val eventId = activeEventId ?: return@withLock
        pruneExpiredLocked()

        if (message.eventId != eventId || message.version != config.protocolVersion) {
            messagesDropped++
            return@withLock
        }
        if (message.expiresAt <= config.clock.now()) {
            messagesDropped++
            return@withLock
        }
        if (message.ttl < 0 || message.ttl > config.defaultTtl) {
            messagesDropped++
            return@withLock
        }

        bytesReceived += message.sizeInBytes().toLong()

        when (message.type) {
            ProtocolMessageType.HELLO -> handleHelloLocked(fromNodeId, message)
            ProtocolMessageType.HELLO_ACK -> handleHelloAckLocked(fromNodeId, message)
            ProtocolMessageType.MESSAGE -> handleMeshMessageLocked(fromNodeId, message)
            ProtocolMessageType.ACK -> Unit
        }
    }

    private fun requireActiveEventId(): String = activeEventId ?: error("Mesh network has not been started")

    private fun handleHelloLocked(fromNodeId: NodeId, message: ProtocolEnvelope) {
        val payload = message.payload as? HelloPayload ?: run {
            messagesDropped++
            return
        }
        markPeerConnectedLocked(fromNodeId, message.eventId, message.version, payload.capabilities, payload.participationPolicy)
        sendHelloAckLocked(fromNodeId)
        flushMessagesToPeerLocked(fromNodeId)
    }

    private fun handleHelloAckLocked(fromNodeId: NodeId, message: ProtocolEnvelope) {
        val payload = message.payload as? HelloAckPayload ?: run {
            messagesDropped++
            return
        }
        markPeerConnectedLocked(fromNodeId, message.eventId, message.version, payload.capabilities, payload.participationPolicy)
        flushMessagesToPeerLocked(fromNodeId)
    }

    private fun handleMeshMessageLocked(fromNodeId: NodeId, message: ProtocolEnvelope) {
        val payload = message.payload as? MessagePayload ?: run {
            messagesDropped++
            return
        }
        if (payload.bytes.size > config.maxPayloadBytes) {
            messagesDropped++
            return
        }
        messagesReceived++

        if (messageStore.contains(message.messageId)) {
            duplicatesSuppressed++
            return
        }

        val now = config.clock.now()
        val stored = StoredMeshMessage(
            envelope = message.copy(payload = payload.copy(bytes = payload.bytes.copyOf())),
            firstSeenAt = now,
            status = StoredMessageStatus.RECEIVED,
            forwardedTo = setOf(fromNodeId),
            lastUpdatedAt = now,
        )

        if (!messageStore.store(stored, now)) {
            messagesDropped++
            return
        }

        if (message.destinationNodeId == identity.nodeId) {
            deliverToApplicationLocked(stored, now)
            return
        }

        if (message.ttl > 0) {
            scheduleForwardingLocked(stored, excludePeers = setOf(fromNodeId))
        }
    }

    private fun deliverToApplicationLocked(message: StoredMeshMessage, deliveredAt: kotlinx.datetime.Instant) {
        deliveredMessages++
        messageStore.markDelivered(message.envelope.messageId, deliveredAt)
        val payload = message.envelope.payload as MessagePayload
        val deliveredMessage = DeliveredMeshMessage(
            messageId = message.envelope.messageId,
            sourceNodeId = message.envelope.sourceNodeId,
            destinationNodeId = message.envelope.destinationNodeId,
            eventId = message.envelope.eventId,
            payload = payload.bytes.copyOf(),
            hopCount = payload.hopCount,
            ttlRemaining = message.envelope.ttl,
            createdAt = message.envelope.createdAt,
            deliveredAt = deliveredAt,
        )
        handlers.values.forEach { it.onMessage(deliveredMessage) }
    }

    private fun flushMessagesToPeerLocked(peerId: NodeId) {
        val now = config.clock.now()
        messageStore.activeMessages(now).forEach { message ->
            if (message.envelope.destinationNodeId != identity.nodeId &&
                message.envelope.ttl > 0 &&
                peerId !in message.forwardedTo
            ) {
                scheduleForwardingLocked(message, excludePeers = emptySet(), targetPeer = peerId)
            }
        }
    }

    private fun scheduleForwardingLocked(
        message: StoredMeshMessage,
        excludePeers: Set<NodeId>,
        targetPeer: NodeId? = null,
    ) {
        val peersToUse = if (targetPeer != null) {
            listOf(targetPeer)
        } else {
            peers.values
                .filter { it.connectionState == PeerConnectionState.CONNECTED }
                .map { it.nodeId }
        }

        peersToUse
            .filterNot { it in excludePeers || it in message.forwardedTo }
            .forEach { peerId ->
                val pendingKey = message.envelope.messageId to peerId
                if (!pendingForwards.add(pendingKey)) {
                    return@forEach
                }
                val delay = config.delayProvider.nextDelay(participationPolicy)
                config.scheduler.schedule(delay) {
                    lock.withLock {
                        pendingForwards.remove(pendingKey)
                        attemptForwardLocked(message.envelope.messageId, peerId)
                    }
                }
            }
    }

    private fun attemptForwardLocked(messageId: MessageId, peerId: NodeId) {
        pruneExpiredLocked()
        val message = messageStore.get(messageId) ?: return
        val peer = peers[peerId] ?: return
        if (peer.connectionState != PeerConnectionState.CONNECTED) {
            return
        }
        if (peerId in message.forwardedTo || message.envelope.ttl <= 0) {
            return
        }

        val payload = message.envelope.payload as? MessagePayload ?: return
        val forwarded = message.envelope.copy(
            ttl = (message.envelope.ttl - 1).coerceAtLeast(0),
            payload = payload.copy(
                bytes = payload.bytes.copyOf(),
                hopCount = payload.hopCount + 1,
            ),
        )
        transport.send(peerId, forwarded)
        messageStore.markForwarded(messageId, peerId, config.clock.now())
        forwardedMessages++
        bytesSent += forwarded.sizeInBytes().toLong()
    }

    private fun markPeerConnectedLocked(
        peerId: NodeId,
        eventId: String,
        protocolVersion: Int,
        capabilities: Set<String>,
        peerParticipationPolicy: ParticipationPolicy,
    ) {
        val now = config.clock.now()
        val previous = peers[peerId]
        if (previous?.connectionState != PeerConnectionState.CONNECTED) {
            connectionsEstablished++
        }
        peers[peerId] = Peer(
            nodeId = peerId,
            eventId = eventId,
            lastSeenMillis = now.toEpochMilliseconds(),
            connectionState = PeerConnectionState.CONNECTED,
            protocolVersion = protocolVersion,
            capabilities = capabilities,
            participationPolicy = peerParticipationPolicy,
        )
    }

    private fun sendHelloLocked(peerId: NodeId) {
        val eventId = requireActiveEventId()
        val now = config.clock.now()
        val hello = ProtocolEnvelope(
            version = config.protocolVersion,
            type = ProtocolMessageType.HELLO,
            messageId = randomMessageId(),
            sourceNodeId = identity.nodeId,
            destinationNodeId = peerId,
            eventId = eventId,
            ttl = 1,
            createdAt = now,
            expiresAt = now.plus(15.seconds),
            payload = HelloPayload(config.capabilities, participationPolicy),
        )
        transport.send(peerId, hello)
        bytesSent += hello.sizeInBytes().toLong()
    }

    private fun sendHelloAckLocked(peerId: NodeId) {
        val eventId = requireActiveEventId()
        val now = config.clock.now()
        val helloAck = ProtocolEnvelope(
            version = config.protocolVersion,
            type = ProtocolMessageType.HELLO_ACK,
            messageId = randomMessageId(),
            sourceNodeId = identity.nodeId,
            destinationNodeId = peerId,
            eventId = eventId,
            ttl = 1,
            createdAt = now,
            expiresAt = now.plus(15.seconds),
            payload = HelloAckPayload(config.capabilities, participationPolicy),
        )
        transport.send(peerId, helloAck)
        bytesSent += helloAck.sizeInBytes().toLong()
    }

    private fun pruneExpiredLocked() {
        val expired = messageStore.expire(config.clock.now())
        if (expired.isNotEmpty()) {
            expiredMessages += expired.size.toLong()
        }
    }
}
