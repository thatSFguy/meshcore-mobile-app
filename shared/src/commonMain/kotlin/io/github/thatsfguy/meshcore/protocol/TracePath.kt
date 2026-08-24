package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.toHex

/**
 * Result of a path trace (PUSH_CODE_TRACE_DATA, 0x89).
 *
 * Wire layout (ported from the MeshCore Open reference client's
 * `_handleTraceResponse`):
 * ```
 * [0]      0x89
 * [1]      reserved
 * [2]      path_len byte
 * [3]      flags        — hop-hash width = 1 << (flags & 0x03)
 * [4..7]   tag  u32     — echoes the tag we sent
 * [8..11]  auth u32
 * [..]     path         — path_len bytes of hop hashes
 * [..]     snr          — (path_len / width) + 1 signed bytes, each /4 dB
 * ```
 * The firmware emits one SNR per hop plus a final SNR for the link back
 * to this node, so [snrs] is always one longer than [hops].
 */
data class TraceResult(
    val tag: Long,
    val auth: Long,
    val flags: Int,
    val hashWidth: Int,
    /** Hop hashes in wire order, each [hashWidth] bytes, hex-encoded. */
    val hops: List<String>,
    /** Per-hop SNR in dB, final entry = link to this node. */
    val snrs: List<Double>,
) {
    val hopCount: Int get() = hops.size
}

object TracePath {

    /**
     * The trace flags byte that declares a hop-hash width.
     *
     * The receiving side reads the width back as
     * `1 shl (flags and 0x03)`, so this is the exact inverse: 1 byte →
     * 0, 2 bytes → 1, 4 bytes → 2. Getting it wrong doesn't degrade the
     * trace, it voids it — the mesh and the probe disagree about how
     * wide a hop is.
     */
    fun flagsForHashWidth(hashWidth: Int): Int = when {
        hashWidth <= 1 -> 0
        hashWidth == 2 -> 1
        else -> 2
    }

    /** Parse a PUSH_CODE_TRACE_DATA frame; null on malformed input. */
    fun parse(frame: ByteArray): TraceResult? {
        if (frame.size < 12) return null
        return try {
            val r = BufferReader(frame)
            r.skipBytes(2) // push code + reserved
            val pathLenByte = r.readByte()
            val flags = r.readByte()
            val tag = r.readUInt32LE()
            val auth = r.readUInt32LE()

            var width = (1 shl (flags and 0x03)).coerceIn(1, 4)
            // 0xFF means "no path" (direct); otherwise the byte is a raw
            // length, unless the top two bits pack a width like the
            // over-the-air path encoding — then re-derive both.
            var pathLength = if (pathLenByte == 0xFF) 0 else pathLenByte
            if (pathLength > r.remaining && (pathLenByte and 0xC0) != 0) {
                val packedWidth = ((pathLenByte and 0xC0) shr 6) + 1
                val packedLength = (pathLenByte and 0x3F) * packedWidth
                if (packedLength <= r.remaining) {
                    width = packedWidth
                    pathLength = packedLength
                }
            }
            if (pathLength > r.remaining) return null

            val pathBytes = r.readBytes(pathLength)
            val hops = pathBytes.toList().chunked(width) { it.toByteArray().toHex() }

            val snrCount = (pathLength / width) + 1
            val snrs = ArrayList<Double>(snrCount)
            repeat(snrCount) {
                if (r.remaining <= 0) return@repeat
                snrs.add(r.readInt8() / 4.0)
            }

            TraceResult(tag, auth, flags, width, hops, snrs)
        } catch (_: TruncatedFrameException) {
            null
        }
    }
}

/**
 * How outbound packets to a contact are routed.
 *
 * MeshCore stores the route in the contact record itself, so switching
 * mode means rewriting that record (CMD_ADD_UPDATE_CONTACT):
 *  - [Auto]   leave the radio's learned path alone.
 *  - [Flood]  clear the path (path_len = 0xFF) so packets flood.
 *  - [Manual] pin an explicit hop list.
 */
