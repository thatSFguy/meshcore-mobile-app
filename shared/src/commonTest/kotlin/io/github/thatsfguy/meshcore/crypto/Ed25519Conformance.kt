package io.github.thatsfguy.meshcore.crypto

import io.github.thatsfguy.meshcore.util.hexToBytes
import io.github.thatsfguy.meshcore.util.toHex
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC 8032 Ed25519 vectors, run against whichever [CryptoProvider] a
 * platform supplies.
 *
 * Why this exists: iOS CI going green proves the CryptoKit bridge
 * COMPILES and LINKS. It says nothing about whether the bytes coming
 * out of it are right — and "the signature verifies" is the entire
 * basis on which this app decides to trust an advert and import a
 * contact. A bridge that links and returns garbage would fail exactly
 * one way: every advert on the mesh would look forged, iOS would
 * silently import nobody, and the logs would say nothing at all.
 *
 * These are published vectors from outside this codebase, which is the
 * point (REBUILD-PLAYBOOK §7.1): a test written against our own output
 * would agree with our own bug. Both platforms run the same file, so
 * Android and iOS cannot drift from each other either.
 *
 * Vectors: RFC 8032 §7.1, TEST 1 / TEST 2 / TEST 3.
 */
object Ed25519Conformance {

    private data class Vector(
        val name: String,
        val seed: String,
        val publicKey: String,
        val message: String,
        val signature: String,
    )

    /**
     * TEST 1's message is EMPTY, and `hexToBytes` rejects an empty
     * string on purpose — an empty QR payload is invalid input, which
     * is the right call for its actual callers. So the empty case is
     * handled here rather than by loosening a parser that guards
     * attacker-supplied text.
     */
    private fun bytes(hex: String): ByteArray =
        if (hex.isEmpty()) ByteArray(0) else hexToBytes(hex)

    private val VECTORS = listOf(
        Vector(
            name = "RFC 8032 TEST 1 (empty message)",
            seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
            publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            message = "",
            signature = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555" +
                "fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ),
        Vector(
            name = "RFC 8032 TEST 2 (one byte)",
            seed = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
            publicKey = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            message = "72",
            signature = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        ),
        Vector(
            name = "RFC 8032 TEST 3 (two bytes)",
            seed = "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
            publicKey = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
            message = "af82",
            signature = "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
                "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        ),
    )

    /** Derive the published public key from the published seed. */
    fun publicKeysMatchTheVectors(crypto: CryptoProvider) {
        for (v in VECTORS) {
            assertEquals(
                v.publicKey,
                crypto.ed25519PublicKey(hexToBytes(v.seed)).toHex(),
                "${v.name}: derived public key",
            )
        }
    }

    /**
     * A signature we produce verifies under the PUBLISHED public key.
     *
     * This is the property that actually matters for interoperability:
     * every other node on the mesh checks our adverts with standard
     * Ed25519 verification, and a valid signature is a valid signature
     * whether or not it matches the RFC's example bytes.
     *
     * Kept separate from [signaturesAreRfcDeterministic] because the two
     * platforms genuinely differ — see that function.
     */
    fun signaturesVerifyUnderThePublishedKey(crypto: CryptoProvider) {
        for (v in VECTORS) {
            val produced = crypto.ed25519Sign(bytes(v.message), hexToBytes(v.seed))
            assertEquals(64, produced.size, "${v.name}: signature length")
            assertTrue(
                crypto.ed25519Verify(produced, bytes(v.message), hexToBytes(v.publicKey)),
                "${v.name}: our own signature does not verify under the published key",
            )
        }
    }

    /**
     * Produce the published signature byte for byte.
     *
     * RFC 8032 Ed25519 derives its nonce from the message and the key,
     * so a conforming implementation is deterministic and this equality
     * is checkable. Bouncy Castle is such an implementation.
     *
     * ⚠ CryptoKit is NOT, and this is asserted on Android only. Apple's
     * Curve25519 signing is hedged — it mixes in randomness — so it
     * emits a DIFFERENT valid signature each time. That is permitted:
     * verification is unaffected, and the mesh only ever verifies. It
     * does mean this particular check cannot be a cross-platform one,
     * and discovering that is why the test was written against
     * published vectors instead of against our own output.
     */
    fun signaturesAreRfcDeterministic(crypto: CryptoProvider) {
        for (v in VECTORS) {
            assertEquals(
                v.signature,
                crypto.ed25519Sign(bytes(v.message), hexToBytes(v.seed)).toHex(),
                "${v.name}: signature",
            )
        }
    }

    fun verifyAcceptsTheVectors(crypto: CryptoProvider) {
        for (v in VECTORS) {
            assertTrue(
                crypto.ed25519Verify(
                    hexToBytes(v.signature),
                    bytes(v.message),
                    hexToBytes(v.publicKey),
                ),
                "${v.name}: valid signature rejected",
            )
        }
    }

    /**
     * The half that actually protects anyone.
     *
     * A verify that returns true for everything would pass every test
     * above. This app imports contacts on the strength of a signature,
     * so the rejections are the security property.
     */
    fun verifyRejectsTampering(crypto: CryptoProvider) {
        val v = VECTORS[2]
        val sig = hexToBytes(v.signature)
        val msg = bytes(v.message)
        val pub = hexToBytes(v.publicKey)

        val flippedSig = sig.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(crypto.ed25519Verify(flippedSig, msg, pub), "accepted a corrupted signature")

        val flippedTail = sig.copyOf().also { it[63] = (it[63].toInt() xor 0x01).toByte() }
        assertFalse(crypto.ed25519Verify(flippedTail, msg, pub), "accepted a corrupted signature tail")

        val tamperedMsg = msg.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(crypto.ed25519Verify(sig, tamperedMsg, pub), "accepted a tampered message")

        val wrongPub = hexToBytes(VECTORS[1].publicKey)
        assertFalse(crypto.ed25519Verify(sig, msg, wrongPub), "accepted the wrong signer")

        // An advert off the mesh can be any length at all. These must
        // decline rather than read past the end of a buffer — on iOS the
        // bridge hands fixed-size pointers to CryptoKit.
        assertFalse(crypto.ed25519Verify(ByteArray(0), msg, pub), "accepted an empty signature")
        assertFalse(crypto.ed25519Verify(sig.copyOf(63), msg, pub), "accepted a short signature")
        assertFalse(crypto.ed25519Verify(sig, msg, ByteArray(0)), "accepted an empty public key")
        assertFalse(crypto.ed25519Verify(sig, msg, pub.copyOf(31)), "accepted a short public key")
        assertFalse(
            crypto.ed25519Verify(sig, msg, ByteArray(32)),
            "accepted an all-zero public key",
        )
    }

    /** Everything every platform must satisfy. */
    fun runAll(crypto: CryptoProvider) {
        publicKeysMatchTheVectors(crypto)
        signaturesVerifyUnderThePublishedKey(crypto)
        verifyAcceptsTheVectors(crypto)
        verifyRejectsTampering(crypto)
    }
}
