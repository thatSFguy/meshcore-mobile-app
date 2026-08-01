package io.github.thatsfguy.meshcore.android.ui

import io.github.thatsfguy.meshcore.android.storage.MessageEntity
import io.github.thatsfguy.meshcore.android.storage.MessageRepository
import io.github.thatsfguy.meshcore.android.ui.screens.hopsLabel
import io.github.thatsfguy.meshcore.android.ui.screens.quotePrefixFor
import io.github.thatsfguy.meshcore.android.ui.screens.splitQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers behind the conversation screen. */
class ConversationHelpersTest {

    private fun message(
        text: String,
        senderName: String? = null,
        outgoing: Boolean = false,
    ) = MessageEntity(
        selfKey = "self",
        kind = MessageRepository.KIND_CHANNEL,
        peerKey = "0",
        senderName = senderName,
        text = text,
        timestamp = 1_700_000_000L,
        receivedAt = 0,
        outgoing = outgoing,
        status = 0,
        ackHash = null,
        contentKey = null,
        snr = null,
    )

    // ---- hop labels ------------------------------------------------------

    @Test
    fun `hop labels read the way a person would say them`() {
        assertEquals("direct", hopsLabel(0))
        assertEquals("1 hop", hopsLabel(1))
        assertEquals("4 hops", hopsLabel(4))
        assertEquals("flood", hopsLabel(-1))
    }

    @Test
    fun `an unknown hop count shows nothing rather than guessing`() {
        // Outgoing rows and anything received before hops were recorded.
        assertNull(hopsLabel(null))
    }

    // ---- quoting ---------------------------------------------------------

    @Test
    fun `a quote carries the sender and a bounded snippet`() {
        val prefix = quotePrefixFor(message("hello there", senderName = "Kaylee"))
        assertTrue(prefix.startsWith("> Kaylee: hello there"))
        assertTrue(prefix.endsWith("\n"))
    }

    @Test
    fun `a long quote is truncated so it can't eat the message budget`() {
        // Every quoted byte comes out of a ~150-byte frame.
        val prefix = quotePrefixFor(message("x".repeat(500), senderName = "N"))
        assertTrue("quote was ${prefix.length} chars", prefix.length < 60)
        assertTrue(prefix.contains("…"))
    }

    @Test
    fun `a quote never contains a newline that would split the reply`() {
        val prefix = quotePrefixFor(message("line one\nline two", senderName = "N"))
        assertEquals(1, prefix.count { it == '\n' })
        assertTrue(prefix.endsWith("\n"))
    }

    @Test
    fun `quoting a message with no sender omits the name`() {
        val prefix = quotePrefixFor(message("hi"))
        assertEquals("> hi\n", prefix)
    }

    @Test
    fun `split separates the quote from the reply body`() {
        val (quote, body) = splitQuote("> Kaylee: hello\nyes I agree")
        assertEquals("Kaylee: hello", quote)
        assertEquals("yes I agree", body)
    }

    @Test
    fun `split leaves an ordinary message alone`() {
        val (quote, body) = splitQuote("no quote here")
        assertNull(quote)
        assertEquals("no quote here", body)
    }

    @Test
    fun `split handles a quote with no reply body`() {
        val (quote, body) = splitQuote("> just the quote\n")
        assertEquals("just the quote", quote)
        assertEquals("", body)
    }

    @Test
    fun `split keeps a mid-text angle bracket as text`() {
        val (quote, body) = splitQuote("5 > 3 is true")
        assertNull(quote)
        assertEquals("5 > 3 is true", body)
    }

    @Test
    fun `quote and split round trip`() {
        val target = message("original message", senderName = "Bob")
        val sent = quotePrefixFor(target) + "my reply"
        val (quote, body) = splitQuote(sent)
        assertNotNull(quote)
        assertTrue(quote!!.contains("Bob"))
        assertEquals("my reply", body)
    }
}
