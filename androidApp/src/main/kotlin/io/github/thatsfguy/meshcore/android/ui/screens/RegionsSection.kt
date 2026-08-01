package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.Regions
import kotlinx.coroutines.launch

/**
 * Regions (flood scope) — Settings → Mesh policies. PARITY.md §8.
 *
 * A region is a routing label: the radio hashes the name and only floods
 * packets carrying the matching scope tag. It is NOT a privacy boundary,
 * and the copy here has to keep saying so — a channel scoped to a region
 * is exactly as readable as it was before, just carried by fewer nodes.
 *
 * Region names are shared out of band or discovered from repeaters.
 * Discovered names come off the mesh from nodes we can't authenticate,
 * so nothing is stored automatically: they are shown, and the user adds
 * the ones they mean to use.
 */
@Composable
fun RegionsSection(vm: MeshCoreViewModel) {
    val regions by vm.regions.collectAsState()
    val channelRegions by vm.channelRegions.collectAsState()
    val stuck by vm.floodScopeStuck.collectAsState()
    var newRegion by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var discovering by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()

    HintText(
        "A region restricts which repeaters will flood a message onward. It is a routing " +
            "label, not privacy: anyone who can hear the traffic can still read it exactly " +
            "as before. Names are lowercase letters, digits and hyphens.",
    )

    stuck?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            "The radio may still be scoped to #$it — restoring the scope after a send " +
                "failed. Reconnect, or set the flood scope below, before sending again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newRegion,
            onValueChange = { newRegion = it },
            label = { Text("Region name (e.g. bayarea)") },
            singleLine = true,
            isError = newRegion.isNotBlank() && !Regions.isValid(newRegion),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { vm.addRegion(newRegion); newRegion = "" },
            enabled = Regions.isValid(newRegion),
        ) { Text("Add") }
    }

    ButtonFlowRow {
        TextButton(
            onClick = {
                discovering = true
                scope.launch {
                    discovered = vm.discoverRegions()
                    discovering = false
                }
            },
            enabled = !discovering,
        ) { Text("Discover from repeaters…") }
    }
    if (discovering) SectionSpinner("Asking nearby repeaters…")

    Spacer(Modifier.height(4.dp))
    if (regions.isEmpty()) {
        HintText("No regions yet. Add one above, or discover what nearby repeaters know.")
    } else {
        for (region in regions) {
            val usedBy = channelRegions.filterValues { it == region }.keys.sorted()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("#$region", style = MaterialTheme.typography.bodyLarge)
                    if (usedBy.isNotEmpty()) {
                        HintText(
                            "Used by channel " + usedBy.joinToString(", ") { it.toString() },
                        )
                    }
                }
                TextButton(onClick = { confirmDelete = region }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    confirmDelete?.let { region ->
        val usedBy = channelRegions.filterValues { it == region }.keys.sorted()
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Forget #$region?") },
            text = {
                Text(
                    if (usedBy.isEmpty()) {
                        "The name is removed from this phone. Nothing changes on the radio " +
                            "or on any repeater."
                    } else {
                        // Silently leaving a channel pointed at a deleted
                        // region would keep scoping its traffic to a name
                        // the user believes is gone.
                        "Channel " + usedBy.joinToString(", ") { it.toString() } +
                            " will go back to sending unscoped (global flood). Nothing " +
                            "changes on the radio or on any repeater."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.removeRegion(region); confirmDelete = null }) {
                    Text("Forget", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }

    discovered?.let { found ->
        DiscoveredRegionsDialog(
            found = found,
            known = regions,
            onAdd = { vm.addRegion(it) },
            onDismiss = { discovered = null },
        )
    }
}

/**
 * What nearby repeaters answered. Nothing here is authenticated — any
 * node in range can claim any region name — so this is a list to choose
 * from, never a list that gets imported.
 */
@Composable
private fun DiscoveredRegionsDialog(
    found: List<String>,
    known: List<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Regions heard") },
        text = {
            Column {
                if (found.isEmpty()) {
                    Text(
                        "No repeater answered with a region list. That can mean nothing was " +
                            "in range, or that the repeaters that answered run firmware " +
                            "without region support.",
                    )
                } else {
                    Text(
                        "Names reported by nearby repeaters. Nothing about them is " +
                            "authenticated — add the ones you recognise.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (region in found) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("#$region", Modifier.weight(1f))
                            if (region in known) {
                                HintText("added")
                            } else {
                                TextButton(onClick = { onAdd(region) }) { Text("Add") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * Region picker for one channel slot. Tapping the selected region clears
 * it, matching the reference client — and "none" is a real choice, not a
 * missing one, so it is spelled out.
 */
@Composable
fun ChannelRegionPicker(vm: MeshCoreViewModel, channelIndex: Int) {
    val regions by vm.regions.collectAsState()
    val channelRegions by vm.channelRegions.collectAsState()
    val current = channelRegions[channelIndex]

    Text("Region (flood scope)", style = MaterialTheme.typography.labelLarge)
    if (regions.isEmpty()) {
        HintText("No regions defined. Add one in Settings → Mesh policies → Regions.")
        return
    }
    HintText(
        if (current == null) {
            "Unscoped: messages on this channel flood the whole mesh."
        } else {
            "Messages on this channel are sent with the #$current flood scope. This " +
                "changes which repeaters carry them, not who can read them."
        },
    )
    ChoiceChips(
        options = listOf("None") + regions.map { "#$it" },
        selected = if (current == null) 0 else regions.indexOf(current) + 1,
    ) { index ->
        vm.setChannelRegion(channelIndex, if (index == 0) null else regions[index - 1])
    }
}
