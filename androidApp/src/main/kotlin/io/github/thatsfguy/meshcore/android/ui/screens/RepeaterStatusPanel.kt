package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontFamily
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
import io.github.thatsfguy.meshcore.protocol.RepeaterStatus
import io.github.thatsfguy.meshcore.protocol.StatusCodec
import io.github.thatsfguy.meshcore.protocol.TelemetryReading
import kotlinx.coroutines.launch

/**
 * Decoded repeater status + telemetry — the binary status response and
 * Cayenne-LPP sensor payload rendered as fields instead of raw CLI
 * text. Both are fetched on demand (the node has to answer over the
 * air, which can take seconds).
 */
@Composable
fun RepeaterStatusPanel(vm: MeshCoreViewModel, keyHex: String) {
    val scope = rememberCoroutineScope()
    var status by remember(keyHex) { mutableStateOf<RepeaterStatus?>(null) }
    var telemetry by remember(keyHex) { mutableStateOf<List<TelemetryReading>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    suspend fun fetchStatus() {
        loading = true
        note = null
        status = vm.repeaterStatus(keyHex)
        if (status == null) note = "No status reply — logged in and in range?"
        loading = false
    }

    // Opening the Status screen IS the request for status. Asking the
    // user to then press "Fetch status" is §6.1 — a control for
    // something the system already knows you want — and it is the one
    // place the rule was applied everywhere else and missed here. The
    // reference client does the same on entry
    // (`repeater_status_screen.initState` → `_loadStatus()`).
    // Refresh stays available for a second look.
    LaunchedEffect(keyHex) { fetchStatus() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        ButtonFlowRow {
            TextButton(
                enabled = !loading,
                onClick = { scope.launch { fetchStatus() } },
            ) { Text("Refresh status") }
            TextButton(
                enabled = !loading,
                onClick = {
                    scope.launch {
                        loading = true; note = null
                        telemetry = vm.repeaterTelemetry(keyHex)
                        if (telemetry.isEmpty()) note = "No telemetry reply (or none published)"
                        loading = false
                    }
                },
            ) { Text("Fetch telemetry") }
        }
        if (loading) SectionSpinner("Waiting for the node…")
        note?.let { HintText(it) }

        // --- Access list -------------------------------------------------
        AccessListSection(vm, keyHex)

        // --- Live noise floor ---------------------------------------------
        NoiseFloorSection(vm, keyHex)

        // --- One-hop neighbours -------------------------------------------
        NeighboursSection(vm, keyHex)

        status?.let { s ->
            Spacer(Modifier.height(8.dp))
            Text("Status", style = MaterialTheme.typography.titleSmall)
            StatField("Battery", "%.2f V".format(s.batteryVolts))
            StatField("Uptime", StatusCodec.formatUptime(s.uptimeSeconds))
            StatField("Queue length", s.queueLength.toString())
            StatField("Last RSSI / SNR", "${s.lastRssi} dBm / %.1f dB".format(s.lastSnr))
            StatField("Noise floor", "${s.noiseFloor} dB")
            StatField("Channel utilisation", "%.1f %%".format(s.channelUtilizationPercent))
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Packets", style = MaterialTheme.typography.titleSmall)
            StatField("Received / sent", "${s.packetsReceived} / ${s.packetsSent}")
            StatField("Flood tx / rx", "${s.floodTx} / ${s.floodRx}")
            StatField("Direct tx / rx", "${s.directTx} / ${s.directRx}")
            StatField("Duplicates (direct/flood)", "${s.directDuplicates} / ${s.floodDuplicates}")
            StatField("Airtime tx / rx", "${s.txAirSeconds}s / ${s.rxAirSeconds}s")
            StatField("Error events", s.errorEvents.toString())
        }

        if (telemetry.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Telemetry", style = MaterialTheme.typography.titleSmall)
            for (t in telemetry) {
                StatField(
                    "${t.label} (ch ${t.channel})",
                    if (t.unit.isEmpty()) "%.2f".format(t.value) else "%.2f %s".format(t.value, t.unit),
                )
            }
        }

        if (status == null && telemetry.isEmpty() && !loading) {
            HintText("The node has not answered yet.")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatField(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}


/**
 * Who the node grants admin or guest access to.
 *
 * Read-only on purpose: writing an ACL entry grants someone control of
 * a repeater, and the set-command syntax varies by firmware. Reading is
 * unambiguous; guessing at a write is not.
 */
@Composable
private fun AccessListSection(vm: MeshCoreViewModel, keyHex: String) {
    val scope = rememberCoroutineScope()
    var parsed by remember(keyHex) {
        mutableStateOf<io.github.thatsfguy.meshcore.protocol.AccessList.Parsed?>(null)
    }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    Spacer(Modifier.height(12.dp))
    Text("Access list", style = MaterialTheme.typography.titleSmall)
    ButtonFlowRow {
        TextButton(
            enabled = !loading,
            onClick = {
                scope.launch {
                    loading = true; note = null
                    val reply = vm.cliQuery(keyHex, "get acl")
                    parsed = io.github.thatsfguy.meshcore.protocol.AccessList.parse(reply)
                    if (reply == null) note = "No reply — logged in and in range?"
                    loading = false
                }
            },
        ) { Text("Fetch access list") }
    }
    if (loading) SectionSpinner("Asking the node…")
    note?.let { HintText(it) }
    parsed?.let { p ->
        if (p.isEmpty) {
            HintText("The node reported no access-list entries.")
        }
        for (entry in p.entries) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    entry.keyPrefixHex,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(entry.permission, style = MaterialTheme.typography.bodySmall)
            }
        }
        // Anything the parser didn't recognise is shown as the node said
        // it. A dropped line in an access list reads as "nobody has that
        // access", which is exactly the wrong thing to imply.
        for (line in p.unparsed) {
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (p.entries.isNotEmpty()) {
            HintText(
                "Prefixes, not full keys — a prefix identifies a node only as far as it " +
                    "goes. Editing the list is done from the node's own console.",
            )
        } else if (p.unparsed.isNotEmpty()) {
            // Seen in the field: firmware without ACL support answers
            // "??: acl". Show its words, then explain them.
            HintText(
                "That's the node's reply verbatim. A \"??\" means this firmware doesn't " +
                    "know the command; some builds also require an admin login first.",
            )
        }
    }
}

/**
 * Noise floor, polled while the screen is open.
 *
 * Each sample is a round-trip over the air, so the interval is seconds,
 * not milliseconds, and it stops when you leave — a background poller
 * against someone else's repeater is airtime you're spending on their
 * behalf.
 */
@Composable
private fun NoiseFloorSection(vm: MeshCoreViewModel, keyHex: String) {
    var watching by remember(keyHex) { mutableStateOf(false) }
    var samples by remember(keyHex) { mutableStateOf<List<Int>>(emptyList()) }

    LaunchedEffect(watching, keyHex) {
        while (watching) {
            vm.repeaterStatus(keyHex)?.let { status ->
                samples = (samples + status.noiseFloor).takeLast(WATCH_SAMPLES)
            }
            kotlinx.coroutines.delay(WATCH_INTERVAL_MS)
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("Noise floor", style = MaterialTheme.typography.titleSmall)
    ButtonFlowRow {
        TextButton(onClick = { watching = !watching }) {
            Text(if (watching) "Stop watching" else "Watch noise floor")
        }
        if (samples.isNotEmpty()) {
            TextButton(onClick = { samples = emptyList() }) { Text("Clear") }
        }
    }
    if (samples.isEmpty()) {
        HintText("Polls the node every ${WATCH_INTERVAL_MS / 1000}s while this is on.")
    } else {
        Text(
            "Now ${samples.last()} · min ${samples.min()} · max ${samples.max()} " +
                "(${samples.size} samples)",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            samples.joinToString(" ") { it.toString() },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Seconds between noise-floor samples — each one costs airtime. */
private const val WATCH_INTERVAL_MS = 5_000L
private const val WATCH_SAMPLES = 24

/**
 * A repeater's one-hop neighbour table (PARITY §6).
 *
 * Two honesty requirements, both from §12. Entries identify nodes by a
 * 4-byte key prefix, so a name is offered only when exactly one contact
 * matches and "(N matches)" otherwise. And the whole table is hearsay:
 * it is what this repeater says it hears, relayed by that repeater —
 * useful for understanding coverage, useless as evidence about anyone.
 */
@Composable
private fun NeighboursSection(vm: MeshCoreViewModel, keyHex: String) {
    val scope = rememberCoroutineScope()
    var table by remember(keyHex) {
        mutableStateOf<io.github.thatsfguy.meshcore.protocol.Neighbours.Table?>(null)
    }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    Spacer(Modifier.height(12.dp))
    Text("Neighbours", style = MaterialTheme.typography.titleSmall)
    ButtonFlowRow {
        TextButton(
            enabled = !loading,
            onClick = {
                scope.launch {
                    loading = true; note = null
                    table = vm.repeaterNeighbours(keyHex)
                    if (table == null) note = "No reply — in range and logged in?"
                    loading = false
                }
            },
        ) { Text("Fetch neighbours") }
    }
    if (loading) SectionSpinner("Asking the node…")
    note?.let { HintText(it) }

    table?.let { t ->
        // "Answered with nothing" and "answered with a page of nothing"
        // are different facts. Seen on real hardware: a repeater
        // reported total=1 with zero entries, and saying "no
        // neighbours" alongside "0 of 1" contradicted itself.
        if (t.entries.isEmpty()) {
            HintText(
                if (t.total > 0) {
                    "The node says it knows ${t.total} neighbour(s) but returned none in " +
                        "this reply. Try again — the table may be paged."
                } else {
                    "The node answered and reported no neighbours."
                },
            )
        }
        for (n in t.entries) {
            val names = vm.neighbourNames(n)
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    io.github.thatsfguy.meshcore.protocol.Neighbours.label(n, names),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text("%.1f dB".format(n.snr), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (t.isPartial && t.entries.isNotEmpty()) {
            HintText("Showing ${t.entries.size} of ${t.total} the node knows.")
        }
        if (t.entries.isNotEmpty()) {
            HintText(
                "Prefixes, not full keys — a prefix names a node only as far as it goes. " +
                    "This is what this repeater says it hears, relayed by that repeater.",
            )
        }
    }
}
