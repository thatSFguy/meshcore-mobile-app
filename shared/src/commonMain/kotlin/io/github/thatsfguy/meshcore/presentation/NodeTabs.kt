package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Codes

/**
 * A node whose signed advert this radio heard but which is not in its
 * contact list — what the radio turned away because auto-add is off for
 * its type, or because the list is full.
 */
interface HeardNode {
    val keyHex: String
    val name: String

    /** Advert type ([Codes.ADV_TYPE_CHAT] and the rest). */
    val type: Int

    /** When this radio last heard it, epoch milliseconds. */
    val lastHeardAt: Long

    /** Advertised position; null or 0,0 when it has none. */
    val latitude: Double? get() = null
    val longitude: Double? get() = null

    /** Fewest hops any of its adverts arrived with; null when unrecorded. */
    val minHops: Int? get() = null
}

/**
 * The Nodes screen's tabs, one per kind of node.
 *
 * There used to be a fifth, "New", holding every heard-but-not-added node
 * of every kind. It was last in a scrolling row, so on a 384 dp phone it
 * sat off the edge of the screen — and it was blank for anyone with
 * auto-add on, which is most people. Those nodes now appear at the top
 * of the tab for their own kind: a new repeater is on Repeaters, where
 * someone looking for it would look. The radio's auto-add settings are
 * per type too, so a tab only grows the section for the kinds that are
 * not added automatically.
 */
enum class NodeTab(val title: String) {
    Contacts("Contacts"),
    Repeaters("Repeaters"),
    Rooms("Rooms"),
    Sensors("Sensors"),
    ;

    companion object {
        /** The tab a node of advert [type] belongs on. Unknown types go with contacts. */
        fun of(type: Int): NodeTab = when (type) {
            Codes.ADV_TYPE_REPEATER -> Repeaters
            Codes.ADV_TYPE_ROOM -> Rooms
            Codes.ADV_TYPE_SENSOR -> Sensors
            else -> Contacts
        }

        /**
         * The tab for a saved position. The retired "New" tab was
         * position 4; anything saved there, or anything else unknown,
         * opens on Contacts rather than on nothing.
         */
        fun fromSaved(index: Int): NodeTab = entries.getOrNull(index) ?: Contacts
    }
}

object NodeTabsModel {

    /**
     * How many heard-but-not-added nodes each tab has. Tabs with none are
     * absent — and so is every tab while [hidden]: a muted newcomer that
     * still lit a badge would be noise moved, not noise gone.
     */
    fun <T : HeardNode> counts(heard: List<T>, hidden: Boolean = false): Map<NodeTab, Int> =
        if (hidden) emptyMap() else heard.groupingBy { NodeTab.of(it.type) }.eachCount()

    /**
     * The line that says newcomers on [tab] are being hidden, or null
     * when nothing is. Hiding is a standing choice, so the list has to
     * keep saying it — otherwise a busy mesh just looks quiet.
     */
    fun <T : HeardNode> hiddenNote(tab: NodeTab, heard: List<T>, hidden: Boolean): String? {
        if (!hidden) return null
        val n = heard.count { NodeTab.of(it.type) == tab }
        return if (n == 0) "Hiding nodes not added" else "Hiding $n ${if (n == 1) "node" else "nodes"} not added"
    }

    /** Read aloud for a tab: "Repeaters, 3 new". */
    fun spokenLabel(tab: NodeTab, newCount: Int): String =
        if (newCount <= 0) tab.title else "${tab.title}, $newCount new"

    /**
     * The heard nodes [tab] shows, in [sort] order — newest first for
     * [NodeListModel.Sort.Activity] and [NodeListModel.Sort.LastHeard].
     *
     * The section used to ignore the sort entirely and always list
     * newest first, which on a tab where most rows are heard-not-added
     * (Repeaters, with auto-add off) made "Fewest hops" look broken.
     * Hops here are the heard hops ([HeardNode.minHops]) — a node not
     * added has no stored route to rank by — with never-recorded last.
     *
     * The search applies to them as it does to the list below, so a name
     * typed into the box finds a node whether or not it has been added.
     * The filters do not: favourites, unread and "heard in 24 h" are
     * questions about contacts, and with any of them on the section is
     * hidden rather than shown unfiltered. [hidden] is the Nodes list's
     * "Hide nodes not added": new nodes muted, for a mesh too busy to
     * want each newcomer shown.
     */
    fun <T : HeardNode> heardFor(
        tab: NodeTab,
        heard: List<T>,
        query: String,
        filtersActive: Boolean,
        hidden: Boolean = false,
        sort: NodeListModel.Sort = NodeListModel.Sort.Activity,
        self: SelfPosition? = null,
    ): List<T> {
        if (filtersActive || hidden) return emptyList()
        val q = query.trim()
        return heard
            .filter { NodeTab.of(it.type) == tab }
            .filter {
                q.isEmpty() || it.name.contains(q, ignoreCase = true) ||
                    it.keyHex.startsWith(q.lowercase())
            }
            .sortedWith(comparatorFor(sort, self))
    }

    private fun <T : HeardNode> comparatorFor(
        sort: NodeListModel.Sort,
        self: SelfPosition?,
    ): Comparator<T> {
        val newest = compareByDescending<T> { it.lastHeardAt }.thenBy { it.keyHex }
        val byName = compareBy<T> { it.name.ifBlank { it.keyHex }.lowercase() }.thenBy { it.keyHex }
        return when (sort) {
            NodeListModel.Sort.Activity, NodeListModel.Sort.LastHeard -> newest
            NodeListModel.Sort.Name -> byName
            NodeListModel.Sort.Hops ->
                compareBy<T> { it.minHops == null }.thenBy { it.minHops ?: 0 }.then(newest)
            NodeListModel.Sort.Distance ->
                NodeListModel.byDistance<T>(self) { it.latitude to it.longitude }.then(byName)
        }
    }
}
