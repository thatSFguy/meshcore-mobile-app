package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.NodeRole

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
 * Two gating rules, and they differ on purpose:
 *
 *  - **Signed in** is enough for Status, Settings and Regions. Those
 *    panels already degrade to read-only for a guest (every `Save` is
 *    `enabled = isAdmin`), and a guest reading a repeater's settings is
 *    a legitimate thing to do. Hiding them would tell the user less
 *    than the node is willing to.
 *  - **Admin** is required for Identity and Console, which is where the
 *    irreversible commands live (`set prv.key`, `erase`, `reboot`).
 *    A guest session cannot run them, so offering them would be a
 *    control that exists only to fail.
 *
 * Command help needs no session at all — it is a local catalogue, not a
 * request to the node.
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
        add(
            HubTile(
                route = "settings",
                title = "Settings",
                subtitle = if (session.isAdmin) {
                    "Radio, position, timing and policy"
                } else {
                    "Radio, position, timing and policy — read-only"
                },
            ),
        )
        if (role == NodeRole.Repeater) {
            add(
                HubTile(
                    route = "regions",
                    title = "Regions",
                    subtitle = if (session.isAdmin) {
                        "Which areas this repeater serves"
                    } else {
                        "Which areas this repeater serves — read-only"
                    },
                ),
            )
        }
    }
    if (session.isAdmin) {
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
    }
    add(
        HubTile(
            route = "help",
            title = "Command help",
            subtitle = "What each command does, and what it expects",
        ),
    )
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
