package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeardReachTest {

    @Test
    fun aNodeHeardDirectShowsItsOwnSignal() {
        assertEquals("direct · 9.5 dB", HeardReach.of(0, 9.5))
        assertEquals("direct · -4.2 dB", HeardReach.of(0, -4.2))
    }

    @Test
    fun aRelayedNodeShowsItsHopsAndNoSignal() {
        // The SNR of a relayed copy is the last repeater's; there is no
        // parameter for it, so it cannot leak in.
        assertEquals("1 hop", HeardReach.of(1, null))
        assertEquals("3 hops", HeardReach.of(3, null))
    }

    @Test
    fun aRelayedNodeOnceHeardDirectIsStillDirect() {
        // minHops is the fewest ever; a later relayed copy does not undo it.
        assertEquals("direct · 7.0 dB", HeardReach.of(0, 7.0))
    }

    @Test
    fun directWithoutADirectReadingSaysDirectAlone() {
        // Heard direct before the direct signal was recorded (pre-0.10.5).
        assertEquals("direct", HeardReach.of(0, null))
    }

    @Test
    fun aDirectReadingIsIgnoredWhenTheNodeIsNotDirect() {
        assertEquals("2 hops", HeardReach.of(2, 9.5))
    }

    @Test
    fun noHopCountClaimsNothing() {
        assertNull(HeardReach.of(null, null))
        assertNull(HeardReach.of(null, 9.5))
        assertNull(HeardReach.of(-1, null))
    }
}
