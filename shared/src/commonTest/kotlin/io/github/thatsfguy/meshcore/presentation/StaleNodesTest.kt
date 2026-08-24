package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Codes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stale-node sweep.
 *
 * This deletes contacts off the radio in bulk, against a list the user
 * has not read, so the tests that matter are the ones about what it
 * must NOT take. Every one of those would pass if the sweep did nothing
 * at all, which is why `a genuinely stale node is removed` is here as
 * the positive control.
 */
class StaleNodesTest {

    private val now = 1_770_000_000_000L
    private fun daysAgo(d: Int) = now - d * 86_400_000L

    private class Node(
        override val keyHex: String,
        override val name: String = keyHex,
        /** OUR radio's clock — the only "last heard" that counts. */
        override val lastModified: Long = 0,
        /** The node's OWN claim, which must not be read here. */
        override val lastSeen: Long = 0,
        override val lastMessageAt: Long = 0,
        override val flags: Int = 0,
        override val pathLen: Int = 0,
        override val unread: Int = 0,
    ) : NodeListItem

    private fun heard(key: String, days: Int, flags: Int = 0) =
        Node(key, lastModified = daysAgo(days) / 1000, flags = flags)

    // --- The positive control ----------------------------------------

    @Test
    fun `a genuinely stale node is removed`() {
        val sweep = StaleNodes.sweep(listOf(heard("old", 20)), olderThanDays = 14, nowMillis = now)
        assertEquals(listOf("old"), sweep.remove.map { it.keyHex })
        assertEquals(1, sweep.count)
    }

    // --- What it must never take -------------------------------------

    @Test
    fun `a favourite is never removed however old it is`() {
        // The one mark the user put on a node by hand. A bulk action
        // must not be able to undo it — not at 30 days, not at 3.
        val ancient = heard("star", days = 3650, flags = Codes.CONTACT_FLAG_FAVORITE)
        for (days in StaleNodes.MIN_DAYS..StaleNodes.MAX_DAYS) {
            val sweep = StaleNodes.sweep(listOf(ancient), days, now)
            assertTrue(sweep.remove.isEmpty(), "a favourite was swept at $days days")
            assertEquals(1, sweep.favouritesKept)
        }
    }

    @Test
    fun `a node you were talking to yesterday is not stale`() {
        // Evidence is evidence. Its advert is a month old, but there is
        // a message from yesterday, so the node plainly exists —
        // deleting the other end of a live conversation is exactly the
        // surprise that makes a sweep untrustworthy.
        val chatty = Node(
            "chatty",
            lastModified = daysAgo(30) / 1000,
            lastMessageAt = daysAgo(1),
        )
        val sweep = StaleNodes.sweep(listOf(chatty), olderThanDays = 14, nowMillis = now)
        assertTrue(sweep.remove.isEmpty())
        assertEquals(1, sweep.freshKept)
    }

    @Test
    fun `a node with no last-heard at all is kept rather than treated as ancient`() {
        // A contact just added from a QR code has no advert and no
        // messages. Reading a missing timestamp as 1970 would delete
        // every freshly imported contact on the first sweep.
        val justAdded = Node("qr")
        val sweep = StaleNodes.sweep(listOf(justAdded), olderThanDays = 3, nowMillis = now)
        assertTrue(sweep.remove.isEmpty())
        assertEquals(1, sweep.neverHeardKept)
    }

    @Test
    fun `a node with a wrong clock is not swept for having one`() {
        // THE test in this file. Driven on a live mesh, real nodes that
        // were transmitting reported advert timestamps 830 days old —
        // and one 20 688 days old — because their own RTCs are wrong.
        // The advert timestamp is the sender's claim, kept by the
        // firmware for replay detection; our radio's `lastmod` is when
        // it actually heard them. Reading the wrong one deletes a live
        // node as a punishment for a bad clock.
        val badClock = Node(
            "badclock",
            lastModified = daysAgo(1) / 1000,      // our radio heard it yesterday
            lastSeen = daysAgo(830) / 1000,        // its own advert claims 2024
        )
        assertTrue(StaleNodes.sweep(listOf(badClock), 3, now).remove.isEmpty())
        assertEquals(1, StaleNodes.sweep(listOf(badClock), 3, now).freshKept)

        // And the mirror: a node whose advert claims RIGHT NOW but that
        // our radio has not heard in a year is stale, whatever it says.
        val liar = Node("liar", lastModified = daysAgo(365) / 1000, lastSeen = now / 1000)
        assertEquals(listOf("liar"), StaleNodes.sweep(listOf(liar), 30, now).remove.map { it.keyHex })
    }

    @Test
    fun `seconds and milliseconds are not mixed up`() {
        // lastModified is epoch SECONDS and lastMessageAt is local
        // MILLIS. Comparing them raw puts every node in 1970 and sweeps
        // the whole contact list.
        val heardAnHourAgo = Node("fresh", lastModified = (now - 3_600_000L) / 1000)
        assertEquals(
            now - 3_600_000L,
            StaleNodes.lastEvidenceMillis(heardAnHourAgo),
            "a last-heard timestamp was read as milliseconds",
        )
        assertTrue(StaleNodes.sweep(listOf(heardAnHourAgo), 3, now).remove.isEmpty())
    }

