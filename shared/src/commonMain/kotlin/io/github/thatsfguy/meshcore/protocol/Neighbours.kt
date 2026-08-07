package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.toHex

/**
 * A repeater's one-hop neighbour table (PARITY.md §6,
 * `RepeaterNeighboursMapScreen`), read with
 * `CMD_SEND_BINARY_REQ` / `REQ_TYPE_GET_NEIGHBOURS`.
 *
 * ## Wire format
 *
 * The request is **11 bytes and every one of them is read**
 * (`examples/simple_repeater/MyMesh.cpp:279`, firmware v1.16.0):
 * ```
 *   [0]     0x06                   REQ_TYPE_GET_NEIGHBOURS
 *   [1]     request_version = 0    only 0 is handled; anything else is ignored
 *   [2]     count      u8          how many entries to return
 *   [3..4]  offset     u16 LE      index into the sorted list
 *   [5]     order_by   u8          see [Order]
 *   [6]     prefix_len u8          bytes of pub key per entry, clamped to 32
 *   [7..10] nonce                  random, for packet-hash uniqueness
 * ```
 * Reply body after the binary-response header:
 * ```
 *   u16 total          neighbours the node knows
 *   u16 count          entries in THIS reply
 *   count × { [prefix_len] key_prefix | u32 heard_seconds_ago | i8 snr_quarters }
 * ```
 *
 * ### Both halves or neither
 *
 * This shipped with a one-byte request — just the type — because the
 * parser was transcribed from a client and the builder was never
 * checked against the firmware's *reader*. The node then took `count`
 * from whatever followed our payload, read 0, and returned a table
 * header with no rows: "knows 2, returned none". It looked like paging
 * and no amount of retrying would ever have fixed it.
 *
 * So the width is not a constant here and it is not carried around
 * derived: a [Request] holds the prefix length that was *sent*, and
 * [parse] is handed that same [Request] rather than re-deciding. Same
 * discipline as `HopSelection` and for the same reason.
 *
 * ## What a neighbour entry is and isn't
 *
 * **The table is other REPEATERS, heard at zero hops.** `onAdvertRecv`
 * calls `putNeighbour` only when `getPathHashCount() == 0`, the packet
 * is not a Share, and the advert type is `ADV_TYPE_REPEATER`
 * (`MyMesh.cpp:641`). Companions, room servers, sensors and trackers
 * are never recorded, and a relayed advert never counts. So a healthy
 * repeater commonly reports two or three neighbours and that is not a
 * cap — `MAX_NEIGHBOURS` is 50 on every shipped variant. The UI has to
 * say this, because "Neighbours" reads as "everything it hears".
 *
 * The identifier is a **key prefix**, not a key — [DEFAULT_PREFIX_BYTES]
 * bytes of one. So an entry names a node only as far as a prefix goes
 * (PARITY §12), and this parser reports every contact that matches
 * rather than choosing one.
 *
 * It is also hearsay: the list is what the *repeater* says it hears,
 * relayed to us by that repeater. It is useful for understanding
 * coverage and useless as evidence about anybody.
 */
object Neighbours {

    /** The only `request_version` the firmware implements. */
    const val REQUEST_VERSION = 0

    /** Fixed request size — the firmware reads all of it. */
    const val REQUEST_BYTES = 11

    /** Random bytes at `payload[7..10]`, for packet-hash uniqueness. */
    const val NONCE_BYTES = 4

    /**
     * Bytes of public key to ask for per entry.
     *
     * 6, not 4. `PUB_KEY_SIZE` would be exact but costs 32 bytes a row
     * against a small buffer; 4 bytes is 32 bits, which §12 already
     * flags as cheap to collide on purpose. 6 is what the firmware's own
     * ACL rows use, and 48 bits puts a deliberate collision out of
     * casual reach. The cost is 11 rows a page instead of 14.
     */
    const val DEFAULT_PREFIX_BYTES = 6

    /** `PUB_KEY_SIZE`; the firmware clamps anything larger to this. */
    const val MAX_PREFIX_BYTES = 32

    /** The firmware's `results_buffer[130]` — the real cap on a page. */
    const val RESULTS_BUFFER_BYTES = 130

    /** `count` is a u8 on the wire. */
    const val MAX_COUNT = 255

    /** Refuse absurd counts rather than trusting a u16 off the mesh. */
    const val MAX_ENTRIES = 512

    /** `order_by` values the firmware sorts on. */
    object Order {
        const val NEWEST_FIRST = 0
        const val OLDEST_FIRST = 1
        const val STRONGEST_FIRST = 2
        const val WEAKEST_FIRST = 3
    }

    /** `[prefix] | u32 heard_seconds_ago | i8 snr` for a given width. */
    fun entryBytes(prefixBytes: Int): Int = prefixBytes + 4 + 1

    /** How many entries the firmware's results buffer can hold. */
    fun maxEntriesPerReply(prefixBytes: Int): Int =
        RESULTS_BUFFER_BYTES / entryBytes(prefixBytes)

