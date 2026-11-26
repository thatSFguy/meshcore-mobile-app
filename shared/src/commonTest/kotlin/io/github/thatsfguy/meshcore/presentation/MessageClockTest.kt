package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageClockTest {

    private val now = 1_764_000_000L
    private val nowMs = now * 1000

    @Test
    fun aClockAheadOfUsShowsWhenItArrived() {
        // Reported 2026-11-26: nodes sending dates in the future.
        assertEquals(now, MessageClock.shownSec(now + 3 * 86_400, nowMs, outgoing = false))
        assertEquals(now, MessageClock.shownSec(now + 1, nowMs, outgoing = false), "even a second ahead")
    }

    @Test
    fun aQueuedBacklogKeepsTheTimesItWasSent() {
        // Positive control: the sender's clock IS used when it can be
        // true. Without this, "always show arrival" would pass the rest.
        val eightHoursAgo = now - 8 * 3600
        assertEquals(eightHoursAgo, MessageClock.shownSec(eightHoursAgo, nowMs, outgoing = false))
        assertEquals(now, MessageClock.shownSec(now, nowMs, outgoing = false), "same second")
    }

    @Test
    fun anUnsetOrHostileClockShowsArrival() {
        assertEquals(now, MessageClock.shownSec(0, nowMs, outgoing = false))
        assertEquals(now, MessageClock.shownSec(-5, nowMs, outgoing = false))
        assertEquals(now, MessageClock.shownSec(Long.MAX_VALUE, nowMs, outgoing = false))
        assertEquals(now, MessageClock.shownSec(0xFFFF_FFFFL, nowMs, outgoing = false), "u32 max")
    }

    @Test
    fun ourOwnMessagesShowOurClock() {
        assertEquals(now, MessageClock.shownSec(now - 100, nowMs, outgoing = true))
    }
}
