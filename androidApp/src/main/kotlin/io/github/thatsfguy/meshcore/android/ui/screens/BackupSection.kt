package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.storage.ConfigBackupRepository
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.ConfigBackup
import kotlinx.coroutines.launch

/**
 * Config backup / restore — Settings → App. PARITY.md §1.
 *
 * The honest framing, which the copy here has to carry: a backup file
 * has none of the phone's protections. Without a passphrase it holds who
 * you talk to; with one it also holds everything needed to *be* you on
 * this mesh. Both facts are said out loud before anything is written.
 */
@Composable
fun BackupSection(vm: MeshCoreViewModel) {
    val scope = rememberCoroutineScope()
    var exportPrompt by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var importPreview by remember { mutableStateOf<ConfigBackup.Parsed?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch { note = vm.writeBackupFile(uri, text) }
    }

    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val parsed = vm.readBackupFile(uri)
            if (parsed == null) {
                note = "That file isn't a MeshCore Hardened backup (or is from a newer version)."
            } else {
                importPreview = parsed
            }
        }
    }

    HintText(
        "A backup is an ordinary file. It leaves the Keystore and every protection this " +
            "phone gives it, so what goes in is a decision, not a default.",
    )
    ButtonFlowRow {
        TextButton(onClick = { exportPrompt = true }) { Text("Export configuration…") }
        TextButton(onClick = { openFile.launch(arrayOf("*/*")) }) { Text("Import configuration…") }
    }
    note?.let { HintText(it) }

    if (exportPrompt) {
        ExportDialog(
            canEncrypt = vm.supportsBackupEncryption,
            onDismiss = { exportPrompt = false },
            onExport = { passphrase ->
                exportPrompt = false
                scope.launch {
                    val text = vm.buildBackup(passphrase)
                    if (text == null) {
                        note = "Couldn't build the backup."
                    } else {
                        pendingExport = text
                        createFile.launch(ConfigBackupRepository.suggestedFileName(vm.nowSeconds()))
                    }
                }
            },
        )
    }

    importPreview?.let { parsed ->
        ImportDialog(
            parsed = parsed,
            selfKeyHex = vm.selfKeyHex(),
            onDismiss = { importPreview = null },
            onApply = { options, passphrase ->
                importPreview = null
                scope.launch { note = vm.applyBackup(parsed, options, passphrase) }
            },
        )
    }
}

@Composable
private fun ExportDialog(
    canEncrypt: Boolean,
    onDismiss: () -> Unit,
    onExport: (String?) -> Unit,
) {
    var includeSecrets by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val passphraseOk = passphrase.length >= ConfigBackup.MIN_PASSPHRASE_LENGTH &&
        passphrase == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export configuration") },
        text = {
            Column {
                Text(
                    "Always included: your settings, region names, and your contact list — " +
                        "names and public keys. That is a map of who you talk to. Keep the " +
                        "file somewhere you'd keep that.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeSecrets,
                        onCheckedChange = { includeSecrets = it },
                        enabled = canEncrypt,
                    )
                    Text("Include channel keys and passwords")
                }
                if (!canEncrypt) {
                    Text(
                        "This build has no authenticated cipher available, so an encrypted " +
                            "backup can't be written. Settings-only export still works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (includeSecrets) {
                    Text(
                        "These are encrypted with your passphrase and nothing else. Anyone " +
                            "who has the file and guesses the passphrase can read every " +
                            "channel you're in and log into every repeater you administer. " +
                            "There is no recovery if you forget it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Passphrase (min ${ConfigBackup.MIN_PASSPHRASE_LENGTH})") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        isError = confirm.isNotEmpty() && confirm != passphrase,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onExport(if (includeSecrets) passphrase else null) },
                enabled = !includeSecrets || passphraseOk,
            ) { Text(if (includeSecrets) "Export with secrets" else "Export settings only") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Preview before applying. Nothing in a backup is trustworthy just
 * because it parsed, and the radio-side parts are not undoable — so
 * every destination is a separate, off-by-default choice.
 */
@Composable
private fun ImportDialog(
    parsed: ConfigBackup.Parsed,
    selfKeyHex: String,
    onDismiss: () -> Unit,
    onApply: (ConfigBackupRepository.ApplyOptions, String?) -> Unit,
) {
    var settings by remember { mutableStateOf(true) }
    var regions by remember { mutableStateOf(true) }
    var channels by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf(false) }
    var wantSecrets by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }

    val differentRadio = parsed.plain.selfKeyHex.isNotEmpty() &&
        selfKeyHex.isNotEmpty() && parsed.plain.selfKeyHex != selfKeyHex

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore from backup") },
        text = {
            Column {
                Text(
                    "Contains ${parsed.plain.settings.size} setting(s), " +
                        "${parsed.plain.contacts.size} contact(s), " +
                        "${parsed.plain.channels.size} channel(s), " +
                        "${parsed.plain.regions.size} region(s)" +
                        if (parsed.hasSecrets) ", plus an encrypted section." else ".",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (differentRadio) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This backup was made on a different radio. Restoring contacts or " +
                            "channels will write them onto the radio connected now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                CheckRow("Settings", settings) { settings = it }
                CheckRow("Regions", regions) { regions = it }
                CheckRow("Channels (writes to the radio)", channels) { channels = it }
                CheckRow("Contacts (writes to the radio)", contacts) { contacts = it }
                if (parsed.hasSecrets) {
                    CheckRow("Channel keys and passwords", wantSecrets) { wantSecrets = it }
                    if (wantSecrets) {
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("Passphrase") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (channels || contacts) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Writing to the radio can't be undone from here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(
                    ConfigBackupRepository.ApplyOptions(
                        settings = settings,
                        regions = regions,
                        channels = channels,
                        contacts = contacts,
                        secrets = wantSecrets,
                    ),
                    passphrase.ifBlank { null },
                )
            }) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
