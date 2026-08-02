package io.github.thatsfguy.meshcore.protocol

/**
 * Region (flood-scope) naming, discovery parsing, and the repeater-side
 * `region …` CLI surface. PARITY.md §8.
 *
 * A region is a **routing tag**, not a security boundary: the radio
 * turns a name into `SHA256("#name")[0..15]` (see
 * [ChannelCrypto.floodScopeHash]) and only floods packets whose scope
 * tag matches. Anyone can name any region, so a region says where
 * traffic is *meant* to go, never who may read it.
 *
 * Two places names arrive from outside this phone, and both are
 * attacker-controlled:
 *  - a repeater's answer to an anonymous regions request ([parseDiscoveryResponse]);
 *  - a repeater's `region …` CLI reply ([parseRegionListing]).
 * Everything from those paths goes through [canonical] before it is
 * stored, displayed, hashed, or pasted back into a CLI command.
 */
object Regions {

    /** Longest region name the reference client will accept or emit. */
    const val MAX_NAME_LENGTH = 30

    /**
     * Cap on names taken from one mesh reply. A repeater can answer with
     * an arbitrarily long list; without a cap one hostile node could
     * flood the picker (and local storage) with junk.
     */
    const val MAX_DISCOVERED = 64

    /** The wildcard selector: the global/legacy scope. */
    const val GLOBAL_SELECTOR = "*"

    /** `region default` sentinel that clears the default scope. */
    const val NULL_SELECTOR = "<null>"

    /**
     * Canonical region names are lowercase `[a-z0-9-]`, 1–30 chars. The
     * ecosystem writes them that way (the reference client's text field
     * literally cannot type anything else), and the flood-scope hash is
     * over the exact bytes — so a name that differs only in case is a
     * *different* region on the air.
     */
    private val VALID = Regex("^[a-z0-9-]{1,30}$")

    /**
     * Normalise [raw] to a canonical region name, or null if it can't be
     * one. Strips a leading `#` (the scope hash adds it back) and
     * lowercases, so "#BayArea" and "bayarea" reach the same scope
     * rather than silently splitting a mesh in two.
     */
    fun canonical(raw: String?): String? {
        val name = raw?.trim()?.removePrefix("#")?.trim()?.lowercase() ?: return null
        return name.takeIf { VALID.matches(it) }
    }

    fun isValid(raw: String?): Boolean = canonical(raw) != null

