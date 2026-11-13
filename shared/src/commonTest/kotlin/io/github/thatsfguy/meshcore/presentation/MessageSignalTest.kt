package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageSignalTest {

    @Test
    fun `a message heard direct shows the sender's own signal plainly`() {
        assertEquals("6.5 dB", MessageSignal.bubble(6.5, hops = 0))
    }

    @Test
    fun `a relayed message says the signal is the last hop's`() {
        assertEquals("last hop 6.5 dB", MessageSignal.bubble(6.5, hops = 2))
        assertEquals("last hop 6.5 dB", MessageSignal.bubble(6.5, hops = 1))
    }

    @Test
    fun `routed or unknown arrivals are not presumed direct`() {
        // A routed arrival records no hop count, and an unknown one none at
        // all; neither is evidence the sender was heard direct.
        assertEquals("last hop -3.0 dB", MessageSignal.bubble(-3.0, hops = -1))
        assertEquals("last hop -3.0 dB", MessageSignal.bubble(-3.0, hops = null))
    }

    @Test
    fun `no SNR shows nothing`() {
        assertNull(MessageSignal.bubble(null, 0))
        assertNull(MessageSignal.info(null, 2))
    }

    @Test
    fun `quarter-dB values round to one place and keep their sign`() {
        assertEquals("-5.5 dB", MessageSignal.db(-5.5))
        assertEquals("7.3 dB", MessageSignal.db(7.25))
        assertEquals("-0.3 dB", MessageSignal.db(-0.25))
        assertEquals("0.0 dB", MessageSignal.db(0.0))
    }

    @Test
    fun `the info sheet names whose signal it is`() {
        assertTrue("sender's own" in MessageSignal.info(9.0, 0)!!)
        assertTrue("not the sender's" in MessageSignal.info(9.0, 3)!!)
    }
}
