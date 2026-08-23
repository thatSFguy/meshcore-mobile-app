package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unread badges and notifications.
 *
 * Both failure directions are cheap to ship and expensive to notice:
 * buzzing for a conversation already on screen reads as a broken app,
 * and staying silent for a real message reads as a broken mesh.
 */
class InboxTest {

    private val open = Inbox.threadKey(Inbox.KIND_DM, "b389aaaa")

    @Test
    fun `the thread key is built one way`() {
        assertEquals("dm|b389aaaa", Inbox.threadKey(Inbox.KIND_DM, "b389aaaa"))
        assertEquals("ch|0", Inbox.threadKey(Inbox.KIND_CHANNEL, "0"))
    }

    @Test
    fun `a message in the open conversation neither badges nor buzzes`() {
        assertTrue(Inbox.isOpen(open, Inbox.KIND_DM, "b389aaaa"))
        assertFalse(Inbox.shouldBumpUnread(open, Inbox.KIND_DM, "b389aaaa"))
        assertFalse(Inbox.shouldNotify(open, Inbox.KIND_DM, "b389aaaa"))
    }

    @Test
    fun `a message in another conversation does both`() {
        assertTrue(Inbox.shouldBumpUnread(open, Inbox.KIND_DM, "ffffffff"))
        assertTrue(Inbox.shouldNotify(open, Inbox.KIND_DM, "ffffffff"))
    }

    @Test
    fun `nothing open means everything alerts`() {
        assertFalse(Inbox.isOpen(null, Inbox.KIND_DM, "b389aaaa"))
        assertTrue(Inbox.shouldNotify(null, Inbox.KIND_DM, "b389aaaa"))
    }

    @Test
    fun `a channel and a DM with the same peer string are different threads`() {
        // Channel peer keys are indices — "0" — and a contact key could
        // in principle be anything. The kind is part of the identity.
        val channelOpen = Inbox.threadKey(Inbox.KIND_CHANNEL, "0")
        assertFalse(Inbox.isOpen(channelOpen, Inbox.KIND_DM, "0"))
        assertTrue(Inbox.isOpen(channelOpen, Inbox.KIND_CHANNEL, "0"))
    }

    @Test
    fun `a duplicate delivery never notifies twice`() {
        // A channel message reaches us via companion sync AND the RX
        // log; the second is bounced by the database and must not buzz.
        assertFalse(
            Inbox.shouldNotify(null, Inbox.KIND_CHANNEL, "0", isDuplicate = true),
        )
    }

    @Test
    fun `a CLI reply never notifies`() {
        // Remote-admin console output rides the message path. Notifying
        // on it would buzz the phone for every `get freq`.
        assertFalse(
            Inbox.shouldNotify(null, Inbox.KIND_DM, "b389aaaa", isCliReply = true),
        )
    }

    @Test
    fun `a partial thread-key match is not a match`() {
        // "dm|b389" must not count as having "dm|b389aaaa" open, or a
        // prefix collision silences a real conversation.
        assertFalse(Inbox.isOpen("dm|b389", Inbox.KIND_DM, "b389aaaa"))
        assertTrue(Inbox.shouldNotify("dm|b389", Inbox.KIND_DM, "b389aaaa"))
    }

    // --- the tab badge (PARITY §14, their v1.49.0) ---

    @Test
    fun `the badge counts unread messages rather than unread threads`() {
        // Three threads, two of them unread, five messages between them:
        // the badge says 5, because it is a message count and that is
        // what a reader is deciding whether to go and read.
        assertEquals(5, Inbox.unreadTotal(listOf(0, 2, 3)))
        assertEquals("5", Inbox.badgeLabel(Inbox.unreadTotal(listOf(0, 2, 3))))
    }

    @Test
    fun `nothing unread shows no badge at all`() {
        // The empty string is the signal to draw no badge. A "0" on the
        // tab is worse than nothing: it looks like an unread count that
        // will not clear.
        assertEquals(0, Inbox.unreadTotal(listOf(0, 0, 0)))
        assertEquals("", Inbox.badgeLabel(0))
        assertEquals("", Inbox.badgeLabel(Inbox.unreadTotal(emptyList())))
    }

    @Test
    fun `a damaged negative count is ignored rather than subtracted`() {
        // A row claiming -3 unread is corrupt. Summing it would hide
        // real unread messages on the other rows — the failure that is
        // invisible, because a badge that is missing looks like an app
        // with nothing to say.
        assertEquals(4, Inbox.unreadTotal(listOf(-3, 4)))
        assertEquals("", Inbox.badgeLabel(Inbox.unreadTotal(listOf(-9))))
    }

    @Test
    fun `a runaway count caps instead of overflowing`() {
        // Two rows near Int.MAX_VALUE would wrap to a negative in Int
        // arithmetic and the badge would vanish — or, worse, render a
        // minus sign. Capped, it stays "99+".
        val huge = listOf(Int.MAX_VALUE, Int.MAX_VALUE, 7)
        assertEquals(Int.MAX_VALUE, Inbox.unreadTotal(huge))
        assertEquals("99+", Inbox.badgeLabel(Inbox.unreadTotal(huge)))
    }

    @Test
    fun `the badge stops counting past ninety-nine`() {
        // 99 still fits the circle and still means something; 100 does
        // not, in either sense.
        assertEquals("99", Inbox.badgeLabel(99))
        assertEquals("99+", Inbox.badgeLabel(100))
        assertEquals("1", Inbox.badgeLabel(1))
    }
}
