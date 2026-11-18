package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.presentation.NodeListItem
import io.github.thatsfguy.meshcore.protocol.Codes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaleSweepTest {

    private val now = 1_770_000_000_000L
    private fun daysAgo(d: Int) = (now - d * 86_400_000L) / 1000

    private class Node(
        override val keyHex: String,
        override val name: String = keyHex,
        override val lastModified: Long = 0,
        override val lastSeen: Long = 0,
        override val lastMessageAt: Long = 0,
        override val flags: Int = 0,
        override val pathLen: Int = 0,
        override val unread: Int = 0,
    ) : NodeListItem

    private class FakeRadio(val refuse: Set<String> = emptySet()) : StaleSweep.Radio {
        val asked = mutableListOf<String>()
        override suspend fun remove(keyHex: String): Boolean { asked += keyHex; return keyHex !in refuse }
    }

    private val nodes = listOf(
        Node("old", lastModified = daysAgo(30)),
        Node("older", lastModified = daysAgo(40)),
        Node("fav", lastModified = daysAgo(40), flags = Codes.CONTACT_FLAG_FAVORITE),
        Node("qr"),
        Node("fresh", lastModified = daysAgo(2)),
    )

    @Test
    fun onlyTheSweptNodesAreRemovedOldestFirst() = runTest {
        val radio = FakeRadio()
        val sweep = StaleSweep.sweep(nodes, 14, now)
        val result = StaleSweep(radio).run(sweep)
        assertEquals(listOf("older", "old"), radio.asked, "favourite, never-heard and fresh are never asked")
        assertEquals(listOf("older", "old"), result.removedKeys)
        assertEquals("Removed 2 nodes.", result.message)
    }

    @Test
    fun aRefusalIsCountedAndTheRestContinue() = runTest {
        val result = StaleSweep(FakeRadio(refuse = setOf("older"))).run(StaleSweep.sweep(nodes, 14, now))
        assertEquals(listOf("old"), result.removedKeys)
        assertEquals("Removed 1; the radio refused 1 node.", result.message)
    }

    @Test
    fun aThrowingRadioIsARefusal() = runTest {
        val radio = object : StaleSweep.Radio {
            override suspend fun remove(keyHex: String): Boolean = error("link gone")
        }
        val result = StaleSweep(radio).run(StaleSweep.sweep(nodes, 14, now))
        assertTrue(result.removedKeys.isEmpty())
        assertEquals("The radio refused to remove 2 nodes.", result.message)
    }

    @Test
    fun thePreviewNamesTheFirstFewAndCountsTheRest() {
        val many = (1..9).map { Node("n$it", lastModified = daysAgo(20 + it)) }
        val sweep = StaleSweep.sweep(many, 14, now)
        assertEquals(StaleSweep.PREVIEW_ROWS, StaleSweep.previewLines(sweep, now).size)
        assertEquals("…and 3 more", StaleSweep.moreLine(sweep))
        assertNull(StaleSweep.moreLine(StaleSweep.sweep(many.take(2), 14, now)))
    }
}
