package com.festivalcommunicator.mesh

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeshCodecTest {
    @Test
    fun `protocol envelope round trips through binary codec`() {
        val envelope = ProtocolEnvelope(
            version = 1,
            type = ProtocolMessageType.MESSAGE,
            messageId = MessageId("message-1"),
            sourceNodeId = NodeId("0000000000000000000000000000000a"),
            destinationNodeId = NodeId("0000000000000000000000000000000b"),
            eventId = "test-event",
            ttl = 7,
            createdAt = Instant.parse("2026-09-30T12:00:00Z"),
            expiresAt = Instant.parse("2026-09-30T12:30:00Z"),
            payload = MessagePayload("payload".encodeToByteArray(), hopCount = 2),
        )

        val decoded = ProtocolEnvelopeCodec.decode(ProtocolEnvelopeCodec.encode(envelope))

        assertEquals(envelope.version, decoded.version)
        assertEquals(envelope.type, decoded.type)
        assertEquals(envelope.messageId, decoded.messageId)
        assertEquals(envelope.sourceNodeId, decoded.sourceNodeId)
        assertEquals(envelope.destinationNodeId, decoded.destinationNodeId)
        assertEquals(envelope.eventId, decoded.eventId)
        assertEquals(envelope.ttl, decoded.ttl)
        assertEquals(envelope.createdAt, decoded.createdAt)
        assertEquals(envelope.expiresAt, decoded.expiresAt)
        val payload = decoded.payload as MessagePayload
        assertEquals(2, payload.hopCount)
        assertContentEquals("payload".encodeToByteArray(), payload.bytes)
    }

    @Test
    fun `ble discovery record encodes event hash and node id`() {
        val nodeId = NodeId("abcdef0123456789abcdef0123456789")
        val record = BleDiscoveryCodec.decode(
            BleDiscoveryCodec.encode(
                eventId = "festival-2027",
                protocolVersion = 1,
                nodeId = nodeId,
            ),
        )

        assertEquals(1, record.protocolVersion)
        assertEquals(nodeId, record.nodeId)
        assertTrue(BleDiscoveryCodec.matchesEvent(record, "festival-2027"))
    }
}
