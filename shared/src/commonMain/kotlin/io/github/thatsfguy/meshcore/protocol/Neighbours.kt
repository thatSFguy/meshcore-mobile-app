package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.toHex

/**
 * A repeater's one-hop neighbour table (PARITY.md §6,
 * `RepeaterNeighboursMapScreen`), read with
 * `CMD_SEND_BINARY_REQ` / `REQ_TYPE_GET_NEIGHBORS`.
 *
 * ## Wire format
 *
 * Body after the binary-response header:
 * ```
 *   u16 total          // neighbours the node knows
 *   u16 count          // entries in THIS reply
 *   count × { [4] key_prefix | u32 last_heard | i8 snr_quarters }
 * ```
 * Transcribed from the reference client's `parseNeighborsData`.
 *
 * ## What a neighbour entry is and isn't
 *
 * The identifier is a **4-byte key prefix**, not a key. That is 32 bits
 * — enough that a collision is unlikely by accident and cheap to
 * produce on purpose. So an entry names a node only as far as a prefix
 * goes (PARITY §12), and this parser reports every contact that matches
 * rather than choosing one.
 *
 * It is also hearsay: the list is what the *repeater* says it hears,
 * relayed to us by that repeater. It is useful for understanding
 * coverage and useless as evidence about anybody.
 */
object Neighbours {

    /** Bytes of public key each entry carries. */
    const val KEY_PREFIX_BYTES = 4

    /** `[4] prefix | u32 last_heard | i8 snr` */
    const val ENTRY_BYTES = KEY_PREFIX_BYTES + 4 + 1

    /** Refuse absurd counts rather than trusting a u16 off the mesh. */
    const val MAX_ENTRIES = 512

    data class Neighbour(
        /** 4-byte key prefix, hex. NOT an identity. */
        val keyPrefixHex: String,
        /** Epoch seconds the repeater last heard this node. */
        val lastHeard: Long,
        /** SNR in dB; the wire carries quarter-dB steps. */
        val snr: Double,
    )

    data class Table(
        /** What the node says it knows in total — may exceed [entries]. */
        val total: Int,
        val entries: List<Neighbour>,
    ) {
        /** True when the reply is a page of a longer list. */
        val isPartial: Boolean get() = total > entries.size
    }

    /**
     * Parse the body of a neighbours response. Returns null when the
     * bytes aren't a neighbour table — short, truncated, or claiming a
     * count it doesn't carry.
     */
    fun parse(body: ByteArray): Table? {
        if (body.size < 4) return null
        val r = BufferReader(body)
        return try {
            val total = r.readUInt16LE()
            val count = r.readUInt16LE()
            if (count > MAX_ENTRIES) return null
            // A count the payload can't back is a malformed reply, not a
            // reason to read past the end.
            if (r.remaining < count * ENTRY_BYTES) return null
            val entries = ArrayList<Neighbour>(count)
            repeat(count) {
                val prefix = r.readBytes(KEY_PREFIX_BYTES).toHex()
                val lastHeard = r.readUInt32LE()
                // Quarter-dB, signed: -128..127 → -32..31.75 dB.
                val snr = r.readInt8() / 4.0
                entries += Neighbour(prefix, lastHeard, snr)
            }
            Table(total.coerceAtLeast(entries.size), entries)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Contacts whose key starts with a neighbour's prefix. Returns every
     * match: 32 bits is not an identity, and silently picking the first
     * would put a name on the wrong node.
     */
    fun <T> resolve(
        neighbour: Neighbour,
        contacts: Iterable<T>,
        keyOf: (T) -> String,
    ): List<T> = contacts.filter {
        keyOf(it).startsWith(neighbour.keyPrefixHex, ignoreCase = true)
    }

    /** "aabbccdd Blue Ridge", "aabbccdd (2 matches)", or the bare prefix. */
    fun label(neighbour: Neighbour, names: List<String>): String = when {
        names.size == 1 -> "${neighbour.keyPrefixHex} ${names[0]}"
        names.size > 1 -> "${neighbour.keyPrefixHex} (${names.size} matches)"
        else -> neighbour.keyPrefixHex
    }

    /** Binary request payload for REQ_TYPE_GET_NEIGHBORS. */
    fun requestPayload(): ByteArray = byteArrayOf(Codes.REQ_TYPE_GET_NEIGHBORS.toByte())
}
