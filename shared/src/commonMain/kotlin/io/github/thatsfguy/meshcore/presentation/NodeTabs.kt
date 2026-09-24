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

    /** How many heard-but-not-added nodes each tab has. Tabs with none are absent. */
    fun <T : HeardNode> counts(heard: List<T>): Map<NodeTab, Int> =
        heard.groupingBy { NodeTab.of(it.type) }.eachCount()

    /** Read aloud for a tab: "Repeaters, 3 new". */
    fun spokenLabel(tab: NodeTab, newCount: Int): String =
        if (newCount <= 0) tab.title else "${tab.title}, $newCount new"

    /**
     * The heard nodes [tab] shows, newest first.
     *
     * The search applies to them as it does to the list below, so a name
     * typed into the box finds a node whether or not it has been added.
     * The filters do not: favourites, unread and "heard in 24 h" are
     * questions about contacts, and with any of them on the section is
     * hidden rather than shown unfiltered.
     */
    fun <T : HeardNode> heardFor(
        tab: NodeTab,
        heard: List<T>,
        query: String,
        filtersActive: Boolean,
    ): List<T> {
        if (filtersActive) return emptyList()
        val q = query.trim()
        return heard
            .filter { NodeTab.of(it.type) == tab }
            .filter {
                q.isEmpty() || it.name.contains(q, ignoreCase = true) ||
                    it.keyHex.startsWith(q.lowercase())
            }
            .sortedWith(compareByDescending<T> { it.lastHeardAt }.thenBy { it.keyHex })
    }
}
