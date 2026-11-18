package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Regions

/**
 * A repeater's Regions screen, for iOS (Phase 5, 2026-11-18): the
 * wording and command-building of Android's RepeaterRegionsPanel, behind
 * functions that never throw. `Regions`' builders throw on an invalid
 * name (they are the injection guard), and a Kotlin exception crossing
 * into Swift is a crash, not an error — so every builder call is caught
 * here and an unusable step is simply not offered.
 */
object RegionPanel {

    /** One write: the command, a confirmation when it is hard to undo, and what to say after. */
    data class Step(val command: String, val confirmTitle: String?, val confirmText: String?, val done: String)

    fun label(action: RegionAdmin.Action): String = when (action) {
        RegionAdmin.Action.MakeDefault -> "Make default region"
        RegionAdmin.Action.SetHome -> "Make home region"
        RegionAdmin.Action.AddChild -> "Add a region under this"
        RegionAdmin.Action.AllowFlood -> "Allow flood"
        RegionAdmin.Action.DenyFlood -> "Deny flood"
        RegionAdmin.Action.Remove -> "Remove"
    }

    /** The step for [action] on region [name]; null for AddChild (its own dialog) or a name a command can't carry. */
    fun step(action: RegionAdmin.Action, name: String): Step? = runCatching {
        when (action) {
            RegionAdmin.Action.MakeDefault -> Step(Regions.setDefault(name), null, null, "#$name is the default region")
            RegionAdmin.Action.SetHome -> Step(Regions.setHome(name), null, null, "#$name is the home region")
            RegionAdmin.Action.AllowFlood -> Step(Regions.allowFlood(name), null, null, "Flood allowed for #$name")
            RegionAdmin.Action.DenyFlood -> Step(
                Regions.denyFlood(name),
                "Deny flood for $name?",
                "This repeater will stop relaying flood traffic tagged #$name. Nothing becomes unreadable — it just stops being carried by this node.",
                "Flood denied for #$name",
            )
            RegionAdmin.Action.Remove -> Step(
                Regions.remove(name),
                "Remove $name?",
                "Removes the region from this repeater, which then stops relaying traffic tagged with it.",
                "Removed #$name",
            )
            RegionAdmin.Action.AddChild -> null
        }
    }.getOrNull()

    /** Add [name] under [parent] (`*` = top level); null when the name is not a region name. */
    fun add(name: String, parent: String): Step? = runCatching {
        Step(Regions.put(name, parent), null, null, "Added #${Regions.canonical(name)}")
    }.getOrNull()

    fun clearDefault(): Step = Step(
        Regions.setDefault(null),
        "Clear the default region?",
        "This repeater's own adverts and replies go back to carrying no region.",
        "Default region cleared",
    )

    fun saveCommand(): String = Regions.save()
    fun treeCommand(): String = Regions.tree()
    fun defaultCommand(): String = Regions.default()

    /** "Top level" for the global selector, else the name. */
    fun parentLabel(name: String): String = if (name == Regions.GLOBAL_SELECTOR) "Top level" else "#$name"

    fun defaultLine(asked: Boolean, defaultReply: String?): String {
        if (!asked) return "Not read yet."
        val scope = Regions.parseDefaultScope(defaultReply)
        return when {
            scope == null -> "The node's answer wasn't recognised — use the console."
            scope == Regions.GLOBAL_SELECTOR -> "None — this repeater's own adverts and replies go out untagged."
            else -> "#$scope — tap a region below to change it."
        }
    }

    fun rowTags(entry: Regions.RegionEntry, defaultScope: String?): String = listOfNotNull(
        "default".takeIf { entry.name == defaultScope },
        "home".takeIf { entry.home },
        if (entry.floodAllowed) "flood allowed" else "flood denied",
    ).joinToString(" · ")

    /**
     * Null when [reply] means the write was taken; otherwise the sentence
     * saying it wasn't. One rule for every region write — silence, "Err"
     * and "??" all stop (Android's panel checked "Err" only).
     */
    fun refusal(command: String, reply: String?): String? = when {
        reply == null -> "No reply to `$command` — logged in and in range?"
        RegionAdmin.treeStepRefused(reply) ->
            "`$command`: ${reply.trim()}" + if (reply.trim().startsWith("??")) " (it needs firmware 1.16 or newer)" else ""
        else -> null
    }

    const val UNSAVED =
        "Unsaved: region edits live in RAM until \"Save to node\" runs. A reboot before then discards them."
    const val NOT_A_TREE =
        "The node's answer wasn't a region tree. Firmware without regions answers \"??: region\"."
    const val EMPTY = "No regions defined."
    const val TRUNCATED =
        "The list filled the node's reply, so regions past the end are missing. `region list allowed` in the console lists every name."
    const val NOT_ACTIONABLE = "Not lowercase letters, digits and dashes — edit it from the console."
    const val HINT = "Decides what this repeater floods onward — routing, not access control. A region the repeater " +
        "refuses to flood can still be heard by anyone in radio range."

    // --- Untagged traffic (flood with no region) ---

    /** For Swift, which must not spell Kotlin enum entries. */
    fun isNearby(mode: RegionAdmin.Untagged?): Boolean = mode == RegionAdmin.Untagged.Nearby
    fun isRemove(action: RegionAdmin.Action): Boolean = action == RegionAdmin.Action.Remove
    fun isAddChild(action: RegionAdmin.Action): Boolean = action == RegionAdmin.Action.AddChild

    fun untaggedLabel(mode: RegionAdmin.Untagged): String = when (mode) {
        RegionAdmin.Untagged.RelayAll -> "Relay all"
        RegionAdmin.Untagged.Nearby -> "Relay only nearby"
        RegionAdmin.Untagged.Refuse -> "Refuse"
    }

    /** The choices to offer: Nearby only where the firmware has the hop limit. */
    fun untaggedChoices(limitSupported: Boolean?): List<RegionAdmin.Untagged> =
        if (limitSupported == true) RegionAdmin.Untagged.entries.toList()
        else listOf(RegionAdmin.Untagged.RelayAll, RegionAdmin.Untagged.Refuse)

    /**
     * The commands to move to [target], as one step; null when nothing
     * needs sending or the input is out of range (the builder's throw is
     * caught here, never in Swift). Refusing is confirmed.
     */
    fun untaggedStep(
        target: RegionAdmin.Untagged,
        hops: Int,
        wildcardFloodAllowed: Boolean,
        hopLimit: Int?,
        limitSupported: Boolean,
    ): List<Step>? {
        val commands = runCatching {
            RegionAdmin.commandsFor(target, hops, wildcardFloodAllowed, hopLimit, limitSupported)
        }.getOrNull() ?: return null
        if (commands.isEmpty()) return null
        val refuse = target == RegionAdmin.Untagged.Refuse
        return commands.mapIndexed { i, c ->
            Step(
                c,
                if (refuse && i == 0) "Refuse untagged traffic?" else null,
                if (refuse && i == 0) {
                    "This repeater will stop relaying flood traffic that carries no region — including anyone nearby " +
                        "whose radio isn't set to one of the regions below. Traffic tagged with an allowed region is still relayed."
                } else null,
                if (refuse) "Untagged traffic refused" else "Untagged traffic updated",
            )
        }
    }

    const val UNTAGGED_HINT = "Traffic carrying no region — what a mesh that doesn't use regions sends."
    const val UNTAGGED_UNKNOWN =
        "The node didn't answer when asked for its untagged hop limit, so whether it relays all of this or only nearby traffic isn't known. Fetch again."
    const val UNTAGGED_NEEDS_116 = "Relaying only nearby traffic needs repeater firmware 1.16 or newer."
}
