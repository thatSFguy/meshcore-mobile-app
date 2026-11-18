package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.messaging.BookMessage
import io.github.thatsfguy.meshcore.messaging.MessageStatus
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.protocol.HeardVia
import io.github.thatsfguy.meshcore.protocol.MessageRepeats
import io.github.thatsfguy.meshcore.protocol.PathCodec
import io.github.thatsfguy.meshcore.util.haversineMetres
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
import io.github.thatsfguy.meshcore.util.isPlausiblePosition

/**
 * What a message bubble and its info sheet say about how it travelled —
 * the wording Android's ConversationScreen built inline, lifted for iOS
 * Phase 4 (2026-11-18) so both apps read the same.
 */
object MessageDetail {

    /** Short hop label for a bubble footer. -1 is "routed", never "direct". */
    fun hopsLabel(hops: Int?): String? = when {
        hops == null -> null
        hops < 0 -> "routed"
        hops == 0 -> "direct"
        hops == 1 -> "1 hop"
        else -> "$hops hops"
    }

    /** The info sheet's "Arrival" line. */
    fun arrivalLabel(hops: Int?): String? = when {
        hops == null -> null
        hops < 0 -> "Routed — the sender had a path, so no hop count was recorded"
        hops == 0 -> "Flooded — heard directly, no repeater in between"
        hops == 1 -> "Flooded — 1 hop"
        else -> "Flooded — $hops hops"
    }

    fun copiesLabel(copies: Int): String? = if (copies > 1) "Delivered $copies times" else null

    const val COPIES_EXPLAINED =
        "The sender re-sent this. A direct message is retried when its ACK does not get back in time, " +
            "and each attempt is a separate packet the mesh will not de-duplicate — so the copies are " +
            "counted here and shown once in the thread."

    fun stateLabel(status: MessageStatus): String = when (status) {
        MessageStatus.Delivered -> "Delivered (end-to-end ACK)"
        MessageStatus.Sent -> "Accepted by radio, no ACK yet"
        MessageStatus.Failed -> "Failed"
        MessageStatus.Pending -> "Pending"
    }

    /**
     * The footer under a bubble: hops and last-hop signal for an inbound
     * message, the repeat badge ("↻ N") for an outgoing one.
     */
    fun bubbleFooter(m: BookMessage): String? = if (m.outgoing) {
        MessageRepeats.badge(m.repeatHopsHex, m.repeatHashWidth)
    } else {
        listOfNotNull(hopsLabel(m.hops), MessageSignal.bubble(m.snr, m.hops))
            .joinToString(" · ").ifEmpty { null }
    }

    /** A titled explanation and the steps beneath it. */
    data class Route(val title: String, val summary: String, val steps: List<String>)

    /**
     * "Arrived via": the sender, each repeater in travel order (named
     * when the hash resolves, with distance from this radio when both
     * positions are known), then this radio. Without a recorded path
     * there are no steps — only the sentence saying what is known.
     */
    fun arrival(
        m: BookMessage,
        contacts: List<Contact>,
        selfLatitude: Double?,
        selfLongitude: Double?,
    ): Route {
        val width = m.arrivalHashWidth?.takeIf { it in 1..4 }
        val path = m.arrivalPathHex?.takeIf { width != null && it.isNotEmpty() }
        val bytes = path?.let { hexToBytesOrNull(it) }
        if (path == null || width == null || bytes == null) {
            return Route("Arrived via", HeardVia.summary(m.hops, null, 1), emptyList())
        }
        val names = contacts.associate { it.publicKeyHex.lowercase() to it.name.ifBlank { it.publicKeyHex.take(12) } }
        val hops = PathCodec.resolveHops(bytes, width, names, metresAwayFrom(selfLatitude, selfLongitude, contacts))
        val steps = buildList {
            add("↑ sender")
            hops.forEachIndexed { i, h -> add("${i + 1}. ${h.label}") }
            add("↓ this radio")
        }
        return Route("Arrived via", HeardVia.summary(m.hops, path, width), steps)
    }

    /** "Repeated by": distinct nodes heard re-broadcasting our own message. */
    fun repeats(m: BookMessage, contacts: List<Contact>): Route {
        val width = m.repeatHashWidth
        val relays = MessageRepeats.relays(m.repeatHopsHex, width)
        val names = contacts.associate { it.publicKeyHex.lowercase() to it.name.ifBlank { it.publicKeyHex.take(12) } }
        val steps = relays.mapIndexed { i, hash ->
            val label = hexToBytesOrNull(hash)
                ?.let { PathCodec.resolveHops(it, width ?: 1, names, null).singleOrNull()?.label }
                ?: hash
            "${i + 1}. $label"
        }
        return Route("Repeated by", MessageRepeats.summary(m.repeatHopsHex, width), steps)
    }

    /** Distance from this radio to a contact, or null when either position is unknown. */
    fun metresAwayFrom(selfLatitude: Double?, selfLongitude: Double?, contacts: List<Contact>): ((String) -> Double?)? {
        if (selfLatitude == null || selfLongitude == null || !isPlausiblePosition(selfLatitude, selfLongitude)) return null
        val positions = contacts
            .filter { isPlausiblePosition(it.latitude, it.longitude) }
            .associate { it.publicKeyHex.lowercase() to (it.latitude!! to it.longitude!!) }
        return { key -> positions[key.lowercase()]?.let { (la, lo) -> haversineMetres(selfLatitude, selfLongitude, la, lo) } }
    }
}
