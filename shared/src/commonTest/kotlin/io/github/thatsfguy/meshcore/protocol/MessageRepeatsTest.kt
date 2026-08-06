package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.hexPadded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Repeats accumulated against one sent message.
 *
 * The load-bearing rule: **nothing heard is not the same as nobody
 * carried it.** A repeater that relayed the message away from us never
 * sends a copy back, so absence is unmeasured, not zero — and the badge
 * has to disappear rather than read "0".
 */
class MessageRepeatsTest {

    // --- accumulating ------------------------------------------------------

    @Test
    fun `the first echo establishes the list`() {
        assertEquals("b389", MessageRepeats.merge(null, "b389", 2))
    }

    @Test
    fun `a second repeater is appended`() {
        val one = MessageRepeats.merge(null, "b389", 2)
        assertEquals("b389f0b3", MessageRepeats.merge(one, "f0b3", 2))
    }

    @Test
    fun `hearing the same repeater again does not double-count it`() {
        // Repeaters re-transmit; a message is not carried twice by one
        // node just because two of its copies reached us.
        val one = MessageRepeats.merge(null, "b389", 2)
        assertEquals("b389", MessageRepeats.merge(one, "b389", 2))
        assertEquals(1, MessageRepeats.count(MessageRepeats.merge(one, "b389", 2), 2))
    }

    @Test
    fun `a multi-hop echo contributes every hop on it`() {
        // Both nodes carried the message; only one of them transmitted
        // the copy we heard, but both are on its route.
        assertEquals("b389f0b3", MessageRepeats.merge(null, "b389f0b3", 2))
        assertEquals(2, MessageRepeats.count("b389f0b3", 2))
    }

    @Test
    fun `first-seen order is preserved so the list does not reshuffle`() {
        var hex = MessageRepeats.merge(null, "ffff", 2)
        hex = MessageRepeats.merge(hex, "aaaa", 2)
        hex = MessageRepeats.merge(hex, "bbbb", 2)
        assertEquals(listOf("ffff", "aaaa", "bbbb"), MessageRepeats.relays(hex, 2))
    }

    @Test
    fun `case is normalised so one repeater is one entry`() {
        val hex = MessageRepeats.merge(MessageRepeats.merge(null, "B389", 2), "b389", 2)
        assertEquals(1, MessageRepeats.count(hex, 2))
    }

    @Test
    fun `the relay list is capped`() {
        var hex: String? = null
        for (i in 0 until MessageRepeats.MAX_RELAYS + 8) {
            hex = MessageRepeats.merge(hex, hexPadded(i, 4), 2)
        }
        assertEquals(MessageRepeats.MAX_RELAYS, MessageRepeats.count(hex, 2))
    }

    // --- refusing bad input ------------------------------------------------

    @Test
    fun `a path that does not divide by the width is refused not stored`() {
        // Returning null lets the caller leave the good value alone
        // rather than blanking it with a half-parsed one.
        assertNull(MessageRepeats.merge("b389", "abc", 2))
        assertNull(MessageRepeats.merge(null, "", 2))
    }

    @Test
    fun `a corrupt stored value does not poison a fresh echo`() {
        // Existing garbage is dropped; the new hop still lands.
        assertEquals("f0b3", MessageRepeats.merge("xyz", "f0b3", 2))
    }

    @Test
    fun `widths other than two are handled`() {
        assertEquals(listOf("b3", "89"), MessageRepeats.relays("b389", 1))
        assertEquals(listOf("b389c985"), MessageRepeats.relays("b389c985", 4))
    }

    // --- what the reader is told -------------------------------------------

    @Test
    fun `nothing heard shows no badge at all never a zero`() {
        // A "0 repeaters" badge would assert something we did not
        // measure. The badge simply is not there.
        assertNull(MessageRepeats.badge(null, 2))
        assertNull(MessageRepeats.badge("", 2))
        assertNull(MessageRepeats.badge("b389", null))
    }

    @Test
    fun `the badge is terse enough to sit under a bubble`() {
        // Glyph and number only — no noun. The footer already carries
        // a time, a tick and sometimes an attempt count.
        assertEquals("↻ 1", MessageRepeats.badge("b389", 2))
        assertEquals("↻ 2", MessageRepeats.badge("b389f0b3", 2))
        assertTrue(!MessageRepeats.badge("b389", 2)!!.contains("repeat"))
    }

    @Test
    fun `silence is explained as unmeasured not as nobody`() {
        val text = MessageRepeats.summary(null, 2)
        assertTrue(text.contains("not the same as"), text)
        assertTrue(text.contains("cannot be heard here"), text)
    }

    @Test
    fun `the wording says node not repeater`() {
        // A room server produced the first real measurement, and a
        // companion with client-repeat does the same. "Repeater" would
        // be wrong in a way the feature itself disproves.
        for (text in listOf(
            MessageRepeats.summary("b389", 2),
            MessageRepeats.summary("b389f0b3", 2),
        )) {
            assertTrue(text.contains("node"), text)
            assertTrue(!text.contains("repeater"), "said repeater: $text")
        }
    }

    // --- crediting a direct-message repeat ---------------------------------

    private fun sent(id: Long, peer: String, at: Long) =
        MessageRepeats.SentRef(id, peer, at)

