package com.festivalcommunicator.mesh

import kotlinx.datetime.Instant

enum class ProtocolMessageType {
    HELLO,
    HELLO_ACK,
    MESSAGE,
    ACK,
}

sealed interface ProtocolPayload

data class HelloPayload(
    val capabilities: Set<String>,
    val participationPolicy: ParticipationPolicy,
) : ProtocolPayload

data class HelloAckPayload(
    val capabilities: Set<String>,
    val participationPolicy: ParticipationPolicy,
) : ProtocolPayload

data class MessagePayload(
    val bytes: ByteArray,
    val hopCount: Int = 0,
) : ProtocolPayload {
    init {
        require(hopCount >= 0) { "Hop count must not be negative" }
    }
}

data class AckPayload(
    val acknowledgedMessageId: MessageId,
) : ProtocolPayload

data class ProtocolEnvelope(
    val version: Int,
    val type: ProtocolMessageType,
    val messageId: MessageId,
    val sourceNodeId: NodeId,
    val destinationNodeId: NodeId,
    val eventId: String,
    val ttl: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
    val payload: ProtocolPayload,
) {
    init {
        require(version > 0) { "Protocol version must be positive" }
        require(eventId.isNotBlank()) { "Event ID must not be blank" }
    }
}

internal fun ProtocolEnvelope.sizeInBytes(): Int {
    val headerSize = messageId.value.length +
        sourceNodeId.value.length +
        destinationNodeId.value.length +
        eventId.length + 32

    val payloadSize = when (val body = payload) {
        is HelloPayload -> body.capabilities.sumOf(String::length) + 8
        is HelloAckPayload -> body.capabilities.sumOf(String::length) + 8
        is MessagePayload -> body.bytes.size + 4
        is AckPayload -> body.acknowledgedMessageId.value.length
    }

    return headerSize + payloadSize
}
