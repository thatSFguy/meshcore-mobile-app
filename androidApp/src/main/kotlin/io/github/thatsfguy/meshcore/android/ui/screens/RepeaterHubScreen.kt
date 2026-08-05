package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.NodeRole
import io.github.thatsfguy.meshcore.protocol.PathCodec

/**
 * The repeater/room/sensor admin **hub** — an identity card, the grant
 * the node gave us, and a list of tools that each open their own
 * screen.
 *
 * This replaced `RepeaterAdminScreen`, which put six scrollable tabs
 * (Status · Settings · Regions · Identity · Console · Help) and a login
 * row on one surface. The reference client has had hub-and-spoke here
 * the whole time — the rebuild copied its *screen list* and invented
 * its own answer to how they connect, which is the exact failure
 * REBUILD-PLAYBOOK §1.4a is about.
 *
 * Which tools appear is [repeaterHubTiles]; it is a pure function so
 * the gating rules are testable without a device.
 */
@Composable
fun RepeaterHubScreen(vm: MeshCoreViewModel, nav: NavController, keyHex: String) {
    val contacts by vm.dbContacts.collectAsState()
    val contact = contacts.firstOrNull { it.keyHex == keyHex }
    val name = contact?.name?.ifBlank { null } ?: keyHex.take(12)
    val role = when (contact?.type) {
        Codes.ADV_TYPE_ROOM -> NodeRole.Room
        Codes.ADV_TYPE_SENSOR -> NodeRole.Sensor
        else -> NodeRole.Repeater
    }

    val sessions by vm.adminSessions.collectAsState()
    val session = sessions[keyHex] ?: AdminSession.None

    // Arriving with no session opens sign-in straight away — the tools
    // are useless without one, so making the user find a button first
    // would only add a tap. Cancelling leaves the hub visible with a
    // Sign in button rather than bouncing back to the node list.
    var showLogin by remember(keyHex) { mutableStateOf(false) }
    // Saveable, so walking into Command help and back does not re-open
    // a dialog the user has already dismissed once.
    var autoPrompted by rememberSaveable(keyHex) { mutableStateOf(false) }
    LaunchedEffect(keyHex, session) {
        if (session == AdminSession.None && !autoPrompted) {
            autoPrompted = true
            showLogin = true
        }
    }

    val tiles = remember(role, session) { repeaterHubTiles(role, session) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = name,
                vm = vm,
                nav = nav,
                subtitle = repeaterRoleLabel(role),
                menuActions = buildList {
                    if (session.signedIn) {
                        add(MenuAction("Request status") { vm.requestRepeaterStatus(keyHex) })
                    }
                    if (session.isAdmin) {
                        add(
                            MenuAction("Sync clock from phone") {
                                vm.sendCli(keyHex, "time ${System.currentTimeMillis() / 1000}")
                            },
                        )
                    }
                    if (session.signedIn) {
                        add(MenuAction("Sign out") { vm.signOutOfNode(keyHex) })
                    }
                    add(
                        MenuAction("Forget saved password", destructive = true) {
                            vm.forgetLoginPassword(keyHex)
                        },
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            IdentityCard(
                name = name,
                keyHex = keyHex,
                type = contact?.type,
                pathLen = contact?.pathLen,
                latitude = contact?.latitude,
                longitude = contact?.longitude,
                session = session,
            )

            if (!session.signedIn) {
                // One line, not the three-line explainer the old screen
                // carried on every visit (§6.3). What read-only means is
                // in the dialog, where it is about to matter.
                Text(
                    "Sign in to read this node's status and settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = { showLogin = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text("Sign in") }
                Spacer(Modifier.height(8.dp))
            }

            SectionLabel(
                when (session) {
                    AdminSession.Admin -> "Management tools"
                    AdminSession.Guest -> "Read-only tools"
                    AdminSession.None -> "Available without signing in"
                },
            )

            for (tile in tiles) {
                HubTileRow(tile) { nav.navigate("repeater/$keyHex/${tile.route}") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showLogin) {
        RepeaterLoginDialog(
            vm = vm,
            keyHex = keyHex,
            nodeName = name,
            onDismiss = { showLogin = false },
        )
    }
}

/**
 * Who you are administering and what you were granted, in one card at
 * the top of the hub. The grant chip is the whole of the old
 * three-line read-only explainer.
 */
@Composable
private fun IdentityCard(
    name: String,
    keyHex: String,
    type: Int?,
    pathLen: Int?,
    latitude: Double?,
    longitude: Double?,
    session: AdminSession,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NodeAvatar(seed = keyHex, label = name, type = type, size = 48.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    keyHex.take(12),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                pathLen?.let {
                    val info = PathCodec.decodePathLen(it)
                    Text(
                        when {
                            info.isFlood -> "Flood — no stored path"
                            info.hops == 0 -> "Direct"
                            info.hops == 1 -> "1 hop away"
                            else -> "${info.hops} hops away"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (latitude != null && longitude != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.height(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "%.4f, %.4f".format(latitude, longitude),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            GrantChip(session)
        }
    }
}

/** What the node granted — never what was asked for. */
@Composable
private fun GrantChip(session: AdminSession) {
    val color = when (session) {
        AdminSession.Admin -> MaterialTheme.colorScheme.primary
        AdminSession.Guest -> MaterialTheme.colorScheme.tertiary
        AdminSession.None -> MaterialTheme.colorScheme.outline
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        contentColor = color,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            session.chipLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun HubTileRow(tile: HubTile, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            hubTileIcon(tile.route),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(tile.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                tile.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun hubTileIcon(route: String): ImageVector = when (route) {
    "status" -> Icons.Filled.Info
    "settings" -> Icons.Filled.Settings
    "regions" -> Icons.Filled.LocationOn
    "identity" -> Icons.Filled.Lock
    "console" -> Icons.AutoMirrored.Filled.Send
    else -> Icons.AutoMirrored.Filled.List
}
