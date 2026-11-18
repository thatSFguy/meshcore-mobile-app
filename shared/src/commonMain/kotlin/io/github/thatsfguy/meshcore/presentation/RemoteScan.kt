package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.CliIds
import io.github.thatsfguy.meshcore.protocol.CliReplies
import io.github.thatsfguy.meshcore.protocol.RadioPresets
import io.github.thatsfguy.meshcore.protocol.RadioUnits
import io.github.thatsfguy.meshcore.protocol.Regions
import io.github.thatsfguy.meshcore.protocol.ShareUri

/**
 * A scanned settings code applied to a REMOTE node (a repeater you
 * administer), rather than to your own radio — Android's
 * RemoteSettingsForm scan flow, lifted for iOS Phase 5 (2026-11-18).
 *
 * Read what the node runs first, so a node already on these settings is
 * neither sent them again nor told to reboot; regions are written only
 * when the person ticks them and only as the shared [ScannedSettingsPlan]
 * says. TX power is never taken from a code. Everything a screen needs
 * is a String, Boolean or list of them, so Swift never names the plan's
 * three-deep types.
 */
class RemoteScan private constructor(private val config: ShareUri.Decoded.RadioConfig) {

    private val replies = HashMap<String, String?>()

    val name: String get() = config.name.ifBlank { "(unnamed mesh)" }
    val summary: String get() = config.summary()
    val needsRegions: Boolean get() = config.regionChoices().isNotEmpty()

    /** What to ask the node before deciding, in order. */
    fun readCommands(): List<String> = buildList {
        add("get ${CliIds.RADIO}")
        add("get ${CliIds.PATH_HASH_MODE}")
        if (needsRegions) {
            add(Regions.tree())
            if (config.region != null) add(Regions.default())
        }
    }

    fun take(command: String, reply: String?) {
        replies[command] = reply
    }

    private val nodeRadio get() = replies["get ${CliIds.RADIO}"]?.let { CliReplies.extractGetValue(it) }?.let { CliReplies.parseRadioCsv(it) }
    private val nodeHash get() = replies["get ${CliIds.PATH_HASH_MODE}"]?.let { CliReplies.extractGetValue(it) }?.trim()?.toIntOrNull()

    /** The node already runs these radio settings: nothing to send, no reboot. */
    val radioSame: Boolean get() = ScannedSettingsPlan.radioMatches(config, nodeRadio, nodeHash)

    private val plan: ScannedSettingsPlan.RegionPlan?
        get() = if (!needsRegions) null else ScannedSettingsPlan.regions(
            config.regionChoices(),
            config.region,
            Regions.parseRegionTree(replies[Regions.tree()]),
            Regions.parseDefaultScope(replies[Regions.default()]),
        )

    fun radioLine(node: String): String = if (radioSame) {
        "$node already runs these radio settings, so they won't be sent again and it doesn't need a reboot."
    } else {
        "This is a remote node. The settings save now and take effect when $node reboots; after that this radio must be on the same settings to reach it again."
    }

    val warning: String
        get() = "Anyone can make one of these codes; nothing in it is signed. " + RadioPresets.REGULATORY_CAVEAT

    /** True when there are region changes the person may choose to write. */
    val canWriteRegions: Boolean get() = plan is ScannedSettingsPlan.RegionPlan.Changes

    /** The bullets describing the region changes, when [canWriteRegions]. */
    val regionChanges: List<String>
        get() = (plan as? ScannedSettingsPlan.RegionPlan.Changes)?.let { ScannedSettingsPlan.describe(it) }.orEmpty()

    /** What to say about regions when there is nothing to tick (null = the code names none). */
    fun regionLine(node: String): String? {
        if (!needsRegions) return null
        return when (val p = plan) {
            null -> "This code's regions can't be written to a node."
            ScannedSettingsPlan.RegionPlan.AlreadyApplied ->
                "$node already has these regions" + (config.region?.let { " and #$it as its default" } ?: "") + ". Nothing to send."
            is ScannedSettingsPlan.RegionPlan.Changes ->
                "Regions this code doesn't name are left alone, and untagged traffic isn't changed. Takes effect at once, " +
                    "without a reboot. Needs repeater firmware 1.16 or newer."
            ScannedSettingsPlan.RegionPlan.Unreadable ->
                "Couldn't read $node's current regions, so they can't be checked and won't be written. Cancel and scan again, or use Regions."
            ScannedSettingsPlan.RegionPlan.TooManyToRead ->
                "$node holds more regions than one reply shows, so these can't be checked here; add them under Regions."
            ScannedSettingsPlan.RegionPlan.TooLong -> "Too long to write in one node command; add these under Regions."
        }
    }

    /** True when Apply would send nothing at all. */
    fun nothingToDo(writeRegions: Boolean): Boolean = commands(writeRegions).isEmpty()

    /** Radio first (unless already the same), then — if ticked — the region steps. */
    fun commands(writeRegions: Boolean): List<String> = buildList {
        if (!radioSame) {
            add(
                "set radio ${RadioUnits.khzToMhzText(config.frequencyKhz)},${RadioUnits.hzToKhzText(config.bandwidthHz)}," +
                    "${config.spreadingFactor},${config.codingRate}",
            )
            add("set ${CliIds.PATH_HASH_MODE} ${config.pathHashMode}")
        }
        if (writeRegions) (plan as? ScannedSettingsPlan.RegionPlan.Changes)?.let { addAll(it.commands) }
    }

    /** Whether a successful apply leaves the node needing a reboot. */
    val needsReboot: Boolean get() = !radioSame

    /** Null when [reply] means the step was taken; otherwise why it stopped. */
    fun refusal(node: String, command: String, reply: String?): String? {
        val isRegion = command.startsWith("region ")
        val refused = if (isRegion) RegionAdmin.treeStepRefused(reply) else reply == null
        if (!refused) return null
        val r = reply?.trim()
        return "$node didn't take `$command`: " + when {
            r == null -> "no reply"
            r.contains("??") -> "$r (it needs firmware 1.16 or newer)"
            else -> r
        }
    }

    companion object {
        /** A scanned code as a remote apply, or null when it isn't a settings code. */
        fun decode(text: String): RemoteScan? =
            (ShareUri.decode(text.trim()) as? ShareUri.Decoded.RadioConfig)?.let { RemoteScan(it) }
    }
}
