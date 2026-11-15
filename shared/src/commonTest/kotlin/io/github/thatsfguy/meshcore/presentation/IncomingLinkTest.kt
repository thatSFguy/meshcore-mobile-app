package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.presentation.LinkInbox.Offer
import io.github.thatsfguy.meshcore.protocol.ShareUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IncomingLinkTest {

    private val card = ShareUri.encodeContact("Alice", "ab".repeat(32), 1)

    @Test
    fun `a contact link is accepted as it came`() {
        // The positive control: everything else here is a refusal.
        assertEquals(card, IncomingLink.accept(card))
        assertEquals(card, IncomingLink.accept("  $card\n"))
    }

    @Test
    fun `the scheme is matched without regard to case`() {
        // Some messaging apps capitalise the start of a line.
        val shouted = "MeshCore://" + card.removePrefix(ShareUri.SCHEME)
        assertEquals(shouted, IncomingLink.accept(shouted))
    }

    @Test
    fun `anything that is not a meshcore link is ignored`() {
        assertNull(IncomingLink.accept(null))
        assertNull(IncomingLink.accept(""))
        assertNull(IncomingLink.accept("https://example.com/meshcore://contact"))
        assertNull(IncomingLink.accept("meshcore:/contact/add"))
        assertNull(IncomingLink.accept(ShareUri.SCHEME))
    }

    @Test
    fun `an oversized link is not held waiting for a radio`() {
        val huge = ShareUri.SCHEME + "a".repeat(ShareUri.MAX_URI_LENGTH)
        assertNull(IncomingLink.accept(huge))
        val inbox = LinkInbox()
        assertEquals(Offer.Ignored, inbox.offer(huge, ready = false))
        assertNull(inbox.onReady())
    }

    @Test
    fun `with a radio ready the link opens at once`() {
        val inbox = LinkInbox()
        assertEquals(Offer.Open(card), inbox.offer(card, ready = true))
        assertNull(inbox.onReady())
    }

    @Test
    fun `a link tapped before the radio connects opens when it does`() {
        // The cold start from a text message: the case that used to vanish.
        val inbox = LinkInbox()
        assertEquals(Offer.Held, inbox.offer(card, ready = false))
        assertEquals(card, inbox.onReady())
        // Once only: a reconnect later must not reopen it.
        assertNull(inbox.onReady())
    }

    @Test
    fun `the newest of several held links wins`() {
        val other = ShareUri.encodeContact("Bob", "cd".repeat(32), 1)
        val inbox = LinkInbox()
        inbox.offer(card, ready = false)
        inbox.offer(other, ready = false)
        assertEquals(other, inbox.onReady())
    }

    @Test
    fun `a junk link does not displace a held one`() {
        val inbox = LinkInbox()
        inbox.offer(card, ready = false)
        assertEquals(Offer.Ignored, inbox.offer("https://example.com", ready = false))
        assertEquals(card, inbox.onReady())
    }
}
