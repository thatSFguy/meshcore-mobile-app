package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.toHex

/**
 * The reply to `get acl` on a repeater or room server.
 *
 * The firmware's exact wording varies by version, so this parses
 * leniently and — importantly — **keeps the raw text**. An access list
 * is a security-relevant thing to render: showing a tidy table that
 * quietly dropped a line the parser didn't recognise would be worse
 * than showing the untidy truth, because a missing entry reads as
 * "nobody has that access".
 *
 * Recognised shapes, all tolerated in any order:
 *
 *     <pubkey-prefix>: admin
 *     <pubkey-prefix> guest
 *     <pubkey-prefix>,1
 */
object AccessList {

    // ------------------------------------------------------------------
    // Binary form — the one that actually works over the air
    // ------------------------------------------------------------------

    /**
     * `CMD_SEND_BINARY_REQ` / `REQ_TYPE_GET_ACCESS_LIST`.
     *
     * The text `get acl` cannot answer a remote client, and this app
     * spent a release asking it to. In the firmware that command is
     * guarded by `sender_timestamp == 0` — which is only true for the
     * SERIAL console (`main.cpp` calls `handleCommand(0, …)`) — and it
     * writes its output with `Serial.println`, setting `reply[0] = 0`.
     * So over the air it matches nothing, answers nothing, and the node
     * says `??: acl`, which reads like a firmware that is too old rather
     * than a request that was never answerable.
     *
     * The binary request is the supported route
     * (`MyMesh.cpp:265`, admin only):
     * ```
     *   request : [0x05][res1=0][res2=0]
     *   reply   : count × { [6] key_prefix | [1] permissions }
     * ```
     * Entries whose permissions are 0 are deleted rows and the firmware
     * skips them, so anything that arrives is live.
     */
    const val REQUEST_RESERVED_BYTES = 2

    /** `[6] prefix | [1] permissions`. */
    const val BIN_ENTRY_BYTES = 7

    /** Bytes of public key each entry carries. NOT an identity. */
    const val KEY_PREFIX_BYTES = 6

    /** Refuse absurd lengths rather than trusting bytes off the mesh. */
    const val MAX_ENTRIES = 256

    /** `PERM_ACL_ROLE_MASK` — the role lives in the low two bits. */
    const val ROLE_MASK = 0x03

    const val ROLE_GUEST = 0
    const val ROLE_READ_ONLY = 1
    const val ROLE_READ_WRITE = 2
    const val ROLE_ADMIN = 3

    /** Request payload for REQ_TYPE_GET_ACCESS_LIST. */
    fun requestPayload(): ByteArray =
        ByteArray(1 + REQUEST_RESERVED_BYTES).also {
            it[0] = Codes.REQ_TYPE_GET_ACCESS_LIST.toByte()
            // res1/res2 must be zero; the firmware refuses anything else.
        }

    /** One entry from the binary reply. */
    data class BinEntry(
        /** 6-byte key prefix, hex. A prefix names a node only so far. */
        val keyPrefixHex: String,
        /** The permissions byte exactly as sent. */
        val permissions: Int,
    ) {
        val role: Int get() = permissions and ROLE_MASK

        /** "Admin", "Read-write", "Read-only", "Guest". */
        val roleLabel: String get() = when (role) {
            ROLE_ADMIN -> "Admin"
            ROLE_READ_WRITE -> "Read-write"
            ROLE_READ_ONLY -> "Read-only"
            else -> "Guest"
        }

        /**
         * True when the byte carries bits outside the role mask. Shown
         * rather than hidden: an unknown flag on an access-list entry is
         * exactly the thing not to render as though it were understood.
         */
        val hasUnknownFlags: Boolean get() = (permissions and ROLE_MASK.inv() and 0xFF) != 0
    }

    /**
     * Parse the binary reply body.
     *
     * ## The body is padded, and the padding looks like entries
     *
     * The reply is encrypted, so it arrives rounded up to a 16-byte
     * cipher block. Entries are 7 bytes, which does not divide 16, so
     * the tail is whatever zeros the padding left. Against a live
     * repeater with three admins that was 4 + 21 = 25 bytes padded to
     * 32, giving 28 bytes of body — exactly four entries, the last of
     * which was a phantom `000000000000 Guest`.
     *
     * Two rules sort real from padding, and both come from the
     * firmware rather than from the shape of the bytes:
     *
     *  - `permissions == 0` is a DELETED row, which `MyMesh.cpp` skips
     *    when building the reply. So a live entry never has it, and
     *    anything that does is padding.
     *  - a trailing partial entry is padding too, but ONLY if it is all
     *    zeros. Anything else means the reply was cut short, and a
     *    truncated access list must not render as a shorter one — a
     *    missing row reads as "nobody has that access".
     */
    fun parseBinary(body: ByteArray): List<BinEntry>? {
        val count = body.size / BIN_ENTRY_BYTES
        if (count > MAX_ENTRIES) return null
        // A partial trailing entry is only ever padding.
        val remainder = body.size % BIN_ENTRY_BYTES
        if (remainder != 0) {
            val tail = body.copyOfRange(body.size - remainder, body.size)
            if (tail.any { it.toInt() != 0 }) return null
        }
        val out = ArrayList<BinEntry>(count)
        var off = 0
        repeat(count) {
            val prefix = body.copyOfRange(off, off + KEY_PREFIX_BYTES)
            off += KEY_PREFIX_BYTES
            val perms = body[off++].toInt() and 0xFF
            if (perms == 0) return@repeat   // deleted row, or padding
            out += BinEntry(
                keyPrefixHex = prefix.toHex(),
                permissions = perms,
            )
        }
        return out
    }

    // ------------------------------------------------------------------
    // Text form — serial console only, kept for the console screen
    // ------------------------------------------------------------------

    /** One access-list entry as the node reported it. */
    data class Entry(
        /** Hex key prefix the node named. Never a full identity — a
         *  prefix is a prefix, and the UI must not imply otherwise. */
        val keyPrefixHex: String,
        /** Permission word exactly as the node said it, lower-cased. */
        val permission: String,
        /** The line this came from, for display when in doubt. */
        val raw: String,
    )

    data class Parsed(
        val entries: List<Entry>,
        /** Lines that didn't match any known shape — shown verbatim. */
        val unparsed: List<String>,
    ) {
        val isEmpty: Boolean get() = entries.isEmpty() && unparsed.isEmpty()
    }

    private val ENTRY = Regex(
        """^\s*([0-9a-fA-F]{2,64})\s*[:,\s]\s*([A-Za-z0-9_-]+)\s*$""",
    )

    fun parse(reply: String?): Parsed {
        if (reply.isNullOrBlank()) return Parsed(emptyList(), emptyList())
        val entries = mutableListOf<Entry>()
        val unparsed = mutableListOf<String>()
        for (line in reply.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // Headers/footers the firmware prints around the list.
            if (trimmed.endsWith(":") && !ENTRY.matches(trimmed)) {
                unparsed.add(trimmed)
                continue
            }
            val m = ENTRY.matchEntire(trimmed)
            if (m != null) {
                entries.add(
                    Entry(
                        keyPrefixHex = m.groupValues[1].lowercase(),
                        permission = m.groupValues[2].lowercase(),
                        raw = trimmed,
                    ),
                )
            } else {
                unparsed.add(trimmed)
            }
        }
        return Parsed(entries, unparsed)
    }
}
