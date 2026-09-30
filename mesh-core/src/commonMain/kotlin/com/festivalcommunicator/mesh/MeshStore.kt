package com.festivalcommunicator.mesh

import kotlinx.datetime.Instant

enum class StoredMessageStatus {
    RECEIVED,
    FORWARDED,
    DELIVERED,
    EXPIRED,
}

internal data class StoredMeshMessage(
    val envelope: ProtocolEnvelope,
    val firstSeenAt: Instant,
    val status: StoredMessageStatus,
    val forwardedTo: Set<NodeId>,
    val lastUpdatedAt: Instant,
)

internal interface MessageStore {
    fun store(message: StoredMeshMessage, now: Instant): Boolean
    fun contains(messageId: MessageId): Boolean
    fun get(messageId: MessageId): StoredMeshMessage?
    fun activeMessages(now: Instant): List<StoredMeshMessage>
    fun markForwarded(messageId: MessageId, peerId: NodeId, at: Instant)
    fun markDelivered(messageId: MessageId, at: Instant)
    fun expire(now: Instant): List<StoredMeshMessage>
    fun size(now: Instant): Int
}

internal class InMemoryMessageStore(
    private val maxStoredMessages: Int,
) : MessageStore {
    private val messages = linkedMapOf<MessageId, StoredMeshMessage>()

    override fun store(message: StoredMeshMessage, now: Instant): Boolean {
        expire(now)
        if (messages.containsKey(message.envelope.messageId)) {
            return false
        }
        if (messages.size >= maxStoredMessages) {
            return false
        }
        messages[message.envelope.messageId] = message
        return true
    }

    override fun contains(messageId: MessageId): Boolean = messages.containsKey(messageId)

    override fun get(messageId: MessageId): StoredMeshMessage? = messages[messageId]

    override fun activeMessages(now: Instant): List<StoredMeshMessage> {
        expire(now)
        return messages.values.toList()
    }

    override fun markForwarded(messageId: MessageId, peerId: NodeId, at: Instant) {
        val existing = messages[messageId] ?: return
        messages[messageId] = existing.copy(
            status = if (existing.status == StoredMessageStatus.DELIVERED) {
                StoredMessageStatus.DELIVERED
            } else {
                StoredMessageStatus.FORWARDED
            },
            forwardedTo = existing.forwardedTo + peerId,
            lastUpdatedAt = at,
        )
    }

    override fun markDelivered(messageId: MessageId, at: Instant) {
        val existing = messages[messageId] ?: return
        messages[messageId] = existing.copy(
            status = StoredMessageStatus.DELIVERED,
            lastUpdatedAt = at,
        )
    }

    override fun expire(now: Instant): List<StoredMeshMessage> {
        val expired = messages.values.filter { it.envelope.expiresAt <= now }
        expired.forEach { messages.remove(it.envelope.messageId) }
        return expired.map { it.copy(status = StoredMessageStatus.EXPIRED, lastUpdatedAt = now) }
    }

    override fun size(now: Instant): Int = activeMessages(now).size
}
