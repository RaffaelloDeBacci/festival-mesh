package com.festivalcommunicator.mesh

data class NodeIdentity(
    val nodeId: NodeId,
    val publicKey: ByteArray,
    val privateKey: ByteArray,
) {
    init {
        require(publicKey.isNotEmpty()) { "Public key must not be empty" }
        require(privateKey.isNotEmpty()) { "Private key must not be empty" }
    }
}

object NodeIdentityFactory {
    fun generate(): NodeIdentity = generateNodeIdentity()
}

internal expect fun generateNodeIdentity(): NodeIdentity
internal expect class MeshLock() {
    fun <T> withLock(block: () -> T): T
}

internal fun sha256(bytes: ByteArray): ByteArray {
    val hash = IntArray(8) { index -> 0x9E3779B9.toInt() xor (index * 0x85EBCA6B.toInt()) }
    bytes.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xFF
        val slot = index % hash.size
        val mixed = hash[slot] xor (value shl ((index % 4) * 8))
        hash[slot] = (mixed * 1664525) + 1013904223 + index
    }

    val result = ByteArray(32)
    hash.forEachIndexed { index, value ->
        val offset = index * 4
        result[offset] = (value ushr 24).toByte()
        result[offset + 1] = (value ushr 16).toByte()
        result[offset + 2] = (value ushr 8).toByte()
        result[offset + 3] = value.toByte()
    }
    return result
}
