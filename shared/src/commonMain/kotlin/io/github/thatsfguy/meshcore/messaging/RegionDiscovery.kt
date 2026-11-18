package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.protocol.NodeDiscovery
import io.github.thatsfguy.meshcore.protocol.Regions

/**
 * "Discover from repeaters": broadcast a discovery, then ask each
 * repeater that answered — and that the radio holds as a contact, since
 * the regions request is addressed — which named regions it carries.
 *
 * Lifted from Android's ViewModel for iOS (2026-11-18). Nothing a
 * repeater reports is authenticated; the result is offered, never added.
 */
class RegionDiscovery(private val radio: Radio) {

    interface Radio {
        /** Key prefixes of repeaters that answered a discovery broadcast. */
        suspend fun discoverRepeaterPrefixes(): Set<String>
        fun heldRepeaters(): List<Contact>
        /** The repeater's region names, or null when it did not answer. */
        suspend fun regionsOf(repeater: Contact): List<String>?
    }

    data class Result(val names: List<String>, val answered: Int, val asked: Int) {
        /** What to say above the list — or instead of it when it is empty. */
        val summary: String get() = when {
            names.isNotEmpty() ->
                "Names reported by nearby repeaters. Nothing about them is authenticated — add the ones you recognise."
            asked == 0 ->
                "No repeater answered the discovery broadcast, so there was nobody to ask. Nothing may be in range."
            answered > 0 ->
                "$answered of $asked repeater(s) answered, and none of them uses named regions — they carry the global scope only. There is nothing to add."
            else ->
                "Asked $asked repeater(s); none replied. They may be out of range, or running firmware without region support."
        }
    }

    suspend fun run(): Result {
        val prefixes = runCatching { radio.discoverRepeaterPrefixes() }.getOrDefault(emptySet())
        val held = radio.heldRepeaters()
        val targets = prefixes
            .flatMap { p -> NodeDiscovery.matching(p, held) { it.publicKeyHex } }
            .distinctBy { it.publicKeyHex }
        val found = LinkedHashSet<String>()
        var answered = 0
        for (repeater in targets) {
            val names = runCatching { radio.regionsOf(repeater) }.getOrNull() ?: continue
            answered++
            // A hostile repeater can name anything: keep only valid names,
            // canonical, once each.
            names.mapNotNullTo(found) { Regions.canonical(it) }
        }
        return Result(found.sorted(), answered, targets.size)
    }

    companion object {
        fun over(engine: MeshCoreEngine): RegionDiscovery = RegionDiscovery(object : Radio {
            override suspend fun discoverRepeaterPrefixes() =
                engine.discoverNodePrefixes(io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_REPEATER, 10_000)
            override fun heldRepeaters() = engine.contacts.value.values.filter { it.isRepeater }
            override suspend fun regionsOf(repeater: Contact) = engine.requestRegions(
                repeaterPubKey = repeater.publicKey,
                replyPath = repeater.storedPath,
                replyHopCount = repeater.pathInfo.hops.coerceAtLeast(0),
                timeoutMs = 30_000,
                replyPathHashWidth = repeater.pathInfo.hashWidth,
            )
        })
    }
}

/**
 * The app's own list of region names — a phone-side list, nothing on
 * the radio. Canonical, unique, sorted; an invalid name is refused.
 */
object RegionList {
    const val INVALID_NAME = "Region names are lowercase letters, digits and hyphens (max ${Regions.MAX_NAME_LENGTH})"

    /** The list with [name] added, or null when [name] is not a valid region. */
    fun add(list: List<String>, name: String): List<String>? {
        val c = Regions.canonical(name) ?: return null
        return (list.mapNotNull { Regions.canonical(it) } + c).distinct().sorted()
    }

    fun remove(list: List<String>, name: String): List<String> {
        val c = Regions.canonical(name) ?: name
        return list.mapNotNull { Regions.canonical(it) }.filter { it != c }.distinct().sorted()
    }

    // --- Per-channel regions (slot -> region), stored as "slot=region" lines ---

    fun parseChannelRegions(raw: String?): Map<Int, String> =
        raw.orEmpty().lines().mapNotNull { line ->
            val slot = line.substringBefore('=', "").trim().toIntOrNull()?.takeIf { it in 0..255 }
            val region = Regions.canonical(line.substringAfter('=', ""))
            if (slot != null && region != null) slot to region else null
        }.toMap()

    fun encodeChannelRegions(map: Map<Int, String>): String =
        map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }

    /** [map] with channel [slot] set to [region], or cleared when [region] is null or invalid. */
    fun withChannelRegion(map: Map<Int, String>, slot: Int, region: String?): Map<Int, String> {
        val c = Regions.canonical(region)
        return if (c == null) map - slot else map + (slot to c)
    }

    /** Forgetting a region sends every channel that used it back to the app-wide scope (Android's rule). */
    fun withoutRegion(map: Map<Int, String>, region: String): Map<Int, String> {
        val c = Regions.canonical(region) ?: region
        return map.filterValues { it != c }
    }
}
