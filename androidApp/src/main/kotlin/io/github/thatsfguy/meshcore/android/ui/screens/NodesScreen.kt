package io.github.thatsfguy.meshcore.android.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
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
        result.contents?.let { vm.importContactUri(it) }
    }
    var showSelfQr by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Nodes",
                vm = vm,
                menuActions = listOf(
                    MenuAction("Import contact QR…") {
                        scanLauncher.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Scan a meshcore:// contact QR")
                                .setBeepEnabled(false)
                                .setCaptureActivity(PortraitCaptureActivity::class.java),
                        )
                    },
                    MenuAction("Share my node QR…") { showSelfQr = true },
                    MenuAction("Sync contacts") { vm.syncContactsNow() },
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                scanLauncher.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan a meshcore:// contact QR")
                        .setBeepEnabled(false)
                        .setCaptureActivity(PortraitCaptureActivity::class.java),
                )
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Import contact QR")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Selection persists across tab switches and restarts.
            var tab by remember { mutableIntStateOf(vm.prefs.nodesTab.coerceIn(0, 3)) }
            val discovered by vm.discovered.collectAsState()
            var query by remember { mutableStateOf("") }
            // Contacts tab folds in sensors/unknown types.
            val tabContacts = when (tab) {
                1 -> contacts.filter { it.type == Codes.ADV_TYPE_REPEATER }
                2 -> contacts.filter { it.type == Codes.ADV_TYPE_ROOM }
                else -> contacts.filter {
                    it.type != Codes.ADV_TYPE_REPEATER && it.type != Codes.ADV_TYPE_ROOM
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
                    "Contacts", "Repeaters", "Rooms",
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

            if (tab != 3) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search name or key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            if (tab == 3) {
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
                        ContactRow(c) { detail = c }
                    }
                }
            }
        }
    }

    if (showSelfQr) {
        SelfQrDialog(vm, onDismiss = { showSelfQr = false })
    }

    detail?.let { contact ->
        ContactDetailSheet(
            vm = vm,
            contact = contact,
            onDismiss = { detail = null },
            onOpenChat = {
                detail = null
                nav.navigate("conversation/dm/${contact.keyHex}")
            },
            onOpenAdmin = {
                detail = null
                nav.navigate("repeater/${contact.keyHex}")
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

@Composable
private fun ContactRow(c: ContactEntity, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                    if (c.pathLen >= 0 && c.pathLen < 64) append(" · ${c.pathLen} hop path")
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
) {
    var renameOpen by remember { mutableStateOf(false) }
    var routingOpen by remember { mutableStateOf(false) }
    var removeConfirm by remember { mutableStateOf(false) }
    var shareQrOpen by remember { mutableStateOf(false) }
    val isAdminable = contact.type == Codes.ADV_TYPE_REPEATER || contact.type == Codes.ADV_TYPE_ROOM

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
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
            Text("Public key", style = MaterialTheme.typography.labelLarge)
            Text(
                contact.keyHex,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (contact.latitude != null && contact.longitude != null) {
                Spacer(Modifier.height(8.dp))
                Text("Location", style = MaterialTheme.typography.labelLarge)
                Text(
                    "%.5f, %.5f".format(contact.latitude, contact.longitude),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))

            if (contact.type == Codes.ADV_TYPE_CHAT || contact.type == Codes.ADV_TYPE_ROOM) {
                TextButton(onClick = onOpenChat) { Text("Open conversation") }
            }
            if (isAdminable) {
                TextButton(onClick = onOpenAdmin) { Text("Administer (login / CLI / settings)") }
            }
            val isFav = contact.flags and Codes.CONTACT_FLAG_FAVORITE != 0
            TextButton(onClick = {
                vm.setFavourite(contact.keyHex, !isFav)
                onDismiss()
            }) { Text(if (isFav) "★ Remove favourite" else "☆ Add favourite") }
            TextButton(onClick = { shareQrOpen = true }) { Text("Share contact QR…") }
            TextButton(onClick = { routingOpen = true }) { Text("Routing / paths…") }
            TextButton(onClick = { renameOpen = true }) { Text("Rename") }
            TextButton(onClick = { removeConfirm = true }) {
                Text("Remove contact", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (shareQrOpen) {
        ContactQrDialog(vm, contact, onDismiss = { shareQrOpen = false })
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
        hint = "Scan with another MeshCore app to add this node as a contact. " +
            "The code carries the node's signed advert — the receiving app verifies it.",
        load = { vm.contactShareUri(contact.keyHex) },
        onDismiss = onDismiss,
    )

/**
 * QR for a `meshcore://` share URI produced by the radio. The blob comes
 * from the device rather than being rebuilt locally, so a shared contact
 * keeps its original Ed25519 signature and stays verifiable downstream.
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