    /**
     * A CLI selector: a region name or [GLOBAL_SELECTOR]. Returned
     * verbatim for `*`; canonicalised otherwise. Null means "don't send
     * this" — never fall back to `*`, which is the widest scope there is.
     */
    fun canonicalSelector(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed == GLOBAL_SELECTOR) return GLOBAL_SELECTOR
        return canonical(trimmed)
    }

    // ------------------------------------------------------------------
    // Discovery (CMD_SEND_ANON_REQ type 0x01 → PUSH_CODE_BINARY_RESPONSE)
    // ------------------------------------------------------------------

    /**
     * Bytes between the binary-response tag and the region names. The
     * reference client skips four; their meaning isn't documented in
     * MESHCORE_PROTOCOL.md and no live capture was available, so this is
     * pinned to the reference's behaviour. A wrong guess costs at most
     * the first name in the list — every name is re-validated below, so
     * a mangled one is dropped rather than stored.
     */
    const val DISCOVERY_BODY_HEADER = 4

    /** NUL pads the name list; it is not whitespace, so trimming won't remove it. */
    private const val NUL = '\u0000'

    /**
     * A node with no named regions answers with the global-scope
     * wildcard alone. Confirmed on hardware (2026-08-01): a repeater
     * replied with a body of `2a 00 00 …` — a single '*'.
     *
     * That is an ANSWER, not silence, and the two must not be reported
     * the same way: "nothing was in range" and "the node uses the
     * global scope" lead somewhere different.
     */
    fun isGlobalScopeOnly(body: ByteArray): Boolean {
        if (body.size <= DISCOVERY_BODY_HEADER) return false
        val text = body.copyOfRange(DISCOVERY_BODY_HEADER, body.size)
            .decodeUtf8Lenient()
            .replace(NUL.toString(), "")
            .trim()
        return text == GLOBAL_SELECTOR
    }

    /**
     * Parse the body of a regions reply (everything after the binary
     * response's `[reserved][tag u32]` header): a header, then a
     * comma-separated, NUL-padded UTF-8 name list.
     *
     * Hostile input is the norm here: the result is de-duplicated,
     * sorted, capped at [MAX_DISCOVERED], and contains only canonical
     * names. Anything else — non-UTF-8, embedded control characters,
     * over-long names, empty fields — is dropped silently.
     */
    fun parseDiscoveryResponse(body: ByteArray): List<String> {
        if (body.size <= DISCOVERY_BODY_HEADER) return emptyList()
        val text = body.copyOfRange(DISCOVERY_BODY_HEADER, body.size)
            .decodeUtf8Lenient()
            .replace(NUL.toString(), "")
        return text.split(',')
            .mapNotNull { canonical(it) }
            .distinct()
            .sorted()
            .take(MAX_DISCOVERED)
    }

    // ------------------------------------------------------------------
    // Repeater CLI: `region …`
    // ------------------------------------------------------------------

    /**
     * One line of a repeater's region listing: `-> name (parent) 'F'`.
     * [floodAllowed] is the `F` permission — whether the repeater will
     * flood traffic tagged with this region.
     */
    data class RegionEntry(
        val name: String,
        /** Parent region, [GLOBAL_SELECTOR] for the global scope, or null when absent. */
        val parent: String?,
        val floodAllowed: Boolean,
    )

    /**
     * Anchored at both ends on purpose. An unanchored pattern happily
     * reads `-> bay area (*) 'F'` as a region called "bay" — inventing a
     * name that was never on the wire. A line we don't fully recognise
     * is not a region.
     */
    private val LISTING_LINE = Regex(
        """^->\s*([a-z0-9-]{1,30})(?:\s+\(\s*([a-z0-9-]{1,30}|\*)\s*\))?(?:\s+'([A-Za-z]*)')?$""",
    )

    /**
     * Parse a `region get`/`region` reply into entries. Only lines in the
     * documented `-> name (parent) 'F'` shape are recognised; callers
     * must show an unrecognised reply verbatim rather than rendering it
     * as "no regions" (firmware without region support answers
     * `??: region`, exactly like the ACL viewer's case).
     */
    fun parseRegionListing(reply: String?): List<RegionEntry> {
        if (reply.isNullOrBlank()) return emptyList()
        return reply.lineSequence()
            .mapNotNull { line -> LISTING_LINE.matchEntire(line.trim()) }
            .mapNotNull { m ->
                val name = canonical(m.groupValues[1]) ?: return@mapNotNull null
                val parentRaw = m.groupValues[2]
                RegionEntry(
                    name = name,
                    parent = if (parentRaw == GLOBAL_SELECTOR) GLOBAL_SELECTOR else canonical(parentRaw),
                    floodAllowed = m.groupValues[3].contains('F'),
                )
            }
            .distinctBy { it.name }
            .take(MAX_DISCOVERED)
            .toList()
    }

    /**
     * Values carried by a CLI reply line. The firmware answers a read
     * with `> value` (CommonCLI's `sprintf(reply, "> %s", …)`) and a
     * region listing with `-> value`; a line in neither shape is not an
     * answer.
     *
     * The strictness is deliberate. A tokenise-everything parser reads
     * the error reply `??: region list allowed` as three regions named
     * "region", "list" and "allowed" — every one of them a valid name.
     * Guessing is worse than reporting the reply as unrecognised.
     */
    private fun replyValues(reply: String): Sequence<String> =
        reply.lineSequence().mapNotNull { line ->
            val t = line.trim()
            when {
                t.startsWith("->") -> t.removePrefix("->")
                t.startsWith(">") -> t.removePrefix(">")
                else -> null
            }
        }

    /**
     * Region names from a listing reply (`region list allowed` and
     * friends, whose exact format isn't pinned by any capture). Only
     * `>`/`->` reply lines are read; a reply we don't recognise yields
     * nothing, and callers must show it verbatim rather than as "none".
     */
    fun parseRegionNames(reply: String?): List<String> {
        if (reply.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        for (value in replyValues(reply)) {
            for (token in value.split(',')) {
                canonical(token.trim().trim('\'', '"'))?.let { out += it }
            }
        }
        return out.take(MAX_DISCOVERED)
    }

    /**
     * `region default` replies with the current default scope. Returns
     * the canonical name or [GLOBAL_SELECTOR]; null when the reply isn't
     * one we recognise — and null must be shown as "unknown", never as
     * "cleared", since the two lead to opposite decisions.
     */
    fun parseDefaultScope(reply: String?): String? {
        if (reply.isNullOrBlank()) return null
        for (value in replyValues(reply)) {
            canonicalSelector(value.trim().trim('\'', '"'))?.let { return it }
        }
        return null
    }

    // --- Command builders -------------------------------------------------
    //
    // Every builder validates its arguments. A region name is pasted
    // straight into a CLI line, and `region load` puts the repeater into
    // a multi-line mode where each following line is a region name — so
    // a name carrying a newline or a space is a command-injection
    // vector, not a cosmetic problem. Invalid input throws; the UI must
    // check with [isValid] before offering the action.

    private fun requireSelector(selector: String): String =
        canonicalSelector(selector)
            ?: throw IllegalArgumentException("not a region selector: $selector")

    private fun requireName(name: String): String =
        canonical(name) ?: throw IllegalArgumentException("not a region name: $name")

    /** `region get {* | name-prefix}` — search for a region definition. */
    fun get(selector: String): String = "region get ${requireSelector(selector)}"

    /** `region put {name} {* | parent-name-prefix}` — add/update a region. */
    fun put(name: String, parent: String = GLOBAL_SELECTOR): String =
        "region put ${requireName(name)} ${requireSelector(parent)}"

    /** `region remove {name}` — exact match, and only when it has no children. */
    fun remove(name: String): String = "region remove ${requireName(name)}"

    /** `region allowf {* | name-prefix}` — grant the flood permission. */
    fun allowFlood(selector: String): String = "region allowf ${requireSelector(selector)}"

    /** `region denyf {* | name-prefix}` — revoke the flood permission. */
    fun denyFlood(selector: String): String = "region denyf ${requireSelector(selector)}"

    /** `region home` — read the home region. */
    fun home(): String = "region home"

    /** `region home {* | name-prefix}` — set the home region. */
    fun setHome(selector: String): String = "region home ${requireSelector(selector)}"

    /** `region default` — read the default region scope. */
    fun default(): String = "region default"

    /**
     * `region default {* | name-prefix | <null>}` — set the default
     * scope; [selector] null clears it via the `<null>` sentinel.
     */
    fun setDefault(selector: String?): String =
        "region default ${if (selector == null) NULL_SELECTOR else requireSelector(selector)}"

    /** `region list allowed` — regions that permit flood traffic. */
    fun listAllowed(): String = "region list allowed"

    /** `region list denied` — regions that refuse flood traffic. */
    fun listDenied(): String = "region list denied"

    /** `region save` — persist the region map to the repeater's storage. */
    fun save(): String = "region save"
}

/**
 * Decode UTF-8, substituting the replacement character for malformed
 * bytes instead of throwing. Region names arrive off the mesh, so a
 * truncated multi-byte sequence must not take out the parse — the
 * replacement character simply fails validation.
 */
private fun ByteArray.decodeUtf8Lenient(): String =
    decodeToString(throwOnInvalidSequence = false)
