package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.util.MAX_DISPLAY_NAME_BYTES
import io.github.thatsfguy.meshcore.util.sanitizeDisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regressions for the 2026-07-31 security review findings — see
 * SECURITY_REVIEW.md. Each test pins a specific fix so the hardening
 * can't silently regress.
 */
class HardeningTest {

    // Spoofing primitives, written as escapes so the source stays plain ASCII.
    private val RLO = "\u202E"        // right-to-left override
    private val LRM = "\u200E"
    private val RLM = "\u200F"
    private val ISOLATE = "\u2066"
    private val POP_ISOLATE = "\u2069"

    @Test
    fun displayNamesStripControlAndBidiSpoofingChars() {
        // Newlines would let one name occupy two list rows.
        assertEquals("AliceBob", sanitizeDisplayName("Alice\nBob"))
        assertEquals("AB", sanitizeDisplayName("AB"))
        // RTL override — the classic display-spoofing primitive.
        assertEquals("gnp.evil", sanitizeDisplayName(RLO + "gnp.evil"))
        assertEquals("ab", sanitizeDisplayName("a" + LRM + RLM + ISOLATE + POP_ISOLATE + "b"))
        // Legitimate names (accents, emoji) survive untouched.
        assertEquals("Kaylee K8KAY", sanitizeDisplayName("Kaylee K8KAY"))
        // Bounded in BYTES, not characters — the firmware's field is a
        // 32-byte C string, so 31 bytes are usable. It used to cap at
        // "32 characters", which is a different and larger limit for
        // every name that isn't ASCII: a 32-character emoji name passed
        // here and was silently cut again by ShareUri's 31-byte rule.
        assertEquals(31, sanitizeDisplayName("x".repeat(200)).length)
        assertEquals(
            MAX_DISPLAY_NAME_BYTES,
            sanitizeDisplayName("x".repeat(200)).encodeToByteArray().size,
        )
    }

    @Test
    fun aLongMultiByteNameIsCutOnACharacterBoundary() {
        // The bound is bytes, so the cut must not land inside a
        // sequence: 15 four-byte emoji are 60 bytes, and 31 bytes is
        // seven of them plus three bytes of an eighth.
        val name = "😀".repeat(15)
        val cut = sanitizeDisplayName(name)
        assertTrue(cut.encodeToByteArray().size <= MAX_DISPLAY_NAME_BYTES)
        assertEquals("😀".repeat(7), cut)
        // Round-trips: a cut through a code point would not.
        assertEquals(cut, cut.encodeToByteArray().decodeToString())
    }

    @Test
    fun contactNamesAreSanitizedAtParseTime() {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_CONTACT)
        w.writeBytes(ByteArray(32) { (it + 1).toByte() })
        w.writeByte(Codes.ADV_TYPE_CHAT); w.writeByte(0); w.writeByte(0)
        w.writeBytesPadded(ByteArray(0), 64)
        w.writeFixedCString("Ev" + RLO + "il", 32)
        w.writeUInt32LE(1L); w.writeInt32LE(0); w.writeInt32LE(0); w.writeUInt32LE(1L)
        val c = ResponseParser.parseContact(w.toBytes())!!
        assertFalse(c.name.contains(RLO))
        assertEquals("Evil", c.name)
    }

    @Test
    fun channelSenderNamesAreSanitized() {
        val (sender, body) = ResponseParser.splitSenderText("ev" + RLO + "il: hello")
        assertFalse(sender.contains(RLO))
        assertEquals("hello", body)
    }

    @Test
    fun advertWithTruncatedLocationIsRejectedNotMisread() {
        // has_location set but fewer than 8 bytes left: the old code fell
        // through and read the coordinate bytes as the node name.
        val payload = ByteArray(100) + byteArrayOf(0x10, 1, 2, 3, 4)
        assertNull(Advert.parse(payload))
    }

    @Test
    fun channelDecryptRejectsWrongSizedPsk() {
        // Short PSKs used to be silently zero-padded into a weak key.
        val blob = ByteArray(2 + 16)
        assertNull(ChannelCrypto.decrypt(StubCrypto, ByteArray(8), blob))
        assertNull(ChannelCrypto.decrypt(StubCrypto, ByteArray(32), blob))
    }

    @Test
    fun bufferReaderTreatsNegativeAndHugeReadsAsTruncation() {
        val r = BufferReader(ByteArray(4))
        for (bad in listOf(-1, -1000, Int.MIN_VALUE, 5, Int.MAX_VALUE)) {
            var threw = false
            try {
                r.readBytes(bad)
            } catch (_: TruncatedFrameException) {
                threw = true
            }
            assertTrue(threw, "readBytes($bad) must raise TruncatedFrameException")
        }
    }

    /** Minimal CryptoProvider — these tests never exercise real crypto. */
    private object StubCrypto : CryptoProvider {
        override fun sha256(data: ByteArray) = ByteArray(32)
        override fun sha512(data: ByteArray) = ByteArray(64)
        override fun hmacSha256(key: ByteArray, data: ByteArray) = ByteArray(32)
        override fun aesEcbDecrypt(key16: ByteArray, ciphertext: ByteArray) = ciphertext
        override fun aesEcbEncrypt(key16: ByteArray, plaintext: ByteArray) = plaintext
        override fun generateEd25519Seed() = ByteArray(32)
        override fun ed25519PublicKey(seed: ByteArray) = ByteArray(32)
        override fun ed25519Sign(message: ByteArray, seed: ByteArray) = ByteArray(64)
        override fun ed25519Verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray) = false
        override fun randomBytes(length: Int) = ByteArray(length)
        override fun aesGcmSeal(
            key32: ByteArray,
            nonce12: ByteArray,
            plaintext: ByteArray,
            aad: ByteArray,
        ) = plaintext
        override fun aesGcmOpen(
            key32: ByteArray,
            nonce12: ByteArray,
            ciphertextAndTag: ByteArray,
            aad: ByteArray,
        ) = ciphertextAndTag
    }
}
