package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quote-reply splitting, and the one-line summary built from it.
 *
 * The bug this pins: the conversation list took the first 80 characters
 * of a reply's raw text, and a reply's raw text BEGINS with the message
 * being replied to. Every reply previewed as the thing it was answering
 * — so the list read as though everyone were repeating each other, and
 * the actual reply was the part that got cut off.
 */
class QuotingTest {

    // --- splitting ---------------------------------------------------------

    @Test
    fun `a reply splits into the quote and the body`() {
        val (quote, body) = Quoting.split("> Kaylee: are you heading out\nyes, about six")
        assertEquals("Kaylee: are you heading out", quote)
        assertEquals("yes, about six", body)
    }

    @Test
    fun `plain text has no quote`() {
        val (quote, body) = Quoting.split("just a message")
        assertNull(quote)
        assertEquals("just a message", body)
    }

    @Test
    fun `a greater-than sign mid-message is not a quote`() {
        // Only a LEADING run counts. "5 > 3" is arithmetic.
        val (quote, body) = Quoting.split("5 > 3 is true")
        assertNull(quote)
        assertEquals("5 > 3 is true", body)
    }

    @Test
    fun `a multi-line quote keeps every quoted line`() {
        val (quote, body) = Quoting.split("> one\n> two\nthe reply")
        assertEquals("one\ntwo", quote)
        assertEquals("the reply", body)
    }

    // --- the preview, which is what was actually broken ---------------------

    @Test
    fun `the preview of a reply is the reply, not what it answered`() {
        // The positive control for the whole fix.
        assertEquals(
            "yes, about six",
            Quoting.previewBody("> Kaylee: are you heading out tomorrow morning\nyes, about six"),
        )
    }

    @Test
    fun `the preview never begins with a quote marker`() {
        val samples = listOf(
            "> a: hello\nhi back",
            "> a: hello\n> b: hi\nboth of you then",
            "plain",
        )
        for (text in samples) {
            assertTrue(!Quoting.previewBody(text).startsWith(">"), "leaked a quote: $text")
        }
    }

    @Test
    fun `a quote with no reply shows the quote rather than nothing`() {
        // Degenerate, but a blank row tells the reader less than the
        // wrong text does.
        assertEquals("just the quote", Quoting.previewBody("> just the quote\n"))
        assertTrue(Quoting.previewBody("> just the quote\n").isNotBlank())
    }

    @Test
    fun `a plain message previews unchanged`() {
        assertEquals("nothing special", Quoting.previewBody("nothing special"))
    }

    // --- one-line clipping -------------------------------------------------

    @Test
    fun `oneLine flattens newlines and clips with an ellipsis`() {
        assertEquals("a b", Quoting.oneLine("a\nb", 40))
        assertEquals("abcde…", Quoting.oneLine("abcdefghij", 5))
        assertEquals("short", Quoting.oneLine("short", 40))
    }

    // --- reaction notification text ----------------------------------------

    @Test
    fun `a reaction notification quotes what was reacted to`() {
        assertEquals(
            "👍 to \"leaving about six\"",
            ReactionNotice.text("👍", "leaving about six"),
        )
    }

    @Test
    fun `reacting to a reply quotes the reply, not its quote`() {
        assertEquals(
            "👍 to \"yes, about six\"",
            ReactionNotice.text("👍", "> Kaylee: heading out?\nyes, about six"),
        )
    }

    @Test
    fun `a reaction to a reaction does not quote wire text`() {
        // Reactions.encode output must never reach a notification.
        val wire = Reactions.encode("1a2b", "👍")
        val notice = ReactionNotice.text("❤️", wire ?: "r:1a2b:00")
        assertTrue(!notice.contains("r:"), "leaked wire text: $notice")
        assertEquals("❤️", notice)
    }

    @Test
    fun `an empty target degrades to the bare emoji`() {
        assertEquals("👍", ReactionNotice.text("👍", ""))
        assertEquals("👍", ReactionNotice.text("👍", "   "))
    }

    @Test
    fun `a long target is clipped, not dumped into the shade`() {
        val notice = ReactionNotice.text("👍", "x".repeat(400))
        assertTrue(notice.length < 100, "notification body too long: ${notice.length}")
        assertTrue(notice.endsWith("…\""))
    }
}
