package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Reading MeshCore One's reactions.
 *
 * The file this tests exists because reactions have no protocol: every
 * client invents a text format, and a client's format is one author's
 * decision, so the only thing that makes this work is emitting and
 * reading the same bytes they do.
 *
 * **The test that carries this file is [theCapturedReactionResolves].**
 * It is not an example from their document — it is a reaction that
 * actually arrived on the Public channel on 2026-08-23, with the target
 * message's real text and real timestamp read off the message info
 * sheet. CLAUDE.md's rule, earned by the `path_len` bug: pin the real
 * captured value, not just a property. A property test would have
 * passed against a hash function with the endianness backwards.
 */
class MeshCoreOneReactionsTest {

    private val crypto = AndroidCryptoProvider()
    private val sha256: (ByteArray) -> ByteArray = { crypto.sha256(it) }

    // Captured 2026-08-23 on the Public channel around Grand Rapids.
    private val capturedWire = "😂@[KE8PKP_TACW]\nb0c26wb5"
    private val capturedTargetText = "Alexa, find me the nearest Crab Rangoon"
    private val capturedTargetTimestamp = 1787534834L
    private val capturedTargetSender = "KE8PKP_TACW"

    @Test
    fun theCapturedReactionResolves() {
        val parsed = assertNotNull(MeshCoreOneReactions.parse(capturedWire))
        assertEquals("b0c26wb5", parsed.targetHash)
        assertEquals(capturedTargetSender, parsed.targetSenderName)
        assertEquals("😂", parsed.emoji)

        // The whole point: their hash, recomputed here, over the real
        // message it pointed at.
        assertEquals(
            "b0c26wb5",
            MeshCoreOneReactions.targetHash(
                capturedTargetText,
                capturedTargetTimestamp,
                sha256,
            ),
        )
    }

    @Test
    fun `the timestamp goes in little-endian`() {
        // The one byte-order decision in the format, and the one that a
        // property test cannot see: a big-endian stamp still produces
        // eight plausible characters.
        val little = MeshCoreOneReactions.targetHash(capturedTargetText, capturedTargetTimestamp, sha256)
        val ts = capturedTargetTimestamp.toInt()
        val bigEndian = sha256(
            capturedTargetText.encodeToByteArray() + byteArrayOf(
                ((ts shr 24) and 0xFF).toByte(),
                ((ts shr 16) and 0xFF).toByte(),
                ((ts shr 8) and 0xFF).toByte(),
                (ts and 0xFF).toByte(),
            ),
        ).copyOfRange(0, 5).let { CrockfordBase32.encode(it) }
        assertEquals("b0c26wb5", little)
        assertEquals(HASH_CHARS, bigEndian.length)
        kotlin.test.assertNotEquals(little, bigEndian)
    }

    @Test
    fun `a direct-message reaction carries no sender name`() {
        val parsed = assertNotNull(MeshCoreOneReactions.parse("👍\nb45pc4ek"))
        assertNull(parsed.targetSenderName)
        assertEquals("b45pc4ek", parsed.targetHash)
    }

    @Test
    fun `an ordinary message that mentions somebody is not a reaction`() {
        // The failure that matters: a false positive HIDES what somebody
        // typed. "@[Name]" is the ecosystem's mention syntax and appears
        // in ordinary sentences.
        assertNull(MeshCoreOneReactions.parse("hey@[Blue Base]\nwhat app are you using?"))
        assertNull(MeshCoreOneReactions.parse("@[Blue Base] it's a beautiful evening"))
        assertNull(MeshCoreOneReactions.parse("look@[Bob]\nb0c26wb5x"))
    }

    @Test
    fun `a hash of the wrong length or alphabet is refused`() {
        // Crockford omits I, L, O and U. A "u" where a hash should be
        // means this is not that format.
        assertNull(MeshCoreOneReactions.parse("😂@[A]\nb0c26wb"))
        assertNull(MeshCoreOneReactions.parse("😂@[A]\nb0c26wb55"))
        assertNull(MeshCoreOneReactions.parse("😂@[A]\nb0c26wbu"))
        assertNull(MeshCoreOneReactions.parse("😂@[A]\n"))
        assertNull(MeshCoreOneReactions.parse("😂@[]\nb0c26wb5"))
        assertNull(MeshCoreOneReactions.parse("b0c26wb5"))
        assertNull(MeshCoreOneReactions.parse(""))
    }

    @Test
    fun `Crockford substitutions are accepted on the way in`() {
        // Their parser normalises O to 0 and I/L to 1, and is
        // case-insensitive. Ours has to agree or a reaction typed or
        // relayed through anything that "helpfully" upper-cases is lost.
        assertEquals(
            "b0c26wb5",
            assertNotNull(MeshCoreOneReactions.parse("😂@[A]\nB0C26WB5")).targetHash,
        )
        assertEquals(
            "b0c26wb1",
            assertNotNull(MeshCoreOneReactions.parse("😂@[A]\nbOc26wbI")).targetHash,
        )
    }

    @Test
    fun `the target is the message whose hash matches`() {
        val candidates = listOf(
            ReactionRouting.Candidate(1, 1787534000L, "Someone", "an earlier post", false),
            ReactionRouting.Candidate(
                2,
                capturedTargetTimestamp,
                capturedTargetSender,
                capturedTargetText,
                false,
            ),
            ReactionRouting.Candidate(3, 1787535000L, "Blue Base", "a later post", false),
        )
        val parsed = assertNotNull(MeshCoreOneReactions.parse(capturedWire))
        assertEquals(
            2L,
            MeshCoreOneReactions.target(candidates, parsed, isChannel = true, sha256 = sha256)?.id,
        )
    }

    @Test
    fun `a channel reaction naming a different author matches nothing`() {
        // The sender name is in the format precisely to tell identical
        // posts apart. Ignoring it would attach the emoji to whichever
        // copy happened to be found first.
        val candidates = listOf(
            ReactionRouting.Candidate(
                2,
                capturedTargetTimestamp,
                "Somebody Else",
                capturedTargetText,
                false,
            ),
        )
        val parsed = assertNotNull(MeshCoreOneReactions.parse(capturedWire))
        assertNull(MeshCoreOneReactions.target(candidates, parsed, isChannel = true, sha256 = sha256))
    }

    @Test
    fun `an unmatched reaction resolves to nothing rather than to something`() {
        val candidates = listOf(
            ReactionRouting.Candidate(1, 1787534000L, "Someone", "an earlier post", false),
        )
        val parsed = assertNotNull(MeshCoreOneReactions.parse(capturedWire))
        assertNull(MeshCoreOneReactions.target(candidates, parsed, isChannel = true, sha256 = sha256))
    }

    @Test
    fun `Crockford encodes five bytes as eight characters without I L O or U`() {
        assertEquals("00000000", CrockfordBase32.encode(ByteArray(5)))
        assertEquals(
            HASH_CHARS,
            CrockfordBase32.encode(byteArrayOf(-1, -1, -1, -1, -1)).length,
        )
        assertEquals("zzzzzzzz", CrockfordBase32.encode(byteArrayOf(-1, -1, -1, -1, -1)))
        val all = (0..255).map { it.toByte() }.chunked(5)
            .filter { it.size == 5 }
            .joinToString("") { CrockfordBase32.encode(it.toByteArray()) }
        for (banned in listOf('i', 'l', 'o', 'u')) {
            kotlin.test.assertFalse(all.contains(banned), "alphabet must not contain $banned")
        }
    }

    private companion object {
        const val HASH_CHARS = 8
    }
}
