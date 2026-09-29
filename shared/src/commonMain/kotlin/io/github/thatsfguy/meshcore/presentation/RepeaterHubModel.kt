package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.NodeRole
import io.github.thatsfguy.meshcore.protocol.PathCodec
import io.github.thatsfguy.meshcore.protocol.Regions

/**
 * What the NODE granted this session — never what the user asked for.
 *
 * The login reply carries the permission byte
 * (`PUSH_CODE_LOGIN_SUCCESS[1]`, 1 = admin) and that byte is the only
 * source for this value. There is deliberately no way for the UI to
 * set it: the previous design had a "Guest (read-only)" checkbox
 * beside the password field, parsed the node's answer and threw it
 * away, so ticking the box with an admin password locked controls the
 * node would have allowed (LESSONS §12, REBUILD-PLAYBOOK §6.1).
 *
 * [None] is a real third state, not "guest by default": before a login
 * round-trip we know nothing, and showing read-only tools would be a
 * claim we can't back.
 */
enum class AdminSession {
    None,
    Guest,
    Admin,
    ;

    val signedIn: Boolean get() = this != None
    val isAdmin: Boolean get() = this == Admin

    /** Short, one-word status for the hub chip. */
    val chipLabel: String
        get() = when (this) {
            None -> "NOT SIGNED IN"
            Guest -> "GUEST"
            Admin -> "ADMIN"
        }
}

/**
 * One row on the repeater hub. [route] is the sub-route appended to
 * `repeater/{key}/`.
 */
data class HubTile(
    val route: String,
    val title: String,
    val subtitle: String,
)

/**
 * The hub's tool list for a node of [role] under [session].
 *
 * Hub-and-spoke, ported from the reference client's
 * `repeater_hub_screen` — the surface that replaced a six-tab
 * mega-screen here (LESSONS §13, REBUILD-PLAYBOOK §1.4a, §6.2).
 *
 * Two gating rules, set by what the FIRMWARE will answer:
 *
 *  - **Signed in** is enough for Status. It is a binary request
 *    (`REQ_TYPE_GET_STATUS`), and the repeater answers it for guests
 *    (`simple_repeater/MyMesh.cpp:219`, "guests can also access this now").
 *  - **Admin** is required for everything that speaks CLI — Settings,
 *    Regions, Identity, Console and Firmware. The repeater only runs CLI
 *    text from an admin (`MyMesh.cpp:689`,
 *    `type == PAYLOAD_TYPE_TXT_MSG && len > 5 && client->isAdmin()`);
 *    room servers and sensors have the same check. A guest's `get` is
 *    dropped with no reply and no ACK.
 *
 * Settings and Regions used to be offered to guests as "read-only", on
 * the belief that a guest could read what it could not write. It could
 * not read either: every field sat empty and every refresh timed out.
 * What a guest CAN read of a repeater's configuration is its firmware
 * version and owner text (`REQ_TYPE_GET_OWNER_INFO`) and the regions it
 * floods (`ANON_REQ_TYPE_REGIONS`) — the card [showsGuestQueries] puts on
 * the hub.
 *
 * Command help is admin-only too. It is a local catalogue and needs no
 * round-trip, but it documents the console, and without the console it
 * lists commands this session cannot send.
 *
 * Regions are repeater-only: a room server and a sensor do not run the
 * `region` CLI, so the tile would 404 against the node.
 */
fun repeaterHubTiles(role: NodeRole, session: AdminSession): List<HubTile> = buildList {
    if (session.signedIn) {
        add(
            HubTile(
                route = "status",
                title = "Status",
                subtitle = "Battery, uptime, airtime and queue depth",
            ),
        )
    }
    if (session.isAdmin) {
        add(
            HubTile(
                route = "settings",
                title = "Settings",
                subtitle = "Radio, position, timing and policy",
            ),
        )
        if (role == NodeRole.Repeater) {
            add(
                HubTile(
                    route = "regions",
                    title = "Regions",
                    subtitle = "Which areas this repeater serves",
                ),
            )
        }
        add(
            HubTile(
                route = "identity",
                title = "Identity",
                subtitle = "Public key, and the keys that replace it",
            ),
        )
        add(
            HubTile(
                route = "console",
                title = "Console",
                subtitle = "Send CLI commands and read the replies",
            ),
        )
        // Admin-only for the same reason as the console: `start ota`
        // takes the node off the mesh until someone stands next to it
        // with a phone. A guest session cannot run it, and a tile that
        // exists only to fail is worse than no tile.
        add(
            HubTile(
                route = "firmware",
                title = "Firmware",
                subtitle = "Update this node — you must be within Bluetooth range",
            ),
        )
        // A reference for the console, so it goes where the console
        // goes: a guest can run no CLI at all, and a list of commands
        // the node will drop is not help.
        add(
            HubTile(
                route = "help",
                title = "Command help",
                subtitle = "What each command does, and what it expects",
            ),
        )
    }
}

/**
 * Whether the hub shows the guest card: owner info and flood regions,
 * each asked for on a tap. For a guest on a repeater.
 *
 * Guest-only because an admin reads all of it, and more, from Settings
 * and Regions. Repeater-only because both requests are `simple_repeater`
 * handlers — `REQ_TYPE_GET_OWNER_INFO` (MyMesh.cpp:375) and
 * `ANON_REQ_TYPE_REGIONS` (MyMesh.cpp:584); a room server or sensor
 * would never answer either.
 */
fun showsGuestQueries(role: NodeRole, session: AdminSession): Boolean =
    role == NodeRole.Repeater && session == AdminSession.Guest

/**
 * The outcome of asking one repeater which regions it floods, for the
 * guest card. Three answers that must not be shown the same way: we did
 * not ask (no route), we asked and heard nothing, and the node answered.
 */
