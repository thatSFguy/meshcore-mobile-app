package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.toHex

/**
 * Active node discovery over CMD_SEND_CONTROL_DATA / PUSH_CODE_CONTROL_DATA.
 *
 * A discovery request is a broadcast: "any node of these types, say
 * hello". Responders answer with a **public-key prefix**, which is not
 * an identity (PARITY §12 — a truncated hash is cheap to collide).
 * Callers match a prefix against contacts they already hold and must
 * treat an ambiguous match as ambiguous.
 *
 * Nothing here is authenticated. The tag correlates *our* request with
 * replies to it; anyone within radio range can hear the request and
 * answer with a prefix they don't own, so a discovery hit is only ever
 * a hint about who to ask next, never proof of anything.
 */
object NodeDiscovery {

    /**
     * Parse a PUSH_CODE_CONTROL_DATA payload (push code already
     * stripped) as a discovery response, returning the responder's
     * public-key prefix in hex, or null when the frame is not a
     * discovery response for [expectedTag], is of the wrong node type,
     * or is short/malformed.
     *
     * Layout: `[snr][rssi][path_len][(subtype << 4) | adv_type][inbound_snr][tag u32][prefix…]`
     */
    fun parseDiscoveryResponse(
        payload: ByteArray,
        expectedTag: Long,
        expectedType: Int = Codes.ADV_TYPE_REPEATER,
    ): String? {
        // 3 header bytes + type byte + snr + u32 tag, then at least one
        // prefix byte. Anything shorter can't be a response.
        if (payload.size < 10) return null
        val r = BufferReader(payload)
        return try {
            r.readBytes(3) // SNR, RSSI, path_len as seen by the companion radio
            val typeByte = r.readByte()
            if ((typeByte shr 4) and 0x0F != Codes.CONTROL_SUBTYPE_DISCOVER_RESP) return null
            if (typeByte and 0x0F != expectedType) return null
            r.readByte() // inbound SNR reported by the responder
            if (r.readUInt32LE() != expectedTag) return null
            val prefix = r.readRemainingBytes()
            if (prefix.isEmpty()) null else prefix.toHex()
        } catch (_: Throwable) {
            // Guarded reads throw on truncation; a malformed frame is
            // just not a discovery response.
            null
        }
    }

    /**
     * Contacts whose public key starts with [prefixHex]. A prefix can
     * match more than one contact — the caller decides what to do about
     * that; this never picks one.
     */
    fun <T> matching(prefixHex: String, contacts: Iterable<T>, keyOf: (T) -> String): List<T> {
        if (prefixHex.isEmpty()) return emptyList()
        val prefix = prefixHex.lowercase()
        return contacts.filter { keyOf(it).lowercase().startsWith(prefix) }
    }
}
