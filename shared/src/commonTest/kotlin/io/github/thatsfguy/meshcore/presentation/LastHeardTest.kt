package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "When did we last hear from this node" — the one definition.
 *
 * Every test here is really the same test: the answer comes from OUR
 * radio's clock and never from the node's claim about itself.
 */
class LastHeardTest {

    private val now = 1_770_000_000L

    private class Node(
        override val keyHex: String = "aa",
        override val name: String = "n",
        override val lastSeen: Long = 0,
        override val lastModified: Long = 0,
        override val lastMessageAt: Long = 0,
        override val flags: Int = 0,
        override val pathLen: Int = 0,
        override val unread: Int = 0,
    ) : NodeListItem

    @Test
    fun `the answer is our radio's stamp and not the node's claim`() {
        // Live capture, 2026-08-24: a repeater heard that morning
        // advertising a near-zero timestamp, rendered everywhere as
        // "20688 days ago · Jan 1, 1970".
        val brokenClock = Node(lastSeen = 1, lastModified = now - 60)
        assertEquals(now - 60, LastHeard.seconds(brokenClock))
        assertEquals((now - 60) * 1000L, LastHeard.millis(brokenClock))
    }

    @Test
    fun `never heard is zero and not a small number`() {
        assertEquals(0, LastHeard.seconds(Node()))
        // A node that has advertised a claim but that we have never
        // logged hearing is still "never heard": the claim is not
        // evidence.
        assertEquals(0, LastHeard.seconds(Node(lastSeen = now)))
    }

    @Test
    fun `a negative stamp stays negative so it sorts last`() {
        // Clamping to 0 would make it tie with never-heard and then sort
        // alphabetically; descending order must keep it at the bottom.
        assertTrue(LastHeard.seconds(Node(lastModified = -1)) < 0)
    }

    // --- Telling the user their neighbour's clock is wrong ------------

    @Test
    fun `an ordinary skew is not worth mentioning`() {
        // Both clocks are real clocks. Minutes apart is nothing, and a
        // row that appeared on every node would say nothing.
        assertFalse(LastHeard.claimDisagrees(Node(lastSeen = now + 300, lastModified = now)))
        assertFalse(LastHeard.claimDisagrees(Node(lastSeen = now - 300, lastModified = now)))
        assertFalse(
            LastHeard.claimDisagrees(
                Node(lastSeen = now - LastHeard.CLAIM_TOLERANCE_SECONDS, lastModified = now),
            ),
        )
    }

    @Test
    fun `a clock that is wrong by more than a day is worth saying`() {
        // Wrong in both directions: a stopped clock reading 1970, and
        // one running ahead.
        assertTrue(LastHeard.claimDisagrees(Node(lastSeen = 1, lastModified = now)))
        assertTrue(
            LastHeard.claimDisagrees(Node(lastSeen = now + 400_000, lastModified = now)),
        )
    }

    @Test
    fun `nothing is claimed about a node with nothing to compare`() {
        // No claim, or nothing heard: silence, not an accusation.
        assertFalse(LastHeard.claimDisagrees(Node(lastSeen = 0, lastModified = now)))
        assertFalse(LastHeard.claimDisagrees(Node(lastSeen = now, lastModified = 0)))
        assertFalse(LastHeard.claimDisagrees(Node()))
    }
}
