package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.presentation.NodeListModel.Filter
import io.github.thatsfguy.meshcore.presentation.NodeListModel.Sort
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.PathCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ordering and narrowing for the Nodes list.
 *
 * These read like UI tests and are not: every rule below decides what a
 * person can find on a mesh with a hundred nodes on it, and none of them
 * needs a phone to check.
 */
class NodeListModelTest {

    private data class Node(
        override val keyHex: String,
        override val name: String = "",
        override val flags: Int = 0,
        override val pathLen: Int = PathCodec.PATH_LEN_FLOOD,
        override val lastSeen: Long = 0,
        override val lastMessageAt: Long = 0,
        override val unread: Int = 0,
    ) : NodeListItem

    private val favourite = Codes.CONTACT_FLAG_FAVORITE

    /** Epoch seconds standing in for "now" throughout. */
    private val now = 1_755_600_000L

    private fun arrange(
        items: List<Node>,
        query: String = "",
        sort: Sort = Sort.Activity,
        filters: Set<Filter> = emptySet(),
    ) = NodeListModel.arrange(items, query, sort, filters, now).map { it.keyHex }

    // --- ordering ----------------------------------------------------------

    @Test
    fun `recent activity floats favourites and then whoever you last talked to`() {
        val nodes = listOf(
            Node("aa", name = "Anna"),
            Node("bb", name = "Ben", lastMessageAt = 5_000),
            Node("cc", name = "Cara", lastMessageAt = 9_000),
            Node("dd", name = "Zed", flags = favourite),
        )
        assertEquals(listOf("dd", "cc", "bb", "aa"), arrange(nodes, sort = Sort.Activity))
    }

    @Test
    fun `every other order leaves favourites where they fall`() {
        // Asking for "last heard" and getting last-heard-except-the-ones-
        // I-starred is a partial sort nobody requested. Only Activity
        // floats them, and this is what pins that.
        val nodes = listOf(
            Node("aa", name = "Anna", lastSeen = now - 60),
            Node("bb", name = "Ben", flags = favourite, lastSeen = now - 9_000),
        )
        assertEquals(listOf("aa", "bb"), arrange(nodes, sort = Sort.LastHeard))
        assertEquals(listOf("aa", "bb"), arrange(nodes, sort = Sort.Name))
    }

    @Test
    fun `last heard puts the freshest advert first`() {
        val nodes = listOf(
            Node("aa", name = "Old", lastSeen = now - 90_000),
            Node("bb", name = "Fresh", lastSeen = now - 30),
            Node("cc", name = "Middling", lastSeen = now - 3_600),
        )
        assertEquals(listOf("bb", "cc", "aa"), arrange(nodes, sort = Sort.LastHeard))
    }

    @Test
    fun `a node never heard sinks below one heard a year ago`() {
        // lastSeen is 0 for a contact imported from a QR and never since
        // heard.
        val nodes = listOf(
            Node("aa", name = "Never"),
            Node("bb", name = "Ancient", lastSeen = now - 365 * 86_400),
        )
        assertEquals(listOf("bb", "aa"), arrange(nodes, sort = Sort.LastHeard))
    }

    @Test
    fun `a negative advert timestamp sinks instead of floating to the top`() {
        // lastSeen comes off the mesh, so the timestamp is
        // attacker-controlled, and a sort key a hostile advert can drive
        // is a sort key that puts the attacker's node first. Descending
        // order sinks it below even a node never heard at all, which is
        // the correct place for a number that cannot be true.
        val nodes = listOf(
            Node("aa", name = "Hostile", lastSeen = -1),
            Node("bb", name = "Never"),
            Node("cc", name = "Real", lastSeen = now - 10),
        )
        assertEquals(listOf("cc", "bb", "aa"), arrange(nodes, sort = Sort.LastHeard))
    }

    @Test
    fun `a negative advert timestamp is never counted as heard today`() {
        val nodes = listOf(Node("aa", name = "Hostile", lastSeen = -1))
        assertTrue(arrange(nodes, filters = setOf(Filter.ActiveDay)).isEmpty())
    }

    @Test
    fun `name sorting ignores case and falls back to the key when blank`() {
        val nodes = listOf(
            Node("ff00", name = "zulu"),
            Node("0011", name = ""),
            Node("aa22", name = "Alpha"),
        )
        assertEquals(listOf("0011", "aa22", "ff00"), arrange(nodes, sort = Sort.Name))
    }

