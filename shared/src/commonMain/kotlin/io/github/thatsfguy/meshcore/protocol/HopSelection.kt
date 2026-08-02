package io.github.thatsfguy.meshcore.protocol

/**
 * Building a manual route by picking nodes and ordering them, rather
 * than typing hex (PARITY.md §13).
 *
 * The typing-based editor asked the user for something they should
 * never have to know. A hop is the leading bytes of a repeater's public
 * key, and *how many* leading bytes is a property of the mesh
 * (`DEVICE_INFO.pathHashByteWidth`), not of the node — so "3f" is a
 * correct hop on one mesh and a rejected one on the next. That width
 * has now produced three separate defects (trace flags, path history,
 * Apply-path parsing) and the repeater picker below shipped with a
 * fourth: it hardcoded a 1-byte hop, so on a 2-byte mesh every tapped
 * repeater inserted half a hop.
 *
 * The fix is to stop carrying the derived value at all. A picked hop
 * stores the **full public key** and derives its hash on demand at the
 * current width, so a width that arrives late (DEVICE_INFO lands after
 * the sheet opens) silently corrects the display instead of pinning a
 * wrong route. Only hops that came in as bare hashes — a traced path,
 * an imported one — keep a literal, and those are the only ones that
 * can fail to resolve.
 */
object HopSelection {

    /**
     * One hop in a route being assembled.
     *
     * Exactly one of [keyHex] / [rawHash] carries the identity; a hop
     * with neither can't be encoded and is reported as unresolved
     * rather than dropped, because silently dropping a hop produces a
     * *shorter route that still looks valid*.
     */
    data class Hop(
        /** Full public key hex of the picked node, when it is known. */
        val keyHex: String? = null,
        /** Literal hop hash, for hops that never had a key behind them. */
        val rawHash: String? = null,
        /** Display name, for the picked case. */
        val name: String? = null,
    ) {
        /**
         * This hop's hash at [hashWidth] bytes, or null if it cannot be
         * expressed there — a key shorter than the width, or a literal
         * captured at a different width.
         */
        fun hashHex(hashWidth: Int): String? {
            val width = hashWidth.coerceIn(1, 4)
            val chars = width * 2
            keyHex?.let { key ->
                val clean = key.trim().lowercase()
                if (clean.length < chars || !clean.isHex()) return null
                return clean.take(chars)
            }
            val raw = rawHash?.trim()?.lowercase() ?: return null
            return raw.takeIf { it.length == chars && it.isHex() }
        }

        /** What to show in the list: the name if we have one, else the hash. */
        fun label(hashWidth: Int): String {
            val hash = hashHex(hashWidth)
            val shown = name?.takeIf { it.isNotBlank() }
            return when {
                shown != null && hash != null -> "$shown · $hash"
                shown != null -> shown
                hash != null -> hash
                // Never render this as an empty row. An invisible hop in
                // an ordered list is a route the user cannot see is wrong.
                else -> rawHash?.takeIf { it.isNotBlank() } ?: "(unusable hop)"
            }
        }
    }

    /** A hop taken from a known node — the ordinary, tap-to-add case. */
    fun fromContact(keyHex: String, name: String?): Hop =
        Hop(keyHex = keyHex.trim().lowercase(), name = name?.takeIf { it.isNotBlank() })

    /** A hop that only ever existed as a hash (traced or pasted path). */
    fun fromHash(hash: String): Hop = Hop(rawHash = hash.trim().lowercase())

    /**
     * Append [hop], refusing to exceed what the radio can store at
     * [hashWidth]. Returns the list unchanged when full — the caller
     * reports the cap; appending-then-truncating on Apply would drop a
     * hop the user watched go in.
     */
    fun add(hops: List<Hop>, hop: Hop, hashWidth: Int): List<Hop> =
        if (hops.size >= PathCodec.maxHopsFor(hashWidth)) hops else hops + hop

