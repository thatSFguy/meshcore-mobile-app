package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Regions

/**
 * The rules behind a repeater's Regions screen, kept out of Compose so
 * they are pinned by tests: what a region offers when tapped, where a new
 * region can hang, and the one control for traffic that carries no
 * region at all.
 *
 * Every fact about the firmware here is from its source, not a client:
 * `CommonCLI::handleRegionCmd` and `handleSetCmd`/`handleGetCmd`
 * (src/helpers/CommonCLI.cpp) and `mesh::isFloodHopLimitExceeded`
 * (src/helpers/RoutingPolicy.h).
 */
object RegionAdmin {

    /** What tapping a region offers. */
    enum class Action { MakeDefault, SetHome, AddChild, AllowFlood, DenyFlood, Remove }

    /**
     * The actions for [entry]. A region whose name we would rewrite gets
     * none: its tag differs from the canonical one, so a command built
     * from it addresses a different region. Remove is withheld while it
     * has children — the firmware answers "Err - not empty".
     */
    fun actionsFor(
        entry: Regions.RegionEntry,
        tree: Regions.RegionTree,
        defaultScope: String?,
    ): List<Action> {
        if (!entry.actionable) return emptyList()
        val hasChildren = tree.regions.any { it.parent == entry.name }
        return buildList {
            if (defaultScope != entry.name) add(Action.MakeDefault)
            if (!entry.home) add(Action.SetHome)
            add(Action.AddChild)
            add(if (entry.floodAllowed) Action.DenyFlood else Action.AllowFlood)
            if (!hasChildren) add(Action.Remove)
        }
    }

    /** A place a new region can be put, with its depth for indenting. */
    data class ParentChoice(val name: String, val depth: Int)

    /** `*` (top level) first, then every region a command can name, in tree order. */
    fun parentChoices(tree: Regions.RegionTree?): List<ParentChoice> =
        listOf(ParentChoice(Regions.GLOBAL_SELECTOR, 0)) +
            tree?.regions.orEmpty().filter { it.actionable }.map { ParentChoice(it.name, it.depth) }

    /**
     * Whether [command] leaves an edit that only `region save` persists.
     *
     * `region default` calls saveRegions() itself ("persist in one atomic
     * step"), which also writes every edit pending before it; `set …`
     * calls savePrefs(). Everything else under `region` changes RAM only.
     */
    fun needsRegionSave(command: String): Boolean =
        command.startsWith("region ") &&
            !command.startsWith("region default") &&
            command != Regions.save()

    /** Whether [command] writes the whole region map, pending edits included. */
    fun savesRegions(command: String): Boolean =
        command == Regions.save() || command.startsWith("region default ")

    // ------------------------------------------------------------------
    // Untagged traffic
    // ------------------------------------------------------------------

    /**
     * What the repeater does with flood traffic that carries no region —
     * which is everything a neighbouring mesh that doesn't use regions
     * sends. Two firmware settings decide it: the wildcard's flood flag
     * (`region allowf|denyf *`) and `flood.max.unscoped`.
     */
    enum class Untagged { RelayAll, Nearby, Refuse }

    /** `flood.max.unscoped`'s default, and the most any flood limit takes. */
    const val MAX_HOPS = 64

    /** What the firmware docs suggest for keeping out a noisy neighbour. */
    const val SUGGESTED_HOPS = 3

    /** 0 drops every untagged flood; 64 drops none. */
    val NEARBY_HOPS: IntRange = 1 until MAX_HOPS

    /**
     * `flood.max.unscoped` first appears in repeater v1.16.0; v1.15.0 has
     * no such setting. It must be decided from the version, not from
     * whether `get` answers: older firmware matches the `flood.max`
     * prefix and returns the OVERALL flood limit, a plausible number that
     * means something else. Null when the version can't be read.
     */
    fun supportsHopLimit(verReply: String?): Boolean? {
        val m = VERSION.find(verReply ?: return null) ?: return null
        val major = m.groupValues[1].toIntOrNull() ?: return null
        val minor = m.groupValues[2].toIntOrNull() ?: return null
        return major > 1 || (major == 1 && minor >= 16)
    }

    private val VERSION = Regex("""v?(\d{1,3})\.(\d{1,3})(?:\.\d{1,3})?""")

    /** `get flood.max.unscoped` answers `> N`. Null for anything else. */
    fun parseHopLimit(reply: String?): Int? {
        val t = reply?.trim() ?: return null
        if (!t.startsWith(">")) return null
        return t.removePrefix(">").trim().toIntOrNull()?.takeIf { it in 0..255 }
    }

    /**
     * The current mode. [hopLimit] is null when the node can't have one
     * (or it couldn't be read), which leaves only relay-all or refuse.
     * A limit of 0 refuses as surely as `denyf *`: every packet has at
     * least 0 hops, and the firmware drops at `hops >= limit`.
     */
    fun untaggedMode(wildcardFloodAllowed: Boolean, hopLimit: Int?): Untagged = when {
        !wildcardFloodAllowed -> Untagged.Refuse
        hopLimit == null || hopLimit >= MAX_HOPS -> Untagged.RelayAll
        hopLimit <= 0 -> Untagged.Refuse
        else -> Untagged.Nearby
    }

    /**
     * The commands that take the node from its current state to [target].
     * Nothing is sent that is already true, so applying the current mode
     * sends nothing.
     *
     * Refuse uses `denyf *` and leaves the hop limit alone, so relaying
     * again later restores whatever limit was there. Relay-all and
     * Nearby both allow `*` — a hop limit behind a closed wildcard does
     * nothing — and set the limit when the node has one.
     */
    fun commandsFor(
        target: Untagged,
        hops: Int,
        wildcardFloodAllowed: Boolean,
        hopLimit: Int?,
        limitSupported: Boolean,
    ): List<String> = buildList {
        val allow = Regions.allowFlood(Regions.GLOBAL_SELECTOR)
        when (target) {
            Untagged.Refuse -> if (wildcardFloodAllowed) add(Regions.denyFlood(Regions.GLOBAL_SELECTOR))
            Untagged.RelayAll -> {
                if (!wildcardFloodAllowed) add(allow)
                if (limitSupported && hopLimit != MAX_HOPS) add(setHopLimit(MAX_HOPS))
            }
            Untagged.Nearby -> {
                require(limitSupported) { "this node has no untagged hop limit" }
                require(hops in NEARBY_HOPS) { "hops must be in $NEARBY_HOPS: $hops" }
                if (!wildcardFloodAllowed) add(allow)
                if (hopLimit != hops) add(setHopLimit(hops))
            }
        }
    }

    const val GET_HOP_LIMIT = "get flood.max.unscoped"

    fun setHopLimit(hops: Int): String {
        require(hops in 0..MAX_HOPS) { "flood.max.unscoped takes 0..$MAX_HOPS: $hops" }
        return "set flood.max.unscoped $hops"
    }

    /**
     * The hop limit in words. The firmware drops an untagged flood when
     * the repeaters it has already passed through number [hops] or more,
     * so a limit of 3 relays what came through at most 2.
     */
    fun describeNearby(hops: Int): String = when (hops) {
        1 -> "Relayed only when heard straight from the sender."
        else -> "Relayed only when it has come through ${hops - 1} " +
            "repeater${if (hops - 1 == 1) "" else "s"} or fewer."
    }
}
