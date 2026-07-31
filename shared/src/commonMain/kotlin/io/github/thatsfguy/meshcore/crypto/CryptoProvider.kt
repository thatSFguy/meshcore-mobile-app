package io.github.thatsfguy.meshcore.crypto

/**
 * Platform-independent crypto surface for the MeshCore protocol layer.
 * Implemented per-platform (Android: JCA + Bouncy Castle; iOS: planned
 * CryptoKit/CommonCrypto bridge, currently Phase-1 stubs).
 *
 * Ed25519 private keys are handled as 32-byte *seeds*. MeshCore's
 * expanded 64-byte private key (SHA512(seed), clamped) derives
 * deterministically from the seed — see protocol/MeshIdentity.kt — so
 * standard seed-based sign/verify produces identical results.
 */
interface CryptoProvider {

    fun sha256(data: ByteArray): ByteArray

    fun sha512(data: ByteArray): ByteArray

    /** HMAC-SHA256, full 32-byte MAC. */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /**
     * AES-128-ECB decrypt (no padding). [ciphertext] length must be a
     * multiple of 16. Used ONLY for MeshCore channel payloads — ECB is a
     * protocol-mandated weakness, not a choice (MESHCORE_PROTOCOL.md §10).
     */
    fun aesEcbDecrypt(key16: ByteArray, ciphertext: ByteArray): ByteArray

    /** AES-128-ECB encrypt (no padding); test-vector construction only. */
    fun aesEcbEncrypt(key16: ByteArray, plaintext: ByteArray): ByteArray

    /** Generate a 32-byte Ed25519 seed from a CSPRNG. */
    fun generateEd25519Seed(): ByteArray

    /** Derive the 32-byte Ed25519 public key from a seed. */
    fun ed25519PublicKey(seed: ByteArray): ByteArray

    /** Ed25519 sign with a seed; returns a 64-byte signature. */
    fun ed25519Sign(message: ByteArray, seed: ByteArray): ByteArray

    /** Ed25519 verify; false (never a throw) on any malformed input. */
    fun ed25519Verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean

    /** [length] bytes of cryptographically secure random data. */
    fun randomBytes(length: Int): ByteArray
}
