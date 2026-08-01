package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.storage.ContactEntity
import io.github.thatsfguy.meshcore.android.storage.PathHistoryEntity
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.PathCodec
import io.github.thatsfguy.meshcore.protocol.RoutingMode
import io.github.thatsfguy.meshcore.protocol.TraceResult
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Per-contact routing control: Auto / Flood / Manual, the paths we have
 * seen with their success record, a hop-by-hop manual editor, and a
 * path trace. MeshCore keeps the route inside the contact record, so
 * every mode here rewrites that record on the radio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingSheet(
    vm: MeshCoreViewModel,
    contact: ContactEntity,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keyHex = contact.keyHex
    val paths by remember(keyHex) { vm.pathHistory(keyHex) }.collectAsState()
    val liveContacts by vm.liveContacts.collectAsState()

    var mode by remember(keyHex) { mutableStateOf(vm.routingMode(keyHex)) }
    var manualHops by remember(keyHex) {
        mutableStateOf(
            liveContacts[keyHex]?.storedPath?.takeIf { it.isNotEmpty() }?.let { bytes ->
                PathCodec.formatHops(bytes, liveContacts[keyHex]!!.pathInfo.hashWidth)
            } ?: "",
        )
    }
    var trace by remember { mutableStateOf<TraceResult?>(null) }
    var tracing by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Routing", style = MaterialTheme.typography.headlineSmall)
            Text(
                contact.name.ifBlank { keyHex.take(12) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RoutingMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = {
                            mode = m
                            if (m != RoutingMode.Manual) vm.setRouting(keyHex, m)
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, RoutingMode.entries.size),
                    ) { Text(m.name) }
                }
            }
            HintText(
                when (mode) {
                    RoutingMode.Auto ->
                        "The radio learns and updates the route itself (resets the stored path)."
                    RoutingMode.Flood ->
                        "Every packet floods the mesh — slower and noisier, but works when a route is broken."
                    RoutingMode.Manual ->
                        "Pin an exact hop list. Each hop is the repeater's path-hash prefix."
                },
            )

            if (mode == RoutingMode.Manual) {
                Spacer(Modifier.height(8.dp))
                SettingsTextField("Hops (e.g. \"3f a1\")", manualHops) { manualHops = it }
                // Offer the repeaters we know as one-tap hop additions.
                val repeaters = liveContacts.values
                    .filter { it.type == Codes.ADV_TYPE_REPEATER }
                    .sortedBy { it.name.lowercase() }
                if (repeaters.isNotEmpty()) {
                    HintText("Add a known repeater:")
                    LazyColumn(Modifier.heightIn(max = 140.dp)) {
                        items(repeaters, key = { it.publicKeyHex }) { r ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // A hop is the leading byte(s) of the
                                        // repeater's public key.
                                        val hop = r.publicKeyHex.take(2)
                                        manualHops = (manualHops.trim() + " " + hop).trim()
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    r.name.ifBlank { r.publicKeyHex.take(12) },
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    r.publicKeyHex.take(2),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                ButtonFlowRow {
                    TextButton(onClick = { manualHops = "" }) { Text("Clear hops") }
                    TextButton(
                        enabled = manualHops.isNotBlank(),
                        onClick = {
                            val bytes = PathCodec.parseHopTokens(manualHops)
                            if (bytes == null || bytes.isEmpty()) {
                                vm.transientMessage.value =
                                    "Hops must be 2-hex-digit tokens (max ${PathCodec.MAX_HOPS})"
                            } else {
                                vm.setRouting(
                                    keyHex, RoutingMode.Manual,
                                    bytes.joinToString("") { "%02x".format(it) },
                                )
                            }
                        },
                    ) { Text("Apply path") }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Known paths", style = MaterialTheme.typography.titleSmall)
            if (paths.isEmpty()) {
                HintText("No paths recorded yet — they appear as the radio learns routes and as messages get delivered.")
            }
            for (p in paths) {
                PathRow(
                    path = p,
                    onUse = {
                        manualHops = PathCodec.formatHops(hexToBytes(p.pathHex))
                        mode = RoutingMode.Manual
                        vm.setRouting(keyHex, RoutingMode.Manual, p.pathHex)
                    },
                    onDelete = { vm.deletePath(keyHex, p.pathHex) },
                )
            }
            if (paths.isNotEmpty()) {
                TextButton(onClick = { vm.clearPathHistory(keyHex) }) {
                    Text("Clear path history", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Trace", style = MaterialTheme.typography.titleSmall)
            HintText("Ask the mesh to report the hops (and per-hop SNR) a packet travels.")
            ButtonFlowRow {
                TextButton(
                    enabled = !tracing,
                    onClick = {
                        scope.launch {
                            tracing = true
                            trace = vm.tracePath()
                            tracing = false
                            if (trace == null) {
                                vm.transientMessage.value = "Trace timed out"
                            }
                        }
                    },
                ) { Text(if (tracing) "Tracing…" else "Run trace") }
            }
            if (tracing) SectionSpinner("Waiting for trace…")
            trace?.let { t ->
                Text(
                    if (t.hopCount == 0) "Direct (no hops)"
                    else "${t.hopCount} hop(s): ${t.hops.joinToString(" → ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "SNR: " + t.snrs.joinToString(", ") { "%.1f dB".format(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (t.hops.isNotEmpty()) {
                    TextButton(onClick = {
                        manualHops = t.hops.joinToString(" ")
                        mode = RoutingMode.Manual
                    }) { Text("Use traced path") }
                }
            }
        }
    }
}

@Composable
private fun PathRow(
    path: PathHistoryEntity,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val isFlood = path.pathHex.isEmpty()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (isFlood) "flood" else PathCodec.formatHops(hexToBytes(path.pathHex)),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                buildString {
                    append(PathCodec.qualityLabel(path.successes, path.failures, isFlood))
                    if (!isFlood) append(" · ${path.hops} hop(s)")
                    append(" · ${path.successes}✓/${path.failures}✗")
                    if (path.lastWorkedAt > 0) {
                        append(
                            " · worked " +
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(path.lastWorkedAt)),
                        )
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!isFlood) TextButton(onClick = onUse) { Text("Use") }
        TextButton(onClick = onDelete) { Text("×", color = MaterialTheme.colorScheme.error) }
    }
}

private fun hexToBytes(hex: String): ByteArray =
    io.github.thatsfguy.meshcore.util.hexToBytesOrNull(hex) ?: ByteArray(0)