    @Test
    fun `fewest hops orders by reachability and puts flood routes last`() {
        // Real path_len bytes, not invented ones: 0x44 is the four-hop /
        // two-byte-hash value pinned elsewhere in this codebase from a
        // live contact, and 0xFF is the flood sentinel.
        val nodes = listOf(
            Node("aa", name = "Flooded", pathLen = PathCodec.PATH_LEN_FLOOD),
            Node("bb", name = "Four hops", pathLen = 0x44),
            Node("cc", name = "One hop", pathLen = 0x01),
            Node("dd", name = "Direct", pathLen = 0x00),
        )
        assertEquals(listOf("dd", "cc", "bb", "aa"), arrange(nodes, sort = Sort.Hops))
    }

    @Test
    fun `the flood sentinel is recognised however the byte arrives`() {
        // decodePathLen accepts -1 (a signed read of 0xFF) as flood too,
        // and a route that sorted as "zero hops" would be the worst
        // possible lie in a reachability order.
        val nodes = listOf(
            Node("aa", name = "Signed flood", pathLen = -1),
            Node("bb", name = "Two hops", pathLen = 0x02),
        )
        assertEquals(listOf("bb", "aa"), arrange(nodes, sort = Sort.Hops))
    }

    @Test
    fun `every order is total so an identical pair cannot shuffle`() {
        // Adverts arrive in bursts and share timestamps constantly. Two
        // nodes tying on the sort key must still land in a fixed order
        // or the list reshuffles under the reader's finger on every
        // recomposition.
        val a = Node("aa11", name = "Same", lastSeen = now, lastMessageAt = 7, pathLen = 0x02)
        val b = Node("bb22", name = "Same", lastSeen = now, lastMessageAt = 7, pathLen = 0x02)
        for (sort in Sort.entries) {
            assertEquals(
                listOf("aa11", "bb22"),
                arrange(listOf(a, b), sort = sort),
                "$sort left an identical pair unordered",
            )
            assertEquals(
                listOf("aa11", "bb22"),
                arrange(listOf(b, a), sort = sort),
                "$sort depends on the order it was handed",
            )
        }
    }

    @Test
    fun `no order invents or drops a node`() {
        val nodes = (0 until 40).map {
            Node(
                keyHex = it.toString(16).padStart(4, '0'),
                name = if (it % 3 == 0) "" else "Node $it",
                flags = if (it % 5 == 0) favourite else 0,
                pathLen = if (it % 4 == 0) PathCodec.PATH_LEN_FLOOD else it % 8,
                lastSeen = if (it % 7 == 0) 0 else now - it * 600L,
                lastMessageAt = if (it % 2 == 0) it * 1_000L else 0,
                unread = it % 3,
            )
        }
        val keys = nodes.map { it.keyHex }.sorted()
        for (sort in Sort.entries) {
            assertEquals(keys, arrange(nodes, sort = sort).sorted(), "$sort changed the set")
        }
    }

    // --- filtering ---------------------------------------------------------

    @Test
    fun `favourites only keeps only favourites`() {
        val nodes = listOf(
            Node("aa", name = "Plain"),
            Node("bb", name = "Starred", flags = favourite),
            // A contact carrying other flags is not a favourite; the
            // check is a bit test, not a non-zero test.
            Node("cc", name = "Other flags", flags = favourite.inv()),
        )
        assertEquals(listOf("bb"), arrange(nodes, filters = setOf(Filter.Favorites)))
    }

    @Test
    fun `unread only keeps the nodes with something waiting`() {
        val nodes = listOf(
            Node("aa", name = "Read", unread = 0),
            Node("bb", name = "One waiting", unread = 1),
            Node("cc", name = "Several", unread = 9),
        )
        assertEquals(listOf("bb", "cc"), arrange(nodes, sort = Sort.Name, filters = setOf(Filter.Unread)))
    }

    @Test
    fun `heard in the last day keeps the boundary and drops the second past it`() {
        val nodes = listOf(
            Node("aa", name = "Exactly a day", lastSeen = now - NodeListModel.ACTIVE_WINDOW_SECONDS),
            Node("bb", name = "A second more", lastSeen = now - NodeListModel.ACTIVE_WINDOW_SECONDS - 1),
            Node("cc", name = "Recent", lastSeen = now - 30),
        )
        assertEquals(
            listOf("cc", "aa"),
            arrange(nodes, sort = Sort.LastHeard, filters = setOf(Filter.ActiveDay)),
        )
    }

