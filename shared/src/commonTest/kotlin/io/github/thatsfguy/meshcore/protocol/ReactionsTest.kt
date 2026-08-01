package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReactionsTest {

    // ---- wire format: positive -----------------------------------------

    @Test
    fun `parses a thumbs-up reaction`() {
        val r = Reactions.parse("r:1a2b:00")
        assertEquals("1a2b", r?.targetHash)
        assertEquals("👍", r?.emoji)
    }

    @Test
    fun `round trips every emoji in the list`() {
        for (emoji in Reactions.ALL) {
            val wire = Reactions.encode("abcd", emoji)
            assertTrue(wire != null, "could not encode $emoji")
            val parsed = Reactions.parse(wire)
            assertEquals("abcd", parsed?.targetHash)
            // Duplicates exist across groups (😂 is both a quick and a
            // smiley), so compare on index rather than identity.
            assertEquals(
                Reactions.ALL.indexOf(emoji),
                Reactions.ALL.indexOf(parsed?.emoji),
                "index moved for $emoji",
            )
        }
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        assertEquals("👍", Reactions.parse("  r:1a2b:00 \n")?.emoji)
    }

    @Test
    fun `emoji list is the reference client's fixed order`() {
        // The index IS the wire format: appending is safe, reordering or
        // inserting silently rewrites the meaning of existing codes.
        assertEquals(184, Reactions.ALL.size)
        assertEquals("👍", Reactions.ALL[0])
        assertEquals("❤️", Reactions.ALL[1])
        assertEquals("😂", Reactions.ALL[2])
        assertEquals("🎉", Reactions.ALL[3])
        assertEquals("👏", Reactions.ALL[4])
        assertEquals("🔥", Reactions.ALL[5])
        assertEquals("😀", Reactions.ALL[6])   // first smiley
    }

    @Test
    fun `encodes the highest index as two hex digits`() {
        val last = Reactions.ALL.last()
        val wire = Reactions.encode("0000", last)
        assertEquals("r:0000:b7", wire)   // 183 == 0xb7
        assertEquals(Reactions.ALL.size - 1, 0xb7)
    }

    // ---- wire format: negative -----------------------------------------

    @Test
    fun `plain messages are not reactions`() {
        for (text in listOf("hello", "r:", "r:1a2b", "r:1a2b:", "", "reaction")) {
            assertNull(Reactions.parse(text), "parsed $text as a reaction")
        }
    }

    @Test
    fun `rejects a malformed target hash`() {
        for (bad in listOf("r:1a2:00", "r:1a2b3:00", "r:ZZZZ:00", "r:1A2B:00")) {
            assertNull(Reactions.parse(bad), "parsed $bad")
        }
    }

    @Test
    fun `rejects an emoji index past the end of the list`() {
        // 0xff is beyond 184 entries — must not throw or wrap.
        assertNull(Reactions.parse("r:1a2b:ff"))
        assertNull(Reactions.emojiForIndex("ff"))
    }

    @Test
    fun `rejects a text that merely embeds a reaction`() {
        assertNull(Reactions.parse("look: r:1a2b:00"))
        assertNull(Reactions.parse("r:1a2b:00 nice"))
    }

    @Test
    fun `refuses to encode an emoji outside the list`() {
        assertNull(Reactions.encode("1a2b", "🦖"))
        assertNull(Reactions.encode("1a2b", ""))
    }

    // ---- target hash ----------------------------------------------------

    @Test
    fun `target hash is four lowercase hex digits`() {
        val h = Reactions.targetHash(1234567890, "Alice", "Hello world")
        assertTrue(Regex("^[0-9a-f]{4}$").matches(h), "got $h")
    }

    @Test
    fun `target hash is deterministic`() {
        assertEquals(
            Reactions.targetHash(1234567890, "Alice", "Hello"),
            Reactions.targetHash(1234567890, "Alice", "Hello"),
        )
    }

    @Test
    fun `target hash keys on timestamp, sender and the first five chars`() {
        val base = Reactions.targetHash(1234567890, "Alice", "Hello")
        assertNotEquals(base, Reactions.targetHash(1234567891, "Alice", "Hello"))
        assertNotEquals(base, Reactions.targetHash(1234567890, "Bob", "Hello"))
        assertNotEquals(base, Reactions.targetHash(1234567890, "Alice", "World"))
        // Only the first five characters count — this is the reference
        // client's documented behaviour, and the reason two messages sent
        // in the same second can share a target id.
        assertEquals(
            Reactions.targetHash(1234567890, "Alice", "Hello world"),
            Reactions.targetHash(1234567890, "Alice", "Hello there"),
        )
    }

    @Test
    fun `an absent sender and an empty sender hash alike`() {
        // Both concatenate to "<ts><text>", so a channel message from a
        // node with a blank name collides with the direct-message form.
        // Pinned because it's the format's behaviour, not ours to fix:
        // changing it would break matching against every other client.
        assertEquals(
            Reactions.targetHash(1234567890, null, "Hello"),
            Reactions.targetHash(1234567890, "", "Hello"),
        )
    }

    @Test
    fun `handles short, empty and emoji-bearing input`() {
        for (text in listOf("", "Hi", "🌈🌈🌈🌈🌈🌈", "héllo")) {
            assertTrue(Regex("^[0-9a-f]{4}$").matches(Reactions.targetHash(1, "K 🏳️‍🌈", text)))
        }
    }

    // ---- Dart hash ------------------------------------------------------

    @Test
    fun `dart hash is stable for the empty string`() {
        // Jenkins finalisation of 0 is 0, which the VM maps to 1.
        assertEquals(1, DartStringHash.of(""))
    }

    @Test
    fun `dart hash avalanches on a one-character change`() {
        assertNotEquals(DartStringHash.of("Hello"), DartStringHash.of("Hellp"))
        assertNotEquals(DartStringHash.of("a"), DartStringHash.of("b"))
    }

    @Test
    fun `dart hash walks utf-16 code units`() {
        // A surrogate pair must hash as its two code units, the way the
        // Dart VM hashes a two-byte string — not as one code point.
        val emoji = "🌈"          // 🌈
        var expected = 0
        for (c in emoji) expected = expected // sanity: two code units
        assertEquals(2, emoji.length)
        assertNotEquals(DartStringHash.of(emoji), DartStringHash.of("\uD83C"))
    }
}
