package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.presentation.Reachability
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.HeardVia
import io.github.thatsfguy.meshcore.protocol.PathCodec
import io.github.thatsfguy.meshcore.protocol.RoutingMode
import io.github.thatsfguy.meshcore.protocol.TraceResult
import io.github.thatsfguy.meshcore.util.fixed
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull

/**
 * A contact's routing — the routing sheet's actions and wording, lifted
 * from Android's RoutingSheet and ViewModel for iOS (Phase 4, 2026-11-18).
 *
 * Hop editing itself is [io.github.thatsfguy.meshcore.protocol.HopSelection],
 * which already stores full keys and derives each hop's hash at the
 * mesh's CURRENT width — the fix for this codebase's recurring defect.
 */
class Routing(private val radio: Radio) {

    interface Radio {
        suspend fun setRouting(keyHex: String, mode: RoutingMode, path: ByteArray): Boolean
        suspend fun trace(path: ByteArray, hashWidth: Int): TraceResult?
        suspend fun discover(keyHex: String): io.github.thatsfguy.meshcore.protocol.PathDiscovery.Result?
    }

    data class Outcome(val ok: Boolean, val message: String)

    /**
     * Set [keyHex]'s routing. Manual needs a non-empty [pathHex]; on
     * success a manual path is remembered in [paths] (Android does the
     * same), and [pinned] says whether the person chose this route.
     */
    suspend fun apply(
        keyHex: String,
        mode: RoutingMode,
        pathHex: String?,
        hashWidth: Int,
        paths: PathHistoryBook?,
    ): Outcome {
        val path = pathHex?.takeIf { it.isNotEmpty() }?.let { hexToBytesOrNull(it) } ?: ByteArray(0)
        if (mode == RoutingMode.Manual && path.isEmpty()) return Outcome(false, "Enter at least one hop")
        val ok = runCatching { radio.setRouting(keyHex, mode, path) }.getOrDefault(false)
        if (!ok) return Outcome(false, "Radio rejected the route")
        if (mode == RoutingMode.Manual) paths?.remember(keyHex, pathHex!!.lowercase(), hashWidth)
        return Outcome(true, "Routing set to ${mode.name.lowercase()}")
    }

    /** Run a trace along [contact]'s stored route; the sentence(s) to show. */
    suspend fun trace(contact: Contact, meshWidth: Int): List<String> {
        if (contact.storedPath.isEmpty()) return listOf(traceFailure(contact.pathLen))
        val result = runCatching { radio.trace(contact.storedPath, meshWidth.coerceIn(1, 4)) }.getOrNull()
            ?: return listOf(traceFailure(contact.pathLen))
        return traceLines(result)
    }

    /**
     * "Check if they can hear you": a path discovery both ways. Chat and
     * sensor nodes only answer if their owner allows it, which changes
     * what silence means.
     */
    suspend fun check(contact: Contact, names: Map<String, String>): String {
        val r = runCatching { radio.discover(contact.publicKeyHex) }.getOrNull()
        val asks = contact.type == Codes.ADV_TYPE_CHAT || contact.type == Codes.ADV_TYPE_SENSOR
        val out = r?.let { PathCodec.resolveHops(it.outPath, it.outHashWidth, names, null).map { h -> h.label } }
        val back = r?.let { PathCodec.resolveHops(it.inPath, it.inHashWidth, names, null).map { h -> h.label } }
        return Reachability.describeCheck(out, back, asks)
    }

