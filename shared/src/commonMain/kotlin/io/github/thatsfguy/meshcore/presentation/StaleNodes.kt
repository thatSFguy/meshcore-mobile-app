package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Codes

/**
 * Clearing out nodes nothing has been heard from — the mesh's own
 * spring clean, in the shape Meshtastic's node list offers it: one
 * slider from [MIN_DAYS] to [MAX_DAYS], and everything older goes.
 *
 * A contact list fills up with nodes that passed through once. The
 * radio holds a bounded number of them, the Nodes tab gets long, and
 * the map fills with pins for hardware that has not been on the air in
 * a month. Nothing prunes it, because a node never announces that it
 * has gone.
 *
 * Three rules, and the last two exist because this is a DESTRUCTIVE
 * sweep run against a list the user did not read first:
 *
 *  - **A favourite is never removed.** Whatever the slider says. It is
 *    the one mark the user has put on a node by hand, and a bulk
 *    action must not be able to undo it.
 *  - **Only our own clocks count.** Staleness is measured from the
 *    LATER of when OUR radio last heard the node and when we last
 *    exchanged a message with it. The advert timestamp is the sending
 *    node's own claim and is worthless here — see [lastEvidenceMillis].
 *    A node you were talking to yesterday is not stale because it
 *    advertises rarely, either: deleting the other end of a live
 *    conversation is the surprise that makes a feature like this
 *    untrustworthy.
 *  - **No evidence is not old evidence.** A contact with no advert and
 *    no messages — one just added from a QR code, typically — has no
 *    last-heard at all. Treating a missing timestamp as "1970" would
 *    delete every freshly imported contact on the first sweep.
 */
object StaleNodes {

    /** Shortest age the slider offers. */
    const val MIN_DAYS = 3

    /** Longest age the slider offers. */
    const val MAX_DAYS = 30

    /** Where the slider starts: a fortnight of silence. */
    const val DEFAULT_DAYS = 14

    private const val DAY_MILLIS = 86_400_000L

    /**
     * The most recent evidence this node still exists, epoch millis, or
     * 0 for none at all.
     *
     * **Read from OUR clocks only, never the node's own.** This is the
     * whole correctness of the feature and it was nearly got wrong:
     * [NodeListItem.lastSeen] is the timestamp the advertising node put
     * in its advert, which the firmware keeps for replay detection
     * (`BaseChatMesh.cpp:131`) and nothing else. Driven against a live
     * mesh on 2026-08-24 it reported real, currently-transmitting nodes
     * as last heard 830 days ago, and one as 20 688 days ago — those
     * are nodes with a wrong RTC, not nodes that are gone, and sweeping
     * on it would have deleted them for having a bad clock.
     *
     * [NodeListItem.lastModified] is our radio's own RTC, stamped every
     * time an advert or a message arrives from that contact
     * (`BaseChatMesh.cpp:117`, `:196`, `:238`). The firmware's own
     * eviction ranks by exactly this field and skips favourites
     * (`:89-93`) — the same rule, arrived at independently.
     *
     * Units differ and mixing them up puts every node in 1970:
     * [NodeListItem.lastModified] is epoch SECONDS,
     * [NodeListItem.lastMessageAt] is local MILLIS.
     */
    fun lastEvidenceMillis(node: NodeListItem): Long = maxOf(
        LastHeard.millis(node),
        node.lastMessageAt.coerceAtLeast(0L),
    )

    fun isFavourite(node: NodeListItem): Boolean =
        node.flags and Codes.CONTACT_FLAG_FAVORITE != 0

    /** What a sweep at one slider position would do. */
    data class Sweep(
        /** Nodes that would be removed, oldest evidence first. */
        val remove: List<NodeListItem>,
        /** Held back because the user starred them. */
        val favouritesKept: Int,
        /** Held back because they have no last-heard at all. */
        val neverHeardKept: Int,
        /** Held back because they are not old enough. */
        val freshKept: Int,
    ) {
        val count: Int get() = remove.size
        val keptCount: Int get() = favouritesKept + neverHeardKept + freshKept
        val total: Int get() = count + keptCount
    }

    /**
     * Which nodes are stale at [olderThanDays], as of [nowMillis].
     *
     * [olderThanDays] is clamped to the slider's range: this is the last
     * place before a delete loop, and a caller that passed 0 would empty
     * the contact list.
     */
    fun sweep(
        nodes: List<NodeListItem>,
        olderThanDays: Int,
        nowMillis: Long,
    ): Sweep {
        val days = olderThanDays.coerceIn(MIN_DAYS, MAX_DAYS)
        val cutoff = nowMillis - days * DAY_MILLIS
        val remove = ArrayList<NodeListItem>()
        var favourites = 0
        var neverHeard = 0
        var fresh = 0
        for (node in nodes) {
            val evidence = lastEvidenceMillis(node)
            when {
                isFavourite(node) -> favourites++
                evidence <= 0L -> neverHeard++
                evidence >= cutoff -> fresh++
                else -> remove.add(node)
            }
        }
        return Sweep(
            // Oldest first: if the sweep is interrupted part way, what
            // went is what was least likely to be wanted.
            remove = remove.sortedBy { lastEvidenceMillis(it) },
            favouritesKept = favourites,
            neverHeardKept = neverHeard,
            freshKept = fresh,
        )
    }

    /** "Remove 12 nodes" / "Remove 1 node" / "Nothing to remove". */
    fun actionLabel(sweep: Sweep): String = when (sweep.count) {
        0 -> "Nothing to remove"
        1 -> "Remove 1 node"
        else -> "Remove ${sweep.count} nodes"
    }

    /**
     * What the sweep will spare, and why — null when it spares nothing.
     *
     * Said before the button is pressed, because "favourites are safe"
     * is the fact that makes the slider usable without reading the list.
     */
    fun keptNote(sweep: Sweep): String? {
        val parts = buildList {
            if (sweep.favouritesKept > 0) add("${sweep.favouritesKept} favourite${plural(sweep.favouritesKept)}")
            if (sweep.neverHeardKept > 0) add("${sweep.neverHeardKept} never heard from")
            if (sweep.freshKept > 0) add("${sweep.freshKept} heard since")
        }
        if (parts.isEmpty()) return null
        return "Keeping " + when (parts.size) {
            1 -> parts[0]
            2 -> "${parts[0]} and ${parts[1]}"
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        } + "."
    }

    /** The report after the sweep has run. */
    fun outcome(removed: Int, failed: Int): String = when {
        removed == 0 && failed == 0 -> "Nothing was removed."
        failed == 0 && removed == 1 -> "Removed 1 node."
        failed == 0 -> "Removed $removed nodes."
        removed == 0 -> "The radio refused to remove ${countLabel(failed)}."
        else -> "Removed $removed; the radio refused ${countLabel(failed)}."
    }

    private fun countLabel(n: Int) = if (n == 1) "1 node" else "$n nodes"

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