    fun removeAt(hops: List<Hop>, index: Int): List<Hop> =
        if (index !in hops.indices) hops else hops.filterIndexed { i, _ -> i != index }

    /**
     * Swap [index] with its neighbour. Out-of-range moves — up from the
     * top, down from the bottom — are no-ops rather than errors, so the
     * buttons can stay enabled-looking without corrupting order.
     */
    fun move(hops: List<Hop>, index: Int, delta: Int): List<Hop> {
        val target = index + delta
        if (index !in hops.indices || target !in hops.indices) return hops
        val out = hops.toMutableList()
        out[index] = hops[target]
        out[target] = hops[index]
        return out
    }

    /** Indices whose hop cannot be expressed at [hashWidth]. */
    fun unresolvedIndices(hops: List<Hop>, hashWidth: Int): List<Int> =
        hops.indices.filter { hops[it].hashHex(hashWidth) == null }

    /**
     * Encode the route for CMD_ADD_UPDATE_CONTACT, or null if it can't
     * be encoded honestly: an unresolved hop, or more hops than the
     * record holds. Null means "tell the user", never "send what fits".
     */
    fun toBytes(hops: List<Hop>, hashWidth: Int): ByteArray? {
        if (hops.isEmpty()) return ByteArray(0)
        if (hops.size > PathCodec.maxHopsFor(hashWidth)) return null
        val hex = StringBuilder()
        for (hop in hops) hex.append(hop.hashHex(hashWidth) ?: return null)
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hex[i * 2].digitToIntOrNull(16) ?: return null
            val lo = hex[i * 2 + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** The route as the contiguous hex the contact record wants. */
    fun toHex(hops: List<Hop>, hashWidth: Int): String? =
        toBytes(hops, hashWidth)?.joinToString("") {
            val v = it.toInt() and 0xFF
            "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 0x0F]
        }

    /**
     * Rebuild a selection from stored path bytes, naming each hop from
     * [contactsByKeyHex] (full key hex -> name).
     *
     * A hop is a truncated hash, so a match is not an identification:
     * only an unambiguous match adopts the contact's key. Several
     * matches stay a bare hash — adopting one would silently rewrite the
     * user's route to a *different node* the next time the width
     * changed, which is the exact failure the key-carrying design exists
     * to prevent.
     */
    fun fromPath(
        path: ByteArray,
        hashWidth: Int,
        contactsByKeyHex: Map<String, String>,
    ): List<Hop> {
        if (path.isEmpty()) return emptyList()
        val width = hashWidth.coerceIn(1, 4)
        return PathCodec.resolveHops(path, width, contactsByKeyHex).map { resolved ->
            val matches = contactsByKeyHex.entries.filter {
                it.key.startsWith(resolved.hashHex, ignoreCase = true)
            }
            if (matches.size == 1) {
                fromContact(matches[0].key, matches[0].value)
            } else {
                fromHash(resolved.hashHex)
            }
        }
    }

    /**
     * Parse the advanced free-text field into hops. Kept as a fallback
     * for routes copied from elsewhere; null on anything malformed,
     * exactly as [PathCodec.parseHopTokens] does.
     */
    fun fromTokens(input: String, hashWidth: Int): List<Hop>? {
        val bytes = PathCodec.parseHopTokens(input, hashWidth) ?: return null
        if (bytes.isEmpty()) return emptyList()
        return PathCodec.formatHops(bytes, hashWidth.coerceIn(1, 4))
            .split(' ')
            .filter { it.isNotEmpty() }
            .map { fromHash(it) }
    }

    /** Free-text rendering of a selection, for the advanced field. */
    fun toTokens(hops: List<Hop>, hashWidth: Int): String =
        hops.joinToString(" ") { it.hashHex(hashWidth) ?: (it.rawHash ?: "??") }

    private fun String.isHex(): Boolean =
        isNotEmpty() && all { it.digitToIntOrNull(16) != null }
}
