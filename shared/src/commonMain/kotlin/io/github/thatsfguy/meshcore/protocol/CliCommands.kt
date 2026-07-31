package io.github.thatsfguy.meshcore.protocol

/**
 * Catalog of the MeshCore text-CLI command surface (the commands a
 * repeater/room firmware understands over `txt_type=cli_data`),
 * inventoried from the MeshCore Open reference client's CLI screen.
 *
 * Role model:
 *  - [NodeRole.Repeater] / [NodeRole.Room] nodes speak this CLI —
 *    the admin screen shows each role only its applicable commands.
 *  - [NodeRole.Companion] nodes do NOT run the text CLI; entries
 *    tagged Companion exist to assert coverage: each MUST name the
 *    companion-frame equivalent the Settings screen already uses
 *    ([companionEquivalent]), and the UI never sends them as CLI.
 */
enum class NodeRole { Companion, Repeater, Room }

/** How a catalog entry is invoked. */
enum class CliKind {
    /** Bare action ("ver", "reboot"). */
    Action,

    /** `get <name>` only. */
    GetOnly,

    /** `get <name>` / `set <name> <value>`. */
    GetSet,

    /** Action with a required argument ("time <epoch>", "password <pw>"). */
    ActionWithArg,
}

data class CliCommand(
    /** Stable id — the variable name or action word. */
    val id: String,
    val kind: CliKind,
    val label: String,
    val description: String,
    val roles: Set<NodeRole>,
    val category: String,
    /** Argument hint for GetSet/ActionWithArg ("<epoch-seconds>"). */
    val argHint: String? = null,
    /** Argument/response contains a secret — redact from logs, mask input. */
    val sensitive: Boolean = false,
    /** Destructive/irreversible — UI must confirm before sending. */
    val requiresConfirm: Boolean = false,
    /** For Companion-tagged entries: the companion-protocol equivalent
     *  (frame/command) the app uses instead of CLI text. */
    val companionEquivalent: String? = null,
) {
    /** The `get` command string, for kinds that support it. */
    fun getCommand(): String {
        check(kind == CliKind.GetOnly || kind == CliKind.GetSet) { "$id has no get form" }
        return "get $id"
    }

    /** The `set`/action command string with [value] substituted. */
    fun buildCommand(value: String? = null): String = when (kind) {
        CliKind.Action -> id
        CliKind.GetOnly -> getCommand()
        CliKind.GetSet -> {
            require(!value.isNullOrBlank()) { "$id requires a value" }
            "set $id $value"
        }
        CliKind.ActionWithArg -> {
            require(!value.isNullOrBlank()) { "$id requires an argument" }
            "$id $value"
        }
    }
}

object CliCatalog {

    private val REPEATER_AND_ROOM = setOf(NodeRole.Repeater, NodeRole.Room)
    private val ALL = setOf(NodeRole.Companion, NodeRole.Repeater, NodeRole.Room)

