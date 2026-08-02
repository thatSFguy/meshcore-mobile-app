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
    val liveContact = liveContacts[keyHex]
    // Bytes per hop on THIS mesh; every hop-token parse depends on it.
    val hopWidth = vm.deviceInfo.collectAsState().value?.pathHashByteWidth ?: 1
    val hasStoredRoute = (liveContact?.storedPath?.size ?: 0) > 0
    val mapPlot = remember(keyHex, liveContact) { vm.plotStoredPath(keyHex) }
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
                            // The hop-hash WIDTH is a property of the mesh
                            // (DEVICE_INFO), not a constant. Parsing with the
                            // default of 1 rejected every token on a 2-byte
                            // mesh, so Apply silently did nothing but flash an
                            // error naming the wrong digit count.
                            val bytes = PathCodec.parseHopTokens(manualHops, hopWidth)
                            if (bytes == null || bytes.isEmpty()) {
                                vm.transientMessage.value =
                                    "Hops must be ${hopWidth * 2}-hex-digit tokens " +
                                        "(max ${PathCodec.maxHopsFor(hopWidth)})"
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

            if (hasStoredRoute) {
                ButtonFlowRow {
                    TextButton(onClick = {
                        vm.mapRouteContact.value = keyHex
                        vm.transientMessage.value =
                            "Route sent to the Map tab" +
                                (mapPlot?.let { " — " + it.summary() } ?: "")
                    }) { Text("Show route on map") }
                    if (vm.mapRouteContact.value != null) {
                        TextButton(onClick = { vm.mapRouteContact.value = null }) {
                            Text("Clear map route")
                        }
                    }
                }
                mapPlot?.let { HintText(it.summary()) }
            }

            Spacer(Modifier.height(12.dp))
            Text("Trace", style = MaterialTheme.typography.titleSmall)
            // Confirmed on hardware: the radio answers a trace with no
            // path with RESP_CODE_ERR. There is genuinely nothing to
            // report for a direct contact, so say that instead of
            // offering a button that spends airtime to be refused.
            val hasRoute = io.github.thatsfguy.meshcore.protocol.PathCodec
                .decodePathLen(contact.pathLen).hops > 0
            HintText(
                if (hasRoute) {
                    "Ask the mesh to report the hops (and per-hop SNR) a packet travels."
                } else {
                    "Nothing to trace: this contact is reached directly, with no repeater " +
                        "in between. A trace reports the hops along a route, so it needs a " +
                        "route with at least one hop."
                },
            )
            ButtonFlowRow {
                TextButton(
                    enabled = !tracing && hasRoute,
                    onClick = {
                        scope.launch {
                            tracing = true
                            trace = vm.tracePath(keyHex)
                            tracing = false
                            if (trace == null) {
                                // Distinguish the two silences: a
                                // flood-routed contact has no route to
                                // probe in the first place.
                                vm.transientMessage.value =
                                    if (contact.pathLen == 0xFF || contact.pathLen < 0) {
                                        "No stored route to trace — this contact is reached " +
                                            "by flooding. Pin a path first, or trace a " +
                                            "contact that has one."
                                    } else {
                                        // A trace has to traverse the whole route and come
                                        // back, so one node being down anywhere along it
                                        // looks exactly like a broken feature. Say which
                                        // to check rather than implying a fault.
                                        "No reply. A trace has to reach every hop on this " +
                                            "route and return — check the nodes on it have " +
                                            "been heard recently."
                                    }
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
                    // Where the route goes, as far as it can honestly be
                    // said (PARITY §9). An ambiguous hop is reported as a
                    // gap rather than pinned to a guess — a map that
                    // guesses looks exactly like one that knows.
                    val plot = vm.plotPath(t.hops.joinToString(" "))
                    HintText(plot.summary())
                    for (hop in plot.hops) {
                        val where = when {
                            hop.isPlotted ->
                                "${hop.name} · %.4f, %.4f".format(hop.latitude, hop.longitude)
                            hop.gap != null ->
                                (hop.name?.plus(" · ") ?: "") +
                                    io.github.thatsfguy.meshcore.protocol.PathGeometry
                                        .gapReason(hop.gap!!)
                            else -> "unknown"
                        }
                        Text(
                            "${hop.hashHex}  $where",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (hop.isPlotted) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
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
