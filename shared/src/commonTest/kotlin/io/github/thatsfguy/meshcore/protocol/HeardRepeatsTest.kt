package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who carries our traffic, recovered from our own advert coming home.
 *
 * The rule the whole file protects: **the measured SNR belongs to the
 * LAST hop and to nothing else.** A path is in travel order, so hop 0
 * heard us and the last hop reached us. Those are different links and
 * only one of them was observed — attributing the number to the wrong
 * end would report a link nobody measured, and it would look exactly
 * like a correct reading.
 */
class HeardRepeatsTest {

    private fun echo(
        path: String,
        width: Int = 2,
        snr: Double? = 8.0,
        rssi: Int? = -90,
        at: Long = 1_000L,
    ) = HeardRepeats.Echo(path, width, snr, rssi, at)

    // --- reading a path ----------------------------------------------------

    @Test
    fun `a path splits into hops at the stated width`() {
        assertEquals(listOf("aabb", "ccdd"), echo("aabbccdd").hops)
        assertEquals(listOf("aa", "bb", "cc"), echo("aabbcc", width = 1).hops)
    }

    @Test
    fun `a path that does not divide by the width yields no hops`() {
        // Malformed rather than half-read: a truncated final hop is not
        // a repeater, it is a parse failure.
        assertEquals(emptyList(), echo("aabbc").hops)
        assertFalse(echo("aabbc").isRelayed)
    }

    @Test
    fun `an empty path is not evidence of a relay`() {
        // We never hear our own transmission, so a path-less copy of our
        // own packet means something is wrong, not that zero repeaters
        // carried it.
        assertFalse(echo("").isRelayed)
        assertEquals(emptyList(), HeardRepeats.record(emptyList(), echo("")))
    }

    @Test
    fun `hops are lower-cased so the same repeater is one row`() {
        val relays = HeardRepeats.tally(listOf(echo("AABB"), echo("aabb")))
        assertEquals(1, relays.size)
        assertEquals(2, relays.single().relayed)
    }

    // --- the rule that must not bend ---------------------------------------

    @Test
    fun `SNR is credited to the hop that transmitted the copy we heard`() {
        // Path [aabb, ccdd]: ccdd transmitted it, so ccdd is the only
        // hop whose link to us was measured.
        val relays = HeardRepeats.tally(listOf(echo("aabbccdd", snr = 11.5)))
        val first = relays.single { it.hashHex == "aabb" }
        val last = relays.single { it.hashHex == "ccdd" }
        assertNull(first.bestSnr, "credited SNR to a hop we never heard transmit")
        assertEquals(11.5, last.bestSnr)
    }

    @Test
    fun `hop zero heard us and the last hop reached us`() {
        val relays = HeardRepeats.tally(listOf(echo("aabbccdd")))
        val first = relays.single { it.hashHex == "aabb" }
        val last = relays.single { it.hashHex == "ccdd" }
        assertTrue(first.heardUs)
        assertFalse(first.reachedUs, "hop 0 did not reach us — another hop did")
        assertTrue(last.reachedUs)
        assertFalse(last.heardUs, "the last hop did not hear us directly")
    }

    @Test
    fun `a one-hop path is the two-way case and says so`() {
        // The positive control for the whole feature: a single repeater
        // that both picked us up and got back to us. If the feature did
        // nothing, this is the assertion that fails.
        val relays = HeardRepeats.tally(listOf(echo("b389", snr = 6.25)))
        val r = relays.single()
        assertEquals("b389", r.hashHex)
        assertTrue(r.heardUs)
        assertTrue(r.reachedUs)
        assertTrue(r.isTwoWay)
        assertEquals(6.25, r.bestSnr)
        assertTrue(HeardRepeats.direction(r).contains("and you heard it directly"))
    }

    @Test
    fun `the four direction sentences are distinct`() {
        // Each flag combination has to read differently or the column is
        // decoration.
        val base = HeardRepeats.Relay("aabb", 1, false, false, null, 0L)
        val sentences = listOf(
            base.copy(heardUs = true, reachedUs = true),
            base.copy(heardUs = true),
            base.copy(reachedUs = true),
            base,
        ).map { HeardRepeats.direction(it) }
        assertEquals(4, sentences.toSet().size, "duplicate wording: $sentences")
    }

