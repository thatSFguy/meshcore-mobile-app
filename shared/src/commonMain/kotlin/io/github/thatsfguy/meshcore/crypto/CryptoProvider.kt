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

    /**
     * False on platforms where [aesGcmSeal] isn't bridged yet (iOS
     * Phase 1). Anything that would write an encrypted file must check
     * this and refuse rather than degrade — an "encrypted" backup that
     * isn't is worse than no backup.
     */
    val supportsAuthenticatedEncryption: Boolean get() = true

    /**
     * AES-256-GCM seal: returns `ciphertext || tag` (16-byte tag), with
     * [aad] authenticated but not encrypted.
     *
     * Unlike [aesEcbEncrypt] — which exists only because the MeshCore
     * channel format mandates ECB — this is the app's own storage
     * crypto and is authenticated. [nonce] must be 12 bytes and MUST
     * NOT repeat under the same [key32].
     */
    fun aesGcmSeal(
        key32: ByteArray,
        nonce12: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray

    /**
     * AES-256-GCM open. Returns null when the tag doesn't verify — a
     * wrong passphrase and a tampered file are the same answer here, and
     * neither is an exception the caller should have to catch.
     */
    fun aesGcmOpen(
        key32: ByteArray,
        nonce12: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray?
}

/**
 * PBKDF2-HMAC-SHA256 (RFC 8018 §5.2), built on [CryptoProvider.hmacSha256]
 * so it is one implementation on every platform and testable without a
 * device.
 *
 * Used to turn a human passphrase into a key for the encrypted section
 * of a config backup. The iteration count is the only thing standing
 * between a stolen backup file and its secrets, so callers pick it high
 * (see ConfigBackup.KDF_ITERATIONS) and the UI is honest that a weak
 * passphrase is the weak link regardless.
 */
fun pbkdf2HmacSha256(
    crypto: CryptoProvider,
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keyLength: Int,
): ByteArray {
    // RFC 8018 permits an empty password; JCE's SecretKeySpec does not,
    // and would surface it as an opaque "Empty key" from deep inside
    // HMAC. Refuse it here so the failure names the actual problem — no
    // caller of this has a legitimate empty passphrase.
    require(password.isNotEmpty()) { "password must not be empty" }
    require(iterations > 0) { "iterations must be positive" }
    require(keyLength > 0) { "keyLength must be positive" }
    val out = ByteArray(keyLength)
    var offset = 0
    var block = 1
    while (offset < keyLength) {
        // U1 = PRF(password, salt || INT_32_BE(block))
        val blockIndex = byteArrayOf(
            (block ushr 24).toByte(), (block ushr 16).toByte(),
            (block ushr 8).toByte(), block.toByte(),
        )
        var u = crypto.hmacSha256(password, salt + blockIndex)
        val t = u.copyOf()
        for (i in 2..iterations) {
            u = crypto.hmacSha256(password, u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        val take = minOf(t.size, keyLength - offset)
        t.copyInto(out, offset, 0, take)
        offset += take
        block++
    }
    return out
}
