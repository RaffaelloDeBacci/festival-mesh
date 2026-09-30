package com.festivalcommunicator.mesh.transport

import com.festivalcommunicator.mesh.NodeId
import com.festivalcommunicator.mesh.ParticipationPolicy
import com.festivalcommunicator.mesh.ProtocolEnvelope

data class MeshAdvertisement(
    val eventId: String,
    val nodeId: NodeId,
    val protocolVersion: Int,
)

interface MeshTransportListener {
    fun onPeerDiscovered(advertisement: MeshAdvertisement)
    fun onPeerDisconnected(nodeId: NodeId)
    fun onMessageReceived(fromNodeId: NodeId, message: ProtocolEnvelope)
}

data class MeshTransportSession(
    val localNodeId: NodeId,
    val eventId: String,
    val protocolVersion: Int,
    val listener: MeshTransportListener,
)

interface MeshTransport {
    fun start(session: MeshTransportSession)
    fun stop()
    fun send(peerId: NodeId, message: ProtocolEnvelope)
    fun updateParticipation(policy: ParticipationPolicy)
}
