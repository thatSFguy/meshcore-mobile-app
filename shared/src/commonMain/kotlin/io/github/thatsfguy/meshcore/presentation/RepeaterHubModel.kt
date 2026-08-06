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
        val hi = Character.digit(encoded[i * 2], 16)
        val lo = Character.digit(encoded[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return ""
        bytes[i] = ((hi shl 4) or lo).toByte()
    }
    return bytes.decodeToString()
}