    @Test
    fun `a node heard slightly ahead of our clock still counts as recent`() {
        // The radio's clock is corrected by this app and mesh packets
        // carry their own timestamps, so small skew is ordinary. Reading
        // it as "not heard in a day" would hide the freshest node there
        // is, which is the exact opposite of the filter's purpose.
        val nodes = listOf(Node("aa", name = "Ahead", lastSeen = now + 120))
        assertEquals(listOf("aa"), arrange(nodes, filters = setOf(Filter.ActiveDay)))
    }

    @Test
    fun `a node never heard is not recent whatever the clock says`() {
        val nodes = listOf(Node("aa", name = "Never", lastSeen = 0))
        assertTrue(arrange(nodes, filters = setOf(Filter.ActiveDay)).isEmpty())
    }

    @Test
    fun `filters combine with and rather than or`() {
        val nodes = listOf(
            Node("aa", name = "Fav, stale", flags = favourite, lastSeen = now - 200_000),
            Node("bb", name = "Fresh, not fav", lastSeen = now - 60),
            Node("cc", name = "Both", flags = favourite, lastSeen = now - 60),
        )
        assertEquals(
            listOf("cc"),
            arrange(nodes, filters = setOf(Filter.Favorites, Filter.ActiveDay)),
        )
    }

    @Test
    fun `no filters hides nothing`() {
        val nodes = listOf(Node("aa"), Node("bb", flags = favourite), Node("cc", unread = 2))
        assertEquals(3, arrange(nodes).size)
    }

    // --- search ------------------------------------------------------------

    @Test
    fun `search matches a name anywhere and a key by prefix`() {
        val nodes = listOf(
            Node("ab12cd", name = "Grand Rapids Repeater"),
            Node("cd34ef", name = "Kitchen sensor"),
        )
        assertEquals(listOf("ab12cd"), arrange(nodes, query = "rapids"))
        assertEquals(listOf("ab12cd"), arrange(nodes, query = "AB12"))
        assertEquals(listOf("cd34ef"), arrange(nodes, query = "sensor"))
    }

    @Test
    fun `a key matches by prefix only and never in the middle`() {
        // Keys are searched by prefix because that is how they are shown
        // and shared. Matching the middle would make a short hex query
        // hit half the mesh.
        val nodes = listOf(Node("ab12cd", name = "Node"))
        assertTrue(arrange(nodes, query = "12cd").isEmpty())
    }

    @Test
    fun `a blank or whitespace query matches everything`() {
        val nodes = listOf(Node("aa", name = "One"), Node("bb", name = "Two"))
        assertEquals(2, arrange(nodes, query = "").size)
        assertEquals(2, arrange(nodes, query = "   ").size)
    }

    @Test
    fun `a query that matches nothing empties the list rather than falling back`() {
        val nodes = listOf(Node("aa", name = "One"), Node("bb", name = "Two"))
        assertTrue(arrange(nodes, query = "zzzz").isEmpty())
    }

    @Test
    fun `search runs before the filters and both before the order`() {
        val nodes = listOf(
            Node("aa", name = "Repeater north", flags = favourite, lastSeen = now - 10),
            Node("bb", name = "Repeater south", lastSeen = now - 20),
            Node("cc", name = "Sensor north", flags = favourite, lastSeen = now - 30),
        )
        assertEquals(
            listOf("aa"),
            arrange(nodes, query = "repeater", filters = setOf(Filter.Favorites)),
        )
    }

    @Test
    fun `an empty list stays empty through every combination`() {
        for (sort in Sort.entries) {
            for (filter in Filter.entries) {
                assertTrue(
                    arrange(emptyList(), query = "x", sort = sort, filters = setOf(filter))
                        .isEmpty(),
                )
            }
        }
    }

    // --- the favourite bit -------------------------------------------------

    @Test
    fun `isFavorite reads the bit and not the whole flags word`() {
        assertTrue(NodeListModel.isFavorite(Node("aa", flags = favourite)))
        assertTrue(NodeListModel.isFavorite(Node("aa", flags = favourite or 0x40)))
        assertFalse(NodeListModel.isFavorite(Node("aa", flags = 0)))
        assertFalse(NodeListModel.isFavorite(Node("aa", flags = favourite.inv())))
    }
}
