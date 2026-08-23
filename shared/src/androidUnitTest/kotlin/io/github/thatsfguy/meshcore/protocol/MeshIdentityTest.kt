package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Test
    fun aVanityPrefixTheFirmwareRefusesIsNeverReturned() {
        // `validatePrivateKey` rejects public keys beginning 00 or ff
        // outright (src/Identity.cpp:71-72). Such keys exist and are
        // easy to find, so a search that ignored the rule would return
        // one within a few hundred attempts and the node would then
        // refuse it with "Error, bad key". Null is the honest answer.
        assertNull(MeshIdentity.generateWithPrefix(crypto, "00", maxAttempts = 2_000))
        assertNull(MeshIdentity.generateWithPrefix(crypto, "ff", maxAttempts = 2_000))
    }

    /**
     * Ground truth, out of the firmware rather than out of this repo.
     *
     * `LocalIdentity::validatePrivateKey` (`src/Identity.cpp:67-90`)
     * carries a known-good keypair as a self-check: 64 bytes of private
     * key and the 32-byte public key it must produce. That pair is the
     * only published statement of what MeshCore's `PRV_KEY_SIZE` bytes
     * *mean*, and it says two things this codebase depends on — the
     * first 32 bytes are a clamped Ed25519 scalar, and the public key is
     * that scalar times the base point.
     *
     * Checked against [Ed25519Reference], which is deliberately not the
     * code under test: everything else here derives public keys through
     * Bouncy Castle's seed-based API, which cannot disagree with itself.
     */
    @Test
    fun theFirmwaresOwnKeypairDerivesItsPublishedPublicKey() {
        assertContentEquals(
            FIRMWARE_TEST_CLIENT_PUB,
            Ed25519Reference.publicKeyFromScalar(FIRMWARE_TEST_CLIENT_PRV.copyOfRange(0, 32)),
        )
    }

    @Test
    fun theFirmwaresOwnKeyIsClampedExactlyAsExpandSeedClampsIt() {
        // The same two edits MeshIdentity.expandSeed makes, present in a
        // key the firmware ships and validates against.
        val scalar = FIRMWARE_TEST_CLIENT_PRV
        assertEquals(0, scalar[0].toInt() and 0x07)
        assertEquals(0x40, scalar[31].toInt() and 0xC0)
    }

    /**
     * The join between the two halves of the app's key handling: the
     * 64-byte key we SEND and the public key we SHOW have to be the same
     * identity, or the preview is a lie and the avoid-list is built
     * against a node that will never exist.
     *
     * `expandSeed` is ours and `ed25519PublicKey` is Bouncy Castle's;
     * [Ed25519Reference] is the third, independent thing that says they
     * agree.
     */
    @Test
    fun theExpandedKeyAndTheSeedNameTheSameNode() {
        repeat(5) {
            val id = MeshIdentity.generate(crypto)
            val expanded = MeshIdentity.expandSeed(crypto, id.seed)
            assertContentEquals(
                id.publicKey,
                Ed25519Reference.publicKeyFromScalar(expanded.copyOfRange(0, 32)),
                "expanded key names a different node than its seed",
            )
        }
    }

    private companion object {
        /** `test_client_prv`, `src/Identity.cpp:75-84`. */
        val FIRMWARE_TEST_CLIENT_PRV = byteArrayOf(
            0x70, 0x65, 0xe1.toByte(), 0x8f.toByte(), 0xd9.toByte(), 0xfa.toByte(),
            0xbb.toByte(), 0x70, 0xc1.toByte(), 0xed.toByte(), 0x90.toByte(), 0xdc.toByte(),
            0xa1.toByte(), 0x99.toByte(), 0x07, 0xde.toByte(),
            0x69, 0x8c.toByte(), 0x88.toByte(), 0xb7.toByte(), 0x09, 0xea.toByte(), 0x14,
            0x6e, 0xaf.toByte(), 0xd9.toByte(), 0x3d, 0x9b.toByte(), 0x83.toByte(), 0x0c,
            0x7b, 0x60,
            0xc4.toByte(), 0x68, 0x11, 0x93.toByte(), 0xc7.toByte(), 0x9b.toByte(),
            0xbc.toByte(), 0x39, 0x94.toByte(), 0x5b, 0xa8.toByte(), 0x06, 0x41, 0x04,
            0xbb.toByte(), 0x61,
            0x8f.toByte(), 0x8f.toByte(), 0xd7.toByte(), 0xa8.toByte(), 0x4a, 0x0a,
            0xf6.toByte(), 0xf5.toByte(), 0x70, 0x33, 0xd6.toByte(), 0xe8.toByte(),
            0xdd.toByte(), 0xcd.toByte(), 0x64, 0x71,
        )

        /** `test_client_pub`, `src/Identity.cpp:85-90`. */
        val FIRMWARE_TEST_CLIENT_PUB = byteArrayOf(
            0x1e, 0xc7.toByte(), 0x71, 0x75, 0xb0.toByte(), 0x91.toByte(), 0x8e.toByte(),
            0xd2.toByte(), 0x06, 0xf9.toByte(), 0xae.toByte(), 0x04, 0xec.toByte(), 0x13,
            0x6d, 0x6d,
            0x5d, 0x43, 0x15, 0xbb.toByte(), 0x26, 0x30, 0x54, 0x27, 0xf6.toByte(), 0x45,
            0xb4.toByte(), 0x92.toByte(), 0xe9.toByte(), 0x35, 0x0c, 0x10,
        )
    }
}
