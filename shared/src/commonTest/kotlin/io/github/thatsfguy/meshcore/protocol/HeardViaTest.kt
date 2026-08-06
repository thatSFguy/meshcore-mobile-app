package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.hexPadded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recovering the route a message arrived on (PARITY §2, §13).
 *
 * Two assertions carry the weight. **Ambiguity yields nothing** — two
 * plausible packets means we do not know which one carried the message,
 * and a route credited to the wrong message looks exactly like a correct
 * one. And **an arrival path is the reverse of a sending path** — offer
 * it as a reply route unreversed and you pin a specific-looking route
 * that addresses its hops backwards.
 */
class HeardViaTest {

    private val now = 1_700_000_000_000L

    // Sender key b389… — the src_hash byte is 0xb3.
    private fun arrival(
        pathHex: String = "b389c985",
        width: Int = 2,
        hops: Int = 2,
        srcHash: Int = 0xb3,
        at: Long = now,
    ) = HeardVia.Arrival(pathHex, width, hops, srcHash, at)

    // --- reversal ---------------------------------------------------------

    @Test
    fun anArrivalPathReversesIntoASendingRoute() {
        // Heard as: SpartaMI then BYRONCTR (BYRONCTR was the last hop
        // before us). Replying goes out the other way round.
        assertEquals("c985b389", HeardVia.reverseHex("b389c985", 2))
        val bytes = byteArrayOf(0xb3.toByte(), 0x89.toByte(), 0xc9.toByte(), 0x85.toByte())
        assertEquals(
            listOf(0xc9, 0x85, 0xb3, 0x89),
            HeardVia.reverse(bytes, 2).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun reversingIsPerHopNotPerByte() {
        // Reversing the BYTES would give "85c989b3" — four hops of
        // nothing. The hop is the unit.
        assertEquals("c985b389", HeardVia.reverseHex("b389c985", 2))
        // At width 1 the same hex IS four hops, and reverses as four.
        assertEquals("85c989b3", HeardVia.reverseHex("b389c985", 1))
    }

    @Test
    fun reversingTwiceIsIdentity() {
        for (width in 1..4) {
            for (hops in 0..8) {
                val hex = (0 until hops * width).joinToString("") { hexPadded(it, 2) }
                assertEquals(hex, HeardVia.reverseHex(HeardVia.reverseHex(hex, width), width))
            }
        }
    }

    @Test
    fun aPathThatDoesNotDivideIntoHopsReversesToNothing() {
        // Better an empty route than a reordered guess.
        assertEquals("", HeardVia.reverseHex("b389c9", 2))
        assertEquals(0, HeardVia.reverse(byteArrayOf(1, 2, 3), 2).size)
        assertEquals("", HeardVia.reverseHex("", 2))
    }

    @Test
    fun oneHopReversesToItself() {
        assertEquals("b389", HeardVia.reverseHex("b389", 2))
    }

    // --- correlation ------------------------------------------------------

    @Test
    fun aLoneMatchingPacketIsTheRoute() {
        val m = HeardVia.match(listOf(arrival()), "b389548d314a", 2, now + 500)
        assertNotNull(m)
        assertEquals("b389c985", m.pathHex)
    }

    @Test
    fun twoPlausiblePacketsYieldNothing() {
        // Same sender byte, same hop count, both in window. We do not
        // know which one carried the message, so we say nothing.
        val pending = listOf(arrival(pathHex = "b389c985"), arrival(pathHex = "d0ce90a8"))
        assertNull(HeardVia.match(pending, "b389548d314a", 2, now + 500))
    }

    @Test
    fun aDifferentSenderIsNotCredited() {
        assertNull(HeardVia.match(listOf(arrival(srcHash = 0xc9)), "b389548d314a", 2, now + 500))
    }

    @Test
    fun aDisagreeingHopCountIsNotCredited() {
        // Both sides state the hop count independently; requiring them
        // to agree is what stops another packet from the same sender
        // being credited.
        assertNull(HeardVia.match(listOf(arrival(hops = 3)), "b389548d314a", 2, now + 500))
    }

    @Test
    fun anUnknownHopCountFallsBackToSenderAndTimeAlone() {
        val m = HeardVia.match(listOf(arrival()), "b389548d314a", null, now + 500)
        assertNotNull(m)
        // …but only while it stays unambiguous.
        assertNull(
            HeardVia.match(
                listOf(arrival(hops = 2), arrival(hops = 3)),
                "b389548d314a", null, now + 500,
            ),
        )
    }

    @Test
    fun aStalePacketIsNotCredited() {
        val old = arrival(at = now - HeardVia.MATCH_WINDOW_MS - 1)
        assertNull(HeardVia.match(listOf(old), "b389548d314a", 2, now))
        // Exactly at the edge still counts.
        val edge = arrival(at = now - HeardVia.MATCH_WINDOW_MS)
        assertNotNull(HeardVia.match(listOf(edge), "b389548d314a", 2, now))
    }

    @Test
    fun aPacketFromTheFutureIsNotCredited() {
        // Clock skew must not resurrect a packet that hasn't happened.
        assertNull(HeardVia.match(listOf(arrival(at = now + 5_000)), "b389548d314a", 2, now))
    }

    @Test
    fun hostileSenderPrefixesAreRejectedRatherThanMatchedLoosely() {
        assertNull(HeardVia.match(listOf(arrival()), "", 2, now))
        assertNull(HeardVia.match(listOf(arrival()), "z", 2, now))
        assertNull(HeardVia.match(listOf(arrival()), "zz89548d314a", 2, now))
    }

    // --- the pending buffer -----------------------------------------------

    @Test
    fun thePendingBufferIsBoundedBecauseAnyoneInRangeCanFillIt() {
        var pending = emptyList<HeardVia.Arrival>()
        repeat(HeardVia.MAX_PENDING * 3) { i ->
            pending = HeardVia.remember(pending, arrival(at = now + i))
        }
        assertEquals(HeardVia.MAX_PENDING, pending.size)
        // The oldest go, not the newest — a message is matched against
        // what was heard most recently.
        assertEquals(now + HeardVia.MAX_PENDING * 3 - 1, pending.last().atMillis)
    }

    @Test
    fun expiryDropsWhatCanNoLongerBeMatched() {
        val pending = listOf(
            arrival(at = now - HeardVia.MATCH_WINDOW_MS - 1),
            arrival(at = now - 1_000),
            arrival(at = now + 1_000),
        )
        val kept = HeardVia.expire(pending, now)
        assertEquals(1, kept.size)
        assertEquals(now - 1_000, kept[0].atMillis)
    }

    // --- wording ----------------------------------------------------------

    @Test
    fun theWordingSeparatesDirectFromUnknown() {
        // These lead somewhere different: "nothing was in between" vs
        // "we can't say what was in between".
        assertTrue(HeardVia.summary(0, null, 2).contains("directly"))
        assertTrue(HeardVia.summary(4, null, 2).contains("isn't known"))
        assertTrue(HeardVia.summary(null, null, 2).contains("isn't known"))
        assertTrue(HeardVia.summary(2, "b389c985", 2).contains("2 repeater(s)"))
    }

    @Test
    fun floodIsNotDirect() {
        // Caught on a real message: the sheet showed "Hops travelled:
        // flood" and, one line below, "Arrived directly — no repeater in
        // between". path_len 0xFF decodes to -1, and folding -1 in with 0
        // asserted the opposite of what was known.
        val flood = HeardVia.summary(PathCodec.decodePathLen(0xFF).hops, null, 2)
        assertFalse(flood.contains("directly"))
        assertTrue(flood.contains("flooding"))
        assertTrue(flood.contains("isn't known"))
        // And the two really are different answers, not two spellings.
        assertNotEquals(HeardVia.summary(0, null, 2), flood)
    }

    @Test
    fun aFloodedMessageCanStillRecoverItsRouteFromAUniquePacket() {
        // A flood states no hop count, so there is nothing to cross-check
        // against — but sender + time + uniqueness still stands, and a
        // flood is exactly the case where "what carried this" is worth
        // knowing. Passing -1 as a hop count matched nothing, ever.
        val m = HeardVia.match(listOf(arrival(hops = 2)), "b389548d314a", null, now + 500)
        assertNotNull(m)
        assertEquals("b389c985", m.pathHex)
    }

    @Test
    fun aRouteThatDoesNotFitTheWidthIsNotDescribedAsARoute() {
        // Falls back to the hop count rather than reporting a fractional
        // number of repeaters.
        assertTrue(HeardVia.summary(2, "b389c9", 2).contains("isn't known"))
    }
}
