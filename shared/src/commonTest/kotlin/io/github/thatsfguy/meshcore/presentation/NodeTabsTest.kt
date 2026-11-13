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
        override val latitude: Double? = null,
        override val longitude: Double? = null,
        override val minHops: Int? = null,
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

    @Test
    fun `hiding nodes not added empties every tab's heard section`() {
        for (tab in NodeTab.entries) {
            assertTrue(
                NodeTabsModel.heardFor(tab, all, query = "", filtersActive = false, hidden = true).isEmpty(),
                "$tab still showed newcomers",
            )
        }
        // Even a search that names one: hidden means hidden.
        assertTrue(
            NodeTabsModel.heardFor(NodeTab.Repeaters, all, query = "Kent", filtersActive = false, hidden = true)
                .isEmpty(),
        )
    }

    @Test
    fun `hidden newcomers light no badge`() {
        assertEquals(emptyMap(), NodeTabsModel.counts(all, hidden = true))
        // Positive control: shown, they count.
        assertEquals(2, NodeTabsModel.counts(all, hidden = false)[NodeTab.Repeaters])
    }

    @Test
    fun `the list keeps saying what it is hiding`() {
        assertEquals("Hiding 2 nodes not added", NodeTabsModel.hiddenNote(NodeTab.Repeaters, all, hidden = true))
        assertEquals("Hiding 1 node not added", NodeTabsModel.hiddenNote(NodeTab.Rooms, all, hidden = true))
        assertEquals("Hiding nodes not added", NodeTabsModel.hiddenNote(NodeTab.Sensors, all, hidden = true))
        assertEquals(null, NodeTabsModel.hiddenNote(NodeTab.Repeaters, all, hidden = false))
    }

    // --- the heard section follows the list's sort ---------------------------

    private val near = Heard("a1", "Near", Codes.ADV_TYPE_REPEATER, 1_000L, 42.98, -85.66, minHops = 2)
    private val far = Heard("b2", "Far", Codes.ADV_TYPE_REPEATER, 3_000L, 42.70, -86.05, minHops = 0)
    private val noFix = Heard("c3", "Alpha", Codes.ADV_TYPE_REPEATER, 2_000L, minHops = null)
    private val repeaters = listOf(near, far, noFix)
    private val here = SelfPosition(42.9634, -85.6681)

    private fun heard(sort: NodeListModel.Sort, self: SelfPosition? = here) =
        NodeTabsModel.heardFor(
            NodeTab.Repeaters, repeaters, query = "", filtersActive = false, sort = sort, self = self,
        ).map { it.name }

    @Test
    fun `nearest orders heard repeaters by distance with the unplaceable last`() {
        // The request that prompted this: skim the heard repeaters for the
        // close ones before adding any.
        assertEquals(listOf("Near", "Far", "Alpha"), heard(NodeListModel.Sort.Distance))
    }

    @Test
    fun `fewest hops orders heard nodes by heard hops with unrecorded last`() {
        // This section used to ignore the sort and stay newest-first, which
        // on a Repeaters tab of mostly-unadded nodes made the sort look
        // broken. Positive control: the order differs from newest-first.
        assertEquals(listOf("Far", "Near", "Alpha"), heard(NodeListModel.Sort.Hops))
        assertEquals(listOf("Far", "Alpha", "Near"), heard(NodeListModel.Sort.LastHeard))
    }

    @Test
    fun `name and the default order still behave`() {
        assertEquals(listOf("Alpha", "Far", "Near"), heard(NodeListModel.Sort.Name))
        assertEquals(listOf("Far", "Alpha", "Near"), heard(NodeListModel.Sort.Activity))
    }
}
