package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.presentation.NeighbourFetch
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.Neighbours
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NeighbourBookTest {

    private val rptr = "13".repeat(32)
    private fun n(prefix: String, snr: Double = 6.5) = Neighbours.Neighbour(prefix, 60, snr)

    @Test
    fun theFirstPageReplacesAndLaterPagesAdd() {
        val b = NeighbourBook()
        b.record(rptr, 0, Neighbours.Table(3, listOf(n("aaaaaaaaaaaa"), n("bbbbbbbbbbbb")), 0), 1)
        b.record(rptr, 2, Neighbours.Table(3, listOf(n("cccccccccccc")), 2), 2)
        assertEquals(3, b.forRepeater(rptr).size)
        b.record(rptr, 0, Neighbours.Table(1, listOf(n("dddddddddddd")), 0), 3)
        assertEquals(listOf("dddddddddddd"), b.forRepeater(rptr).map { it.keyPrefixHex })
    }

    @Test
    fun aRejectedRequestNeverWipesAGoodReading() {
        val b = NeighbourBook()
        b.record(rptr, 0, Neighbours.Table(1, listOf(n("aaaaaaaaaaaa")), 0), 1)
        // "Knows 4, returned none": a refusal, not an empty table.
        b.record(rptr, 0, Neighbours.Table(4, emptyList(), 0), 2)
        assertEquals(1, b.forRepeater(rptr).size)
    }

    @Test
    fun collectionPagesUntilTheTableIsWhole() = runTest {
        val b = NeighbourBook()
        val asked = mutableListOf<Int>()
        val out = b.collect(rptr, 1) { offset ->
            asked += offset
            if (offset == 0) Neighbours.Table(3, listOf(n("aaaaaaaaaaaa"), n("bbbbbbbbbbbb")), 0)
            else Neighbours.Table(3, listOf(n("cccccccccccc")), offset)
        }
        assertEquals(listOf(0, 2), asked)
        val got = assertIs<NeighbourFetch.Collected>(out)
        assertEquals(3, got.count)
        assertEquals(3, b.forRepeater(rptr).size)
    }

    @Test
    fun silenceFirstIsNoAnswerAndARefusalIsSaidAsOne() = runTest {
        assertIs<NeighbourFetch.NoAnswer>(NeighbourBook().collect(rptr, 1) { null })
        assertIs<NeighbourFetch.Rejected>(NeighbourBook().collect(rptr, 1) { Neighbours.Table(4, emptyList(), 0) })
    }

    @Test
    fun collectionStopsAtThePageLimit() = runTest {
        var pages = 0
        NeighbourBook().collect(rptr, 1) { offset ->
            pages++
            Neighbours.Table(10_000, listOf(n((offset + 1).toString(16).padStart(12, '0'))), offset)
        }
        assertEquals(NeighbourBook.PAGE_LIMIT, pages)
    }

    @Test
    fun linksResolvePrefixesAgainstContacts() {
        val b = NeighbourBook()
        val ridge = Contact(
            publicKey = ByteArray(32) { 0xAA.toByte() }, type = Codes.ADV_TYPE_REPEATER, flags = 0, pathLen = 0xFF,
            path = ByteArray(64), name = "Ridge", timestamp = 0, latitude = 42.9, longitude = -85.6, lastModified = 0,
        )
        b.record(rptr, 0, Neighbours.Table(2, listOf(n("aaaaaaaaaaaa"), n("eeeeeeeeeeee")), 0), 1)
        val links = b.links(rptr, listOf(ridge), 1)
        assertEquals(2, links.size)
        assertTrue(links.single { it.isDrawable }.label.contains("Ridge"))
    }
}
