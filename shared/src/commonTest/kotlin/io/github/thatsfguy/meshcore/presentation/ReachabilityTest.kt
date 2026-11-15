package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.presentation.Reachability.Evidence
import io.github.thatsfguy.meshcore.presentation.Reachability.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReachabilityTest {

    private val now = 1_800_000_000L
    private val day = 86_400L

    private fun state(e: Evidence) = Reachability.of(e, now).state

    @Test
    fun `a delivered message this week is two way`() {
        // Positive control: the strongest evidence must yield the strongest
        // answer. Every "declines to claim" test below would pass if the
        // model never said two-way at all.
        val v = Reachability.of(Evidence(confirmedAt = now - 2 * day, heardThem = true), now)
        assertEquals(State.TwoWay, v.state)
        assertEquals("Two-way · confirmed 2 days ago", v.line)
        assertTrue(v.isTwoWay)
    }

    @Test
    fun `proof ages into two way before at seven days`() {
        assertEquals(State.TwoWay, state(Evidence(confirmedAt = now - Reachability.FRESH_SECONDS)))
        val old = Reachability.of(Evidence(confirmedAt = now - Reachability.FRESH_SECONDS - 1), now)
        assertEquals(State.TwoWayBefore, old.state)
        assertEquals("Two-way before · last confirmed 7 days ago", old.line)
        assertTrue(old.isTwoWay)
    }

    @Test
    fun `a proof stamped in the future still counts as now`() {
        val v = Reachability.of(Evidence(confirmedAt = now + 3_600), now)
        assertEquals(State.TwoWay, v.state)
        assertEquals("Two-way · confirmed just now", v.line)
    }

    @Test
    fun `a repeater that repeats us is two way without a date`() {
        assertEquals(State.TwoWay, state(Evidence(repeatsUs = true, heardThem = true)))
        // ...but a fresh dated proof says more, so it wins the wording.
        val v = Reachability.of(Evidence(confirmedAt = now - 60, repeatsUs = true), now)
        assertEquals("Two-way · confirmed 1 min ago", v.line)
        // And an old dated proof does not hide that it repeats us today.
        assertEquals(
            "Two-way · repeats your messages",
            Reachability.of(Evidence(confirmedAt = now - 30 * day, repeatsUs = true), now).line,
        )
    }

    @Test
    fun `a route they sent back means they heard us`() {
        assertEquals(State.TheyHeardYou, state(Evidence(routeFromThem = true, heardThem = true)))
        // An old two-way proof is the stronger statement.
        assertEquals(
            State.TwoWayBefore,
            state(Evidence(confirmedAt = now - 30 * day, routeFromThem = true)),
        )
    }

    @Test
    fun `hearing them is never two way on its own`() {
        // The Chicago case.
        val v = Reachability.of(Evidence(heardThem = true), now)
        assertEquals(State.HeardOnly, v.state)
        assertEquals("Heard only · nothing shows they can hear you", v.line)
        assertFalse(v.isTwoWay)
        assertEquals(
            "Heard only · nothing you've sent has reached them",
            Reachability.of(Evidence(heardThem = true, tried = true), now).line,
        )
    }

    @Test
    fun `sending without any answer is no answer and not unreachable`() {
        val v = Reachability.of(Evidence(tried = true), now)
        assertEquals(State.NoAnswer, v.state)
        assertFalse("unreachable" in v.line.lowercase())
    }

    @Test
    fun `nothing known says nothing`() {
        val v = Reachability.of(Evidence(), now)
        assertEquals(State.Unknown, v.state)
        assertEquals("", v.line)
        assertEquals(null, v.short)
    }

    @Test
    fun `every state has a short label except unknown`() {
        for (s in State.entries) {
            val short = Reachability.Verdict(s, "x").short
            if (s == State.Unknown) assertEquals(null, short) else assertTrue(!short.isNullOrBlank(), "$s")
        }
    }

    @Test
    fun `far means heard only and four hops or more`() {
        val heardOnly = Reachability.of(Evidence(heardThem = true), now)
        assertFalse(Reachability.isFarHeardOnly(heardOnly, 3))
        assertTrue(Reachability.isFarHeardOnly(heardOnly, 4))
        assertTrue(Reachability.isFarHeardOnly(heardOnly, 9))
        // Unknown hops is not far: that would hide nodes for lack of data.
        assertFalse(Reachability.isFarHeardOnly(heardOnly, null))
        // A far node that has proven itself is not folded away.
        val proven = Reachability.of(Evidence(heardThem = true, repeatsUs = true), now)
        assertFalse(Reachability.isFarHeardOnly(proven, 9))
        val pinged = Reachability.of(Evidence(heardThem = true, confirmedAt = now - 40 * day), now)
        assertFalse(Reachability.isFarHeardOnly(pinged, 9))
    }

    @Test
    fun `far also means over 100 km when the position is known`() {
        // The case that made hops alone wrong: Chicago, 3 hops, 151 mi.
        val heardOnly = Reachability.of(Evidence(heardThem = true), now)
        assertTrue(Reachability.isFarHeardOnly(heardOnly, 3, metres = 243_000.0))
        assertTrue(Reachability.isFarHeardOnly(heardOnly, null, metres = 100_000.0))
        assertFalse(Reachability.isFarHeardOnly(heardOnly, 3, metres = 99_999.0))
        assertFalse(Reachability.isFarHeardOnly(heardOnly, 3, metres = null))
        // Distance never folds away a node that has proven itself.
        val proven = Reachability.of(Evidence(heardThem = true, repeatsUs = true), now)
        assertFalse(Reachability.isFarHeardOnly(proven, 3, metres = 243_000.0))
    }

    @Test
    fun `the add warning says what is known and nothing more`() {
        assertTrue(Reachability.addWarning(3, "151 mi").startsWith("This node is 3 hops, 151 mi away, and nothing"))
        assertTrue(Reachability.addWarning(5, null).startsWith("This node is 5 hops, and nothing"))
        assertTrue(Reachability.addWarning(1, null).startsWith("This node is 1 hop,"))
        assertTrue(Reachability.addWarning(null, null).startsWith("Nothing shows"))
    }

    @Test
    fun `a check that came back names both routes`() {
        assertEquals(
            "Two-way. Your messages reach them through Hill, 13 Mile; theirs reach you directly.",
            Reachability.describeCheck(listOf("Hill", "13 Mile"), emptyList(), asksPermission = true),
        )
    }

    @Test
    fun `silence from a person is explained and not called unreachable`() {
        val text = Reachability.describeCheck(null, null, asksPermission = true)
        assertTrue(text.startsWith("No answer."))
        assertTrue("allows it" in text)
        assertFalse("unreachable" in text.lowercase())
        val node = Reachability.describeCheck(null, null, asksPermission = false)
        assertFalse("allows it" in node)
    }
}
