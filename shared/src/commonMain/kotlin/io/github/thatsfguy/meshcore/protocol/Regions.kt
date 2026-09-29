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
 *  - a repeater's `region …` CLI reply ([parseRegionTree]).
 * Everything from those paths goes through [canonical] before it is
 * stored, displayed, hashed, or pasted back into a CLI command.
 */
object Regions {

    /**
     * Longest region name the firmware will accept: **29 bytes of
     * UTF-8**, per MeshCore's own region-filtering documentation
     * ("maximum 29 _bytes_ (UTF-8)"; "Only _lower case_ alpha-numeric
     * chars, and - (hyphen)").
     *
     * This was 30. One character over is not a cosmetic difference: a
     * 30-character name canonicalises here, gets hashed into a flood
     * scope, and gets pasted into a `region put` the repeater refuses —
     * so the scope looks set on the phone and routes nothing on the air.
     * Because the charset is ASCII-only, bytes and characters are the
     * same count here.
     */
    const val MAX_NAME_LENGTH = 29

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
     * Canonical region names are lowercase `[a-z0-9-]`, 1–[MAX_NAME_LENGTH]
     * chars. The ecosystem writes them that way, and the flood-scope
     * hash is over the exact bytes — so a name that differs only in
     * case is a *different* region on the air.
     */
    private val VALID = Regex("^[a-z0-9-]{1,$MAX_NAME_LENGTH}$")

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
     * Bytes between the binary-response tag and the region names: the
     * repeater's clock. `handleAnonRegionsReq` writes `reply_data[4..7] =
     * now` "for easy clock sync, and packet hash uniqueness", then the
     * names from `reply_data[8]` (simple_repeater/MyMesh.cpp:157-160).
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

    /**
     * What one repeater told an anonymous regions request.
     *
     * NOT the repeater's region setup. The firmware answers with
     * `region_map.exportNamesTo(…, REGION_DENY_FLOOD)`
     * (simple_repeater/MyMesh.cpp:160; RegionMap.cpp:316): only the regions
     * it will FLOOD, plus `*` when untagged traffic floods too. A region
     * set to deny flooding is absent, and so are the tree, the default
     * scope and the hop limit — those are CLI reads, admin only.
     */
    data class FloodList(
        /** Regions this repeater floods, canonical, sorted. */
        val regions: List<String>,
        /** The `*` entry: traffic with no region tag is flooded. */
        val untaggedFloods: Boolean,
    )

    /**
     * Parse a regions reply body for one repeater. Unlike
     * [parseDiscoveryResponse], which merges names across repeaters and
     * so has no use for it, this keeps the `*` as [FloodList.untaggedFloods].
     * Null for a body too short to carry the clock header.
     */
    fun parseFloodList(body: ByteArray): FloodList? {
        if (body.size < DISCOVERY_BODY_HEADER) return null
        val fields = body.copyOfRange(DISCOVERY_BODY_HEADER, body.size)
            .decodeUtf8Lenient()
            .replace(NUL.toString(), "")
            .split(',')
            .map { it.trim() }
        return FloodList(
            regions = fields.mapNotNull { canonical(it) }.distinct().sorted().take(MAX_DISCOVERED),
            untaggedFloods = GLOBAL_SELECTOR in fields,
        )
    }

    // ------------------------------------------------------------------
    // Repeater CLI: `region …`
    // ------------------------------------------------------------------

    /**
     * One region in a repeater's region tree. [floodAllowed] is the `F`
     * permission — whether the repeater will flood traffic tagged with
     * this region.
     */
    data class RegionEntry(
        val name: String,
        /** Parent region, [GLOBAL_SELECTOR] for a top-level region. */
        val parent: String?,
        val floodAllowed: Boolean,
        /** 1 for a top-level region, 2 for its children, and so on. */
        val depth: Int = 1,
        /** Marked `^` — this repeater's home region. */
        val home: Boolean = false,
    ) {
        /**
         * Whether [name] can be pasted back into a command. A name the
         * firmware holds but [canonical] would change (upper case, say) is
         * shown, but acting on it would address a different region.
         */
        val actionable: Boolean get() = canonical(name) == name
    }

    /**
     * A repeater's answer to a bare `region`: the wildcard's own flags,
     * then every region beneath it.
     */
    data class RegionTree(
        /** Whether the repeater floods untagged traffic (`region denyf *` clears this). */
        val wildcardFloodAllowed: Boolean,
        val regions: List<RegionEntry>,
        /**
         * The reply filled the firmware's 160-byte buffer, so regions past
         * the end are missing and the last line may have been cut. The cut
         * line is dropped rather than shown under a wrong name.
         */
        val truncated: Boolean,
    )

    /**
     * The firmware's CLI reply buffer. `RegionMap::exportTo(reply, 160)`
     * stops writing there, one byte kept for the terminator.
     */
    private const val CLI_REPLY_MAX = 159

    private val WILDCARD_LINE = Regex("""^\*\^?( F)?$""")

    /** `<indent><name>[^][ F]` — `RegionMap::printChildRegions`. */
    private val TREE_LINE = Regex("""^( +)#?([^\s^]{1,64})(\^?)( F)?$""")

    /**
     * Parse the reply to a bare `region`: the firmware's region tree, as
     * `RegionMap::printChildRegions` (src/helpers/RegionMap.cpp) prints it
     * in every release from v1.10.0 to current main —
     *
     * ```
     * * F
     *  midwest F
     *   mi F
     *    mi-west F
     *     grr^ F
     * ```
     *
     * One leading space per level, the wildcard `*` at the root, ` F` when
     * flood is allowed, `^` on the home region. Returns null for anything
     * not in that shape — firmware without regions answers `??: region`
     * — and the caller shows the reply verbatim rather than as "no
     * regions", which is a different claim.
     *
     * This replaced a parser for `-> name (parent) 'F'`, a shape no
     * firmware version has ever printed; its tests pinned the guess.
     */
    fun parseRegionTree(reply: String?): RegionTree? {
        if (reply.isNullOrBlank()) return null
        val truncated = reply.length >= CLI_REPLY_MAX
        var lines = reply.split('\n').map { it.trimEnd('\r', ' ') }.filter { it.isNotEmpty() }
        if (truncated && lines.size > 1) lines = lines.dropLast(1)
        val root = WILDCARD_LINE.matchEntire(lines.firstOrNull() ?: return null) ?: return null

        val regions = mutableListOf<RegionEntry>()
        // path[d] is the region at depth d on the way to the current line.
        val path = mutableListOf(GLOBAL_SELECTOR)
        for (line in lines.drop(1)) {
            val m = TREE_LINE.matchEntire(line) ?: return null
            val depth = m.groupValues[1].length
            // A child can sit at most one level below the line before it.
            if (depth > path.size) return null
            while (path.size > depth) path.removeAt(path.size - 1)
            val name = m.groupValues[2]
            regions += RegionEntry(
                name = name,
                parent = path.last(),
                floodAllowed = m.groupValues[4].isNotEmpty(),
                depth = depth,
                home = m.groupValues[3].isNotEmpty(),
            )
            path += name
        }
        return RegionTree(
            wildcardFloodAllowed = root.groupValues[1].isNotEmpty(),
            regions = regions.take(MAX_DISCOVERED),
            truncated = truncated,
        )
    }

    private val DEFAULT_SCOPE_REPLY = Regex("""^default scope is (?:now )?#?(\S{1,64})$""")

    /**
     * `region default` replies ` default scope is <name>`, or `<null>`
     * when there is none (CommonCLI::handleRegionCmd; `region default
     * <name>` answers `... is now <name>`). Returns the region's name, or
     * [GLOBAL_SELECTOR] when the scope is `<null>`: the repeater's own
     * floods then go out untagged. Null when the reply isn't one we
     * recognise — and null must be shown as "unknown", never as
     * "cleared", since the two lead to opposite decisions.
     */
    fun parseDefaultScope(reply: String?): String? {
        if (reply.isNullOrBlank()) return null
        for (line in reply.lineSequence()) {
            val m = DEFAULT_SCOPE_REPLY.matchEntire(line.trim()) ?: continue
            val value = m.groupValues[1]
            return if (value == NULL_SELECTOR || value == GLOBAL_SELECTOR) GLOBAL_SELECTOR else value
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

    /** `region` — the whole region tree; see [parseRegionTree]. */
    fun tree(): String = "region"

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
