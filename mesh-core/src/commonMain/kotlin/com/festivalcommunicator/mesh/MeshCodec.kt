package com.festivalcommunicator.mesh

object ProtocolEnvelopeCodec {
    fun encode(envelope: ProtocolEnvelope): ByteArray {
        val writer = ByteWriter()
        writer.writeInt(envelope.version)
        writer.writeInt(envelope.type.ordinal)
        writer.writeUtf8(envelope.messageId.value)
        writer.writeUtf8(envelope.sourceNodeId.value)
        writer.writeUtf8(envelope.destinationNodeId.value)
        writer.writeUtf8(envelope.eventId)
        writer.writeInt(envelope.ttl)
        writer.writeLong(envelope.createdAt.toEpochMilliseconds())
        writer.writeLong(envelope.expiresAt.toEpochMilliseconds())

        when (val payload = envelope.payload) {
            is HelloPayload -> {
                writer.writeInt(payload.capabilities.size)
                payload.capabilities.sorted().forEach(writer::writeUtf8)
                writer.writeInt(payload.participationPolicy.ordinal)
            }
            is HelloAckPayload -> {
                writer.writeInt(payload.capabilities.size)
                payload.capabilities.sorted().forEach(writer::writeUtf8)
                writer.writeInt(payload.participationPolicy.ordinal)
            }
            is MessagePayload -> {
                writer.writeInt(payload.hopCount)
                writer.writeInt(payload.bytes.size)
                writer.writeBytes(payload.bytes)
            }
            is AckPayload -> writer.writeUtf8(payload.acknowledgedMessageId.value)
        }

        return writer.toByteArray()
    }

    fun decode(bytes: ByteArray): ProtocolEnvelope {
        val reader = ByteReader(bytes)
        val version = reader.readInt()
        val type = ProtocolMessageType.entries[reader.readInt()]
        val messageId = MessageId(reader.readUtf8())
        val sourceNodeId = NodeId(reader.readUtf8())
        val destinationNodeId = NodeId(reader.readUtf8())
        val eventId = reader.readUtf8()
        val ttl = reader.readInt()
        val createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(reader.readLong())
        val expiresAt = kotlinx.datetime.Instant.fromEpochMilliseconds(reader.readLong())

        val payload = when (type) {
            ProtocolMessageType.HELLO -> HelloPayload(
                capabilities = reader.readCapabilities(),
                participationPolicy = ParticipationPolicy.entries[reader.readInt()],
            )
            ProtocolMessageType.HELLO_ACK -> HelloAckPayload(
                capabilities = reader.readCapabilities(),
                participationPolicy = ParticipationPolicy.entries[reader.readInt()],
            )
            ProtocolMessageType.MESSAGE -> {
                val hopCount = reader.readInt()
                val payloadLength = reader.readInt()
                MessagePayload(bytes = reader.readBytes(payloadLength), hopCount = hopCount)
            }
            ProtocolMessageType.ACK -> AckPayload(MessageId(reader.readUtf8()))
        }

        return ProtocolEnvelope(
            version = version,
            type = type,
            messageId = messageId,
            sourceNodeId = sourceNodeId,
            destinationNodeId = destinationNodeId,
            eventId = eventId,
            ttl = ttl,
            createdAt = createdAt,
            expiresAt = expiresAt,
            payload = payload,
        )
    }
}

data class BleDiscoveryRecord(
    val protocolVersion: Int,
    val eventHash: ByteArray,
    val nodeId: NodeId,
)

object BleDiscoveryCodec {
    private const val EVENT_HASH_BYTES = 8
    private const val VERSION_BYTES = 1
    private const val RECORD_BYTES = VERSION_BYTES + EVENT_HASH_BYTES + NODE_ID_BYTES

    fun encode(eventId: String, protocolVersion: Int, nodeId: NodeId): ByteArray {
        require(protocolVersion in 0..255) { "Protocol version must fit into one byte" }
        val output = ByteArray(RECORD_BYTES)
        output[0] = protocolVersion.toByte()
        val eventHash = sha256(eventId.encodeToByteArray()).copyOf(EVENT_HASH_BYTES)
        eventHash.copyInto(output, destinationOffset = VERSION_BYTES)
        nodeId.value.decodeHex().copyInto(output, destinationOffset = VERSION_BYTES + EVENT_HASH_BYTES)
        return output
    }

    fun decode(bytes: ByteArray): BleDiscoveryRecord {
        require(bytes.size == RECORD_BYTES) {
            "Discovery record must be exactly $RECORD_BYTES bytes"
        }
        return BleDiscoveryRecord(
            protocolVersion = bytes[0].toInt() and 0xFF,
            eventHash = bytes.copyOfRange(VERSION_BYTES, VERSION_BYTES + EVENT_HASH_BYTES),
            nodeId = NodeId.fromBytes(bytes.copyOfRange(VERSION_BYTES + EVENT_HASH_BYTES, RECORD_BYTES)),
        )
    }

    fun matchesEvent(record: BleDiscoveryRecord, eventId: String): Boolean =
        record.eventHash.contentEquals(sha256(eventId.encodeToByteArray()).copyOf(EVENT_HASH_BYTES))
}

private class ByteWriter {
    private val buffer = ArrayList<Byte>()

    fun writeInt(value: Int) {
        for (shift in 24 downTo 0 step 8) {
            buffer += ((value ushr shift) and 0xFF).toByte()
        }
    }

    fun writeLong(value: Long) {
        for (shift in 56 downTo 0 step 8) {
            buffer += ((value ushr shift) and 0xFF).toByte()
        }
    }

    fun writeUtf8(value: String) {
        val bytes = value.encodeToByteArray()
        writeInt(bytes.size)
        writeBytes(bytes)
    }

    fun writeBytes(value: ByteArray) {
        value.forEach(buffer::add)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}

private class ByteReader(
    private val bytes: ByteArray,
) {
    private var offset = 0

    fun readInt(): Int {
        ensureAvailable(4)
        var value = 0
        repeat(4) {
            value = (value shl 8) or (bytes[offset++].toInt() and 0xFF)
        }
        return value
    }

    fun readLong(): Long {
        ensureAvailable(8)
        var value = 0L
        repeat(8) {
            value = (value shl 8) or (bytes[offset++].toLong() and 0xFF)
        }
        return value
    }

    fun readUtf8(): String = readBytes(readInt()).decodeToString()

    fun readBytes(length: Int): ByteArray {
        require(length >= 0) { "Length must not be negative" }
        ensureAvailable(length)
        return bytes.copyOfRange(offset, offset + length).also { offset += length }
    }

    fun readCapabilities(): Set<String> {
        val count = readInt()
        require(count >= 0) { "Capability count must not be negative" }
        return buildSet {
            repeat(count) { add(readUtf8()) }
        }
    }

    private fun ensureAvailable(length: Int) {
        require(offset + length <= bytes.size) { "Not enough bytes available" }
    }
}
