package com.festivalcommunicator.mesh.transport

import com.festivalcommunicator.mesh.MeshLock
import com.festivalcommunicator.mesh.NodeId
import com.festivalcommunicator.mesh.ParticipationPolicy
import com.festivalcommunicator.mesh.ProtocolEnvelope

class InMemoryMeshEnvironment {
    private data class ActiveNode(
        val session: MeshTransportSession,
        val transport: InMemoryMeshTransport,
    )

    private val lock = MeshLock()
    private val activeNodes = linkedMapOf<NodeId, ActiveNode>()
    private val links = mutableSetOf<Pair<NodeId, NodeId>>()

    internal fun register(transport: InMemoryMeshTransport, session: MeshTransportSession) = lock.withLock {
        activeNodes[session.localNodeId] = ActiveNode(session, transport)
        linkedPeers(session.localNodeId).forEach { peerId ->
            val peer = activeNodes[peerId] ?: return@forEach
            session.listener.onPeerDiscovered(
                MeshAdvertisement(
                    eventId = peer.session.eventId,
                    nodeId = peer.session.localNodeId,
                    protocolVersion = peer.session.protocolVersion,
                ),
            )
            peer.session.listener.onPeerDiscovered(
                MeshAdvertisement(
                    eventId = session.eventId,
                    nodeId = session.localNodeId,
                    protocolVersion = session.protocolVersion,
                ),
            )
        }
    }

    internal fun unregister(localNodeId: NodeId) = lock.withLock {
        if (activeNodes.remove(localNodeId) == null) {
            return@withLock
        }
        linkedPeers(localNodeId).forEach { peerId ->
            activeNodes[peerId]?.session?.listener?.onPeerDisconnected(localNodeId)
        }
    }

    fun connect(first: NodeId, second: NodeId) = lock.withLock {
        val link = normalizedLink(first, second)
        if (!links.add(link)) {
            return@withLock
        }
        val firstNode = activeNodes[first]
        val secondNode = activeNodes[second]
        if (firstNode != null && secondNode != null) {
            firstNode.session.listener.onPeerDiscovered(
                MeshAdvertisement(
                    eventId = secondNode.session.eventId,
                    nodeId = secondNode.session.localNodeId,
                    protocolVersion = secondNode.session.protocolVersion,
                ),
            )
            secondNode.session.listener.onPeerDiscovered(
                MeshAdvertisement(
                    eventId = firstNode.session.eventId,
                    nodeId = firstNode.session.localNodeId,
                    protocolVersion = firstNode.session.protocolVersion,
                ),
            )
        }
    }

    fun disconnect(first: NodeId, second: NodeId) = lock.withLock {
        val link = normalizedLink(first, second)
        if (!links.remove(link)) {
            return@withLock
        }
        activeNodes[first]?.session?.listener?.onPeerDisconnected(second)
        activeNodes[second]?.session?.listener?.onPeerDisconnected(first)
    }

    internal fun deliver(from: NodeId, to: NodeId, message: ProtocolEnvelope) = lock.withLock {
        if (!links.contains(normalizedLink(from, to))) {
            return@withLock
        }
        activeNodes[to]?.session?.listener?.onMessageReceived(from, message)
    }

    fun isOnline(nodeId: NodeId): Boolean = lock.withLock {
        activeNodes.containsKey(nodeId)
    }

    private fun linkedPeers(nodeId: NodeId): List<NodeId> = links.mapNotNull { link ->
        when (nodeId) {
            link.first -> link.second
            link.second -> link.first
            else -> null
        }
    }

    private fun normalizedLink(first: NodeId, second: NodeId): Pair<NodeId, NodeId> =
        if (first.value <= second.value) {
            first to second
        } else {
            second to first
        }
}

class InMemoryMeshTransport(
    private val environment: InMemoryMeshEnvironment,
) : MeshTransport {
    private var session: MeshTransportSession? = null

    override fun start(session: MeshTransportSession) {
        this.session = session
        environment.register(this, session)
    }

    override fun stop() {
        val activeSession = session ?: return
        environment.unregister(activeSession.localNodeId)
        session = null
    }

    override fun send(peerId: NodeId, message: ProtocolEnvelope) {
        val activeSession = session ?: error("Transport has not been started")
        environment.deliver(activeSession.localNodeId, peerId, message)
    }

    override fun updateParticipation(policy: ParticipationPolicy) = Unit
}
