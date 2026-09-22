package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ACK wait, and the distance the radio's flood estimate leaves out.
 *
 * The numbers here are the real ones from the case that produced this
 * code: the USA/Canada preset (910.525 MHz, 62.5 kHz, SF7, CR4/5), a
 * short DM at roughly 165 ms of airtime, and a contact 4–11 hops out
 * that delivered one message nine times.
 */
class AckTimeoutTest {

    /** What the firmware's flood formula returns for a 165 ms packet. */
    private val floodEstimate = AckTimeout.BASE_MS + 16 * 165L   // 3140

    @Test
    fun `the airtime is recovered from the radio's own flood estimate`() {
        assertEquals(165L, AckTimeout.airtimeFromFloodEstimate(floodEstimate))
    }

    @Test
    fun `an estimate that cannot have come from that formula yields no airtime`() {
        // At or below the base there is no airtime term left to recover,
        // and a negative one would turn into a shortened wait.
        assertNull(AckTimeout.airtimeFromFloodEstimate(AckTimeout.BASE_MS))
        assertNull(AckTimeout.airtimeFromFloodEstimate(0))
        assertNull(AckTimeout.airtimeFromFloodEstimate(-5_000))
    }

    @Test
    fun `the per-hop budget is the firmware's own arithmetic`() {
        // 500 + (165*6 + 250) * (11+1) = 500 + 1240*12
        assertEquals(15_380L, AckTimeout.perHopBudgetMs(165, 11))
        // 500 + 1240 * (4+1)
        assertEquals(6_700L, AckTimeout.perHopBudgetMs(165, 4))
        // hops+1 transmissions: a 0-hop path is still one transmission.
        assertEquals(1_740L, AckTimeout.perHopBudgetMs(165, 0))
    }

    // ---- the case this exists for ----------------------------------------

    @Test
    fun `a flood to a distant contact waits far longer than the radio asked`() {
        // THE POSITIVE CONTROL. Every other test here says the wait is
        // left alone; if waitFor did nothing at all they would all still
        // pass. This is the one that fails if the fix is deleted.
        val wait = AckTimeout.waitFor(floodEstimate, isFlood = true, knownHops = 11)
        assertEquals(15_380L, wait)
        assertTrue(
            wait > floodEstimate,
            "an 11-hop round trip is ~3.6s of pure airtime; 3.1s cannot cover it",
        )
    }

    @Test
    fun `the contact from the duplicate-message case gets a workable wait`() {
        // 4-11 hops observed. Even the near end of that range needs more
        // than the flat flood estimate.
        for (hops in 4..11) {
            assertTrue(
                AckTimeout.waitFor(floodEstimate, isFlood = true, knownHops = hops) > floodEstimate,
                "$hops hops still waits only the flat flood estimate",
            )
        }
    }

    // ---- what must NOT change --------------------------------------------

    @Test
    fun `a stored-path send is left exactly as the radio computed it`() {
        // The direct formula already scales with path_len. Second-guessing
        // the half the firmware gets right is how you break the common case
        // to fix the rare one.
        val direct = 6_700L
        assertEquals(direct, AckTimeout.waitFor(direct, isFlood = false, knownHops = 11))
        assertEquals(direct, AckTimeout.waitFor(direct, isFlood = false, knownHops = null))
    }

    @Test
    fun `a flood to a contact we have never placed is left alone`() {
        // No distance known is not the same as zero distance. Inventing
        // one would be a guess with a number on it.
        assertEquals(
            floodEstimate,
            AckTimeout.waitFor(floodEstimate, isFlood = true, knownHops = null),
        )
    }

    @Test
    fun `the wait never comes out shorter than the radio asked for`() {
        // A 0-hop budget (1740ms) is less than the flood estimate. Taking
        // the smaller would shorten a wait this change exists to lengthen.
        assertEquals(
            floodEstimate,
            AckTimeout.waitFor(floodEstimate, isFlood = true, knownHops = 0),
        )
    }

    // ---- hostile and broken inputs ---------------------------------------

    @Test
    fun `an absurd hop count is not spent as a budget`() {
        // The firmware's field is six bits. 64 did not come off the wire
        // intact, and 200 hops would park a send for the clamp's maximum.
        assertEquals(
            floodEstimate,
            AckTimeout.waitFor(floodEstimate, isFlood = true, knownHops = 64),
        )
        assertEquals(
            floodEstimate,
            AckTimeout.waitFor(floodEstimate, isFlood = true, knownHops = -1),
        )
    }

    @Test
    fun `every answer stays inside the floor and the ceiling`() {
        assertEquals(
            AckTimeout.MIN_WAIT_MS,
            AckTimeout.waitFor(1, isFlood = false, knownHops = null),
        )
        assertEquals(
            AckTimeout.MAX_WAIT_MS,
            AckTimeout.waitFor(10_000_000, isFlood = false, knownHops = null),
        )
        // A credible-but-huge distance clamps rather than overflowing the
        // send into next week.
        assertEquals(
            AckTimeout.MAX_WAIT_MS,
            AckTimeout.waitFor(floodEstimate, isFlood = true, knownHops = 63),
        )
    }

    @Test
    fun `a negative estimate cannot produce a negative wait`() {
        assertTrue(AckTimeout.waitFor(-1, isFlood = true, knownHops = 5) >= AckTimeout.MIN_WAIT_MS)
    }

    // ---- which distance signal wins --------------------------------------

    @Test
    fun `the larger distance signal wins`() {
        // A 2-hop stored path is exactly the figure that is stale when a
        // flood is needed. If their messages reach us across 8, the round
        // trip is 8-ish, not 2.
        assertEquals(8, AckTimeout.estimateHops(storedPathHops = 2, lastHeardHops = 8))
        assertEquals(8, AckTimeout.estimateHops(storedPathHops = 8, lastHeardHops = 2))
    }

    @Test
    fun `either signal alone is enough and neither is null`() {
        assertEquals(6, AckTimeout.estimateHops(storedPathHops = 6, lastHeardHops = null))
        assertEquals(6, AckTimeout.estimateHops(storedPathHops = null, lastHeardHops = 6))
        assertNull(AckTimeout.estimateHops(storedPathHops = null, lastHeardHops = null))
    }

    @Test
    fun `a routed arrival's sentinel is not read as a distance`() {
        // -1 means "no count was reported", and reading it as zero would
        // quietly shorten the wait for the nodes that most need it long.
        assertNull(AckTimeout.estimateHops(storedPathHops = null, lastHeardHops = -1))
        assertEquals(3, AckTimeout.estimateHops(storedPathHops = 3, lastHeardHops = -1))
    }
}
