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
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.Retention
import kotlinx.coroutines.launch

/**
 * Retention and purge — Settings → App. PARITY.md §1 (PurgeDataScreen)
 * and §3 (ChannelMessageRetentionSettingsScreen).
 *
 * Both are privacy features wearing storage clothes. The database is
 * encrypted at rest, but the strongest form of "encrypted" is "not
 * there", and these are the two ways to get there.
 */
@Composable
fun DataSection(vm: MeshCoreViewModel) {
    val scope = rememberCoroutineScope()
    var policy by remember { mutableStateOf(vm.prefs.retentionPolicy) }
    var purgeConfirm by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    Text("Message retention", style = MaterialTheme.typography.labelLarge)
    ExpandableHint("Deletes old messages from this phone only, in every conversation.") {
        DetailText(
            "Nothing is deleted from anyone else's device. Trimming counts by when a " +
                "message ARRIVED here, not by the time it claims — a sender's clock " +
                "shouldn't decide which of your messages survive.",
        )
    }
    ChoiceChips(
        options = Retention.PRESETS.map { it.shortLabel() },
        selected = Retention.PRESETS.indexOfFirst { it == policy }.coerceAtLeast(0),
    ) { index ->
        val chosen = Retention.PRESETS[index]
        policy = chosen
        vm.prefs.retentionPolicy = chosen
        scope.launch {
            val removed = vm.applyRetentionNow()
            note = if (removed > 0) {
                "${policy.label()}. Removed $removed message(s) now."
            } else {
                "${policy.label()}. Nothing needed removing."
            }
        }
    }
    note?.let { HintText(it) }

    Spacer(Modifier.height(16.dp))
    Text("Purge local data", style = MaterialTheme.typography.labelLarge)
    HintText(
        "Deletes this phone's copy of everything. It does NOT touch the radio: its " +
            "contact list, channel slots and identity are on the device and stay there.",
    )
    ButtonFlowRow {
        TextButton(onClick = { purgeConfirm = true }) {
            Text("Purge local data…", color = MaterialTheme.colorScheme.error)
        }
    }

    if (purgeConfirm) {
        PurgeDialog(
            onDismiss = { purgeConfirm = false },
            onConfirm = { alsoSecrets ->
                purgeConfirm = false
                scope.launch { note = vm.purgeLocalData(alsoSecrets) }
            },
        )
    }
}

/**
 * Purge needs the list spelled out. "Are you sure?" is not consent when
 * the user can't see what is about to go — especially here, where the
 * message history is the one thing that cannot be re-synced from the
 * radio afterwards.
 */
@Composable
private fun PurgeDialog(onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var typed by remember { mutableStateOf("") }
    var alsoSecrets by remember { mutableStateOf(false) }
    val armed = typed.trim().equals(CONFIRM_WORD, ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Purge local data") },
        text = {
            Column {
                Text("This deletes, from this phone only:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                for (line in listOf(
                    "• Every message, in every conversation",
                    "• The cached contact list and channel names",
                    "• Path history and the discovery inbox",
                    "• Nicknames, pins, mutes, and region labels",
                )) {
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text("It does NOT delete:", style = MaterialTheme.typography.bodyMedium)
                for (line in listOf(
                    "• Anything on the radio — contacts, channels and its identity stay",
                    "• Messages other people received from you",
                )) {
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your message history cannot be recovered — the radio does not keep a " +
                        "copy to re-sync from.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                SettingRow("Also forget stored keys and passwords", alsoSecrets) {
                    alsoSecrets = it
                }
                if (alsoSecrets) {
                    Text(
                        "Channel PSKs, repeater passwords and the saved identity seed go " +
                            "too. Channels you have no other copy of the key for become " +
                            "unreadable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
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
            TextButton(onClick = { onConfirm(alsoSecrets) }, enabled = armed) {
                Text("Purge", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val CONFIRM_WORD = "PURGE"

/** Compact chip label — the full sentence lives in the hint. */
private fun Retention.Policy.shortLabel(): String = when {
    !isBounded -> "Forever"
    mode == Retention.Mode.Days && effectiveValue >= 365 -> "1 year"
    mode == Retention.Mode.Days -> "${effectiveValue}d"
    else -> "$effectiveValue msg"
}
