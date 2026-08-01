package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.IdentityKey
import kotlinx.coroutines.launch

/**
 * A repeater's identity key (PARITY.md §6 — the row it calls the
 * highest-consequence screen in the app).
 *
 * Three deliberate refusals shape this:
 *
 *  1. **The key is never shown unasked.** Reading it puts it back on
 *     the air; that is a decision with its own confirmation, not a side
 *     effect of opening a settings tab.
 *  2. **It is never stored.** The app will not offer to remember
 *     another node's private key. Repeater *passwords* go in the
 *     Keystore because the user has to type them constantly; an
 *     identity key is typed once and belongs in whatever the user uses
 *     for backups.
 *  3. **Changing it takes a typed confirmation**, after the
 *     consequences are listed, because nothing about the result is
 *     reversible or even visible — the node simply becomes a stranger
 *     to everyone who knew it.
 */
@Composable
fun RepeaterIdentityPanel(vm: MeshCoreViewModel, keyHex: String, isAdmin: Boolean) {
    if (!isAdmin) {
        HintText("Log in as admin to manage this node's identity key.")
        return
    }
    val scope = rememberCoroutineScope()
    var revealed by remember(keyHex) { mutableStateOf<String?>(null) }
    var revealConfirm by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var changeConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    Text("Identity key", style = MaterialTheme.typography.titleSmall)
    HintText(
        "This node's Ed25519 private key. The key IS the node: everything anyone knows " +
            "about this repeater's identity is derived from it.",
    )

    ButtonFlowRow {
        TextButton(enabled = !busy, onClick = { revealConfirm = true }) { Text("Read key…") }
        TextButton(
            enabled = !busy,
            onClick = { newKey = vm.generateIdentityKey() },
        ) { Text("Generate a new one") }
    }
    if (busy) SectionSpinner("Talking to the node…")
    note?.let { HintText(it) }

    revealed?.let { key ->
        Spacer(Modifier.height(8.dp))
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error,
        )
        HintText(
            "Write this somewhere safe. It is not stored by this app, and there is no " +
                "way to recover it from the mesh if the node is lost.",
        )
        ButtonFlowRow {
            TextButton(onClick = { revealed = null }) { Text("Hide") }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("Replace the key", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = newKey,
        onValueChange = { newKey = it },
        label = { Text("New private key (64 hex characters)") },
        singleLine = true,
        isError = newKey.isNotBlank() && !IdentityKey.isValidHex(newKey),
        modifier = Modifier.fillMaxWidth(),
    )
    if (newKey.isNotBlank()) {
        when {
            !IdentityKey.isValidHex(newKey) ->
                HintText("A key is exactly 64 hex characters (32 bytes).")
            IdentityKey.isDegenerate(newKey) -> Text(
                "That key is all one repeated byte. It is structurally valid and " +
                    "cryptographically worthless — anyone can reproduce it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> vm.publicKeyFor(newKey)?.let { pub ->
                HintText("This node would become $pub")
            }
        }
    }
    ButtonFlowRow {
        TextButton(
            enabled = !busy && IdentityKey.isValidHex(newKey) && !IdentityKey.isDegenerate(newKey),
            onClick = { changeConfirm = true },
        ) { Text("Replace identity key…", color = MaterialTheme.colorScheme.error) }
    }

    if (revealConfirm) {
        AlertDialog(
            onDismissRequest = { revealConfirm = false },
            title = { Text("Read the identity key?") },
            text = { Text(IdentityKey.REVEAL_CAVEAT) },
            confirmButton = {
                TextButton(onClick = {
                    revealConfirm = false
                    busy = true
                    note = null
                    scope.launch {
                        val reply = vm.readIdentityKey(keyHex)
                        revealed = reply
                        if (reply == null) note = "No reply, or the node refused."
                        busy = false
                    }
                }) { Text("Read it", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { revealConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (changeConfirm) {
        ReplaceKeyDialog(
            onDismiss = { changeConfirm = false },
            onConfirm = {
                changeConfirm = false
                busy = true
                note = null
                scope.launch {
                    note = vm.replaceIdentityKey(keyHex, newKey)
                    newKey = ""
                    busy = false
                }
            },
        )
    }
}

@Composable
private fun ReplaceKeyDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val armed = typed.trim().equals(CONFIRM_WORD, ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace this node's identity?") },
        text = {
            Column {
                for (line in IdentityKey.CHANGE_CONSEQUENCES) {
                    Text("• $line", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type $CONFIRM_WORD to confirm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = armed) {
                Text("Replace", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val CONFIRM_WORD = "REPLACE"