enum class RoutingMode { Auto, Flood, Manual }

object PathCodec {
    /** path_len value meaning "no stored path — flood". */
    const val PATH_LEN_FLOOD = 0xFF

    /**
     * A decoded `path_len` byte.
     *
     * The byte is NOT a length: the low 6 bits are the hop count and the
     * top 2 bits are the hash-width mode (width = mode + 1). A path of 4
     * hops at 2 bytes per hop is 0x44 — read as a byte count that's 68,
     * which is how "4 hops" turns into "34 hops" on screen.
     */
    data class PathInfo(
        /** Hops travelled; -1 when the message floods. */
        val hops: Int,
        /** Bytes per hop hash (1..4). */
        val hashWidth: Int,
        /** Bytes of [Contact.path] that are actually the path. */
        val byteLength: Int,
    ) {
        val isFlood: Boolean get() = hops < 0
    }

    /** Decode a `path_len` byte from a contact record or a frame. */
    fun decodePathLen(raw: Int): PathInfo {
        // readInt8 gives -1 for 0xFF; accept either spelling of flood.
        if (raw < 0 || (raw and 0xFF) == PATH_LEN_FLOOD) return PathInfo(-1, 1, 0)
        val b = raw and 0xFF
        val width = ((b and 0xC0) shr 6) + 1
        val hops = b and 0x3F
        return PathInfo(hops = hops, hashWidth = width, byteLength = hops * width)
    }

    /**
     * Largest hop count representable at [hashWidth].
     *
     * Bounded twice: the path itself must fit the record's 64-byte
     * buffer, and 63 hops at 4-byte hashes would encode to 0xFF — the
     * flood sentinel — so a route would silently become "no route".
     */
    fun maxHopsFor(hashWidth: Int): Int {
        val width = hashWidth.coerceIn(1, 4)
        return minOf(0x3F - 1, Codes.MAX_PATH_SIZE / width)
    }

    /**
     * Encode hops + hash width back into a `path_len` byte for
     * CMD_ADD_UPDATE_CONTACT. Negative [hops] means flood.
     */
    fun encodePathLen(hops: Int, hashWidth: Int): Int {
        if (hops < 0) return PATH_LEN_FLOOD
        val width = hashWidth.coerceIn(1, 4)
        val mode = width - 1
        val capped = hops.coerceAtMost(maxHopsFor(width))
        return (capped and 0x3F) or (mode shl 6)
    }

    /** Max hops a path may contain (MAX_PATH_SIZE / 1-byte hashes). */
    const val MAX_HOPS = Codes.MAX_PATH_SIZE

    /**
     * Parse a user-entered hop list: whitespace/comma/colon-separated
     * hex tokens, each [hashWidth] bytes. Returns null if any token is
     * malformed or the path is too long — the editor shows an error
     * rather than sending a route the radio would reject.
     */
    fun parseHopTokens(input: String, hashWidth: Int = 1): ByteArray? {
        val tokens = input.split(' ', ',', ':', '\n', '\t', '-')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ByteArray(0)
        if (tokens.size > MAX_HOPS) return null
        val out = ArrayList<Byte>(tokens.size * hashWidth)
        for (t in tokens) {
            if (t.length != hashWidth * 2) return null
            for (i in 0 until hashWidth) {
                val hi = t[i * 2].digitToIntOrNull(16) ?: return null
                val lo = t[i * 2 + 1].digitToIntOrNull(16) ?: return null
                out.add(((hi shl 4) or lo).toByte())
            }
        }
        return out.toByteArray()
    }

