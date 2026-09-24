package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Codes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeTabsTest {

    private data class Heard(
        override val keyHex: String,
        override val name: String,
        override val type: Int,
        override val lastHeardAt: Long,
    ) : HeardNode

    private val rptA = Heard("aa11", "Kent Hill", Codes.ADV_TYPE_REPEATER, 3_000L)
    private val rptB = Heard("bb22", "Byron Center", Codes.ADV_TYPE_REPEATER, 5_000L)
    private val chat = Heard("cc33", "Someone", Codes.ADV_TYPE_CHAT, 4_000L)
    private val room = Heard("dd44", "Club room", Codes.ADV_TYPE_ROOM, 1_000L)
    private val odd = Heard("ee55", "Mystery", 9, 2_000L)
    private val all = listOf(rptA, rptB, chat, room, odd)

    @Test
    fun `a heard node appears on the tab for its own kind and nowhere else`() {
        assertEquals(
            listOf(rptB, rptA),
            NodeTabsModel.heardFor(NodeTab.Repeaters, all, query = "", filtersActive = false),
        )
        assertEquals(
            listOf(room),
            NodeTabsModel.heardFor(NodeTab.Rooms, all, query = "", filtersActive = false),
        )
        assertTrue(
            NodeTabsModel.heardFor(NodeTab.Sensors, all, query = "", filtersActive = false).isEmpty(),
        )
    }

    @Test
    fun `an unknown kind goes with contacts as the list below it does`() {
        assertEquals(
            listOf(chat, odd),
            NodeTabsModel.heardFor(NodeTab.Contacts, all, query = "", filtersActive = false),
        )
    }

    @Test
    fun `the counts are per tab and a tab with none is absent`() {
        val counts = NodeTabsModel.counts(all)
        assertEquals(2, counts[NodeTab.Repeaters])
        assertEquals(2, counts[NodeTab.Contacts])
        assertEquals(1, counts[NodeTab.Rooms])
        assertEquals(null, counts[NodeTab.Sensors])
        // With auto-add on the inbox is empty, and so is every section.
        assertTrue(NodeTabsModel.counts(emptyList<Heard>()).isEmpty())
    }

    @Test
    fun `the search finds a heard node by name or key`() {
        assertEquals(
            listOf(rptA),
            NodeTabsModel.heardFor(NodeTab.Repeaters, all, query = "kent", filtersActive = false),
        )
        assertEquals(
            listOf(rptB),
            NodeTabsModel.heardFor(NodeTab.Repeaters, all, query = "BB2", filtersActive = false),
        )
    }

    @Test
    fun `any filter hides the section rather than showing it unfiltered`() {
        // Favourites, unread and heard-in-24h are questions about
        // contacts; a heard node answers none of them.
        assertTrue(
            NodeTabsModel.heardFor(NodeTab.Repeaters, all, query = "", filtersActive = true).isEmpty(),
        )
    }

    @Test
    fun `a saved position for the retired New tab opens on Contacts`() {
        assertEquals(NodeTab.Contacts, NodeTab.fromSaved(4))
        assertEquals(NodeTab.Contacts, NodeTab.fromSaved(-1))
        assertEquals(NodeTab.Repeaters, NodeTab.fromSaved(1))
        assertEquals(NodeTab.Sensors, NodeTab.fromSaved(3))
    }

    @Test
    fun `the spoken label carries the count`() {
        assertEquals("Repeaters, 3 new", NodeTabsModel.spokenLabel(NodeTab.Repeaters, 3))
        assertEquals("Repeaters", NodeTabsModel.spokenLabel(NodeTab.Repeaters, 0))
    }
}
