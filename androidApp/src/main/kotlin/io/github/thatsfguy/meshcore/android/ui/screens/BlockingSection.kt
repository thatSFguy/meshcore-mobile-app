package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.BlockList

/**
 * Blocked senders and hidden channel names — Settings → App.
 * PARITY.md §3 (BlockedChannelSendersScreen).
 *
 * These are presented as two different things because they ARE two
 * different things, and the difference is the point:
 *
 *  - Blocking a contact matches their public key. It holds.
 *  - Hiding a channel name matches text anyone can type. It doesn't.
 *
 * PARITY §3 asks for block-by-key rather than by name. For direct
 * messages that is what this does. For channels it is not possible: a
 * MeshCore group message carries no sender key at all — the name is
 * inside the ciphertext and any holder of the channel PSK can write any
 * name (MESHCORE_PROTOCOL §9/§10). Rather than ship name-matching under
 * the word "block", the two are named for what they can actually
 * promise.
 */
@Composable
fun BlockingSection(vm: MeshCoreViewModel) {
    val blocked by vm.blockedKeys.collectAsState()
    val filtered by vm.filteredChannelNames.collectAsState()
    val contacts by vm.dbContacts.collectAsState()
    var newName by remember { mutableStateOf("") }

    Text("Blocked contacts", style = MaterialTheme.typography.labelLarge)
    HintText(
        "Matched on the public key, so renaming doesn't get around it. Their direct " +
            "messages are dropped as they arrive — not stored and hidden, but never " +
            "written down at all.",
    )
    if (blocked.isEmpty()) {
        HintText("Nobody is blocked. Block someone from their contact sheet.")
    } else {
        for (key in blocked) {
            val name = contacts.firstOrNull { it.keyHex == key }?.name
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name?.ifBlank { null } ?: "(not in contacts)")
                    Text(
                        key.take(16) + "…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.setBlocked(key, false) }) { Text("Unblock") }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("Hidden channel names", style = MaterialTheme.typography.labelLarge)
    // The caveat lives in shared next to the matching code so the two
    // can't drift apart.
    Text(
        BlockList.CHANNEL_FILTER_CAVEAT,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text("Sender name to hide") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { vm.setChannelNameFiltered(newName.trim(), true); newName = "" },
            enabled = BlockList.canonicalName(newName) != null,
        ) { Text("Hide") }
    }
    for (name in filtered) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, Modifier.weight(1f))
            TextButton(onClick = { vm.setChannelNameFiltered(name, false) }) { Text("Show") }
        }
    }
}