    @Test
    fun `the only plausible send is credited`() {
        val c = listOf(sent(1, "b389aaaa", 1_000L))
        assertEquals(1L, MessageRepeats.creditDirect(c, 0xb3, 3_000L)?.id)
    }

    @Test
    fun `several sends to one contact minutes apart each get their own repeat`() {
        // The reported bug, verbatim: three messages to one person, and
        // the old rule discarded every repeat because more than one row
        // sat inside its window. Sending a burst is what testing looks
        // like; refusing when uncertain is right, refusing whenever busy
        // is broken.
        val c = listOf(
            sent(1, "b389aaaa", 0L),
            sent(2, "b389aaaa", 60_000L),
            sent(3, "b389aaaa", 420_000L),
        )
        assertEquals(1L, MessageRepeats.creditDirect(c, 0xb3, 2_000L)?.id)
        assertEquals(2L, MessageRepeats.creditDirect(c, 0xb3, 62_000L)?.id)
        assertEquals(3L, MessageRepeats.creditDirect(c, 0xb3, 421_000L)?.id)
    }

    @Test
    fun `two sends seconds apart are genuinely ambiguous and refused`() {
        val c = listOf(
            sent(1, "b389aaaa", 10_000L),
            sent(2, "b389aaaa", 12_000L),
        )
        assertNull(MessageRepeats.creditDirect(c, 0xb3, 13_000L))
    }

    @Test
    fun `an echo older than its candidate is not credited`() {
        // A repeat cannot precede the transmission it repeats.
        val c = listOf(sent(1, "b389aaaa", 10_000L))
        assertNull(MessageRepeats.creditDirect(c, 0xb3, 9_000L))
    }

    @Test
    fun `an echo long after the send is not credited`() {
        val c = listOf(sent(1, "b389aaaa", 0L))
        assertNull(
            MessageRepeats.creditDirect(c, 0xb3, MessageRepeats.MAX_ECHO_LAG_MS + 1),
        )
        assertNotNull(MessageRepeats.creditDirect(c, 0xb3, MessageRepeats.MAX_ECHO_LAG_MS))
    }

    @Test
    fun `a retried message still gets its echo since the row is stamped at attempt one`() {
        // Three attempts spread over half a minute; the echo of the last
        // one still belongs to the row stamped at the first.
        val c = listOf(sent(1, "b389aaaa", 0L))
        assertEquals(1L, MessageRepeats.creditDirect(c, 0xb3, 30_000L)?.id)
    }

    @Test
    fun `a different recipient in the same window is not confused for ours`() {
        val c = listOf(
            sent(1, "b389aaaa", 1_000L),
            sent(2, "f0b3bbbb", 2_000L),
        )
        assertEquals(1L, MessageRepeats.creditDirect(c, 0xb3, 3_000L)?.id)
        assertEquals(2L, MessageRepeats.creditDirect(c, 0xf0, 3_000L)?.id)
    }

    @Test
    fun `two DIFFERENT recipients sharing a dest byte are refused`() {
        // One byte is 256 buckets; two contacts land in one routinely.
        val c = listOf(
            sent(1, "b389aaaa", 10_000L),
            sent(2, "b3ffcccc", 12_000L),
        )
        assertNull(MessageRepeats.creditDirect(c, 0xb3, 13_000L))
    }

    @Test
    fun `no candidate matching the dest byte is refused`() {
        assertNull(MessageRepeats.creditDirect(listOf(sent(1, "f0b3bbbb", 0L)), 0xb3, 1_000L))
        assertNull(MessageRepeats.creditDirect(emptyList(), 0xb3, 1_000L))
    }

    @Test
    fun `dest byte matching is case-insensitive and zero-padded`() {
        assertEquals(1L, MessageRepeats.creditDirect(listOf(sent(1, "0A45D1", 0L)), 0x0a, 5L)?.id)
        assertEquals(1L, MessageRepeats.creditDirect(listOf(sent(1, "0a45d1", 0L)), 0x0a, 5L)?.id)
    }

    @Test
    fun `an out-of-range dest byte is refused rather than wrapped`() {
        val c = listOf(sent(1, "b389aaaa", 0L))
        assertNull(MessageRepeats.creditDirect(c, 256, 1_000L))
        assertNull(MessageRepeats.creditDirect(c, -1, 1_000L))
    }

    @Test
    fun `a truncated key cannot match`() {
        assertNull(MessageRepeats.creditDirect(listOf(sent(1, "b", 0L)), 0xb3, 1_000L))
    }

    @Test
    fun `the live capture credits the one recipient that matches`() {
        // Verbatim from the phone, 2026-08-06: three recent sent DMs to
        // two contacts, an echo for dest 0x0a, and only one candidate
        // whose key starts 0a. Both echoes (paths f0b3 and b389) landed
        // on the same message.
        val c = listOf(
            sent(18, "0a45d1d46043", 10_000L),
            sent(17, "d1f5aaaa", 5_000L),
            sent(16, "d1f5aaaa", 1_000L),
        )
        assertEquals(18L, MessageRepeats.creditDirect(c, 0x0a, 12_000L)?.id)
        // And the two echoes accumulate to the two relays seen.
        var hex = MessageRepeats.merge(null, "f0b3", 2)
        hex = MessageRepeats.merge(hex, "b389", 2)
        assertEquals("↻ 2", MessageRepeats.badge(hex, 2))
    }
}
