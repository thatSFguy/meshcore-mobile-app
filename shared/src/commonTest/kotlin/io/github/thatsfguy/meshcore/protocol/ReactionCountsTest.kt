package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReactionCountsTest {

    @Test
    fun `round trips counts`() {
        val counts = mapOf("👍" to 2, "🔥" to 1, "❤️" to 11)
        assertEquals(counts, ReactionCounts.decode(ReactionCounts.encode(counts)))
    }

    @Test
    fun `round trips every emoji in the reaction list`() {
        val counts = Reactions.ALL.associateWith { 1 }
        assertEquals(counts.size, ReactionCounts.decode(ReactionCounts.encode(counts)).size)
    }

    @Test
    fun `no reactions stores NULL rather than an empty object`() {
        assertNull(ReactionCounts.encode(emptyMap()))
        assertNull(ReactionCounts.encode(mapOf("👍" to 0)))
        assertNull(ReactionCounts.encode(mapOf("👍" to -1)))
    }

    @Test
    fun `decodes an empty or absent column as no reactions`() {
        assertEquals(emptyMap(), ReactionCounts.decode(null))
        assertEquals(emptyMap(), ReactionCounts.decode(""))
        assertEquals(emptyMap(), ReactionCounts.decode("   "))
        assertEquals(emptyMap(), ReactionCounts.decode("{}"))
    }

    @Test
    fun `malformed JSON decodes to empty instead of throwing`() {
        // Read on the render path from mesh-derived data: a bad blob must
        // never take a conversation down.
        for (bad in listOf(
            "not json", "[1,2,3]", "{", "}", "{\"a\"", "{\"a\":}", "null",
            "{\"a\":\"b\"}", "{{{{", " ", "{\"a\":1",
        )) {
            ReactionCounts.decode(bad) // must not throw
        }
        assertEquals(emptyMap(), ReactionCounts.decode("[1,2,3]"))
        assertEquals(emptyMap(), ReactionCounts.decode("not json"))
    }

    @Test
    fun `drops non-numeric, zero and negative counts`() {
        assertEquals(
            mapOf("👍" to 3),
            ReactionCounts.decode("""{"👍":3,"🔥":"lots","😀":-2,"🎉":0}"""),
        )
    }

    @Test
    fun `tolerates whitespace between tokens`() {
        assertEquals(mapOf("👍" to 2), ReactionCounts.decode("{ \"👍\" : 2 }"))
    }

    @Test
    fun `a hostile key cannot break out of the JSON string`() {
        val encoded = ReactionCounts.encode(mapOf("a\"b\\c" to 1))!!
        assertTrue(encoded.contains("\\\""), encoded)
        assertEquals(mapOf("a\"b\\c" to 1), ReactionCounts.decode(encoded))
    }

    @Test
    fun `control characters are stripped rather than emitted raw`() {
        val encoded = ReactionCounts.encode(mapOf("ab" to 1))!!
        assertTrue('' !in encoded)
        assertEquals(mapOf("ab" to 1), ReactionCounts.decode(encoded))
    }
}