    companion object {
        // Swift names for the modes: Kotlin/Native renames `Auto` (`auto`
        // is a C keyword), so Swift never spells an entry (IOS_DECISIONS
        // interop rules).
        val AUTO: RoutingMode = RoutingMode.Auto
        val FLOOD: RoutingMode = RoutingMode.Flood
        val MANUAL: RoutingMode = RoutingMode.Manual
        val MODES: List<RoutingMode> = RoutingMode.entries.toList()

        fun modeLabel(mode: RoutingMode): String = mode.name

        fun modeHint(mode: RoutingMode): String = when (mode) {
            RoutingMode.Auto -> "The radio learns and updates the route itself (resets the stored path)."
            RoutingMode.Flood -> "Every packet floods the mesh — slower and noisier, but works when a route is broken."
            RoutingMode.Manual -> "Pin an exact hop list. Each hop is the repeater's path-hash prefix."
        }

        const val NO_HOPS = "No hops yet. Tap a node below to add it, in the order a packet should travel."
        const val NO_RELAYS = "No repeaters known yet — they appear here as they advertise. Until then use the advanced field below."
        const val NO_PATHS = "No paths recorded yet — they appear as the radio learns routes and as messages get delivered."
        const val UNUSABLE_HOPS = "Some hops can't be used on this mesh — fix them before applying."

        fun unresolvedWarning(indices: List<Int>, meshWidth: Int): String =
            "Hop(s) ${indices.joinToString(", ") { (it + 1).toString() }} can't be addressed at this mesh's " +
                "${meshWidth * 2}-hex-digit width — remove them, or re-add from the list below."

        fun relayHint(hopCount: Int, meshWidth: Int): String {
            val max = PathCodec.maxHopsFor(meshWidth)
            return if (hopCount >= max) "Route is full ($max hops max on this mesh)." else "Tap to add a hop:"
        }

        fun tokensHint(meshWidth: Int): String =
            "For routes copied from elsewhere. Hops are ${meshWidth * 2}-hex-digit tokens on this mesh, in travel order."

        fun tokensRefused(meshWidth: Int): String =
            "Hops must be ${meshWidth * 2}-hex-digit tokens (max ${PathCodec.maxHopsFor(meshWidth)})"

        /** Relays a route can go through: repeaters and room servers, by name. */
        fun relays(contacts: Collection<Contact>): List<Contact> = contacts
            .filter { it.type == Codes.ADV_TYPE_REPEATER || it.type == Codes.ADV_TYPE_ROOM }
            .sortedBy { it.name.lowercase() }

        /** A trace needs at least one hop to report on. */
        fun canTrace(contact: Contact): Boolean = PathCodec.decodePathLen(contact.pathLen).hops > 0

        fun traceHint(contact: Contact): String = if (canTrace(contact)) {
            "Ask the mesh to report the hops (and per-hop SNR) a packet travels."
        } else {
            "Nothing to trace: this contact is reached directly, with no repeater in between. A trace " +
                "reports the hops along a route, so it needs a route with at least one hop."
        }

        fun traceFailure(pathLen: Int): String = if (pathLen == PathCodec.PATH_LEN_FLOOD || pathLen < 0) {
            "No stored route to trace — this contact is reached by flooding. Pin a path first, or trace a contact that has one."
        } else {
            "No reply. A trace has to reach every hop on this route and return — check the nodes on it have been heard recently."
        }

        fun traceLines(t: TraceResult): List<String> = listOf(
            if (t.hopCount == 0) "Direct (no hops)" else "${t.hopCount} hop(s): ${t.hops.joinToString(" → ")}",
            "SNR: " + t.snrs.joinToString(", ") { "${fixed(it, 1)} dB" },
        )

        /**
         * "Reply the way they reached me": the newest inbound route in
         * [threadKey], reversed into the order a reply travels. Null when
         * none was recorded or its width is not a real one.
         */
        fun replyRoute(book: MessageBook, threadKey: String): String? {
            val m = book.latestArrival(threadKey) ?: return null
            val width = m.arrivalHashWidth?.takeIf { it in 1..4 } ?: return null
            val path = m.arrivalPathHex?.takeIf { it.isNotEmpty() } ?: return null
            return HeardVia.reverseHex(path, width).takeIf { it.isNotEmpty() }
        }

        fun over(engine: MeshCoreEngine): Routing = Routing(object : Radio {
            override suspend fun setRouting(keyHex: String, mode: RoutingMode, path: ByteArray): Boolean {
                val key = hexToBytesOrNull(keyHex) ?: return false
                return engine.setRouting(key, mode, path)
            }
            override suspend fun trace(path: ByteArray, hashWidth: Int) =
                engine.tracePath(engine.nowMillis() and 0xFFFFFFFFL, path, hashWidth, 0L, 30_000)
            override suspend fun discover(keyHex: String) =
                hexToBytesOrNull(keyHex)?.let { engine.discoverPath(it, io.github.thatsfguy.meshcore.protocol.BinaryRequestBudget.MAX_BUDGET_MS) }
        })
    }
}
