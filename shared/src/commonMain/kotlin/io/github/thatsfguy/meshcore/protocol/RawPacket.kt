package io.github.thatsfguy.meshcore.protocol

/**
 * An over-the-air mesh packet, as handed up verbatim inside
 * PUSH_CODE_LOG_RX_DATA (MESHCORE_PROTOCOL.md §9).
 *
 * Header byte: (payload_ver << 6) | (payload_type << 2) | route_type.
 * When route_type indicates transport routing, 4 transport bytes follow
 * the header before the encoded path length.
 */
data class RawPacket(
    val header: Int,
    val routeType: Int,
    val payloadType: Int,
    val payloadVer: Int,
    val pathLenRaw: Int,
    val pathBytes: ByteArray,
    val payload: ByteArray,
) {
    /** Per-hop hash width encoded in the top 2 bits of path_len. */
    val pathHashWidth: Int get() = ((pathLenRaw and 0xC0) shr 6) + 1

    /** Hop count encoded in the low 6 bits of path_len. */
    val hopCount: Int get() = pathLenRaw and 0x3F

    companion object {
        private const val ROUTE_MASK = 0x03
        private const val TYPE_SHIFT = 2
        private const val TYPE_MASK = 0x0F
        private const val VER_SHIFT = 6
        private const val VER_MASK = 0x03

        // Route types that carry 4 extra transport bytes after the header.
        private const val ROUTE_TRANSPORT_FLOOD = 0x00
        private const val ROUTE_TRANSPORT_DIRECT = 0x03

        /** Parse a raw packet; null (never a throw) on malformed input. */
        fun parse(raw: ByteArray): RawPacket? {
            return try {
                val r = BufferReader(raw)
                val header = r.readByte()
                val routeType = header and ROUTE_MASK
                if (routeType == ROUTE_TRANSPORT_FLOOD || routeType == ROUTE_TRANSPORT_DIRECT) {
                    r.skipBytes(4)
                }
                val pathLenRaw = r.readByte()
                val width = ((pathLenRaw and 0xC0) shr 6) + 1
                val hops = pathLenRaw and 0x3F
                val pathBytes = r.readBytes(hops * width)
                val payload = r.readRemainingBytes()
                RawPacket(
                    header = header,
                    routeType = routeType,
                    payloadType = (header shr TYPE_SHIFT) and TYPE_MASK,
                    payloadVer = (header shr VER_SHIFT) and VER_MASK,
                    pathLenRaw = pathLenRaw,
                    pathBytes = pathBytes,
                    payload = payload,
                )
            } catch (_: TruncatedFrameException) {
                null
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is RawPacket && header == other.header && pathLenRaw == other.pathLenRaw &&
            pathBytes.contentEquals(other.pathBytes) && payload.contentEquals(other.payload)

    override fun hashCode(): Int = header * 31 + payload.contentHashCode()
}
