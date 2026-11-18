package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.protocol.PathHistoryHygiene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathHistoryBookTest {

    private var clock = 1_000L
    private fun book() = PathHistoryBook({ clock++ })
    private val al = "AA".repeat(32)

    @Test
    fun aRouteIsRememberedOnceAndCountedInHopsAtItsWidth() {
        val b = book()
        // b389c985 at 2 bytes per hop: two hops, not four.
        b.remember(al, "B389C985", 2)
        b.remember(al, "b389c985", 2)
        val row = b.forContact(al).single()
        assertEquals("b389c985", row.pathHex)
        assertEquals(2, row.hops)
        assertEquals("aa".repeat(32), row.contactKey)
    }

    @Test
    fun aRouteThatIsNotWholeHopsIsRefused() {
        val b = book()
        b.remember(al, "b389c9", 2) // 3 bytes at width 2
        assertTrue(b.forContact(al).isEmpty())
    }

    @Test
    fun theFloodRouteIsARowToo() {
        val b = book()
        b.score(al, "", delivered = true, hashWidth = 2)
        val flood = b.forContact(al).single()
        assertTrue(flood.isFlood)
        assertEquals(1, flood.successes)
        assertEquals("flood", PathHistoryBook.hopsLine(flood, 2))
    }

    @Test
    fun scoringOrdersProvenRoutesFirst() {
        val b = book()
        b.remember(al, "1111", 2)
        b.remember(al, "2222", 2)
        b.score(al, "2222", delivered = true, hashWidth = 2)
        b.score(al, "1111", delivered = false, hashWidth = 2)
        assertEquals(listOf("2222", "1111"), b.forContact(al).map { it.pathHex })
        val r = b.forContact(al)[0]
        assertEquals(
            "${io.github.thatsfguy.meshcore.protocol.PathCodec.qualityLabel(1, 0, false)} · 1 hop(s) · 1✓/0✗ · worked today",
            PathHistoryBook.recordLine(r, 2, "today"),
        )
    }

    @Test
    fun pruningKeepsTheFloodRowAndTheProvenOnes() {
        val b = book()
        b.score(al, "", delivered = false, hashWidth = 1)
        b.remember(al, "01", 1)
        b.score(al, "01", delivered = true, hashWidth = 1)
        repeat(PathHistoryHygiene.MAX_PER_CONTACT + 5) { b.remember(al, (0x10 + it).toString(16).padStart(2, '0'), 1) }
        val rows = b.forContact(al)
        assertEquals(PathHistoryHygiene.MAX_PER_CONTACT, rows.size)
        assertTrue(rows.any { it.isFlood }, "the flood row is never pruned")
        assertTrue(rows.any { it.pathHex == "01" }, "a proven route outlives untested ones")
    }

    @Test
    fun repairDropsRowsTheMeshWidthCannotSplit() {
        // A 1-byte-width row of three bytes cannot be whole hops at width 2.
        val b = book()
        b.remember(al, "b389c9", 1)
        b.remember(al, "b389c985", 1)
        assertEquals(1, b.repair(2))
        val kept = b.forContact(al).single()
        assertEquals(2, kept.hops)
        assertEquals(2, kept.hashWidth)
    }

    @Test
    fun itRoundTripsAndAnUnreadableFileIsEmpty() {
        val b = book()
        b.remember(al, "b389c985", 2)
        b.score(al, "b389c985", delivered = true, hashWidth = 2)
        val back = PathHistoryBook.fromJson(b.toJson()) { 0 }
        assertEquals(b.rows.value, back.rows.value)
        assertTrue(PathHistoryBook.fromJson("{not json", { 0 }).rows.value.isEmpty())
        assertTrue(PathHistoryBook.fromJson(null, { 0 }).rows.value.isEmpty())
    }

    @Test
    fun deleteAndClearAreForOneContact() {
        val b = book()
        b.remember(al, "1111", 2)
        b.remember(al, "2222", 2)
        b.remember("bb".repeat(32), "1111", 2)
        b.delete(al, "1111")
        assertEquals(listOf("2222"), b.forContact(al).map { it.pathHex })
        b.clear(al)
        assertTrue(b.forContact(al).isEmpty())
        assertEquals(1, b.forContact("bb".repeat(32)).size)
    }
}
