package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuotingPrefixTest {
    @Test
    fun aReplyQuotesTheSenderAndOneShortLine() {
        assertEquals("> Kaylee: hello there\n", Quoting.prefixFor("Kaylee", "hello there"))
        assertEquals("> hi all\n", Quoting.prefixFor(null, "hi\nall"))
        val long = Quoting.prefixFor("N", "x".repeat(500))
        assertTrue(long.length < 60 && long.endsWith("…\n"), long)
        // What a reply sends splits back into quote and body.
        val (quoted, body) = Quoting.split(Quoting.prefixFor("Kaylee", "hello") + "yes")
        assertEquals("Kaylee: hello", quoted)
        assertEquals("yes", body)
    }

    @Test
    fun theHalvesAreAvailableSeparately() {
        val t = Quoting.prefixFor("Kaylee", "hello") + "yes"
        assertEquals("Kaylee: hello", Quoting.quotedPart(t))
        assertEquals("yes", Quoting.bodyPart(t))
        assertEquals(null, Quoting.quotedPart("plain"))
        assertEquals("plain", Quoting.bodyPart("plain"))
    }

    @Test
    fun mentionHighlightsAreRangesAndKnowWhoIsMe() {
        val text = "hi @[Rob] and @[Amy]"
        val h = io.github.thatsfguy.meshcore.presentation.Mentions.highlights(text, "rob")
        assertEquals(listOf(3, 9, 1, 14, 20, 0), h)
        assertEquals("@[Rob]", text.substring(h[0], h[1]))
        assertEquals(emptyList(), io.github.thatsfguy.meshcore.presentation.Mentions.highlights("no tags", "rob"))
    }
}