    val all: List<CliCommand> = listOf(
        // ---- Info / status -------------------------------------------------
        CliCommand(
            "ver", CliKind.Action, "Firmware version",
            "Firmware version and build info.", ALL, "Info",
            companionEquivalent = "CMD_DEVICE_QUERY → RESP_CODE_DEVICE_INFO",
        ),
        CliCommand(
            "board", CliKind.Action, "Board info",
            "Hardware board identifier.", REPEATER_AND_ROOM, "Info",
        ),
        CliCommand(
            "role", CliKind.GetOnly, "Node role",
            "Reports repeater/room role.", REPEATER_AND_ROOM, "Info",
        ),
        CliCommand(
            "public.key", CliKind.GetOnly, "Public key",
            "The node's Ed25519 public key.", ALL, "Info",
            companionEquivalent = "RESP_CODE_SELF_INFO pubkey",
        ),
        CliCommand(
            "bootloader.ver", CliKind.GetOnly, "Bootloader version",
            "Bootloader version.", REPEATER_AND_ROOM, "Info",
        ),
        CliCommand(
            "neighbors", CliKind.Action, "Neighbors",
            "One-hop neighbor table.", setOf(NodeRole.Repeater), "Info",
        ),
        CliCommand(
            "acl", CliKind.GetOnly, "Access list",
            "Access-control list (admin/guest entries).", REPEATER_AND_ROOM, "Security",
        ),

        // ---- Identity / position ------------------------------------------
        CliCommand(
            "name", CliKind.GetSet, "Node name",
            "Advertised node name.", ALL, "Identity", argHint = "<name>",
            companionEquivalent = "CMD_SET_ADVERT_NAME",
        ),
        CliCommand(
            "owner.info", CliKind.GetSet, "Owner info",
            "Free-form owner/contact info string.", REPEATER_AND_ROOM, "Identity",
            argHint = "<text>",
        ),
        CliCommand(
            "lat", CliKind.GetSet, "Latitude",
            "Advertised latitude (degrees).", ALL, "Position", argHint = "<degrees>",
            companionEquivalent = "CMD_SET_ADVERT_LATLON",
        ),
        CliCommand(
            "lon", CliKind.GetSet, "Longitude",
            "Advertised longitude (degrees).", ALL, "Position", argHint = "<degrees>",
            companionEquivalent = "CMD_SET_ADVERT_LATLON",
        ),

        // ---- Radio ---------------------------------------------------------
        CliCommand(
            "radio", CliKind.GetSet, "Radio params",
            "Combined freq,bw,sf,cr.", ALL, "Radio", argHint = "<freq,bw,sf,cr>",
            companionEquivalent = "CMD_SET_RADIO_PARAMS",
        ),
        CliCommand(
            "freq", CliKind.GetSet, "Frequency",
            "Center frequency (MHz).", ALL, "Radio", argHint = "<MHz>",
            companionEquivalent = "CMD_SET_RADIO_PARAMS",
        ),
        CliCommand(
            "tx", CliKind.GetSet, "TX power",
            "Transmit power (dBm).", ALL, "Radio", argHint = "<dBm>",
            companionEquivalent = "CMD_SET_RADIO_TX_POWER",
        ),
        CliCommand(
            "af", CliKind.GetSet, "Airtime factor",
            "Airtime budget factor.", REPEATER_AND_ROOM, "Radio", argHint = "<factor>",
        ),
        CliCommand(
            "radio.rxgain", CliKind.GetSet, "RX gain",
            "Receiver gain setting.", REPEATER_AND_ROOM, "Radio", argHint = "<gain>",
        ),
        CliCommand(
            "agc.reset.interval", CliKind.GetSet, "AGC reset interval",
            "AGC reset interval (secs).", REPEATER_AND_ROOM, "Radio", argHint = "<secs>",
        ),
        CliCommand(
            "int.thresh", CliKind.GetSet, "Interference threshold",
            "Interference detection threshold.", REPEATER_AND_ROOM, "Radio", argHint = "<value>",
        ),
        CliCommand(
            "dutycycle", CliKind.GetSet, "Duty cycle",
            "TX duty-cycle limit (%).", REPEATER_AND_ROOM, "Radio", argHint = "<percent>",
        ),

        // ---- Mesh / routing -----------------------------------------------
        CliCommand(
            "repeat", CliKind.GetSet, "Repeat (forwarding)",
            "Enable/disable packet forwarding.", setOf(NodeRole.Repeater), "Mesh",
            argHint = "<on|off>",
        ),
        CliCommand(
            "flood.max", CliKind.GetSet, "Flood max hops",
            "Maximum flood hop count.", setOf(NodeRole.Repeater), "Mesh", argHint = "<hops>",
        ),
        CliCommand(
            "multi.acks", CliKind.GetSet, "Multi-acks",
            "Redundant ACK copies.", ALL, "Mesh", argHint = "<0|1>",
            companionEquivalent = "CMD_SET_OTHER_PARAMS",
        ),
        CliCommand(
            "path.hash.mode", CliKind.GetSet, "Path hash mode",
            "On-air path hash width mode 0–3.", ALL, "Mesh", argHint = "<0-3>",
            companionEquivalent = "CMD_SET_PATH_HASH_MODE",
        ),
        CliCommand(
            "loop.detect", CliKind.GetSet, "Loop detection",
            "Flood loop detection.", setOf(NodeRole.Repeater), "Mesh", argHint = "<on|off>",
        ),
        CliCommand(
            "rxdelay", CliKind.GetSet, "RX delay",
            "Receive delay tuning (ms).", REPEATER_AND_ROOM, "Mesh", argHint = "<ms>",
        ),
        CliCommand(
            "txdelay", CliKind.GetSet, "TX delay",
            "Transmit delay tuning (ms).", REPEATER_AND_ROOM, "Mesh", argHint = "<ms>",
        ),
        CliCommand(
            "direct.txdelay", CliKind.GetSet, "Direct TX delay",
            "TX delay for direct (routed) packets.", REPEATER_AND_ROOM, "Mesh", argHint = "<ms>",
        ),

        // ---- Adverts / clock ----------------------------------------------
        CliCommand(
            "advert", CliKind.Action, "Send advert",
            "Broadcast an advert now.", ALL, "Advert",
            companionEquivalent = "CMD_SEND_SELF_ADVERT",
        ),
        CliCommand(
            "advert.interval", CliKind.GetSet, "Advert interval",
            "Periodic zero-hop advert interval (mins).", REPEATER_AND_ROOM, "Advert",
            argHint = "<mins>",
        ),
        CliCommand(
            "flood.advert.interval", CliKind.GetSet, "Flood advert interval",
            "Periodic flood advert interval (hours).", REPEATER_AND_ROOM, "Advert",
            argHint = "<hours>",
        ),
        CliCommand(
            "clock", CliKind.Action, "Read clock",
            "Read the node's RTC.", ALL, "Clock",
            companionEquivalent = "CMD_GET_DEVICE_TIME",
        ),
        CliCommand(
            "clock sync", CliKind.Action, "Sync clock",
            "Sync the node clock from the sender's message timestamp.",
            REPEATER_AND_ROOM, "Clock",
        ),
        CliCommand(
            "time", CliKind.ActionWithArg, "Set clock",
            "Set RTC to an epoch timestamp.", ALL, "Clock", argHint = "<epoch-seconds>",
            companionEquivalent = "CMD_SET_DEVICE_TIME",
        ),

        // ---- Security ------------------------------------------------------
        CliCommand(
            "password", CliKind.ActionWithArg, "Admin password",
            "Change the admin login password.", REPEATER_AND_ROOM, "Security",
            argHint = "<new-password>", sensitive = true,
        ),
        CliCommand(
            "guest.password", CliKind.GetSet, "Guest password",
            "Guest (read-only) login password.", REPEATER_AND_ROOM, "Security",
            argHint = "<password>", sensitive = true,
        ),
        CliCommand(
            "allow.read.only", CliKind.GetSet, "Allow read-only guests",
            "Permit guest (read-only) logins.", setOf(NodeRole.Room), "Security",
            argHint = "<on|off>",
        ),
        CliCommand(
            "prv.key", CliKind.GetSet, "Private key",
            "Node identity private key — EXTREMELY sensitive.", REPEATER_AND_ROOM,
            "Security", argHint = "<hex>", sensitive = true, requiresConfirm = true,
        ),

        // ---- Power ---------------------------------------------------------
        CliCommand(
            "adc.multiplier", CliKind.GetSet, "ADC multiplier",
            "Battery ADC calibration multiplier.", REPEATER_AND_ROOM, "Power",
            argHint = "<value>",
        ),
        CliCommand(
            "pwrmgt.support", CliKind.GetOnly, "Power mgmt support",
            "Power-management capability.", REPEATER_AND_ROOM, "Power",
        ),
        CliCommand(
            "pwrmgt.source", CliKind.GetOnly, "Power source",
            "Active power source.", REPEATER_AND_ROOM, "Power",
        ),
        CliCommand(
            "pwrmgt.bootmv", CliKind.GetOnly, "Boot voltage",
            "Battery millivolts at boot.", REPEATER_AND_ROOM, "Power",
        ),
        CliCommand(
            "pwrmgt.bootreason", CliKind.GetOnly, "Boot reason",
            "Last boot/reset reason.", REPEATER_AND_ROOM, "Power",
        ),

        // ---- Bridge (repeater serial/LoRa bridge) -------------------------
        CliCommand(
            "bridge.enabled", CliKind.GetSet, "Bridge enabled",
            "Serial bridge on/off.", setOf(NodeRole.Repeater), "Bridge", argHint = "<on|off>",
        ),
        CliCommand(
            "bridge.type", CliKind.GetSet, "Bridge type",
            "Bridge transport type.", setOf(NodeRole.Repeater), "Bridge", argHint = "<type>",
        ),
        CliCommand(
            "bridge.source", CliKind.GetSet, "Bridge source",
            "Bridge source id.", setOf(NodeRole.Repeater), "Bridge", argHint = "<id>",
        ),
        CliCommand(
            "bridge.channel", CliKind.GetSet, "Bridge channel",
            "Bridge channel.", setOf(NodeRole.Repeater), "Bridge", argHint = "<n>",
        ),
        CliCommand(
            "bridge.baud", CliKind.GetSet, "Bridge baud",
            "Bridge serial baud rate.", setOf(NodeRole.Repeater), "Bridge", argHint = "<baud>",
        ),
        CliCommand(
            "bridge.delay", CliKind.GetSet, "Bridge delay",
            "Bridge forwarding delay (ms).", setOf(NodeRole.Repeater), "Bridge", argHint = "<ms>",
        ),
        CliCommand(
            "bridge.secret", CliKind.GetSet, "Bridge secret",
            "Bridge shared secret.", setOf(NodeRole.Repeater), "Bridge",
            argHint = "<secret>", sensitive = true,
        ),

        // ---- Maintenance ---------------------------------------------------
        CliCommand(
            "reboot", CliKind.Action, "Reboot",
            "Reboot the node.", ALL, "Maintenance", requiresConfirm = true,
            companionEquivalent = "CMD_REBOOT",
        ),
        CliCommand(
            "clear stats", CliKind.Action, "Clear stats",
            "Reset packet/traffic statistics.", REPEATER_AND_ROOM, "Maintenance",
            requiresConfirm = true,
        ),
        CliCommand(
            "log start", CliKind.Action, "Log start",
            "Start on-device packet logging.", REPEATER_AND_ROOM, "Maintenance",
        ),
        CliCommand(
            "log stop", CliKind.Action, "Log stop",
            "Stop on-device packet logging.", REPEATER_AND_ROOM, "Maintenance",
        ),
        CliCommand(
            "log erase", CliKind.Action, "Log erase",
            "Erase the on-device packet log.", REPEATER_AND_ROOM, "Maintenance",
            requiresConfirm = true,
        ),
        CliCommand(
            "erase", CliKind.Action, "Factory erase",
            "Erase node storage/config.", REPEATER_AND_ROOM, "Maintenance",
            requiresConfirm = true,
        ),
        CliCommand(
            "start ota", CliKind.Action, "Start OTA",
            "Enter firmware-update (OTA) mode.", REPEATER_AND_ROOM, "Maintenance",
            requiresConfirm = true,
        ),
    )

    /** Commands applicable to [role], catalog order preserved. */
    fun forRole(role: NodeRole): List<CliCommand> = all.filter { role in it.roles }

    /** [forRole] grouped by category, catalog order preserved. */
    fun forRoleByCategory(role: NodeRole): Map<String, List<CliCommand>> {
        val out = LinkedHashMap<String, MutableList<CliCommand>>()
        for (c in forRole(role)) out.getOrPut(c.category) { mutableListOf() }.add(c)
        return out
    }

    fun byId(id: String): CliCommand? = all.firstOrNull { it.id == id }
}
