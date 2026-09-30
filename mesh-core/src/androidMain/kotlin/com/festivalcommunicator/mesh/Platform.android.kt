package com.festivalcommunicator.mesh

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

actual class DefaultMeshScheduler actual constructor() : MeshScheduler, AutoCloseable {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "festival-mesh-scheduler").apply { isDaemon = true }
    }

    override fun schedule(delay: Duration, action: () -> Unit): Cancellable {
        val future: ScheduledFuture<*> =
            executor.schedule(action, delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        return Cancellable { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

actual fun generateNodeIdentity(): NodeIdentity {
    val keyPairGenerator = KeyPairGenerator.getInstance("EC")
    keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
    val keyPair = keyPairGenerator.generateKeyPair()
    val publicKey = keyPair.public.encoded
    val privateKey = keyPair.private.encoded
    val digest = sha256(publicKey)
    return NodeIdentity(
        nodeId = NodeId.fromBytes(digest.copyOf(NODE_ID_BYTES)),
        publicKey = publicKey.copyOf(),
        privateKey = privateKey.copyOf(),
    )
}

actual class MeshLock actual constructor() {
    private val lock = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
