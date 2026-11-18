package io.github.thatsfguy.meshcore.crypto

import io.github.thatsfguy.meshcore.platform.IosCryptoProvider
import io.github.thatsfguy.meshcore.protocol.ConfigBackup
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
import io.github.thatsfguy.meshcore.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CryptoKit AES-GCM bridge (iOS Phase 3, for encrypted backups).
 *
 * Pinned to a published vector — NIST GCM spec Test Case 16, AES-256
 * with AAD, checked against an independent implementation before it was
 * written here — not just to a round trip, which a bridge that returned
 * its input unchanged would pass.
 */
class IosAesGcmTest {
    private val crypto = IosCryptoProvider()

    private fun hex(s: String) = hexToBytesOrNull(s)!!

    private val key = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
    private val iv = hex("cafebabefacedbaddecaf888")
    private val plain = hex(
        "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
            "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
    )
    private val aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
    private val sealed =
        "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
            "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
            "76fc6ece0f4e1768cddf8853bb2d551b"

    @Test
    fun sealMatchesTheNistVector() {
        assertTrue(crypto.supportsAuthenticatedEncryption)
        assertEquals(sealed, crypto.aesGcmSeal(key, iv, plain, aad).toHex())
    }

    @Test
    fun openRecoversTheNistPlaintext() {
        assertContentEquals(plain, crypto.aesGcmOpen(key, iv, hex(sealed), aad))
    }

    @Test
    fun anyTamperingIsRefused() {
        val bad = hex(sealed).also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertNull(crypto.aesGcmOpen(key, iv, bad, aad), "flipped ciphertext bit")
        val badTag = hex(sealed).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(crypto.aesGcmOpen(key, iv, badTag, aad), "flipped tag bit")
        assertNull(crypto.aesGcmOpen(key, iv, hex(sealed), aad.copyOf(aad.size - 1)), "different AAD")
        assertNull(crypto.aesGcmOpen(key.copyOf().also { it[0] = 0 }, iv, hex(sealed), aad), "wrong key")
        assertNull(crypto.aesGcmOpen(key, iv, ByteArray(15), aad), "shorter than a tag")
    }

    @Test
    fun emptyPlaintextAndEmptyAadRoundTrip() {
        // The zero-length pin path: Kotlin/Native cannot pin an empty array.
        val out = crypto.aesGcmSeal(key, iv, ByteArray(0), ByteArray(0))
        assertEquals(16, out.size)
        assertContentEquals(ByteArray(0), crypto.aesGcmOpen(key, iv, out, ByteArray(0)))
    }

    @Test
    fun badSizesFailClosed() {
        assertFailsWith<IllegalArgumentException> { crypto.aesGcmSeal(ByteArray(16), iv, plain, aad) }
        assertNull(crypto.aesGcmOpen(key, ByteArray(8), hex(sealed), aad))
    }

    @Test
    fun anEncryptedBackupOpensWithItsPassphraseOnlyOnIos() {
        val secret = ConfigBackup.BackupSecret("psk:1", "8b3387e9c5cdea6ac9e5edbaa115cd72")
        val text = ConfigBackup.encode(
            crypto,
            ConfigBackup.Plain(createdAt = 1, selfKeyHex = "aa".repeat(32)),
            listOf(secret),
            "correct horse battery",
        )
        val parsed = assertNotNull(ConfigBackup.decode(text))
        val section = assertNotNull(parsed.sealed)
        assertEquals(listOf(secret), ConfigBackup.openSecrets(crypto, section, "correct horse battery"))
        assertNull(ConfigBackup.openSecrets(crypto, section, "wrong horse battery"))
    }
}
