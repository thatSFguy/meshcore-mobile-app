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
