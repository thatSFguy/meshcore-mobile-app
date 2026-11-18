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
    ): List<ContactNode> = NodeListModel.arrange(
        nodes.filter { tab == null || it.tab == tab },
        query, sort, filters, nowSeconds,
    )

    /** How many nodes each tab holds, for the tab labels. */
    fun counts(nodes: List<ContactNode>): Map<NodeTab, Int> = nodes.groupingBy { it.tab }.eachCount()
}
