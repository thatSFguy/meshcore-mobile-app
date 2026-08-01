package io.github.thatsfguy.meshcore.crypto

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import io.github.thatsfguy.meshcore.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PBKDF2-HMAC-SHA256 and AES-256-GCM — the two primitives holding up an
 * encrypted config backup.
 *
 * The KDF vectors are pinned to values produced by an independent
 * implementation (CPython's `hashlib.pbkdf2_hmac`), not to numbers
 * recalled from a spec. That is the whole point of a vector: it catches
 * an off-by-one in the block-index encoding or the XOR loop, which
 * would otherwise produce a perfectly stable, perfectly wrong key.
 */
class Pbkdf2Test {

    private val crypto = AndroidCryptoProvider()

    private fun dk(password: String, salt: String, iterations: Int, length: Int = 32): String =
        pbkdf2HmacSha256(
            crypto, password.encodeToByteArray(), salt.encodeToByteArray(), iterations, length,
        ).toHex()

    @Test
    fun matchesIndependentlyGeneratedVectors() {
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            dk("password", "salt", 1),
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            dk("password", "salt", 2),
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            dk("password", "salt", 4096),
        )
    }

    @Test
    fun spansMultipleBlocksCorrectly() {
        // 40 bytes needs two PRF blocks, which exercises the block-index
        // counter — the classic place to be off by one.
        assertEquals(
            "348c89dbcbd32b2f32d814b8116e84cf2b17347ebc1800181c4e2a1fb8dd53e1c635518c7dac47e9",
            dk(
                "passwordPASSWORDpassword",
                "saltSALTsaltSALTsaltSALTsaltSALTsalt",
                4096,
                length = 40,
            ),
        )
        // …and the first 32 bytes of that are exactly the 32-byte answer.
        assertTrue(
            dk("passwordPASSWORDpassword", "saltSALTsaltSALTsaltSALTsaltSALTsalt", 4096, 40)
                .startsWith(
                    dk("passwordPASSWORDpassword", "saltSALTsaltSALTsaltSALTsaltSALTsalt", 4096, 32),
                ),
        )
    }

    @Test
    fun everyInputChangesTheKey() {
        val base = dk("password", "salt", 100)
        assertNotEquals(base, dk("passwore", "salt", 100))
        assertNotEquals(base, dk("password", "salu", 100))
        assertNotEquals(base, dk("password", "salt", 101))
    }

    @Test
    fun refusesNonsenseParameters() {
        assertFailsWith<IllegalArgumentException> { dk("p", "s", 0) }
        assertFailsWith<IllegalArgumentException> { dk("p", "s", -1) }
        assertFailsWith<IllegalArgumentException> { dk("p", "s", 1, length = 0) }
    }

    @Test
    fun refusesAnEmptyPasswordByName() {
        // RFC 8018 allows it; JCE does not, and would surface it as an
        // opaque "Empty key" from inside HMAC. No caller here has a
        // legitimate empty passphrase, so it fails with a message that
        // says which argument was wrong.
        val e = assertFailsWith<IllegalArgumentException> { dk("", "salt", 1) }
        assertTrue("password" in e.message!!, "unhelpful message: ${e.message}")
    }

    @Test
    fun anEmptySaltIsStillFine() {
        // Not something we do, but it is legal and must not throw.
        assertEquals(64, dk("password", "", 1).length)
    }

    // ------------------------------------------------------------------
    // AES-256-GCM
    // ------------------------------------------------------------------

    @Test
    fun gcmRoundTrips() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val msg = "channel psk material".encodeToByteArray()
        val aad = "header v1".encodeToByteArray()

        val sealed = crypto.aesGcmSeal(key, nonce, msg, aad)
        // Ciphertext carries a 16-byte tag and never equals the plaintext.
        assertEquals(msg.size + 16, sealed.size)
        assertEquals(msg.toList(), crypto.aesGcmOpen(key, nonce, sealed, aad)!!.toList())
    }

    @Test
    fun gcmFailsClosedOnEveryWrongInput() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val aad = "header v1".encodeToByteArray()
        val sealed = crypto.aesGcmSeal(key, nonce, "secret".encodeToByteArray(), aad)

        // Wrong key (a wrong passphrase, after the KDF).
        assertNull(crypto.aesGcmOpen(ByteArray(32) { 9 }, nonce, sealed, aad))
        // Wrong nonce.
        assertNull(crypto.aesGcmOpen(key, ByteArray(12) { 9 }, sealed, aad))
        // Tampered AAD — this is the downgrade attack: edit the KDF
        // header in the file and the tag must stop verifying.
        assertNull(crypto.aesGcmOpen(key, nonce, sealed, "header v0".encodeToByteArray()))
        // Tampered ciphertext, at every byte position.
        for (i in sealed.indices) {
            val bad = sealed.copyOf()
            bad[i] = (bad[i].toInt() xor 0x01).toByte()
            assertNull(crypto.aesGcmOpen(key, nonce, bad, aad), "byte $i survived tampering")
        }
        // Truncated.
        assertNull(crypto.aesGcmOpen(key, nonce, sealed.copyOfRange(0, sealed.size - 1), aad))
        assertNull(crypto.aesGcmOpen(key, nonce, ByteArray(0), aad))
    }

    @Test
    fun gcmRefusesWrongSizedKeysAndNonces() {
        assertFailsWith<IllegalArgumentException> {
            crypto.aesGcmSeal(ByteArray(16), ByteArray(12), ByteArray(4))
        }
        assertFailsWith<IllegalArgumentException> {
            crypto.aesGcmSeal(ByteArray(32), ByteArray(16), ByteArray(4))
        }
        assertNull(crypto.aesGcmOpen(ByteArray(16), ByteArray(12), ByteArray(32)))
    }

    @Test
    fun androidReportsAuthenticatedEncryptionAvailable() {
        // The backup UI gates on this; iOS Phase 1 returns false.
        assertTrue(crypto.supportsAuthenticatedEncryption)
    }
}
