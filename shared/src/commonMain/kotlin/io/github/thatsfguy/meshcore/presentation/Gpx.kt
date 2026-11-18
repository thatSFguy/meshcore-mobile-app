package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.util.isPlausiblePosition

/**
 * "Export nodes as GPX" — one waypoint per contact with a plausible
 * position. Shared since iOS Phase 4 (2026-11-18); was built inline in
 * Android's ViewModel.
 *
 * Names come off the mesh and are attacker-controlled, so they are
 * escaped for XML AND stripped of characters XML 1.0 cannot carry at
 * all — a control character in an advert name would otherwise make the
 * whole file unreadable to every GPX tool.
 */
object Gpx {
    const val FILE_NAME = "meshcore-nodes.gpx"
    const val NOTHING_TO_EXPORT = "No nodes with GPS to export"

    /** One node to export; any source of contacts can make these. */
    data class Waypoint(val keyHex: String, val name: String, val latitude: Double?, val longitude: Double?)

    /** The file's text for the radio's live contacts, or null when none has a position. */
    fun fromContacts(contacts: Collection<Contact>): String? =
        build(contacts.map { Waypoint(it.publicKeyHex, it.name, it.latitude, it.longitude) })

    /** The file's text, or null when no waypoint has a plausible position. */
    fun build(points: Collection<Waypoint>): String? {
        val nodes = points.filter { isPlausiblePosition(it.latitude, it.longitude) }
        if (nodes.isEmpty()) return null
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"MeshCore Hardened\" ")
            append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            for (n in nodes.sortedBy { it.keyHex }) {
                append("  <wpt lat=\"${n.latitude}\" lon=\"${n.longitude}\">\n")
                append("    <name>${escape(n.name.ifBlank { n.keyHex.take(12) })}</name>\n")
                append("    <desc>${escape(n.keyHex)}</desc>\n")
                append("  </wpt>\n")
            }
            append("</gpx>\n")
        }
    }

    fun escape(text: String): String = buildString {
        for (ch in text) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' -> append(' ')
                ch < ' ' || ch == '￾' || ch == '￿' -> Unit // not allowed in XML 1.0
                else -> append(ch)
            }
        }
    }
}
