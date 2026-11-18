package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.messaging.BookMessage
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.protocol.PathGeometry
import io.github.thatsfguy.meshcore.protocol.PathSketch
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull

/**
 * The two small route maps — how a message arrived, and the route a
 * contact is reached by — built from the shared geometry. Lifted from
 * Android's ViewModel (sketchArrival / sketchStoredPath) for iOS
 * Phase 5 (2026-11-18). Plus Swift-safe predicates, so Swift never
 * spells a Kotlin enum entry.
 */
object PathMaps {

    private fun positioned(contacts: Collection<Contact>) =
        contacts.map { PathGeometry.PositionedContact(it.publicKeyHex, it.name, it.latitude, it.longitude) }

    private fun hopWaypoint(hop: PathGeometry.HopPoint) = PathSketch.Waypoint(
        label = hop.name ?: hop.hashHex,
        latitude = hop.latitude,
        longitude = hop.longitude,
        unidentifiedReason = hop.gap?.takeIf { it != PathGeometry.Gap.NoPosition }?.let { PathGeometry.gapReason(it) },
        isEndpoint = false,
    )

    /** Sender → each repeater → this radio, for an inbound message with a recorded route; else null. */
    fun arrival(
        m: BookMessage,
        senderLabel: String,
        contacts: Collection<Contact>,
        selfName: String,
        selfLatitude: Double?,
        selfLongitude: Double?,
    ): PathSketch.Sketch? {
        val width = m.arrivalHashWidth?.takeIf { it in 1..4 } ?: return null
        val bytes = m.arrivalPathHex?.takeIf { it.isNotEmpty() }?.let { hexToBytesOrNull(it) } ?: return null
        val plot = PathGeometry.plot(bytes, width, positioned(contacts))
        if (plot.hops.isEmpty()) return null
        val peer = m.threadKey.substringAfter('|')
        val sender = contacts.firstOrNull { it.publicKeyHex.equals(peer, ignoreCase = true) }
        val chain = buildList {
            add(PathSketch.Waypoint(senderLabel, sender?.latitude, sender?.longitude, null, true))
            plot.hops.forEach { add(hopWaypoint(it)) }
            add(PathSketch.Waypoint(selfName.ifBlank { "This radio" }, selfLatitude, selfLongitude, null, true))
        }
        return PathSketch.build(chain).takeUnless { it.isEmpty }
    }

    /** This radio → each repeater → [dest], along its stored route; null when it floods. */
    fun storedRoute(
        dest: Contact,
        contacts: Collection<Contact>,
        selfName: String,
        selfLatitude: Double?,
        selfLongitude: Double?,
    ): PathSketch.Sketch? {
        if (dest.storedPath.isEmpty()) return null
        val plot = PathGeometry.plot(dest.storedPath, dest.pathInfo.hashWidth, positioned(contacts))
        val chain = PathSketch.outboundChain(
            selfName.ifBlank { "This radio" }, selfLatitude, selfLongitude,
            plot.hops, dest.publicKeyHex, dest.name.ifBlank { dest.publicKeyHex.take(12) },
            dest.latitude, dest.longitude,
        )
        if (chain.isEmpty()) return null
        return PathSketch.build(chain).takeUnless { it.isEmpty }
    }

    fun isSolid(segment: PathSketch.Segment): Boolean = segment.style == PathSketch.Style.Solid
    fun isDotted(segment: PathSketch.Segment): Boolean = segment.style == PathSketch.Style.Dotted
    fun isInferred(node: PathSketch.Node): Boolean = node.certainty == PathSketch.Certainty.Inferred

    /** A pin's title: an inferred one says it is approximate. */
    fun title(node: PathSketch.Node): String =
        if (isInferred(node)) "${node.label} — approximate (position unknown)" else node.label

    /** The legend, when anything on the map was inferred or left off. */
    fun legend(sketch: PathSketch.Sketch): String? =
        PathSketch.LEGEND.takeIf { sketch.inferred > 0 || sketch.unplaced > 0 }
}
