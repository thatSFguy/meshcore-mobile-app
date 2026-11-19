package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.presentation.DiscoveredAdd
import io.github.thatsfguy.meshcore.presentation.HeardReach
import io.github.thatsfguy.meshcore.util.RelativeTime
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
import io.github.thatsfguy.meshcore.util.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A node heard advertising that the radio does not hold as a contact. */
@Serializable
data class HeardNode(
    val keyHex: String,
    val name: String,
    val type: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Our clock, ms. */
    val firstHeardAt: Long,
    val lastHeardAt: Long,
    val snr: Double,
    val rssi: Int,
    /** The signed advert, kept so Add can verify and write it exactly. */
    val advertHex: String,
    /** The fewest hops it was ever heard over; one flood copy via three repeaters doesn't undo hearing it direct. */
    val minHops: Int? = null,
    /** SNR of a direct (0-hop) hearing, kept once seen. */
    val directSnr: Double? = null,
)

@Serializable
private data class HeardSnapshot(val version: Int = 1, val nodes: List<HeardNode> = emptyList())

/**
 * The discovery inbox: verified adverts from nodes the radio doesn't hold
 * (auto-add off, or a type it doesn't auto-add), for the person to Add or
 * Dismiss — Android's `discovered` table as a pure class, for iOS
 * (2026-11-18). One per radio, persisted as JSON, capped at [MAX].
 */
class DiscoveredBook(private val nowMs: () -> Long, initial: List<HeardNode> = emptyList()) {

    private val _nodes = MutableStateFlow(initial)
    val nodes: StateFlow<List<HeardNode>> = _nodes.asStateFlow()

    /** Record a verified advert from a node the radio does NOT hold. */
    fun heard(keyHex: String, name: String, type: Int, latitude: Double?, longitude: Double?,
              snr: Double, rssi: Int, advert: ByteArray, hops: Int?) {
        val key = keyHex.lowercase()
        val now = nowMs()
        val prev = _nodes.value.firstOrNull { it.keyHex == key }
        val row = HeardNode(
            keyHex = key, name = name, type = type, latitude = latitude, longitude = longitude,
            firstHeardAt = prev?.firstHeardAt ?: now, lastHeardAt = now, snr = snr, rssi = rssi,
            advertHex = advert.toHex(),
            minHops = listOfNotNull(prev?.minHops, hops?.takeIf { it >= 0 }).minOrNull(),
            directSnr = if (hops == 0) snr else prev?.directSnr,
        )
        _nodes.value = (_nodes.value.filterNot { it.keyHex == key } + row)
            .sortedByDescending { it.lastHeardAt }
            .take(MAX)
    }

    fun dismiss(keyHex: String) {
        _nodes.value = _nodes.value.filterNot { it.keyHex == keyHex.lowercase() }
    }

    fun clear() { _nodes.value = emptyList() }

    /** Drop whatever the radio now holds — added here, or by its own auto-add. */
    fun forgetHeld(heldKeys: Collection<String>) {
        val held = heldKeys.map { it.lowercase() }.toSet()
        _nodes.value = _nodes.value.filterNot { it.keyHex in held }
    }

    fun get(keyHex: String): HeardNode? = _nodes.value.firstOrNull { it.keyHex == keyHex.lowercase() }

    fun toJson(): String = JSON.encodeToString(HeardSnapshot.serializer(), HeardSnapshot(nodes = _nodes.value))

    companion object {
        /** Enough for a busy mesh; past it the oldest-heard go first. */
        const val MAX = 200

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun fromJson(json: String?, nowMs: () -> Long): DiscoveredBook =
            DiscoveredBook(nowMs, json?.let { runCatching { JSON.decodeFromString(HeardSnapshot.serializer(), it).nodes }.getOrNull() }.orEmpty())

        /**
         * "Repeater · heard, not added". Android used to derive the noun by
         * dropping the last letter of its plural tab label, which made an
         * unknown type "Othe".
         */
        fun kindLine(type: Int): String {
            val noun = when (type) {
                io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_CHAT -> "Contact"
                io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_REPEATER -> "Repeater"
                io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_ROOM -> "Room server"
                io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_SENSOR -> "Sensor"
                else -> "Node"
            }
            return "$noun · heard, not added"
        }

        /** Why a heard node is worth adding — what needs it in the radio's contacts. */
        fun addHint(type: Int): String =
            "Messaging, " + (if (type == io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_CHAT) "" else "administering, ") +
                "routes, favourites and renaming need it in your radio's contacts. Add it, and its full sheet opens."

        /** One line under the name: how it was heard, and when. */
        fun detail(node: HeardNode, nowMs: Long): String {
            val reach = HeardReach.of(node.minHops, node.directSnr)
            val ago = RelativeTime.agoMillis(nowMs - node.lastHeardAt)
            return listOfNotNull(reach, "heard $ago").joinToString(" · ")
        }

        /**
         * "Discover nodes": broadcast a discovery and say which repeaters
         * that the radio holds answered (Android's discoverNodes). Nodes
         * it doesn't hold arrive as adverts into the inbox instead.
         */
        suspend fun discover(engine: MeshCoreEngine): String {
            val prefixes = runCatching { engine.discoverNodePrefixes(io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_REPEATER, 10_000) }
                .getOrDefault(emptySet())
            val held = engine.contacts.value.values
            val answered = prefixes
                .flatMap { p -> io.github.thatsfguy.meshcore.protocol.NodeDiscovery.matching(p, held) { it.publicKeyHex } }
                .distinctBy { it.publicKeyHex }
                .map { it.name.ifBlank { it.publicKeyHex.take(12) } }
            return when {
                prefixes.isEmpty() -> "No repeater answered. Nothing may be in range."
                answered.isEmpty() -> "${prefixes.size} repeater(s) answered, none of them in your contacts."
                else -> "${answered.size} answered: ${answered.sorted().joinToString(", ")}" +
                    if (prefixes.size > answered.size) " — and ${prefixes.size - answered.size} not in your contacts." else "."
            }
        }

        /**
         * What to warn about before adding [node], or null to add straight
         * away. A heard node is heard-only by definition (nothing shows it
         * hears us), so the test is only how far: [HeardReach]'s hops or the
         * distance from the radio (Android's isFarHeardOnly + addWarning).
         */
        fun addWarning(
            node: HeardNode,
            origin: io.github.thatsfguy.meshcore.presentation.SelfPosition,
            system: io.github.thatsfguy.meshcore.presentation.UnitSystem,
        ): String? {
            val metres = origin.metresTo(node.latitude, node.longitude)
            val far = (node.minHops != null && node.minHops >= io.github.thatsfguy.meshcore.presentation.Reachability.FAR_HOPS) ||
                (metres != null && metres >= io.github.thatsfguy.meshcore.presentation.Reachability.FAR_METRES)
            if (!far) return null
            return io.github.thatsfguy.meshcore.presentation.Reachability.addWarning(
                node.minHops, metres?.let { io.github.thatsfguy.meshcore.presentation.Units.distance(it, system) },
            )
        }

        /** Add from the stored, signed advert; the sentence to show. */
        suspend fun add(engine: MeshCoreEngine, node: HeardNode): String {
            val payload = hexToBytesOrNull(node.advertHex)
            val outcome = if (payload == null) {
                MeshCoreEngine.AddOutcome.Unverified
            } else {
                runCatching { engine.addContactFromAdvert(payload) }.getOrDefault(MeshCoreEngine.AddOutcome.Failed)
            }
            return DiscoveredAdd.message(outcome, node.name.ifBlank { node.keyHex.take(12) })
        }
    }
}
