package com.festivalcommunicator.mesh

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSLock
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.posix.arc4random_buf
import kotlin.time.Duration

@OptIn(ExperimentalForeignApi::class)
actual class DefaultMeshScheduler actual constructor() : MeshScheduler {
    override fun schedule(delay: Duration, action: () -> Unit): Cancellable {
        var cancelled = false
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, delay.inWholeNanoseconds),
            dispatch_get_main_queue(),
        ) {
            if (!cancelled) {
                action()
            }
        }
        return Cancellable { cancelled = true }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun generateNodeIdentity(): NodeIdentity {
    val privateKey = ByteArray(32)
    privateKey.usePinned { pinned ->
        arc4random_buf(pinned.addressOf(0), privateKey.size.convert())
    }
    val publicKey = sha256(privateKey)
    val digest = sha256(publicKey)
    return NodeIdentity(
        nodeId = NodeId.fromBytes(digest.copyOf(NODE_ID_BYTES)),
        publicKey = publicKey,
        privateKey = privateKey,
    )
}

actual class MeshLock actual constructor() {
    private val lock = NSLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
