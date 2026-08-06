package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
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
            hex = MessageRepeats.merge(hex, "%04x".format(i), 2)
        }
        assertEquals(MessageRepeats.MAX_RELAYS, MessageRepeats.count(hex, 2))
    }

    // --- refusing bad input ------------------------------------------------

    @Test
    fun `a path that does not divide by the width is refused, not stored`() {
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
    fun `nothing heard shows no badge at all, never a zero`() {
        // A "0 repeaters" badge would assert something we did not
        // measure. The badge simply is not there.
        assertNull(MessageRepeats.badge(null, 2))
        assertNull(MessageRepeats.badge("", 2))
        assertNull(MessageRepeats.badge("b389", null))
    }

    @Test
    fun `the badge is terse enough to sit under a bubble`() {
        assertEquals("↻ 1 repeat", MessageRepeats.badge("b389", 2))
        assertEquals("↻ 2 repeats", MessageRepeats.badge("b389f0b3", 2))
    }

    @Test
    fun `silence is explained as unmeasured, not as nobody`() {
        val text = MessageRepeats.summary(null, 2)
        assertTrue(text.contains("not the same as"), text)
        assertTrue(text.contains("cannot be heard here"), text)
    }

    @Test
    fun `the wording says node, not repeater`() {
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

    @Test
    fun `exactly one candidate is credited`() {
        val keys = listOf("b389aaaa", "f0b3bbbb")
        assertEquals("b389aaaa", MessageRepeats.creditDirect(keys, 0xb3))
    }

    @Test
    fun `two candidates sharing the dest byte are refused`() {
        // The positive-control's mirror: a one-byte hash puts these two
        // recipients in the same bucket, and picking the newer one would
        // be a guess presented as a measurement.
        val keys = listOf("b389aaaa", "b3ffcccc")
        assertNull(MessageRepeats.creditDirect(keys, 0xb3))
    }

    @Test
    fun `no candidate matching the dest byte is refused`() {
        assertNull(MessageRepeats.creditDirect(listOf("f0b3bbbb"), 0xb3))
        assertNull(MessageRepeats.creditDirect(emptyList(), 0xb3))
    }

    @Test
    fun `the same recipient twice is one candidate, not two`() {
        // Two messages to one person still identify the PEER
        // unambiguously; which of the two messages it was is a separate
        // question the caller settles.
        assertEquals(
            "b389aaaa",
            MessageRepeats.creditDirect(listOf("b389aaaa", "b389aaaa"), 0xb3),
        )
    }

    @Test
    fun `dest byte matching is case-insensitive and zero-padded`() {
        assertEquals("0A45D1", MessageRepeats.creditDirect(listOf("0A45D1"), 0x0a))
        assertEquals("0a45d1", MessageRepeats.creditDirect(listOf("0a45d1"), 0x0a))
    }

    @Test
    fun `an out-of-range dest byte is refused rather than wrapped`() {
        assertNull(MessageRepeats.creditDirect(listOf("b389aaaa"), 256))
        assertNull(MessageRepeats.creditDirect(listOf("b389aaaa"), -1))
    }

    @Test
    fun `a truncated key cannot match`() {
        assertNull(MessageRepeats.creditDirect(listOf("b"), 0xb3))
    }
}
