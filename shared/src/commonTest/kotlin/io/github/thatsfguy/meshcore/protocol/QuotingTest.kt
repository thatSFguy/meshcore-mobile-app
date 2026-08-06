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
    fun `the preview of a reply is the reply not what it answered`() {
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
    fun `reacting to a reply quotes the reply not its quote`() {
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
    fun `a long target is clipped not dumped into the shade`() {
        val notice = ReactionNotice.text("👍", "x".repeat(400))
        assertTrue(notice.length < 100, "notification body too long: ${notice.length}")
        assertTrue(notice.endsWith("…\""))
    }

    // --- notification bodies -----------------------------------------------

    @Test
    fun `a reply notification separates the quote from the answer`() {
        // The reported bug, verbatim: a reply "good" to a message "yeah"
        // arrived in the shade as ">yeah good".
        val notice = MessageNotice.forMessage("> yeah\ngood")
        assertEquals("good", notice.collapsed)
        assertEquals("↩ yeah\ngood", notice.expanded)
        assertTrue(!notice.collapsed.contains("yeah"), "the answer still carries the question")
        assertTrue(!notice.expanded.startsWith(">"), "raw quote marker leaked")
    }

    @Test
    fun `the collapsed line is the reply never the message it answered`() {
        // Android shows one line before you expand. That line must be
        // what was just said.
        val notice = MessageNotice.forMessage("> Kaylee: are you heading out tomorrow\nyes, about six")
        assertEquals("yes, about six", notice.collapsed)
    }

    @Test
    fun `the expanded form keeps the context marked`() {
        val notice = MessageNotice.forMessage("> Kaylee: heading out?\nyes")
        assertTrue(notice.expanded.startsWith(MessageNotice.QUOTE_MARK))
        assertTrue(notice.expanded.contains("Kaylee: heading out?"))
        assertTrue(notice.expanded.endsWith("\nyes"))
    }

    @Test
    fun `a plain message is unchanged in both forms`() {
        val notice = MessageNotice.forMessage("just a message")
        assertEquals("just a message", notice.collapsed)
        assertEquals("just a message", notice.expanded)
    }

    @Test
    fun `an already-formatted reaction notice passes through untouched`() {
        // Reactions reach the same builder pre-formatted; it must not
        // try to re-split them.
        val reaction = ReactionNotice.text("👍", "leaving about six")
        val notice = MessageNotice.forMessage(reaction)
        assertEquals(reaction, notice.collapsed)
        assertEquals(reaction, notice.expanded)
    }

    @Test
    fun `a quote with no reply still says something`() {
        val notice = MessageNotice.forMessage("> just the quote\n")
        assertTrue(notice.collapsed.isNotBlank())
        assertTrue(notice.collapsed.contains("just the quote"))
    }

    @Test
    fun `a long quote is clipped so the reply stays visible`() {
        val notice = MessageNotice.forMessage("> " + "x".repeat(400) + "\nshort answer")
        assertEquals("short answer", notice.collapsed)
        assertTrue(
            notice.expanded.length < 140,
            "expanded body too long: ${notice.expanded.length}",
        )
    }

    @Test
    fun `a multi-line quote is flattened into one context line`() {
        val notice = MessageNotice.forMessage("> one\n> two\nthe answer")
        assertEquals("the answer", notice.collapsed)
        assertEquals(2, notice.expanded.lines().size)
    }
}