    // --- aggregation --------------------------------------------------------

    @Test
    fun `a repeater seen on several echoes is counted once per echo`() {
        val relays = HeardRepeats.tally(
            listOf(echo("aabb", at = 1L), echo("aabbccdd", at = 2L), echo("aabb", at = 3L)),
        )
        assertEquals(3, relays.single { it.hashHex == "aabb" }.relayed)
        assertEquals(1, relays.single { it.hashHex == "ccdd" }.relayed)
    }

    @Test
    fun `a repeater appearing twice in one path counts once for that packet`() {
        // A routing loop is one repeater carrying one packet, not two.
        val relays = HeardRepeats.tally(listOf(echo("aabbccddaabb")))
        assertEquals(1, relays.single { it.hashHex == "aabb" }.relayed)
    }

    @Test
    fun `the best SNR wins, not the most recent`() {
        val relays = HeardRepeats.tally(
            listOf(echo("b389", snr = 2.0, at = 1L), echo("b389", snr = 9.0, at = 2L), echo("b389", snr = 3.0, at = 3L)),
        )
        assertEquals(9.0, relays.single().bestSnr)
    }

    @Test
    fun `an echo with no SNR does not erase a measured one`() {
        val relays = HeardRepeats.tally(
            listOf(echo("b389", snr = 7.0, at = 1L), echo("b389", snr = null, at = 2L)),
        )
        assertEquals(7.0, relays.single().bestSnr)
    }

    @Test
    fun `last-heard tracks the newest echo the repeater appeared in`() {
        val relays = HeardRepeats.tally(
            listOf(echo("aabb", at = 500L), echo("aabbccdd", at = 900L)),
        )
        assertEquals(900L, relays.single { it.hashHex == "aabb" }.lastAtMillis)
        assertEquals(900L, relays.single { it.hashHex == "ccdd" }.lastAtMillis)
    }

    @Test
    fun `busiest relay sorts first and ties break deterministically`() {
        val relays = HeardRepeats.tally(
            listOf(echo("ffff"), echo("aaaa"), echo("bbbb"), echo("bbbb")),
        )
        assertEquals("bbbb", relays.first().hashHex)
        // Equal counts: hash order, so repeated builds agree.
        assertEquals(listOf("aaaa", "ffff"), relays.drop(1).map { it.hashHex })
    }

    @Test
    fun `tally is deterministic across repeated runs`() {
        val echoes = listOf(echo("aabb", at = 1L), echo("ccddaabb", at = 2L), echo("ccdd", at = 3L))
        assertEquals(HeardRepeats.tally(echoes), HeardRepeats.tally(echoes))
    }

    // --- bounding -----------------------------------------------------------

    @Test
    fun `the echo buffer is bounded and keeps the newest`() {
        var list = emptyList<HeardRepeats.Echo>()
        for (i in 1..HeardRepeats.MAX_ECHOES + 30) {
            list = HeardRepeats.record(list, echo("aabb", at = i.toLong()))
        }
        assertEquals(HeardRepeats.MAX_ECHOES, list.size)
        assertEquals((HeardRepeats.MAX_ECHOES + 30).toLong(), list.last().atMillis)
    }

    // --- what the screen says ----------------------------------------------

    @Test
    fun `an empty screen tells you how to make it not empty`() {
        val text = HeardRepeats.summary(emptyList(), emptyList())
        assertTrue(text.contains("flood advert"), text)
    }

    @Test
    fun `the summary counts repeaters and returned copies separately`() {
        val echoes = listOf(echo("aabb"), echo("aabbccdd"))
        val text = HeardRepeats.summary(echoes, HeardRepeats.tally(echoes))
        assertTrue(text.contains("2 repeater(s)"), text)
        assertTrue(text.contains("2 returned copy(s)"), text)
    }

    @Test
    fun `the caveat says the list is a floor, not coverage`() {
        // LESSONS §18: the failure mode for a feature like this is a
        // screen that reads as a coverage map. It must say what it
        // cannot see.
        assertTrue(HeardRepeats.CAVEAT.contains("floor"))
        assertTrue(HeardRepeats.CAVEAT.contains("cannot appear here"))
    }
}
