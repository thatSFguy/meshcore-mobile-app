package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.isHexDigits

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.util.toHex

/**
 * MeshCore node identity — Ed25519 (MESHCORE_PROTOCOL.md §12).
 *
 * The app keeps the 32-byte *seed* (standard Ed25519 private key form);
 * MeshCore firmware wants the **expanded 64-byte private key**
 * (SHA512(seed) with standard clamping) e.g. when importing a key into a
 * repeater via `set prv.key`. Both derive the same public key and
 * signatures, so seed-based platform crypto interoperates fully.
 */
data class MeshIdentity(
    val seed: ByteArray,
    val publicKey: ByteArray,
) {
    val publicKeyHex: String get() = publicKey.toHex()

    /** SHA512(seed), clamped — the 64-byte form firmware expects. */
    fun expandedPrivateKey(crypto: CryptoProvider): ByteArray {
        val h = crypto.sha512(seed)
        h[0] = (h[0].toInt() and 248).toByte()
        h[31] = ((h[31].toInt() and 63) or 64).toByte()
        return h
    }

    override fun equals(other: Any?): Boolean =
        other is MeshIdentity && seed.contentEquals(other.seed) &&
            publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int = publicKey.contentHashCode()

    companion object {
        fun generate(crypto: CryptoProvider): MeshIdentity {
            val seed = crypto.generateEd25519Seed()
            return MeshIdentity(seed, crypto.ed25519PublicKey(seed))
        }

        fun fromSeed(crypto: CryptoProvider, seed: ByteArray): MeshIdentity {
            require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
            return MeshIdentity(seed, crypto.ed25519PublicKey(seed))
        }

        /**
         * Vanity keygen: regenerate until the public key starts with
         * [hexPrefix]. [maxAttempts] bounds the search (an 8-hex-char
         * prefix would take ~2^32 tries — keep prefixes short).
         */
        fun generateWithPrefix(
            crypto: CryptoProvider,
            hexPrefix: String,
            maxAttempts: Int = 1_000_000,
        ): MeshIdentity? {
            val prefix = hexPrefix.lowercase()
            require(isHexDigits(prefix)) { "prefix must be hex" }
            repeat(maxAttempts) {
                val id = generate(crypto)
                if (id.publicKeyHex.startsWith(prefix)) return id
            }
            return null
        }
    }
}
