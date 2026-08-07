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
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.delay
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
            // dBm, not dB: an absolute power, like RSSI beside it. SNR
            // is the ratio and keeps dB. (The firmware's own comment
            // says "dBi", which is antenna gain — wrong a third way.)
            StatField("Noise floor", "${s.noiseFloor} dBm")
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
        // The unit once, on the leading value — the run below is a
        // shape, and repeating "dBm" on every figure buries it.
        Text(
            "Now ${samples.last()} dBm · min ${samples.min()} · max ${samples.max()} " +
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

/**
 * How long to let discover replies land before re-reading the table.
 *
 * Not a guess: driven against a live repeater, a neighbour that had been
 * missing for hours appeared roughly 30s after the broadcast. Re-reading
 * immediately returns the same stale list, which reads as "the probe did
 * nothing".
 */
private const val PROBE_SETTLE_MS = 40_000L

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
    // Only repeater firmware keeps a neighbour table — room servers and
    // sensors have no `0x06` handler at all, so the button could only
    // ever time out into "no reply, are you in range?", which blames the
    // link for a question the node cannot be asked.
    val contacts by vm.dbContacts.collectAsState()
    val isRepeater = contacts.firstOrNull { it.keyHex == keyHex }?.type ==
        io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_REPEATER
    if (!isRepeater) return

    val scope = rememberCoroutineScope()
    var table by remember(keyHex) {
        mutableStateOf<io.github.thatsfguy.meshcore.protocol.Neighbours.Table?>(null)
    }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    // Rows accumulated across pages; the node returns at most a
    // bufferful per reply and we ask for the rest by offset.
    var rows by remember(keyHex) {
        mutableStateOf<List<io.github.thatsfguy.meshcore.protocol.Neighbours.Neighbour>>(emptyList())
    }

    // The probe goes through the admin CLI, and Status is reachable on a
    // read-only session, so the button has to be gated separately from
    // the section.
    val sessions by vm.adminSessions.collectAsState()
    val isAdmin = sessions[keyHex]?.isAdmin == true
    var probing by remember(keyHex) { mutableStateOf(false) }

    suspend fun fetch(offset: Int) {
        loading = true; note = null
        val t = vm.repeaterNeighbours(keyHex, offset = offset)
        table = t
        when {
            t == null -> note = "No reply — in range and logged in?"
            // A correct request cannot produce this: the node is
            // claiming rows and returning an empty first page. Say it is
            // wrong rather than inviting a retry that cannot help.
            t.isEmptyButNotEmpty ->
                note = "The node says it knows ${t.total} neighbour(s) but returned an " +
                    "empty page. That is a rejected request, not a paged table — retrying " +
                    "will not change it."
            else -> rows = if (offset == 0) t.entries else rows + t.entries
        }
        loading = false
    }

    Spacer(Modifier.height(12.dp))
    Text("Neighbours", style = MaterialTheme.typography.titleSmall)
    ButtonFlowRow {
        TextButton(
            enabled = !loading && !probing,
            onClick = { scope.launch { rows = emptyList(); fetch(0) } },
        ) { Text("Fetch neighbours") }
        if (isAdmin) {
            TextButton(
                enabled = !loading && !probing,
                onClick = {
                    scope.launch {
                        probing = true
                        note = null
                        if (!vm.probeNeighbours(keyHex)) {
                            note = "The node did not take the probe."
                        } else {
                            // Replies arrive over the air, one per
                            // repeater that hears it. Measured against a
                            // live node: a new neighbour landed in the
                            // table about 30s after the broadcast, so
                            // re-reading immediately would show the same
                            // stale list and look like the probe failed.
                            delay(PROBE_SETTLE_MS)
                            rows = emptyList()
                            fetch(0)
                        }
                        probing = false
                    }
                },
            ) { Text("Probe") }
        }
        table?.let { t ->
            if (t.isPartial && rows.isNotEmpty()) {
                TextButton(
                    enabled = !loading && !probing,
                    onClick = { scope.launch { fetch(rows.size) } },
                ) { Text("Fetch more") }
            }
        }
    }
    if (probing) {
        SectionSpinner("Asking nearby repeaters to answer…")
        // Without this the spinner sits for the better part of a minute
        // with nothing to say why.
        HintText("They answer over the air; this takes about a minute.")
    }
    if (loading && !probing) SectionSpinner("Asking the node…")
    note?.let { HintText(it) }

    table?.let { t ->
        if (rows.isEmpty() && t.total == 0) {
            // "Nobody is out there" and "nobody has advertised lately"
            // look identical here, and only one of them is true — so
            // point at the probe rather than letting an empty list read
            // as a finding.
            HintText(
                if (isAdmin) {
                    "The node reported no neighbours. Nothing keeps this list current, so " +
                        "try Probe before believing it."
                } else {
                    "The node reported no neighbours — but nothing keeps this list current."
                },
            )
        }
        for (n in rows) {
            val names = vm.neighbourNames(n)
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    io.github.thatsfguy.meshcore.protocol.Neighbours.label(n, names),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    io.github.thatsfguy.meshcore.protocol.Neighbours
                        .heardAgoLabel(n.heardSecondsAgo),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text("%.1f dB".format(n.snr), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (rows.isNotEmpty()) {
            if (t.isPartial) {
                HintText("Showing ${rows.size} of ${t.total} the node knows.")
            }
            // Lead with what the table actually contains. "Neighbours"
            // reads as "everyone this node hears", and the firmware
            // records something much narrower — so a short list looks
            // like a bug until you know that.
            ExpandableHint("Other repeaters heard directly — and only as fresh as their last advert.") {
                HintText(
                    "The node records a neighbour only from a repeater's own advert that " +
                        "arrived at zero hops. Companions, rooms and sensors never appear.",
                )
                HintText(
                    "Nothing polls and nothing expires, so this is who advertised since the " +
                        "node booted, not who is in range. Probe makes them answer now.",
                )
                HintText(
                    "A prefix names a node only as far as it goes, so a row can match " +
                        "more than one contact.",
                )
                HintText(
                    "The list is what this repeater says it hears, relayed by that " +
                        "repeater, and heard-ago is on its clock.",
                )
            }
        }
    }
}
