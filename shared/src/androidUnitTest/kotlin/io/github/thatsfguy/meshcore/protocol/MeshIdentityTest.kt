package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MeshIdentityTest {

    private val crypto = AndroidCryptoProvider()

    @Test
    fun expandedKeyIsClampedSha512OfSeed() {
        val id = MeshIdentity.generate(crypto)
        val expanded = id.expandedPrivateKey(crypto)
        assertEquals(64, expanded.size)

        val raw = crypto.sha512(id.seed)
        // Clamping (required by Ed25519 and MeshCore repeater key checks):
        assertEquals(0, expanded[0].toInt() and 0x07)              // low 3 bits cleared
        assertEquals(0x40, expanded[31].toInt() and 0xC0)          // bit 254 set, bit 255 clear
        // Bytes 1..30 and 32..63 pass through unchanged.
        assertContentEquals(raw.copyOfRange(1, 31), expanded.copyOfRange(1, 31))
        assertContentEquals(raw.copyOfRange(32, 64), expanded.copyOfRange(32, 64))
    }

    @Test
    fun seedSignsVerifiably() {
        val id = MeshIdentity.generate(crypto)
        val msg = "meshcore".encodeToByteArray()
        val sig = crypto.ed25519Sign(msg, id.seed)
        assertEquals(64, sig.size)
        assertTrue(crypto.ed25519Verify(sig, msg, id.publicKey))
        assertTrue(!crypto.ed25519Verify(sig, "other".encodeToByteArray(), id.publicKey))
    }

    @Test
    fun fromSeedIsDeterministic() {
        val seed = ByteArray(32) { it.toByte() }
        val a = MeshIdentity.fromSeed(crypto, seed)
        val b = MeshIdentity.fromSeed(crypto, seed)
        assertContentEquals(a.publicKey, b.publicKey)
        assertEquals(64, a.publicKeyHex.length)
    }

    @Test
    fun vanityPrefixSearch() {
        // Single hex char = expected ~16 attempts; bound generously.
        val id = MeshIdentity.generateWithPrefix(crypto, "a", maxAttempts = 10_000)
        assertNotNull(id)
        assertTrue(id.publicKeyHex.startsWith("a"))
    }
}