    /** Render path bytes as space-separated hop tokens for the editor. */
    /**
     * One hop of a stored path, resolved against known contacts.
     *
     * A hop is a TRUNCATED hash of a node's public key — typically two
     * bytes. That is 16 bits: several nodes can legitimately share one,
     * and an attacker can manufacture a collision cheaply. So this never
     * picks a winner. Exactly one match gets a name; more than one is
     * reported as ambiguous with the candidates listed; none stays a
     * bare hash.
     */
    data class Hop(
        /** The hop hash as lower-case hex, e.g. "b389". */
        val hashHex: String,
        /**
         * Names of every contact whose key starts with [hashHex],
         * nearest first when the caller gave [resolveHops] a distance.
         */
        val candidates: List<String>,
    ) {
        val isResolved: Boolean get() = candidates.size == 1
        val isAmbiguous: Boolean get() = candidates.size > 1

        /**
         * "b389 SpartaMI", "b389 SpartaMI or Impostor", or "b389".
         *
         * An ambiguous hop NAMES its candidates rather than counting
         * them. "(2 matches)" was true and useless: the reader is
         * looking at a route to work out who carried something, and a
         * count tells them only that the app knows something it will not
         * say. Naming every possibility asserts no identity — "A or B"
         * cannot be misread as a resolution the way a single name could
         * — which is the guarantee this class exists to keep (PARITY
         * §12: a truncated hash is not an identity).
         *
         * Long lists stay readable: past [NAMED_CANDIDATES] the rest are
         * counted, because a hop matching six nodes is telling you about
         * the width of the hash, not about six nodes.
         */
        val label: String
            get() = when {
                isResolved -> "$hashHex ${candidates[0]}"
                isAmbiguous -> "$hashHex " + orList(candidates)
                else -> hashHex
            }
    }

    /** How many candidates an ambiguous hop names before it counts. */
    const val NAMED_CANDIDATES = 3

    /** "A or B", "A, B or C", "A, B or 3 others". */
    private fun orList(names: List<String>): String {
        if (names.size <= NAMED_CANDIDATES) {
            return names.dropLast(1).joinToString(", ") + " or " + names.last()
        }
        val shown = names.take(NAMED_CANDIDATES)
        val rest = names.size - NAMED_CANDIDATES
        return shown.joinToString(", ") + " or $rest other" + if (rest == 1) "" else "s"
    }

    /**
     * Split [path] into hops and name each one from [contactsByKeyHex]
     * (full pubkey hex -> display name).
     */
    fun resolveHops(
        path: ByteArray,
        hashWidth: Int,
        contactsByKeyHex: Map<String, String>,
        metresAway: ((keyHex: String) -> Double?)? = null,
    ): List<Hop> {
        val width = hashWidth.coerceIn(1, 4)
        return path.toList().chunked(width) { chunk ->
            val hex = chunk.toByteArray().toHex()
            val matched = contactsByKeyHex.entries
                .filter { it.key.startsWith(hex, ignoreCase = true) }
                .map { it.key to it.value.ifBlank { it.key.take(12) } }
            // Nearest first when distances are known, alphabetical
            // otherwise — and a node whose distance is unknown sorts
            // AFTER every node whose distance is known rather than
            // ranking as if it were at zero. Unknown is not near.
            val ordered = if (metresAway == null) {
                matched.sortedBy { it.second }
            } else {
                matched.sortedWith(
                    compareBy(
                        { metresAway(it.first) == null },
                        { metresAway(it.first) ?: 0.0 },
                        { it.second },
                    ),
                )
            }
            Hop(hashHex = hex, candidates = ordered.map { it.second })
        }
    }

    fun formatHops(path: ByteArray, hashWidth: Int = 1): String =
        path.toList().chunked(hashWidth) { it.toByteArray().toHex() }.joinToString(" ")

    /**
     * Quality label for a remembered path, from its success/failure
     * history (mirrors the reference client's wording).
     */
    fun qualityLabel(successes: Int, failures: Int, isFlood: Boolean): String = when {
        isFlood -> "flood"
        successes == 0 && failures == 0 -> "untested"
        successes == 0 -> "failing"
        failures == 0 && successes >= 5 -> "proven"
        successes.toDouble() / (successes + failures) >= 0.8 -> "strong"
        successes.toDouble() / (successes + failures) >= 0.5 -> "good"
        else -> "fair"
    }
}