    // --- The boundary -------------------------------------------------

    @Test
    fun `the cutoff is the slider position to the day`() {
        val nodes = listOf(heard("thirteen", 13), heard("fifteen", 15))
        val sweep = StaleNodes.sweep(nodes, olderThanDays = 14, nowMillis = now)
        assertEquals(listOf("fifteen"), sweep.remove.map { it.keyHex })
        assertEquals(1, sweep.freshKept)
    }

    @Test
    fun `a node heard exactly at the cutoff is kept`() {
        // Ties go to keeping it. The user asked for "older than", and a
        // destructive default must round towards doing less.
        val exact = Node("edge", lastModified = daysAgo(14) / 1000)
        assertTrue(StaleNodes.sweep(listOf(exact), 14, now).remove.isEmpty())
    }

    @Test
    fun `a slider position outside the range cannot empty the list`() {
        // The last place before a delete loop. 0 days would take
        // everything; the range is what the UI offers and what this
        // enforces.
        val recent = heard("today", 1)
        val old = heard("old", 40)
        val zero = StaleNodes.sweep(listOf(recent, old), olderThanDays = 0, nowMillis = now)
        assertEquals(listOf("old"), zero.remove.map { it.keyHex })
        val huge = StaleNodes.sweep(listOf(recent, old), olderThanDays = 9999, nowMillis = now)
        assertEquals(listOf("old"), huge.remove.map { it.keyHex })
    }

    @Test
    fun `the oldest go first`() {
        // If the sweep is interrupted part way through — the radio drops
        // out — what went is what was least likely to be wanted.
        val nodes = listOf(heard("b", 20), heard("c", 40), heard("a", 16))
        val sweep = StaleNodes.sweep(nodes, 14, now)
        assertEquals(listOf("c", "b", "a"), sweep.remove.map { it.keyHex })
    }

    // --- What it tells the user before they press it ------------------

    @Test
    fun `the counts add up to the list that was swept`() {
        val nodes = listOf(
            heard("old", 20),
            heard("older", 25),
            heard("star", 100, flags = Codes.CONTACT_FLAG_FAVORITE),
            heard("recent", 2),
            Node("qr"),
        )
        val sweep = StaleNodes.sweep(nodes, 14, now)
        assertEquals(2, sweep.count)
        assertEquals(1, sweep.favouritesKept)
        assertEquals(1, sweep.neverHeardKept)
        assertEquals(1, sweep.freshKept)
        assertEquals(nodes.size, sweep.total)
    }

    @Test
    fun `the button says how many it will take`() {
        assertEquals("Nothing to remove", StaleNodes.actionLabel(StaleNodes.sweep(emptyList(), 14, now)))
        assertEquals(
            "Remove 1 node",
            StaleNodes.actionLabel(StaleNodes.sweep(listOf(heard("a", 20)), 14, now)),
        )
        assertEquals(
            "Remove 2 nodes",
            StaleNodes.actionLabel(StaleNodes.sweep(listOf(heard("a", 20), heard("b", 20)), 14, now)),
        )
    }

    @Test
    fun `what is spared is named before the button is pressed`() {
        val sweep = StaleNodes.sweep(
            listOf(
                heard("star", 100, flags = Codes.CONTACT_FLAG_FAVORITE),
                Node("qr"),
                heard("recent", 1),
                heard("old", 20),
            ),
            14,
            now,
        )
        assertEquals(
            "Keeping 1 favourite, 1 never heard from and 1 heard since.",
            StaleNodes.keptNote(sweep),
        )
        assertNull(StaleNodes.keptNote(StaleNodes.sweep(listOf(heard("old", 20)), 14, now)))
    }

    @Test
    fun `a plural favourite reads as one`() {
        val sweep = StaleNodes.sweep(
            listOf(
                heard("a", 100, flags = Codes.CONTACT_FLAG_FAVORITE),
                heard("b", 100, flags = Codes.CONTACT_FLAG_FAVORITE),
            ),
            14,
            now,
        )
        assertEquals("Keeping 2 favourites.", StaleNodes.keptNote(sweep))
    }

    // --- The report afterwards ---------------------------------------

    @Test
    fun `a refusal is reported rather than folded into the count`() {
        // The radio owns the contact list; a removal it declines has not
        // happened, and saying "removed 12" would be a lie the next
        // contact sync corrects.
        assertEquals("Removed 12 nodes.", StaleNodes.outcome(removed = 12, failed = 0))
        assertEquals("Removed 1 node.", StaleNodes.outcome(removed = 1, failed = 0))
        assertEquals(
            "Removed 10; the radio refused 2 nodes.",
            StaleNodes.outcome(removed = 10, failed = 2),
        )
        assertEquals(
            "The radio refused to remove 1 node.",
            StaleNodes.outcome(removed = 0, failed = 1),
        )
        assertEquals("Nothing was removed.", StaleNodes.outcome(removed = 0, failed = 0))
    }
}
