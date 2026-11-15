package io.github.thatsfguy.meshcore.protocol

/**
 * Path discovery: one flooded request that comes back with BOTH routes —
 * the one our packets take to reach the node, and the one its packets
 * take back to us.
 *
 * The radio sends it as a telemetry request with the stored route
 * suppressed, so it floods (companion_radio/MyMesh.cpp:1651-1680). The
 * node answers with a path return carrying the route our flood took,
 * and the radio pushes both to the app (MyMesh.cpp:781-805) without
 * storing either as the contact's route ("DON'T send reciprocal path").
 *
 * Two limits decide what an answer — and a silence — means:
 *
 *  - The node must have US as a contact, or it can't decrypt the
 *    request (BaseChatMesh.cpp:203).
 *  - It answers only when it grants base telemetry. A companion's
 *    default is to deny (NodePrefs.h:26, `telemetry_mode_base = 0`), and
 *    a denied request gets no reply at all (BaseChatMesh.cpp:314-316).
 *
 * So an answer proves both directions, and silence proves nothing.
 */
object PathDiscovery {

    /** `MAX_PATH_SIZE` in the firmware (MeshCore.h). */
    const val MAX_PATH_BYTES = 64

    class Result(
        /** First 6 bytes of the node's key, as hex. */
        val keyPrefixHex: String,
        /** Our route TO the node — what our flood took to reach it. */
        val outPath: ByteArray,
        val outHashWidth: Int,
        /** The node's route back to us — how its answer arrived. */
        val inPath: ByteArray,
        val inHashWidth: Int,
    ) {
        val outHops: Int get() = outPath.size / outHashWidth
        val inHops: Int get() = inPath.size / inHashWidth
    }

    /**
     * Parse the whole push frame, code byte included:
     * `[0x8D][reserved][key prefix x6][out_len][out path][in_len][in path]`
     * (MyMesh.cpp:793-801). Each length byte is a firmware `path_len`:
     * hop count in the low 6 bits, hash width minus one in the top 2,
     * and the path is `count × width` bytes (Packet.cpp:20-30).
     *
     * Null for anything malformed — short, or a length the firmware's own
     * `isValidPathLen` would refuse (width 4 is reserved, Packet.cpp:16).
     */
    fun parse(frame: ByteArray): Result? {
        if (frame.isEmpty() || (frame[0].toInt() and 0xFF) != Codes.PUSH_CODE_PATH_DISCOVERY_RESPONSE) {
            return null
        }
        var i = 2 // code, reserved
        if (frame.size < i + 6 + 1) return null
        val prefix = frame.copyOfRange(i, i + 6)
        i += 6
        val out = readPath(frame, i) ?: return null
        i = out.next
        val inn = readPath(frame, i) ?: return null
        return Result(
            keyPrefixHex = prefix.joinToString("") { b ->
                val v = b.toInt() and 0xFF
                (if (v < 16) "0" else "") + v.toString(16)
            },
            outPath = out.bytes,
            outHashWidth = out.width,
            inPath = inn.bytes,
            inHashWidth = inn.width,
        )
    }

    private class Read(val bytes: ByteArray, val width: Int, val next: Int)

    private fun readPath(frame: ByteArray, at: Int): Read? {
        if (at >= frame.size) return null
        val lenByte = frame[at].toInt() and 0xFF
        val width = (lenByte shr 6) + 1
        if (width == 4) return null
        val count = lenByte and 0x3F
        val bytes = count * width
        if (bytes > MAX_PATH_BYTES) return null
        val start = at + 1
        if (start + bytes > frame.size) return null
        return Read(frame.copyOfRange(start, start + bytes), width, start + bytes)
    }
}
