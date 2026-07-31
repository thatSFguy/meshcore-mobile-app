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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        ButtonFlowRow {
            TextButton(
                enabled = !loading,
                onClick = {
                    scope.launch {
                        loading = true; note = null
                        status = vm.repeaterStatus(keyHex)
                        if (status == null) note = "No status reply — logged in and in range?"
                        loading = false
                    }
                },
            ) { Text("Fetch status") }
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
            HintText("Fetch status or telemetry to see live values from this node.")
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
