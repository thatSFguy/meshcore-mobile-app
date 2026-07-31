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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
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
        if (contacts.isEmpty()) {
            EmptyHint(
                modifier = Modifier.padding(padding),
                text = "No contacts yet.\nContacts appear when nearby nodes advertise, or scan a contact QR with +.",
            )
        } else {
            val groups = contacts.groupBy { it.type }
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                for ((type, group) in groups.toSortedMap()) {
                    item(key = "header_$type") {
                        Text(
                            typeLabel(type),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(group, key = { it.keyHex }) { c ->
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
                c.name.ifBlank { c.keyHex.take(12) },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                buildString {
                    append(c.keyHex.take(12))
                    if (c.pathLen >= 0 && c.pathLen < 64) append(" · ${c.pathLen} hop path")
                    else append(" · flood")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    var removeConfirm by remember { mutableStateOf(false) }
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
            TextButton(onClick = { renameOpen = true }) { Text("Rename") }
            TextButton(onClick = { vm.resetPath(contact.keyHex); onDismiss() }) {
                Text("Reset path")
            }
            TextButton(onClick = { removeConfirm = true }) {
                Text("Remove contact", color = MaterialTheme.colorScheme.error)
            }
        }
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
fun SelfQrDialog(vm: MeshCoreViewModel, onDismiss: () -> Unit) {
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val uri = vm.selfShareUri()
        if (uri != null) qr = Qr.encode(uri) else failed = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share this node") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                qr?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = "Contact QR",
                        modifier = Modifier.size(280.dp),
                    )
                    Text(
                        "Scan with another MeshCore app to add this node as a contact.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (failed) Text("Radio didn't return an export blob — is it connected?")
                if (qr == null && !failed) Text("Requesting export from radio…")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
