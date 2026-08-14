package io.github.thatsfguy.meshcore.android.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.thatsfguy.meshcore.android.platform.PortraitCaptureActivity
import io.github.thatsfguy.meshcore.android.platform.Qr
import io.github.thatsfguy.meshcore.android.storage.ContactEntity
import io.github.thatsfguy.meshcore.android.storage.MessageRepository
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.presentation.encodePrefill
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.thatsfguy.meshcore.util.RelativeTime
import io.github.thatsfguy.meshcore.protocol.BlockList
import io.github.thatsfguy.meshcore.protocol.PathCodec
import io.github.thatsfguy.meshcore.protocol.Codes
import java.text.DateFormat
import java.util.Date

/**
 * Contacts/nodes list grouped by type, QR import via the + FAB, and the
 * contact detail sheet (pubkey, QR share, rename, reset path, remove;
 * repeaters/rooms add the admin entry point). SCOPE.md "Contacts /
 * nodes" + "Repeater / room administration" entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(vm: MeshCoreViewModel, nav: NavController) {
    val contacts by vm.dbContacts.collectAsState()
    var detail by remember { mutableStateOf<ContactEntity?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.importScannedCode(it) }
    }
    var showSelfQr by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Nodes",
                vm = vm,
                menuActions = listOf(
                    MenuAction("Import contact QR…") {
                        scanLauncher.launch(
                            meshScanOptions("Scan a MeshCore QR — contact, channel or community"),
                        )
                    },
                    // The camera is not the only way a code arrives, and
                    // for contacts shared between clients it is not even
                    // the usual one — they get copied as a link. Same
                    // decoder, same confirmations; only the way in is new.
                    MenuAction("Paste a code") {
                        vm.importPastedText(clipboard.getText()?.text)
                    },
                    MenuAction("Share my node QR…") { showSelfQr = true },
                    MenuAction("Sync contacts") { vm.syncContactsNow() },
                    // Active discovery (PARITY §2): a broadcast asking
                    // nearby repeaters to speak up, as opposed to the
                    // passive advert inbox on the New tab.
                    MenuAction("Discover nearby repeaters") { discovering = true },
                    // The other direction from "Discover": not who is
                    // out there, but who is carrying MY traffic.
                    MenuAction("Who repeats me") { nav.navigate(HEARD_REPEATS_ROUTE) },
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                scanLauncher.launch(
                    meshScanOptions("Scan a MeshCore QR — contact, channel or community"),
                )
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Import contact QR")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Selection persists across tab switches and restarts.
            var tab by remember { mutableIntStateOf(vm.prefs.nodesTab.coerceIn(0, 4)) }
            val discovered by vm.discovered.collectAsState()
            var query by remember { mutableStateOf("") }
            // Contacts tab folds in sensors/unknown types.
            val tabContacts = when (tab) {
                1 -> contacts.filter { it.type == Codes.ADV_TYPE_REPEATER }
                2 -> contacts.filter { it.type == Codes.ADV_TYPE_ROOM }
                3 -> contacts.filter { it.type == Codes.ADV_TYPE_SENSOR }
                else -> contacts.filter {
                    it.type != Codes.ADV_TYPE_REPEATER && it.type != Codes.ADV_TYPE_ROOM &&
                        it.type != Codes.ADV_TYPE_SENSOR
                }
            }.filter { c ->
                query.isBlank() ||
                    c.name.contains(query, ignoreCase = true) ||
                    c.keyHex.startsWith(query.lowercase())
            }.sortedWith(
                // Favourites first, then nodes you've interacted with
                // (messaged / administered) by recency, then the rest A-Z.
                compareByDescending<ContactEntity> {
                    it.flags and Codes.CONTACT_FLAG_FAVORITE != 0
                }
                    .thenByDescending { it.lastMessageAt > 0 }
                    .thenByDescending { it.lastMessageAt }
                    .thenBy { it.name.ifBlank { it.keyHex }.lowercase() },
            )

            // ScrollableTabRow, not TabRow: a fixed row divides the width
            // evenly and WRAPS labels ("Repeat/ers") once the user raises
            // the font or display size. Scrollable tabs size to their text
            // and scroll if they overflow, so the user's accessibility
            // setting is respected rather than fought.
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                val labels = listOf(
                    "Contacts", "Repeaters", "Rooms", "Sensors",
                    if (discovered.isEmpty()) "New" else "New (${discovered.size})",
                )
                for ((i, label) in labels.withIndex()) {
                    Tab(
                        selected = tab == i,
                        onClick = {
                            tab = i
                            vm.prefs.nodesTab = i
                        },
                        text = { Text(label, maxLines = 1, softWrap = false) },
                    )
                }
            }

            if (tab != 4) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search name or key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            if (tab == 4) {
                // Discovery inbox — heard over the air, not yet contacts.
                if (discovered.isEmpty()) {
                    EmptyHint(
                        text = "Nothing new heard yet.\nNodes whose signed adverts reach this radio " +
                            "but aren't in its contact list show up here.",
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(discovered, key = { it.keyHex }) { d ->
                            DiscoveredRow(
                                node = d,
                                onAdd = { vm.addDiscovered(d.keyHex) },
                                onDismiss = { vm.dismissDiscovered(d.keyHex) },
                            )
                        }
                        item(key = "clear_all") {
                            TextButton(onClick = { vm.clearDiscovered() }) {
                                Text("Dismiss all", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            } else if (tabContacts.isEmpty()) {
                EmptyHint(
                    text = when (tab) {
                        1 -> "No repeaters heard yet."
                        2 -> "No room servers heard yet."
                        else -> "No contacts yet.\nContacts appear when nearby nodes advertise, or scan a contact QR with +."
                    },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(tabContacts, key = { it.keyHex }) { c ->
                        // Infrastructure rows go straight to administration —
                        // the reference client does this
                        // (`contacts_screen._showRepeaterLogin`), and driving
                        // both on hardware put us two taps and a scroll behind
                        // it on the commonest admin task: the detail sheet sat
                        // in the way and "Administer" was below the fold.
                        // Long-press still opens the sheet for the things that
                        // live there (routing, rename, favourite, QR).
                        val adminable = c.type == Codes.ADV_TYPE_REPEATER ||
                            c.type == Codes.ADV_TYPE_ROOM ||
                            c.type == Codes.ADV_TYPE_SENSOR
                        ContactRow(
                            c = c,
                            onClick = {
                                if (adminable) nav.navigate("repeater/${c.keyHex}") else detail = c
                            },
                            onLongClick = { detail = c },
                        )
                    }
                }
            }
        }
    }

    if (showSelfQr) {
        SelfQrDialog(vm, onDismiss = { showSelfQr = false })
    }
    if (discovering) {
        DiscoverNodesDialog(vm, nav, onDismiss = { discovering = false })
    }

    detail?.let { contact ->
        ContactDetailSheet(
            vm = vm,
            contact = contact,
            onDismiss = { detail = null },
            onOpenChat = {
                detail = null
                nav.navigate(conversationRoute(MessageRepository.KIND_DM, contact.keyHex))
            },
            onOpenAdmin = {
                detail = null
                nav.navigate("repeater/${contact.keyHex}")
            },
            // Straight to the transfer, using the address the node
            // announced. A node stuck in update mode cannot be reached
            // through its admin session — that route needs the mesh,
            // and it left the mesh.
            onFlashFirmware = {
                detail = null
                val role = if (contact.type == Codes.ADV_TYPE_ROOM) "room" else "repeater"
                // Everything known about the node travels with the
                // route; what is not known is scanned for. The node key
                // is always present, so the screen can read the board
                // and address from the contact record as they arrive.
                nav.navigate(
                    "firmware/node?role=$role&node=${contact.keyHex}" +
                        (contact.otaAddress?.let { "&mac=$it" } ?: "") +
                        (contact.boardName?.let { "&board=${encodePrefill(it)}" } ?: ""),
                )
            },
        )
    }
}

@Composable
private fun DiscoveredRow(
    node: io.github.thatsfguy.meshcore.android.storage.DiscoveredEntity,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NodeAvatar(
            seed = node.keyHex,
            label = node.name.ifBlank { node.keyHex },
            type = node.type,
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.name.ifBlank { node.keyHex.take(12) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                "${typeLabel(node.type).dropLast(1)} · ${"%.1f".format(node.snr)} dB · " +
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(node.lastHeardAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAdd) { Text("Add") }
        TextButton(onClick = onDismiss) { Text("×", color = MaterialTheme.colorScheme.error) }
    }
}

private fun typeLabel(type: Int): String = when (type) {
    Codes.ADV_TYPE_CHAT -> "Contacts"
    Codes.ADV_TYPE_REPEATER -> "Repeaters"
    Codes.ADV_TYPE_ROOM -> "Room servers"
    Codes.ADV_TYPE_SENSOR -> "Sensors"
    else -> "Other"
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    c: ContactEntity,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NodeAvatar(
            seed = c.keyHex,
            label = c.name.ifBlank { c.keyHex },
            type = c.type,
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (c.flags and Codes.CONTACT_FLAG_FAVORITE != 0) "★ " else "") +
                    c.name.ifBlank { c.keyHex.take(12) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(c.keyHex.take(12))
                    val info = PathCodec.decodePathLen(c.pathLen)
                    if (!info.isFlood) append(" · ${info.hops} hop path")
                    else append(" · flood")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (c.lastSeen > 0) {
            Text(
                DateFormat.getDateInstance(DateFormat.SHORT).format(Date(c.lastSeen * 1000)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailSheet(
    vm: MeshCoreViewModel,
    contact: ContactEntity,
    onDismiss: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenAdmin: () -> Unit,
    onFlashFirmware: () -> Unit = {},
) {
    var renameOpen by remember { mutableStateOf(false) }
    var routingOpen by remember { mutableStateOf(false) }
    var removeConfirm by remember { mutableStateOf(false) }
    var shareQrOpen by remember { mutableStateOf(false) }
    var updateModeOpen by remember { mutableStateOf(false) }
    var updateModeNote by remember { mutableStateOf<String?>(null) }
    var updateModeBusy by remember { mutableStateOf(false) }
    var permissionsOpen by remember { mutableStateOf(false) }
    var telemetryOpen by remember { mutableStateOf(false) }
    // Sensors run the same CLI (PARITY §7): login, settings, telemetry.
    val isAdminable = contact.type == Codes.ADV_TYPE_REPEATER ||
        contact.type == Codes.ADV_TYPE_ROOM ||
        contact.type == Codes.ADV_TYPE_SENSOR
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val live = vm.liveContacts.collectAsState().value[contact.keyHex]
    val self = vm.selfInfo.collectAsState().value
    val hashWidth = vm.deviceInfo.collectAsState().value?.pathHashByteWidth

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                contact.name.ifBlank { "Unnamed node" },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                typeLabel(contact.type).dropLast(1),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Public key", style = MaterialTheme.typography.labelLarge)
                    Text(
                        contact.keyHex,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(contact.keyHex))
                }) { Text("Copy") }
            }

            if (contact.latitude != null && contact.longitude != null) {
                Spacer(Modifier.height(8.dp))
                DetailRow("Position", "%.5f, %.5f".format(contact.latitude, contact.longitude))
                // Distance is only meaningful once BOTH ends have a
                // position; the radio reports 0,0 when it has no fix, and
                // treating that as the equator would invent a distance.
                val selfLat = self?.latitude?.takeIf { it != 0.0 }
                val selfLon = self?.longitude?.takeIf { it != 0.0 }
                val theirLat = contact.latitude.takeIf { it != 0.0 }
                val theirLon = contact.longitude.takeIf { it != 0.0 }
                val distance = if (selfLat != null && selfLon != null &&
                    theirLat != null && theirLon != null
                ) {
                    formatDistance(haversineMetres(selfLat, selfLon, theirLat, theirLon))
                } else {
                    "Unknown"
                }
                DetailRow("Distance away", distance)
            }

            if (contact.lastSeen > 0) {
                DetailRow(
                    "Last advert heard",
                    relativeAge(contact.lastSeen) + " · " +
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(contact.lastSeen * 1000)),
                )
            }

            // Path, mirroring what the radio actually holds for this
            // contact rather than what we last asked for.
            val pathInfo = PathCodec.decodePathLen(live?.pathLen ?: contact.pathLen)
            DetailRow(
                "Hops away",
                when {
                    pathInfo.isFlood -> "Flood (no stored path)"
                    pathInfo.hops == 0 -> "Direct (0 hops)"
                    pathInfo.hops == 1 -> "1 hop"
                    else -> "${pathInfo.hops} hops"
                },
            )
            live?.storedPath?.takeIf { it.isNotEmpty() }?.let { bytes ->
                val known = vm.liveContacts.collectAsState().value
                    .mapValues { (_, c) -> c.name }
                val hops = PathCodec.resolveHops(bytes, pathInfo.hashWidth, known)
                Column(Modifier.padding(top = 8.dp)) {
                    Text("Out path", style = MaterialTheme.typography.labelLarge)
                    for ((index, hop) in hops.withIndex()) {
                        Text(
                            "${index + 1}. ${hop.label}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (hop.isResolved) null else FontFamily.Monospace,
                            color = if (hop.isAmbiguous) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        // A two-byte hash is 16 bits: more than one node
                        // can own it, so list the candidates rather than
                        // asserting one of them is the hop.
                        if (hop.isAmbiguous) {
                            Text(
                                "   " + hop.candidates.joinToString(" or "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            // The contact's own record says how wide ITS hashes are; the
            // device setting only applies to new paths.
            val width = if (pathInfo.isFlood) hashWidth else pathInfo.hashWidth
            width?.let { DetailRow("Path hash size", "$it-byte${if (it == 1) "" else "s"} per hop") }

            Spacer(Modifier.height(16.dp))

            if (contact.type == Codes.ADV_TYPE_CHAT || contact.type == Codes.ADV_TYPE_ROOM) {
                TextButton(onClick = onOpenChat) { Text("Open conversation") }
            }
            if (isAdminable) {
                TextButton(onClick = onOpenAdmin) { Text("Administer this node") }
            }
            // Recovery lives here, on the node, because this is where
            // someone looks for it — and because everything else that
            // reaches a repeater goes through the mesh, which is the one
            // thing a node in update mode no longer has.
            //
            // Offered whether or not an address was recorded: a node can
            // enter update mode from a session this app never saw, and
            // "no address stored" is the case that most needs a way in,
            // not the case to withhold one from.
            if (isAdminable) {
                // Labelled for what it is. "Update-mode address" read as
                // a statement about the node's current state; it is the
                // node's Bluetooth address, learned in update mode and
                // true whatever the node is doing now.
                contact.otaAddress?.let { DetailRow("Bluetooth address", it) }
                TextButton(onClick = { updateModeOpen = true }) {
                    Text("Recover / firmware…")
                }
            }
            val isFav = contact.flags and Codes.CONTACT_FLAG_FAVORITE != 0
            TextButton(onClick = {
                vm.setFavourite(contact.keyHex, !isFav)
                onDismiss()
            }) { Text(if (isFav) "★ Remove favourite" else "☆ Add favourite") }
            TextButton(onClick = { permissionsOpen = !permissionsOpen }) {
                Text(if (permissionsOpen) "Hide permissions" else "Permissions…")
            }
            if (permissionsOpen) {
                ContactPermissions(vm, contact)
            }
            TextButton(onClick = { shareQrOpen = true }) { Text("Share contact QR…") }
            TextButton(onClick = { telemetryOpen = true }) { Text("Telemetry…") }
            TextButton(onClick = { routingOpen = true }) { Text("Routing / paths…") }
            TextButton(onClick = { renameOpen = true }) { Text("Rename") }
            // Blocking is local and key-based: it drops their direct
            // messages before they are written down. Removing the
            // contact does NOT block them — the radio would happily
            // re-add them on the next advert.
            //
            // Offered only where there is something to block. It used to
            // appear on every node, including repeaters, where it could
            // not do the thing its name promised (relayed traffic
            // carries the original sender's key) and did do one thing
            // nobody wanted: swallow the node's console replies.
            // ...but never hidden while a block is in force, or a
            // repeater blocked by an older build would have no way back.
            // Hiding the only "Unblock" is how a setting becomes
            // permanent by accident.
            val blocked = vm.isBlocked(contact.keyHex)
            if (BlockList.isBlockableNodeType(contact.type) || blocked) {
                TextButton(onClick = { vm.setBlocked(contact.keyHex, !blocked) }) {
                    Text(
                        if (blocked) "Unblock" else "Block",
                        color = if (blocked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            TextButton(onClick = { removeConfirm = true }) {
                Text("Remove contact", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (updateModeOpen) {
        val otaAddress = contact.otaAddress
        AlertDialog(
            onDismissRequest = { if (!updateModeBusy) updateModeOpen = false },
            title = { Text("Recover ${contact.name.ifBlank { "this node" }}") },
            text = {
                Column {
                    Text(
                        if (otaAddress != null) {
                            "This node announced $otaAddress when it was told to update, so " +
                                "it can be identified exactly."
                        } else {
                            "No update-mode address was recorded for this node, so it will " +
                                "be identified by what it advertises — and named before " +
                                "anything is written to it."
                        } +
                            "\n\nA node in update mode is off the mesh and can only be " +
                            "reached over Bluetooth, from within range of it.\n\n" +
                            "\"Restart it\" asks its bootloader to boot the firmware it " +
                            "already has. That works when the transfer never got as far as " +
                            "erasing anything — the usual case for an update that failed " +
                            "early. If the firmware was already erased it will stay in " +
                            "update mode, which is not a failure of this button: the node " +
                            "then needs flashing rather than restarting.",
                    )
                    updateModeNote?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !updateModeBusy,
                    onClick = {
                        updateModeBusy = true
                        updateModeNote = "Looking for the node…"
                        vm.exitUpdateMode(contact.keyHex) { result ->
                            updateModeNote = result
                            updateModeBusy = false
                        }
                    },
                ) { Text(if (updateModeBusy) "Working…" else "Restart it") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !updateModeBusy,
                        onClick = {
                            updateModeOpen = false
                            onFlashFirmware()
                        },
                    ) { Text("Flash it…") }
                    TextButton(enabled = !updateModeBusy, onClick = { updateModeOpen = false }) {
                        Text("Close")
                    }
                }
            },
        )
    }

    if (shareQrOpen) {
        ContactQrDialog(vm, contact, onDismiss = { shareQrOpen = false })
    }
    if (telemetryOpen) {
        ContactTelemetryDialog(vm, contact, onDismiss = { telemetryOpen = false })
    }
    if (routingOpen) {
        RoutingSheet(vm, contact, onDismiss = { routingOpen = false })
    }

    if (renameOpen) {
        var name by remember { mutableStateOf(contact.name) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename contact") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameContact(contact, name.trim())
                    renameOpen = false
                    onDismiss()
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (removeConfirm) {
        AlertDialog(
            onDismissRequest = { removeConfirm = false },
            title = { Text("Remove contact?") },
            text = { Text("Removes ${contact.name.ifBlank { contact.keyHex.take(12) }} from the radio's contact list.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeContact(contact.keyHex)
                    removeConfirm = false
                    onDismiss()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removeConfirm = false }) { Text("Cancel") }
            },
        )
    }
}


/** One label/value line in the contact sheet. */
@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

