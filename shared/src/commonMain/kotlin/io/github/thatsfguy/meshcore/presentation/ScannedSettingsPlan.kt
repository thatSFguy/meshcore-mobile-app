package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.CliReplies
import io.github.thatsfguy.meshcore.protocol.Regions
import io.github.thatsfguy.meshcore.protocol.ShareUri
import kotlin.math.roundToLong

/**
 * What applying a scanned settings code to a repeater would change, worked
 * out against what the repeater holds NOW, so that scanning the same code
 * twice sends nothing the second time.
 *
 * Re-sending is not harmless on the region side. `region def` sets
 * `flags = 0` — flood allowed — on every region it names, existing ones
 * included (CommonCLI.cpp, processRegionDefSegment), and
 * `region default <name>` does the same to the default
 * (handleRegionCmd). So a region its operator had since set to deny
 * flood would be quietly re-opened by a second scan. The plan sends only
 * what differs, and puts back any deny those commands would undo.
 */
object ScannedSettingsPlan {

    /**
     * True when the node already runs [config]'s radio values. False when
     * they differ or couldn't be read — "unknown" must never skip a write.
     *
     * The node stores frequency as a float32 and prints it in full
     * (910.525 comes back as 910.5250244), so both sides are compared in
     * whole kHz and Hz, the units the code carries.
     */
    fun radioMatches(
        config: ShareUri.Decoded.RadioConfig,
        nodeRadio: CliReplies.RadioCsv?,
        nodePathHashMode: Int?,
    ): Boolean {
        val r = nodeRadio ?: return false
        return (r.freqMhz * 1000).roundToLong() == config.frequencyKhz &&
            (r.bwKhz * 1000).roundToLong() == config.bandwidthHz &&
            r.sf == config.spreadingFactor &&
            r.cr == config.codingRate &&
            nodePathHashMode == config.pathHashMode
    }

    sealed interface RegionPlan {
        /** The node already has the tree, parents and default. Nothing to send. */
        data object AlreadyApplied : RegionPlan

        /**
         * Commands to send, in order, with what they do in words. [save]
         * is always last, so a refused step leaves nothing on flash.
         */
        data class Changes(
            val commands: List<String>,
            /** Regions the node doesn't have yet. */
            val added: List<String>,
            /** Regions the node has under a different parent: name to new parent. */
            val moved: List<Pair<String, String>>,
            /** The new default, when it changes; [previousDefault] is what it was. */
            val newDefault: String?,
            val previousDefault: String?,
            /** Regions that deny flood now and will still deny it afterwards. */
            val keptDenied: List<String>,
        ) : RegionPlan

        /** The node's regions couldn't be read, so nothing can be checked. */
        data object Unreadable : RegionPlan

        /**
         * The node holds more regions than one reply shows, and some of
         * this tree is past the cut — whether they exist, or deny flood,
         * can't be seen.
         */
        data object TooManyToRead : RegionPlan

        /** Too long for one `region def` line; add them under Regions. */
        data object TooLong : RegionPlan
    }

    /**
     * Plan writing [tree] (widest first) and [default] to a node whose
     * `region` reply parsed to [current] and whose `region default`
     * parsed to [currentDefault] ([Regions.GLOBAL_SELECTOR] for none,
     * null if unread). Null when the code carries no usable tree.
     */
    fun regions(
        tree: List<String>,
        default: String?,
        current: Regions.RegionTree?,
        currentDefault: String?,
    ): RegionPlan? {
        val names = ShareUri.validRegionTree(tree, default) ?: return null
        val def = "region def " + names.joinToString(" ")
        if (def.length > RegionAdmin.MAX_COMMAND_LENGTH) return RegionPlan.TooLong
        val now = current ?: return RegionPlan.Unreadable
        if (default != null && currentDefault == null) return RegionPlan.Unreadable

        val byName = now.regions.associateBy { it.name }
        if (now.truncated && names.any { it !in byName }) return RegionPlan.TooManyToRead

        fun parentOf(i: Int) = if (i == 0) GLOBAL else names[i - 1]
        val added = names.filter { it !in byName }
        val moved = names.withIndex()
            .filter { (i, n) -> byName[n] != null && byName[n]!!.parent != parentOf(i) }
            .map { (i, n) -> n to parentOf(i) }
        val sendDef = added.isNotEmpty() || moved.isNotEmpty()
        val sendDefault = default != null && currentDefault != default
        if (!sendDef && !sendDefault) return RegionPlan.AlreadyApplied

        // Each command re-opens flood on what it names; put back any deny.
        val reopened = (if (sendDef) names else emptyList()) + listOfNotNull(default.takeIf { sendDefault })
        val keptDenied = names.filter { byName[it]?.floodAllowed == false }
        val restore = keptDenied.filter { it in reopened }

        val commands = buildList {
            if (sendDef) add(def)
            if (sendDefault) default?.let { add(Regions.setDefault(it)) }
            restore.forEach { add(Regions.denyFlood(it)) }
            add(Regions.save())
        }
        return RegionPlan.Changes(
            commands = commands,
            added = added,
            moved = moved,
            newDefault = default.takeIf { sendDefault },
            previousDefault = currentDefault?.takeIf { sendDefault && it != GLOBAL },
            keptDenied = keptDenied,
        )
    }

    /** [changes] in words, one line each, for the confirmation dialog. */
    fun describe(changes: RegionPlan.Changes): List<String> = buildList {
        fun tags(names: List<String>) = names.joinToString(", ") { "#$it" }
        if (changes.added.isNotEmpty()) add("Adds ${tags(changes.added)}")
        changes.moved.forEach { (name, parent) ->
            add("Moves #$name " + if (parent == GLOBAL) "to the top level" else "under #$parent")
        }
        changes.newDefault?.let {
            add("Makes #$it its default region" + (changes.previousDefault?.let { p -> " (now #$p)" } ?: " (none set now)"))
        }
        if (changes.keptDenied.isNotEmpty()) {
            add("Keeps ${tags(changes.keptDenied)} refusing flood traffic, as now")
        }
    }

    private const val GLOBAL = Regions.GLOBAL_SELECTOR
}