    data class Neighbour(
        /** Key prefix, hex. NOT an identity — see the class docs. */
        val keyPrefixHex: String,
        /**
         * How long ago the repeater heard this node, in seconds.
         *
         * The firmware sends `now - heard_timestamp`, an elapsed time —
         * not an epoch timestamp. It is measured against the
         * *repeater's* clock, so it is only as good as that clock.
         */
        val heardSecondsAgo: Long,
        /** SNR in dB; the wire carries quarter-dB steps. */
        val snr: Double,
    )

    /**
     * The parameters of one page request. Kept as a value so the width
     * used to parse a reply is literally the width that was sent.
     */
    data class Request(
        val offset: Int = 0,
        val orderBy: Int = Order.NEWEST_FIRST,
        val prefixBytes: Int = DEFAULT_PREFIX_BYTES,
        /** Null asks for as many as a page can hold. */
        val count: Int? = null,
    ) {
        /** What the firmware will actually use, after its clamp. */
        val effectivePrefixBytes: Int = prefixBytes.coerceIn(1, MAX_PREFIX_BYTES)

        /** Never ask for more than a page can carry — it just truncates. */
        val effectiveCount: Int =
            (count ?: maxEntriesPerReply(effectivePrefixBytes))
                .coerceIn(0, minOf(MAX_COUNT, maxEntriesPerReply(effectivePrefixBytes)))

        /**
         * The 11 bytes to send. [nonce] must be [NONCE_BYTES] of
         * randomness — two identical requests otherwise hash to the same
         * packet and the mesh drops the second as a duplicate.
         */
        fun payload(nonce: ByteArray): ByteArray {
            require(nonce.size == NONCE_BYTES) {
                "nonce must be $NONCE_BYTES bytes, was ${nonce.size}"
            }
            require(offset in 0..0xFFFF) { "offset out of u16 range: $offset" }
            val w = BufferWriter()
            w.writeByte(Codes.REQ_TYPE_GET_NEIGHBORS)
            w.writeByte(REQUEST_VERSION)
            w.writeByte(effectiveCount)
            w.writeUInt16LE(offset)
            w.writeByte(orderBy)
            w.writeByte(effectivePrefixBytes)
            w.writeBytes(nonce)
            return w.toBytes()
        }
    }

    data class Table(
        /** What the node says it knows in total — may exceed [entries]. */
        val total: Int,
        val entries: List<Neighbour>,
        /** The offset this page was requested at. */
        val offset: Int = 0,
    ) {
        /** Index to ask for next, if there is more. */
        val nextOffset: Int get() = offset + entries.size

        /** True when the node holds rows this page didn't carry. */
        val isPartial: Boolean get() = nextOffset < total

        /**
         * True for the shape that means "you asked wrong", not "there is
         * more": a node claiming rows while returning an empty first
         * page. A correct request cannot produce this.
         */
        val isEmptyButNotEmpty: Boolean
            get() = entries.isEmpty() && offset == 0 && total > 0
    }

    /**
     * Parse the body of a neighbours response against the [request] that
     * asked for it. Returns null when the bytes aren't a neighbour table
     * — short, truncated, or claiming a count it doesn't carry.
     */
    fun parse(body: ByteArray, request: Request = Request()): Table? {
        if (body.size < 4) return null
        val entryBytes = entryBytes(request.effectivePrefixBytes)
        val r = BufferReader(body)
        return try {
            val total = r.readUInt16LE()
            val count = r.readUInt16LE()
            if (count > MAX_ENTRIES) return null
            // A count the payload can't back is a malformed reply, not a
            // reason to read past the end.
            if (r.remaining < count * entryBytes) return null
            val entries = ArrayList<Neighbour>(count)
            repeat(count) {
                val prefix = r.readBytes(request.effectivePrefixBytes).toHex()
                val heardSecondsAgo = r.readUInt32LE()
                // Quarter-dB, signed: -128..127 → -32..31.75 dB.
                val snr = r.readInt8() / 4.0
                entries += Neighbour(prefix, heardSecondsAgo, snr)
            }
            Table(
                total = total.coerceAtLeast(request.offset + entries.size),
                entries = entries,
                offset = request.offset,
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Contacts whose key starts with a neighbour's prefix. Returns every
     * match: a prefix is not an identity, and silently picking the first
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

    /** "12s ago", "5m ago", "3h ago", "2d ago" — coarse on purpose. */
    fun heardAgoLabel(secondsAgo: Long): String = when {
        secondsAgo < 0 -> "clock skew"
        secondsAgo < 60 -> "${secondsAgo}s ago"
        secondsAgo < 3_600 -> "${secondsAgo / 60}m ago"
        secondsAgo < 86_400 -> "${secondsAgo / 3_600}h ago"
        else -> "${secondsAgo / 86_400}d ago"
    }
}
