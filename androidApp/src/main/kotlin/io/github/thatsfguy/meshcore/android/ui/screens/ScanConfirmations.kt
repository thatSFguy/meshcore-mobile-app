package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel

/**
 * The confirmations for anything scanned, hosted once at the app root.
 *
 * These lived inside NodesScreen, which meant they only existed while
 * that screen was composed. A code scanned from the Chats button set the
 * pending state and nothing ever drew it — the scan silently did
 * nothing, for contact cards, channel shares and settings codes alike.
 * Same failure as the old "Show route on map": a flag set for a screen
 * that was not listening.
 *
 * Nothing here applies anything on its own. Each dialog states what will
 * change and waits, because none of these codes is authenticated and one
 * of them retunes the radio.
 */
@Composable
fun ScanConfirmations(vm: MeshCoreViewModel) {
    val pendingChannel by vm.pendingChannelShare.collectAsState()
    pendingChannel?.let { share ->
        AlertDialog(
            onDismissRequest = { vm.dismissChannelShare() },
            title = { Text("Join this channel?") },
            text = {
                Column {
                    Text(
                        share.name.ifBlank { "(unnamed channel)" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // A scope is a routing decision, so it is stated
                    // BEFORE the join rather than reported after it —
                    // joining may add a region the user does not have,
                    // and that is not something to discover in a
                    // snackbar.
                    if (share.regionScope.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Scoped to region ${share.regionScope} — messages here will " +
                                "only be flooded within it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (share.rawRegionScope.isNotBlank()) {
                        // The code asked for a scope this build can't
                        // use. Saying nothing here is what made the
                        // dropped scope invisible: the dialog looked
                        // exactly like an unscoped channel's, and the
                        // join reported plain success.
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "This code asks for region \"${share.rawRegionScope}\", which this " +
                                "app can't use. Joining anyway means messages here flood the " +
                                "whole mesh — wider than the person sharing it intended.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "This code carries the channel's secret key, so joining lets you " +
                            "read everything on it — and everyone else with the key can read " +
                            "what you send. Channel traffic is obfuscated (AES-ECB with a " +
                            "2-byte MAC), not secure.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmChannelShare(share) }) { Text("Join channel") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissChannelShare() }) { Text("Cancel") }
            },
        )
    }

    val pendingCard by vm.pendingContactCard.collectAsState()
    pendingCard?.let { card ->
        ContactCardConfirmDialog(
            card = card,
            onConfirm = { vm.confirmContactCard(card) },
            onDismiss = { vm.dismissContactCard() },
        )
    }

    // A scanned settings code. This dialog is the whole safety story:
    // nothing about the code is authenticated, and applying it decides
    // whether this radio is on a mesh at all — and what frequency it
    // transmits on, which is a legal question wherever you are standing.
    // So every value is shown, and the regulatory caveat comes with it.
    val pendingRadio by vm.pendingRadioConfig.collectAsState()
    pendingRadio?.let { config ->
        AlertDialog(
            onDismissRequest = { vm.pendingRadioConfig.value = null },
            title = { Text("Apply these radio settings?") },
            text = {
                Column {
                    Text(
                        config.name.ifBlank { "(unnamed mesh)" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(config.summary(), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "This retunes your radio. If these values are wrong you will not " +
                            "see errors — you will see an empty mesh.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Anyone can make one of these codes; nothing in it is signed. " +
                            io.github.thatsfguy.meshcore.protocol.RadioPresets.REGULATORY_CAVEAT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    config.region?.let {
                        Spacer(Modifier.height(8.dp))
                        // Named separately because it is a different
                        // failure: wrong radio values make you deaf,
                        // a wrong region leaves you audible but unable
                        // to propagate.
                        Text(
                            "It also names flood region \"$it\", which affects routing " +
                                "rather than whether you can hear the mesh. Set it yourself " +
                                "under Mesh policies if you want it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmRadioConfig(config) }) {
                    Text("Apply", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.pendingRadioConfig.value = null }) { Text("Cancel") }
            },
        )
    }
}
