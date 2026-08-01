package io.github.thatsfguy.meshcore.protocol

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