/** Great-circle distance in metres. */
private fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return 2 * r * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}

private fun formatDistance(metres: Double): String = when {
    metres < 1000 -> "%.0f m".format(metres)
    metres < 100_000 -> "%.1f km".format(metres / 1000)
    else -> "%.0f km".format(metres / 1000)
}

/** "9 hours ago" — wording shared with the heard-repeats list. */
private fun relativeAge(epochSeconds: Long): String =
    RelativeTime.ago(System.currentTimeMillis() / 1000 - epochSeconds)


/**
 * Telemetry published by a node (Cayenne LPP over a binary request).
 *
 * The node decides whether to answer at all: telemetry is permissioned
 * on its side, so silence is a normal outcome, not an error — say that
 * rather than showing a failure.
 */
@Composable
private fun ContactTelemetryDialog(
    vm: MeshCoreViewModel,
    contact: ContactEntity,
    onDismiss: () -> Unit,
) {
    var readings by remember {
        mutableStateOf<List<io.github.thatsfguy.meshcore.protocol.TelemetryReading>?>(null)
    }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(contact.keyHex, attempt) {
        loading = true
        readings = vm.repeaterTelemetry(contact.keyHex)
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Telemetry", maxLines = 1) },
        text = {
            Column {
                Text(
                    contact.name.ifBlank { contact.keyHex.take(12) },
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> Text("Asking the node…", style = MaterialTheme.typography.bodySmall)
                    readings.isNullOrEmpty() -> Text(
                        "No reply. The node may publish no telemetry, or may not grant " +
                            "this device permission to read it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> for (r in readings!!) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                r.label,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatReading(r.value) + (if (r.unit.isBlank()) "" else " ${r.unit}"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (!loading) {
            {
                TextButton(onClick = { attempt++ }) { Text("Retry") }
            }
        } else {
            null
        },
    )
}

/** Trim trailing zeros: "23.0" reads better than "23.000000". */
private fun formatReading(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)

/** Self-share QR dialog (used from Settings). */
@Composable
fun SelfQrDialog(vm: MeshCoreViewModel, onDismiss: () -> Unit) =
    ShareQrDialog(
        title = "Share this node",
        hint = "Scan with another MeshCore app to add this node as a contact.",
        load = { vm.selfShareUri() },
        onDismiss = onDismiss,
    )

/** Share an existing contact so someone else can add the same node. */
@Composable
fun ContactQrDialog(vm: MeshCoreViewModel, contact: ContactEntity, onDismiss: () -> Unit) =
    ShareQrDialog(
        title = "Share ${contact.name.ifBlank { "contact" }}",
        hint = "Scan with any MeshCore app to add this node as a contact.",
        load = { vm.contactShareUri(contact) },
        onDismiss = onDismiss,
    )

/**
 * Confirmation for a scanned contact card.
 *
 * A card is unsigned — the name is whatever the sender typed, and only
 * the public key identifies anyone — so the app shows the key in full
 * and makes the user accept it rather than adding the contact silently.
 */
@Composable
internal fun ContactCardConfirmDialog(
    card: io.github.thatsfguy.meshcore.protocol.ShareUri.Decoded.Contact,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add contact?") },
        text = {
            Column {
                Text(
                    card.name.ifBlank { "(unnamed)" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    typeLabel(card.type).dropLast(1),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text("Public key", style = MaterialTheme.typography.labelLarge)
                Text(
                    card.pubKeyHex,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "This code carries no signature — the name is whatever " +
                        "the sender typed. Only add it if you scanned it from " +
                        "someone you trust, and check the key matches theirs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Add contact") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * QR for a `meshcore://contact/add?…` card — the form the mainstream
 * MeshCore app scans. Built from local state, so it works whether or not
 * the radio is answering.
 */
@Composable
private fun ShareQrDialog(
    title: String,
    hint: String,
    load: suspend () -> String?,
    onDismiss: () -> Unit,
) {
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var uri by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    LaunchedEffect(Unit) {
        val text = load()
        if (text != null) {
            uri = text
            qr = Qr.encode(text)
        } else {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                qr?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = "Contact QR",
                        modifier = Modifier.size(280.dp),
                    )
                    Text(hint, style = MaterialTheme.typography.bodySmall)
                }
                if (failed) Text("Radio didn't return an export blob — is it connected?")
                if (qr == null && !failed) Text("Requesting export from radio…")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            uri?.let { text ->
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                }) { Text("Copy link") }
            }
        },
    )
}

/**
 * Per-contact permissions (PARITY §2, `ContactPermissionsScreen`).
 *
 * These are the CONTACT_FLAG_TELE_* bits on the radio's contact record:
 * which parts of THIS node's telemetry that contact may read. They only
 * take effect when the matching global policy is set to "Flags" — a
 * per-contact grant cannot widen a global Deny, and a switch that
 * silently does nothing is worse than no switch, so the state is said
 * out loud.
 */
@Composable
private fun ContactPermissions(
    vm: io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel,
    contact: io.github.thatsfguy.meshcore.android.storage.ContactEntity,
) {
    val self by vm.selfInfo.collectAsState()
    val modes = self?.telemetryModes ?: 0
    val live by vm.liveContacts.collectAsState()
    val flags = live[contact.keyHex]?.flags ?: contact.flags

    HintText(
        "Which of YOUR telemetry this contact may read. Also mute their notifications " +
            "here — that part is local and never leaves this phone.",
    )
    for ((label, flag, shift) in listOf(
        Triple("Battery", Codes.CONTACT_FLAG_TELE_BASE, 0),
        Triple("Location", Codes.CONTACT_FLAG_TELE_LOC, 2),
        Triple("Environment", Codes.CONTACT_FLAG_TELE_ENV, 4),
    )) {
        val globalMode = (modes shr shift) and 0x03
        SettingRow("$label telemetry", flags and flag != 0) {
            vm.setContactTelemetryFlag(contact.keyHex, flag, it)
        }
        // 0 = Deny everyone, 1 = per-contact flags, 2 = allow all.
        HintText(
            when (globalMode) {
                1 -> "Global policy is Flags — this switch decides."
                2 -> "Global policy is All: everyone can read this regardless of the switch."
                else -> "Global policy is Deny: nobody can read this, switch or not."
            },
        )
    }

    var muted by remember(contact.keyHex) {
        mutableStateOf(vm.prefs.isContactMuted(contact.keyHex))
    }
    SettingRow("Mute notifications", muted) {
        muted = it
        vm.prefs.setContactMuted(contact.keyHex, it)
    }
}

/**
 * Active discovery (PARITY §2, `DiscoverScreen`/`DiscoverNodesScreen`).
 *
 * A broadcast asking nearby repeaters to answer, as opposed to the
 * passive advert inbox on the New tab. Responders identify themselves
 * with a public-key PREFIX, which is not an identity (PARITY §12) — so
 * this only reports contacts already known, and says plainly that a
 * silent node is not necessarily an absent one.
 */
@Composable
private fun DiscoverNodesDialog(
    vm: MeshCoreViewModel,
    nav: NavController,
    onDismiss: () -> Unit,
) {
    var found by remember { mutableStateOf<List<ContactEntity>?>(null) }
    LaunchedEffect(Unit) { found = vm.discoverNodes() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nearby repeaters") },
        text = {
            val list = found
            Column {
                when {
                    list == null -> SectionSpinner("Listening for answers…")
                    list.isEmpty() -> Text(
                        "Nothing answered. That can mean none are in range, that they " +
                            "answered with a key prefix matching no contact you hold, or " +
                            "that they run firmware without discovery.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> {
                        Text(
                            "Answered within range. These are contacts you already hold " +
                                "whose key matched a responder's prefix — a prefix is not " +
                                "proof of identity.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        for (contact in list) {
                            TextButton(onClick = {
                                onDismiss()
                                nav.navigate("repeater/${contact.keyHex}")
                            }) {
                                Text(contact.name.ifBlank { contact.keyHex.take(12) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
