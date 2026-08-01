package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.RadioPresets

/**
 * Regional radio presets (PARITY §1).
 *
 * Applying one rewrites the radio's frequency, bandwidth, spreading
 * factor, coding rate and TX power in a single command — which is
 * exactly what makes it useful and exactly what makes it worth a
 * confirmation. A node on the wrong parameters isn't on a degraded
 * mesh, it is on no mesh, and if the user is out of range of anything
 * familiar there is no feedback to tell them why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioPresetSheet(vm: MeshCoreViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf<RadioPresets.Preset?>(null) }

    val grouped = remember(query) {
        RadioPresets.ALL
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .groupBy { RadioPresets.region(it) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Radio presets", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                RadioPresets.REGULATORY_CAVEAT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                for ((region, presets) in grouped) {
                    item(key = "h-$region") {
                        Text(
                            region,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(presets, key = { it.name }) { preset ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { confirming = preset }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                preset.summary(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    confirming?.let { preset ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Apply ${preset.name}?") },
            text = {
                Column {
                    Text(preset.summary(), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Every node you talk to must be on these same four values. If " +
                            "they aren't, you won't see errors — you'll see an empty mesh.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        RadioPresets.REGULATORY_CAVEAT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.applyRadioPreset(preset)
                    confirming = null
                    onDismiss()
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            },
        )
    }
}
