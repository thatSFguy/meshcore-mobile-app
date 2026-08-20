package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.PathCodec

/**
 * The fields the node list orders and filters on.
 *
 * An interface rather than the Room entity so the arranging lives in
 * `shared` and can be tested without a device — the same reason
 * [SettingsHubModel] and [RepeaterHubModel] are here. `ContactEntity`
 * implements it; iOS will implement it with whatever it persists.
 */
interface NodeListItem {
    val keyHex: String
    val name: String
    val flags: Int

    /** `path_len` byte — decode with [PathCodec.decodePathLen]. */
    val pathLen: Int

    /** Advert timestamp, epoch **seconds**. 0 means never heard. */
    val lastSeen: Long

    /** Last message either way, epoch **milliseconds**. 0 means never. */
    val lastMessageAt: Long

    val unread: Int
}

/**
 * Ordering and filtering for the Nodes list.
 *
 * Pure, so the rules can be pinned in tests instead of driven on a
 * phone. The screen owns only the menu and the remembered selection.
 *
 * The two clocks in [NodeListItem] are deliberately different units and
 * that is not a wart to tidy: `lastSeen` is the radio's advert
 * timestamp, which arrives from the mesh in seconds, and
 * `lastMessageAt` is our own database's millisecond mark. Converting
 * either one at rest would mean rounding a value the firmware owns.
 * Comparisons here never mix the two.
 */
object NodeListModel {

    /**
     * How the list is ordered. Every order is total — ties break on
     * name and then key — so the list cannot shuffle between
     * recompositions when two nodes share a timestamp, which they do
     * constantly on a mesh where adverts arrive in bursts.
     */
    enum class Sort(val label: String) {
        /**
         * Favourites, then whoever you last talked to, then the rest
         * A–Z. The historical default, and the only order that floats
         * favourites — pick any other and you have asked for that key,
         * not for a partial re-sort you did not ask for.
         */
        Activity("Recent activity"),

        /** Freshest advert first; never-heard nodes last. */
        LastHeard("Last heard"),

        /** A–Z, for when you know the name and want it found. */
        Name("Name (A–Z)"),

        /**
         * Nearest in hops first, flood-routed last. This is a
         * reachability order, not a distance one: it answers "who can
         * I get to cheaply right now", which is the question worth
         * asking before sending anything large.
         */
        Hops("Fewest hops"),
    }

    /**
     * Narrowing toggles, combined with AND.
     *
     * Deliberately few. A busy mesh produces a long list of nodes you
     * have never spoken to and will never speak to, and all three of
     * these answer that: the ones I chose, the ones talking to me, and
     * the ones actually alive.
     */
    enum class Filter(val label: String) {
        Favorites("Favourites only"),
        Unread("Unread only"),
        ActiveDay("Heard in last 24 h"),
    }

    /** How recent an advert has to be for [Filter.ActiveDay]. */
    const val ACTIVE_WINDOW_SECONDS: Long = 24 * 60 * 60

    fun isFavorite(item: NodeListItem): Boolean =
        item.flags and Codes.CONTACT_FLAG_FAVORITE != 0

    /**
     * Search, filter, then sort — in that order, so the sort only ever
     * runs over what survives.
     *
     * [query] matches a name anywhere, case-insensitively, or a key by
     * prefix; blank matches everything. [nowSeconds] is required rather
     * than defaulted because [Filter.ActiveDay] is meaningless without
     * it, and a defaulted 0 would silently empty the list.
     */
    fun <T : NodeListItem> arrange(
        items: List<T>,
        query: String,
        sort: Sort,
        filters: Set<Filter>,
        nowSeconds: Long,
    ): List<T> = items
        .filter { matchesQuery(it, query) }
        .filter { item -> filters.all { keeps(item, it, nowSeconds) } }
        .sortedWith(comparatorFor(sort))

    private fun matchesQuery(item: NodeListItem, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return item.name.contains(q, ignoreCase = true) ||
            item.keyHex.startsWith(q.lowercase())
    }

    private fun keeps(item: NodeListItem, filter: Filter, now: Long): Boolean = when (filter) {
        Filter.Favorites -> isFavorite(item)
        Filter.Unread -> item.unread > 0
        // A node heard *ahead* of our clock still counts as recent. The
        // radio's clock is corrected by this app and the mesh carries
        // its own timestamps, so a few seconds of skew either way is
        // ordinary and must not read as "not heard in a day".
        Filter.ActiveDay -> item.lastSeen > 0 && now - item.lastSeen <= ACTIVE_WINDOW_SECONDS
    }

    private fun <T : NodeListItem> comparatorFor(sort: Sort): Comparator<T> {
        val byName = compareBy<T> { it.name.ifBlank { it.keyHex }.lowercase() }
            .thenBy { it.keyHex }
        return when (sort) {
            Sort.Activity ->
                compareByDescending<T> { isFavorite(it) }
                    .thenByDescending { it.lastMessageAt > 0 }
                    .thenByDescending { it.lastMessageAt }
                    .then(byName)

            Sort.LastHeard ->
                // Plain descending is enough to sink both the
                // never-heard (0) and anything a hostile advert drove
                // below it. An "ever heard" key ahead of this looked
                // like a guard and was unreachable — no input could
                // reach it, and the test written to pin it passed with
                // it deleted.
                compareByDescending<T> { it.lastSeen }
                    .then(byName)

            Sort.Name -> byName

            Sort.Hops ->
                compareBy<T> { PathCodec.decodePathLen(it.pathLen).isFlood }
                    .thenBy { PathCodec.decodePathLen(it.pathLen).hops }
                    .then(byName)
        }
    }
}
