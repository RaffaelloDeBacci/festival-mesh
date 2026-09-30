package com.festivalcommunicator.mesh

import com.festivalcommunicator.mesh.transport.InMemoryMeshEnvironment
import com.festivalcommunicator.mesh.transport.InMemoryMeshTransport
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FestivalMeshAcceptanceTest {
    @Test
    fun `messages propagate across four nodes without infrastructure`() {
        val fixture = fixture()
        val a = fixture.node(1)
        val b = fixture.node(2)
        val c = fixture.node(3)
        val d = fixture.node(4)

        fixture.environment.connect(a.identity.nodeId, b.identity.nodeId)
        fixture.environment.connect(b.identity.nodeId, c.identity.nodeId)
        fixture.environment.connect(c.identity.nodeId, d.identity.nodeId)
        fixture.scheduler.runUntilIdle()

        a.network.send(d.identity.nodeId, "Meet at Stage 4".encodeToByteArray())
        fixture.scheduler.runUntilIdle()

        assertEquals(1, d.receivedMessages.size)
        assertContentEquals("Meet at Stage 4".encodeToByteArray(), d.receivedMessages.single().payload)
        assertEquals(3, d.receivedMessages.single().hopCount)
        assertTrue(fixture.environment.isOnline(a.identity.nodeId))
    }

    @Test
    fun `duplicate deliveries are suppressed`() {
        val fixture = fixture()
        val a = fixture.node(1)
        val b = fixture.node(2)
        val c = fixture.node(3)
        val d = fixture.node(4)

        fixture.environment.connect(a.identity.nodeId, b.identity.nodeId)
        fixture.environment.connect(a.identity.nodeId, c.identity.nodeId)
        fixture.environment.connect(b.identity.nodeId, d.identity.nodeId)
        fixture.environment.connect(c.identity.nodeId, d.identity.nodeId)
        fixture.scheduler.runUntilIdle()

        a.network.send(d.identity.nodeId, "mesh".encodeToByteArray())
        fixture.scheduler.runUntilIdle()

        assertEquals(1, d.receivedMessages.size)
        assertEquals(1, d.network.getStatus().duplicatesSuppressed)
    }

    @Test
    fun `ttl blocks forwarding beyond configured hop budget`() {
        val fixture = fixture(defaultTtl = 2)
        val a = fixture.node(1)
        val b = fixture.node(2)
        val c = fixture.node(3)
        val d = fixture.node(4)

        fixture.environment.connect(a.identity.nodeId, b.identity.nodeId)
        fixture.environment.connect(b.identity.nodeId, c.identity.nodeId)
        fixture.environment.connect(c.identity.nodeId, d.identity.nodeId)
        fixture.scheduler.runUntilIdle()

        a.network.send(d.identity.nodeId, "ttl".encodeToByteArray())
        fixture.scheduler.runUntilIdle()

        assertTrue(d.receivedMessages.isEmpty())
        assertEquals(1, c.network.getStatus().storedMessages)
    }

    @Test
    fun `expired messages are deleted before later forwarding opportunities`() {
        val fixture = fixture(messageExpiration = 1.seconds)
        val a = fixture.node(1)
        val b = fixture.node(2)
        val c = fixture.node(3)
        val d = fixture.node(4)

        fixture.environment.connect(a.identity.nodeId, b.identity.nodeId)
        fixture.scheduler.runUntilIdle()

        a.network.send(d.identity.nodeId, "late".encodeToByteArray())
        fixture.scheduler.runUntilIdle()
        fixture.environment.disconnect(a.identity.nodeId, b.identity.nodeId)

        fixture.clock.advanceBy(2.seconds)
        fixture.environment.connect(b.identity.nodeId, c.identity.nodeId)
        fixture.environment.connect(c.identity.nodeId, d.identity.nodeId)
        fixture.scheduler.runUntilIdle()

        assertTrue(d.receivedMessages.isEmpty())
        assertEquals(1, b.network.getStatus().expiredMessages)
        assertEquals(0, b.network.getStatus().storedMessages)
    }

    @Test
    fun `mesh stays operational when an intermediate node disappears`() {
        val fixture = fixture()
        val a = fixture.node(1)
        val b = fixture.node(2)
        val c = fixture.node(3)
        val d = fixture.node(4)

        fixture.environment.connect(a.identity.nodeId, b.identity.nodeId)
        fixture.environment.connect(b.identity.nodeId, c.identity.nodeId)
        fixture.environment.connect(c.identity.nodeId, d.identity.nodeId)
        fixture.scheduler.runUntilIdle()

        c.network.stop()
        fixture.environment.disconnect(b.identity.nodeId, c.identity.nodeId)
        fixture.environment.disconnect(c.identity.nodeId, d.identity.nodeId)
        fixture.environment.connect(b.identity.nodeId, d.identity.nodeId)
        fixture.scheduler.runUntilIdle()

        a.network.send(d.identity.nodeId, "reroute".encodeToByteArray())
        fixture.scheduler.runUntilIdle()

        assertEquals(1, d.receivedMessages.size)
        assertFalse(fixture.environment.isOnline(c.identity.nodeId))
        assertEquals(2, b.network.getStatus().connectedPeers)
    }

    private fun fixture(
        defaultTtl: Int = 8,
        messageExpiration: Duration = 30.minutes,
    ): TestFixture {
        val clock = ManualMeshClock(Instant.parse("2026-09-30T12:00:00Z"))
        val scheduler = ManualMeshScheduler(clock)
        val environment = InMemoryMeshEnvironment()
        return TestFixture(
            clock = clock,
            scheduler = scheduler,
            environment = environment,
            defaultTtl = defaultTtl,
            messageExpiration = messageExpiration,
        )
    }

    private class TestFixture(
        val clock: ManualMeshClock,
        val scheduler: ManualMeshScheduler,
        val environment: InMemoryMeshEnvironment,
        private val defaultTtl: Int,
        private val messageExpiration: Duration,
    ) {
        private val nodes = linkedMapOf<Int, TestNode>()

        fun node(index: Int): TestNode = nodes.getOrPut(index) {
            val identity = testIdentity(index)
            val config = MeshConfig(
                defaultTtl = defaultTtl,
                messageExpiration = messageExpiration,
                delayProvider = DelayProvider { Duration.ZERO },
                clock = clock,
                scheduler = scheduler,
            )
            val network = FestivalMesh.create(
                identity = identity,
                transport = InMemoryMeshTransport(environment),
                config = config,
            )
            val receivedMessages = mutableListOf<DeliveredMeshMessage>()
            network.observeMessages { receivedMessages += it }
            network.start("test-event")
            TestNode(identity, network, receivedMessages)
        }
    }

    private data class TestNode(
        val identity: NodeIdentity,
        val network: MeshNetwork,
        val receivedMessages: MutableList<DeliveredMeshMessage>,
    )

    private companion object {
        fun testIdentity(index: Int): NodeIdentity {
            val nodeId = NodeId(index.toString(16).padStart(32, '0'))
            return NodeIdentity(
                nodeId = nodeId,
                publicKey = ByteArray(32) { index.toByte() },
                privateKey = ByteArray(32) { (index + 1).toByte() },
            )
        }
    }
}
