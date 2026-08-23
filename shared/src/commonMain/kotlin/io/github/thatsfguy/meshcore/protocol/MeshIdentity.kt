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
    fun expandedPrivateKey(crypto: CryptoProvider): ByteArray = expandSeed(crypto, seed)

    override fun equals(other: Any?): Boolean =
        other is MeshIdentity && seed.contentEquals(other.seed) &&
            publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int = publicKey.contentHashCode()

    companion object {

        /**
         * `SHA512(seed)` with standard Ed25519 clamping — the 64-byte
         * `PRV_KEY_SIZE` form `set prv.key` reads, and the form the
         * firmware itself stores (`ed25519_create_keypair`, called from
         * `LocalIdentity::LocalIdentity(RNG*)`, `src/Identity.cpp:61-65`).
         *
         * The layout is `[clamped scalar (32) || nonce prefix (32)]`, and
         * the firmware derives the public key from the scalar half alone
         * (`ed25519_derive_pub`, `src/Identity.cpp:69`). Pinned by the
         * firmware's own known-good keypair in
         * `MeshIdentityTest.theFirmwaresOwnKeypairRoundTrips`.
         */
        fun expandSeed(crypto: CryptoProvider, seed: ByteArray): ByteArray {
            require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
            val h = crypto.sha512(seed)
            h[0] = (h[0].toInt() and 248).toByte()
            h[31] = ((h[31].toInt() and 63) or 64).toByte()
            return h
        }

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
         *
         * Keys the firmware would refuse are skipped, so asking for a
         * prefix of "00" or "ff" returns null however long it searches —
         * `validatePrivateKey` rejects those outright
         * (`src/Identity.cpp:71-72`, and [IdentityKey.isAcceptablePublicKey]).
         * Returning null is the honest answer: such a key exists, and no
         * MeshCore node will take it.
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
                if (id.publicKeyHex.startsWith(prefix) &&
                    IdentityKey.isAcceptablePublicKey(id.publicKeyHex)
                ) {
                    return id
                }
            }
            return null
        }
    }
}
