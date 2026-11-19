package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.presentation.Inbox
import io.github.thatsfguy.meshcore.presentation.LastHeard
import io.github.thatsfguy.meshcore.presentation.NodeListItem
import io.github.thatsfguy.meshcore.presentation.NodeListModel
import io.github.thatsfguy.meshcore.presentation.NodeTab
import io.github.thatsfguy.meshcore.presentation.Reachability
import io.github.thatsfguy.meshcore.protocol.PathCodec
import io.github.thatsfguy.meshcore.util.RelativeTime

/**
 * One row of the Nodes list on a platform with no contact database.
 *
 * Android's rows are Room entities carrying local facts (last message,
 * unread); iOS has the radio's [Contact] and the [MessageBook], so this
 * joins the two and implements [NodeListItem] — which lets iOS reuse
 * [NodeListModel]'s search, sort and filters unchanged instead of
 * re-deriving them in Swift.
 */
class ContactNode(
    val contact: Contact,
    override val lastMessageAt: Long,
    override val unread: Int,
    val reach: Reachability.Verdict,
) : NodeListItem {
    override val keyHex: String get() = contact.publicKeyHex
    override val name: String get() = contact.name
    override val flags: Int get() = contact.flags
    override val pathLen: Int get() = contact.pathLen
    /** The advert's own timestamp — the node's claim, not our hearing. */
    override val lastSeen: Long get() = contact.timestamp
    override val lastModified: Long get() = contact.lastModified
    override val latitude: Double? get() = contact.latitude
    override val longitude: Double? get() = contact.longitude

    val type: Int get() = contact.type
    val tab: NodeTab get() = NodeTab.of(contact.type)
    val isFavorite: Boolean get() = NodeListModel.isFavorite(this)

    /**
     * [Reachability.Verdict.short] under a name Swift can reach: `short`
     * is a C keyword, so the Objective-C header Kotlin/Native writes
     * cannot carry it as-is and the property is renamed on the way out.
     */
    val reachLabel: String? get() = reach.short
    val reachLine: String get() = reach.line
    val isTwoWay: Boolean get() = reach.isTwoWay

    /** "direct", "3 hops", or "flood" — read at the contact's own width. */
    val hopsLabel: String
        get() {
            val info = PathCodec.decodePathLen(contact.pathLen)
            return when {
                info.isFlood -> "flood"
                info.hops == 0 -> "direct"
                info.hops == 1 -> "1 hop"
                else -> "${info.hops} hops"
            }
        }

    /** "heard 5 min ago", or null when the radio has no time for it. */
    fun heardLabel(nowSeconds: Long): String? {
        val at = LastHeard.seconds(this)
        if (at <= 0) return null
        return "heard ${RelativeTime.ago(nowSeconds - at)}"
    }
}

object ContactNodes {

    /**
     * Join the radio's contacts with the message history.
     *
     * Reachability evidence follows Android's (MeshCoreViewModel
     * `reachabilityOf`), with what iOS can know: a delivered DM is the
     * proof; a stored route came from them; an advert timestamp means
     * one arrived (not lastmod — the firmware stamps that on every add
     * or rename); any DM we sent means we tried. iOS has no route
     * history, so "repeats your messages" is never claimed.
     */
    fun build(contacts: Collection<Contact>, book: MessageBook?, nowSeconds: Long): List<ContactNode> {
        val rows = book?.messages?.value.orEmpty()
        val unread = book?.unread?.value.orEmpty()
        val byThread = rows.groupBy { it.threadKey }
        return contacts.map { c ->
            val key = Inbox.threadKey(Inbox.KIND_DM, c.publicKeyHex)
            val thread = byThread[key].orEmpty()
            val outgoing = thread.filter { it.outgoing }
            val confirmed = outgoing.mapNotNull { it.deliveredAtMs }.maxOrNull()?.div(1000)
            val evidence = Reachability.Evidence(
                confirmedAt = confirmed,
                routeFromThem = !PathCodec.decodePathLen(c.pathLen).isFlood,
                heardThem = c.timestamp > 0,
                tried = outgoing.isNotEmpty(),
            )
            ContactNode(
                contact = c,
                lastMessageAt = thread.maxOfOrNull { it.receivedAtMs } ?: 0L,
                unread = unread[key] ?: 0,
                reach = Reachability.of(evidence, nowSeconds),
            )
        }
    }

    /** One tab of the list, searched, filtered and sorted by [NodeListModel]. */
    fun arrange(
        nodes: List<ContactNode>,
        tab: NodeTab?,
        query: String,
        sort: NodeListModel.Sort,
        filters: Set<NodeListModel.Filter>,
        nowSeconds: Long,
        /** Where distance is measured from — the radio's own advertised position, as Android. */
        origin: io.github.thatsfguy.meshcore.presentation.SelfPosition?,
    ): List<ContactNode> = NodeListModel.arrange(
        nodes.filter { tab == null || it.tab == tab },
        query, sort, filters, nowSeconds, self = origin,
    )

    /**
     * The New message picker over the radio's nodes (shared NewMessage rule).
     * Not named `new…`: Kotlin/Native renames those for Objective-C's
     * ownership convention, and Swift then sees `doNew…`.
     */
    fun messageCandidates(nodes: List<ContactNode>, query: String): List<ContactNode> =
        io.github.thatsfguy.meshcore.presentation.NewMessage.candidates(nodes, { it.contact.type }, query)

    /**
     * The contact sheet's path rows (Android's): each hop of the stored
     * out-path, named where one contact owns its hash and listing the
     * candidates where several do — never asserting one of them — and the
     * hash width. The width is the CONTACT's own record's (its path_len),
     * not the device setting, which only applies to new paths; a flood
     * contact has no path, so the mesh's width is what applies.
     */
    fun outPathLines(contact: io.github.thatsfguy.meshcore.model.Contact, names: Map<String, String>): List<String> {
        val info = io.github.thatsfguy.meshcore.protocol.PathCodec.decodePathLen(contact.pathLen)
        if (info.isFlood || contact.storedPath.isEmpty()) return emptyList()
        return io.github.thatsfguy.meshcore.protocol.PathCodec.resolveHops(contact.storedPath, info.hashWidth, names)
            .mapIndexed { i, hop -> "${i + 1}. ${hop.label}" }
    }

    fun hashWidthLine(contact: io.github.thatsfguy.meshcore.model.Contact, meshWidth: Int?): String? {
        val info = io.github.thatsfguy.meshcore.protocol.PathCodec.decodePathLen(contact.pathLen)
        val width = if (info.isFlood) meshWidth else info.hashWidth
        return width?.let { "$it-byte${if (it == 1) "" else "s"} per hop" }
    }

    /** "1.2 km" / "0.8 mi" from the radio to [node], or null when either can't be placed. */
    fun distanceLabel(
        node: ContactNode,
        origin: io.github.thatsfguy.meshcore.presentation.SelfPosition,
        system: io.github.thatsfguy.meshcore.presentation.UnitSystem,
    ): String? = origin.metresTo(node.latitude, node.longitude)
        ?.let { io.github.thatsfguy.meshcore.presentation.Units.distance(it, system) }

    /** How many nodes each tab holds, for the tab labels. */
    fun counts(nodes: List<ContactNode>): Map<NodeTab, Int> = nodes.groupingBy { it.tab }.eachCount()
}
