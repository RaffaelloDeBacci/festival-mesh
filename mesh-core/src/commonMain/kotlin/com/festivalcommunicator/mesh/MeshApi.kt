package com.festivalcommunicator.mesh

import com.festivalcommunicator.mesh.transport.MeshTransport
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.jvm.JvmInline
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private const val NODE_ID_LENGTH = 32
const val NODE_ID_BYTES = NODE_ID_LENGTH / 2
private val NODE_ID_PATTERN = Regex("^[0-9a-f]{$NODE_ID_LENGTH}$")

@JvmInline
value class NodeId(val value: String) {
    init {
        require(NODE_ID_PATTERN.matches(value)) {
            "NodeId must be a $NODE_ID_LENGTH-character lowercase hexadecimal string"
        }
    }

    override fun toString(): String = value

    companion object {
        fun fromBytes(bytes: ByteArray): NodeId {
            require(bytes.size == NODE_ID_BYTES) {
                "NodeId requires exactly $NODE_ID_BYTES bytes"
            }
            return NodeId(bytes.toHexString())
        }
    }
}

@JvmInline
value class MessageId(val value: String) {
    init {
        require(value.isNotBlank()) { "MessageId must not be blank" }
    }

    override fun toString(): String = value
}

enum class ParticipationPolicy {
    NORMAL,
    LOW_BATTERY,
}

fun interface MessageHandler {
    fun onMessage(message: DeliveredMeshMessage)
}

fun interface MeshSubscription {
    fun cancel()
}

fun interface DelayProvider {
    fun nextDelay(policy: ParticipationPolicy): Duration

    companion object {
        fun randomized(
            normalRangeMs: IntRange = 50..500,
            lowBatteryMultiplier: Int = 3,
            random: Random = Random.Default,
        ): DelayProvider = DelayProvider { policy ->
            val base = random.nextInt(normalRangeMs.first, normalRangeMs.last + 1)
            val multiplier = if (policy == ParticipationPolicy.LOW_BATTERY) lowBatteryMultiplier else 1
            (base * multiplier).milliseconds
        }
    }
}

interface MeshClock {
    fun now(): Instant
}

object SystemMeshClock : MeshClock {
    override fun now(): Instant = Clock.System.now()
}

class ManualMeshClock(
    initialInstant: Instant,
) : MeshClock {
    private var currentInstant: Instant = initialInstant

    override fun now(): Instant = currentInstant

    fun advanceBy(duration: Duration) {
        require(!duration.isNegative()) { "Duration must not be negative" }
        currentInstant = currentInstant.plus(duration)
    }
}

fun interface Cancellable {
    fun cancel()
}

interface MeshScheduler {
    fun schedule(delay: Duration, action: () -> Unit): Cancellable
}

expect class DefaultMeshScheduler() : MeshScheduler

class ManualMeshScheduler(
    private val clock: ManualMeshClock,
) : MeshScheduler {
    private data class ScheduledAction(
        val dueAtMillis: Long,
        val sequence: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )

    private val queue = mutableListOf<ScheduledAction>()
    private var nextSequence = 0L

    override fun schedule(delay: Duration, action: () -> Unit): Cancellable {
        require(!delay.isNegative()) { "Delay must not be negative" }
        val scheduled = ScheduledAction(
            dueAtMillis = clock.now().toEpochMilliseconds() + delay.inWholeMilliseconds,
            sequence = nextSequence++,
            action = action,
        )
        queue += scheduled
        queue.sortWith(compareBy<ScheduledAction>({ it.dueAtMillis }, { it.sequence }))
        return Cancellable { scheduled.cancelled = true }
    }

    fun advanceBy(duration: Duration) {
        clock.advanceBy(duration)
        runUntilIdle()
    }

    fun runUntilIdle() {
        while (queue.isNotEmpty()) {
            val next = queue.first()
            val nowMillis = clock.now().toEpochMilliseconds()
            if (next.dueAtMillis > nowMillis) {
                clock.advanceBy((next.dueAtMillis - nowMillis).milliseconds)
            }
            queue.removeAt(0)
            if (!next.cancelled) {
                next.action()
            }
        }
    }
}

data class MeshConfig(
    val protocolVersion: Int = 1,
    val defaultTtl: Int = 8,
    val messageExpiration: Duration = 30.minutes,
    val maxPayloadBytes: Int = 4 * 1024,
    val maxStoredMessages: Int = 1_024,
    val delayProvider: DelayProvider = DelayProvider.randomized(),
    val clock: MeshClock = SystemMeshClock,
    val scheduler: MeshScheduler = DefaultMeshScheduler(),
    val capabilities: Set<String> = setOf("message-forwarding", "store-and-forward"),
) {
    init {
        require(protocolVersion > 0) { "Protocol version must be positive" }
        require(defaultTtl > 0) { "Default TTL must be positive" }
        require(!messageExpiration.isNegative() && messageExpiration != Duration.ZERO) {
            "Message expiration must be positive"
        }
        require(maxPayloadBytes > 0) { "Max payload bytes must be positive" }
        require(maxStoredMessages > 0) { "Max stored messages must be positive" }
    }
}

data class DeliveredMeshMessage(
    val messageId: MessageId,
    val sourceNodeId: NodeId,
    val destinationNodeId: NodeId,
    val eventId: String,
    val payload: ByteArray,
    val hopCount: Int,
    val ttlRemaining: Int,
    val createdAt: Instant,
    val deliveredAt: Instant,
)

data class MeshStatus(
    val nodeId: NodeId,
    val eventId: String?,
    val discoveredPeers: Int,
    val connectedPeers: Int,
    val storedMessages: Int,
    val forwardedMessages: Long,
    val deliveredMessages: Long,
    val expiredMessages: Long,
    val duplicatesSuppressed: Long,
    val messagesReceived: Long,
    val messagesDropped: Long,
    val connectionsEstablished: Long,
    val bytesSent: Long,
    val bytesReceived: Long,
    val participationPolicy: ParticipationPolicy,
)

interface MeshNetwork {
    fun start(eventId: String)
    fun stop()
    fun send(destinationNodeId: NodeId, payload: ByteArray): MessageId
    fun observeMessages(handler: MessageHandler): MeshSubscription
    fun getStatus(): MeshStatus
    fun setParticipation(policy: ParticipationPolicy)
}

object FestivalMesh {
    fun create(
        identity: NodeIdentity = NodeIdentityFactory.generate(),
        transport: MeshTransport,
        config: MeshConfig = MeshConfig(),
    ): MeshNetwork = DefaultMeshNetwork(identity, transport, config)
}

internal fun ByteArray.toHexString(): String {
    val digits = "0123456789abcdef"
    val result = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xFF
        result[index * 2] = digits[value ushr 4]
        result[index * 2 + 1] = digits[value and 0x0F]
    }
    return result.concatToString()
}

internal fun String.decodeHex(): ByteArray {
    require(length % 2 == 0) { "Hex string must have an even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

internal fun randomMessageId(): MessageId = MessageId(Random.Default.nextBytes(16).toHexString())

internal fun Instant.plus(duration: Duration): Instant =
    Instant.fromEpochMilliseconds(toEpochMilliseconds() + duration.inWholeMilliseconds)