sealed interface FloodRegionsAsk {
    /** No stored direct route, so nothing was sent — see [floodRegionsRoute]. */
    data object NoRoute : FloodRegionsAsk
    data object NoAnswer : FloodRegionsAsk
    data class Answered(val list: Regions.FloodList) : FloodRegionsAsk
}

/**
 * The stored route to use for the anonymous regions request, or null
 * when there is none and the request should not be sent.
 *
 * The repeater answers it only when it arrives DIRECT
 * (simple_repeater/MyMesh.cpp:584), and the companion floods it when the
 * contact has no path (BaseChatMesh.cpp:607) — so without a route the
 * request is airtime spent on a guaranteed silence. Zero hops is a
 * route: a direct send with an empty path.
 */
fun floodRegionsRoute(pathLen: Int): PathCodec.PathInfo? =
    PathCodec.decodePathLen(pathLen).takeUnless { it.isFlood }

/**
 * One line for what the node answered. It says "floods", never "has":
 * a region set to deny flooding is absent from the answer, not absent
 * from the repeater.
 */
fun floodRegionsSummary(list: Regions.FloodList): String {
    val named = list.regions
    return when {
        named.isEmpty() && list.untaggedFloods -> "Floods untagged traffic only — no named regions."
        named.isEmpty() -> "Floods no regions, and not untagged traffic."
        list.untaggedFloods -> "Floods ${named.joinToString(", ")}, and untagged traffic."
        else -> "Floods ${named.joinToString(", ")}. Untagged traffic is not flooded."
    }
}

/** Screen title for a node of [role] — the hub's app-bar subtitle. */
fun repeaterRoleLabel(role: NodeRole): String = when (role) {
    NodeRole.Room -> "Room server"
    NodeRole.Sensor -> "Sensor"
    else -> "Repeater"
}

// ----------------------------------------------------------------------
// Console prefill transport
// ----------------------------------------------------------------------

/**
 * Carry a CLI usage string from Command help to the Console as a route
 * argument.
 *
 * Hex, not percent-encoding. Usage strings out of [CliCatalog] contain
 * spaces, angle brackets and slashes (`set flood.max <n>`,
 * `set repeat on/off`), and a route argument goes through the
 * navigation library's own decode on the way out — so a
 * percent-encoded value has two layers that must agree about `+`, `/`
 * and `%` for the round trip to hold. Hex has no reserved characters,
 * so there is nothing for the two layers to disagree about.
 */
fun encodePrefill(usage: String): String =
    usage.encodeToByteArray().joinToString("") { byte ->
        val v = byte.toInt() and 0xFF
        val hex = v.toString(16)
        if (hex.length == 1) "0$hex" else hex
    }

/**
 * Inverse of [encodePrefill]. Returns "" for anything that is not a
 * well-formed even-length hex string — this value arrives from a route
 * and the console must not show garbage if it is malformed.
 */
fun decodePrefill(encoded: String): String {
    if (encoded.isEmpty() || encoded.length % 2 != 0) return ""
    val bytes = ByteArray(encoded.length / 2)
    for (i in bytes.indices) {
        // digitToIntOrNull, not Character.digit: the latter is
        // java.lang, imported implicitly on the JVM so it carries no
        // `java.` prefix in source and reads as ordinary Kotlin. It
        // compiled for Android and broke the first iOS build.
        val hi = encoded[i * 2].digitToIntOrNull(16) ?: -1
        val lo = encoded[i * 2 + 1].digitToIntOrNull(16) ?: -1
        if (hi < 0 || lo < 0) return ""
        bytes[i] = ((hi shl 4) or lo).toByte()
    }
    return bytes.decodeToString()
}

/**
 * What the firmware panel may claim about a node's update mode.
 *
 * Two inputs, and they are different kinds of thing. Keeping them apart
 * is the whole point, because merging them is what made the screen lie.
 *
 * - [flaggedInUpdateMode] — **recorded state**, and the only thing that
 *   may be asserted. Set when the node answers `start ota`; cleared by
 *   the events that end it — a finished transfer, an accepted restart,
 *   or the operator saying so. A flag with named transitions.
 * - [knownAddress] — **a durable fact about the hardware**, like the
 *   board name. A radio's BLE address does not change across a reboot, a
 *   firmware update, or a reflash over USB. It is kept for good, it is
 *   what lets a flash pick this node out of everything else nearby
 *   advertising for an update, and it says nothing whatever about what
 *   the node is doing.
 *
 * The defect this replaces: `inUpdateMode` was `knownAddress != null`.
 * Nothing cleared the address — correctly, since nothing should — so a
 * node that entered update mode once was described as being in it for
 * ever, and the screen hid `Send start ota`, the control that would have
 * helped.
 *
 * The same trap waits one table along. The console thread is persisted,
 * so `OK - mac: …` is a row that sits in the database indefinitely and
 * is re-read on every render; asserting the state from *that* would be
 * just as permanent. The reply is consumed once, against a watermark
 * (`ContactEntity.otaReplyHandledAt`), and what it produces is this
 * flag.
 *
 * Nothing here can observe the state directly, and no signal from the
 * mesh could: `start ota` leaves the node running and repeating, so a
 * node still answering proves nothing either way, and a node reflashed
 * over USB looks exactly like one still waiting. So the state is
 * tracked, and it is correctable by hand.
 */
data class UpdateModeView(
    val inUpdateMode: Boolean,
    /** Best address to hand the flash route; kept whatever the state. */
    val flashAddress: String?,
) {
    companion object {
        fun of(flaggedInUpdateMode: Boolean, knownAddress: String?): UpdateModeView =
            UpdateModeView(
                inUpdateMode = flaggedInUpdateMode,
                flashAddress = knownAddress,
            )
    }
}
