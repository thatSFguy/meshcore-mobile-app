package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.platform.BleScanner
import io.github.thatsfguy.meshcore.android.platform.UsbDevices
import io.github.thatsfguy.meshcore.android.storage.ChannelEntity
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.engine.EngineState
import io.github.thatsfguy.meshcore.transport.ConnectionMemory
import kotlinx.coroutines.flow.collectLatest

/**
 * Settings: Connection (per-transport toggles, TCP behind the stern
 * plaintext warning, saved nodes, add-node scan), Device (name / GPS /
 * radio params / TX power / advert / reboot), Channels, App (theme,
 * diagnostics log). Mirrors SCOPE.md's Settings section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MeshCoreViewModel) {
    var showAddNode by remember { mutableStateOf(false) }
    var showTcpWarning by remember { mutableStateOf(false) }
    var showSelfQr by remember { mutableStateOf(false) }
    var editChannel by remember { mutableStateOf<ChannelEntity?>(null) }

    // Local mirrors of prefs (SharedPreferences isn't observable here).
    var bleEnabled by remember { mutableStateOf(vm.prefs.bleEnabled) }
    var usbEnabled by remember { mutableStateOf(vm.prefs.usbEnabled) }
    var tcpEnabled by remember { mutableStateOf(vm.prefs.tcpEnabled) }
    var autoReconnect by remember { mutableStateOf(vm.prefs.autoReconnect) }
    var diagnostics by remember { mutableStateOf(vm.prefs.diagnosticsEnabled) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ----------------------------------------------------------
            SectionHeader("Connection")
            ConnectionStatusCard(vm)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showAddNode = true }) { Text("Add / connect node") }
                val engineState by vm.engineState.collectAsState()
                if (engineState != EngineState.Detached) {
                    OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
                }
            }

            SavedNodesList(vm)

            SettingRow("Auto-reconnect on launch", autoReconnect) {
                autoReconnect = it
                vm.prefs.autoReconnect = it
            }

            SectionHeader("Transports")
            SettingRow("Bluetooth LE", bleEnabled) {
                bleEnabled = it
                vm.prefs.bleEnabled = it
            }
            SettingRow("USB serial", usbEnabled) {
                usbEnabled = it
                vm.prefs.usbEnabled = it
            }
            SettingRow("TCP (unencrypted)", tcpEnabled) { wanted ->
                if (wanted && !vm.prefs.tcpWarningAccepted) {
                    showTcpWarning = true
                } else {
                    tcpEnabled = wanted
                    vm.prefs.tcpEnabled = wanted
                }
            }
            if (tcpEnabled) {
                Text(
                    "⚠ TCP links are unencrypted and unauthenticated: message text and repeater " +
                        "login passwords cross the network in the clear.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // ----------------------------------------------------------
            SectionHeader("Device")
            DeviceSection(vm, onShowSelfQr = { showSelfQr = true })

            // ----------------------------------------------------------
            SectionHeader("Channels")
            val channels by vm.dbChannels.collectAsState()
            if (channels.isEmpty()) {
                Text(
                    "No channels configured. Add one from the Chats tab (+).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (ch in channels) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${ch.idx}: ${ch.name.ifBlank { "(unnamed)" }}")
                    }
                    TextButton(onClick = { editChannel = ch }) { Text("Edit") }
                }
            }

            // ----------------------------------------------------------
            SectionHeader("App")
            var theme by remember { mutableStateOf(vm.prefs.theme) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                for (option in listOf("system", "light", "dark")) {
                    OutlinedButton(
                        onClick = {
                            theme = option
                            vm.prefs.theme = option
                        },
                        enabled = theme != option,
                    ) { Text(option.replaceFirstChar { it.uppercase() }) }
                }
            }
            SettingRow("Diagnostics log (redaction-aware)", diagnostics) {
                diagnostics = it
                vm.prefs.diagnosticsEnabled = it
            }
            if (diagnostics) {
                DiagnosticsViewer(vm)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showAddNode) {
        AddNodeSheet(vm, tcpEnabled = tcpEnabled, onDismiss = { showAddNode = false })
    }
    if (showTcpWarning) {
        TcpSternWarningDialog(
            onAccept = {
                vm.prefs.tcpWarningAccepted = true
                vm.prefs.tcpEnabled = true
                tcpEnabled = true
                showTcpWarning = false
            },
            onCancel = { showTcpWarning = false },
        )
    }
    if (showSelfQr) {
        SelfQrDialog(vm, onDismiss = { showSelfQr = false })
    }
    editChannel?.let { ch ->
        ChannelEditSheet(vm, ch, onDismiss = { editChannel = null })
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ConnectionStatusCard(vm: MeshCoreViewModel) {
    val engineState by vm.engineState.collectAsState()
    val label by vm.connectionLabel.collectAsState()
    val plaintext by vm.plaintextLink.collectAsState()
    val lastError by vm.lastError.collectAsState()
    val battery by vm.battery.collectAsState()
    val self by vm.selfInfo.collectAsState()

    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            when (engineState) {
                EngineState.Ready -> "Connected: ${label ?: "radio"}"
                EngineState.Handshaking -> "Handshaking with ${label ?: "radio"}…"
                EngineState.Connecting -> "Connecting…"
                EngineState.Detached -> "Not connected"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (plaintext && engineState == EngineState.Ready) {
            Text(
                "⚠ This link is UNENCRYPTED (TCP)",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        self?.let {
            Text(
                "Node \"${it.name}\" · ${it.publicKeyHex.take(16)}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        battery?.let {
            Text(
                "Battery: %.2f V".format(it.batteryMillivolts / 1000.0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SavedNodesList(vm: MeshCoreViewModel) {
    var refresh by remember { mutableIntStateOf(0) }
    val nodes = remember(refresh) { vm.prefs.savedNodes() }
    if (nodes.isEmpty()) return
    Column {
        Text(
            "Saved nodes",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        for (node in nodes) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(node.name ?: node.address, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${node.kind.uppercase()} · ${node.address}${node.port?.let { ":$it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.connectSaved(node) }) { Text("Connect") }
                TextButton(onClick = {
                    vm.prefs.forgetNode(node.key)
                    refresh++
                }) { Text("Forget") }
            }
        }
    }
}

/**
 * The stern one-time warning SCOPE.md requires before TCP can be
 * enabled.
 */
@Composable
fun TcpSternWarningDialog(onAccept: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Enable TCP transport?") },
        text = {
            Text(
                "The MeshCore TCP link is UNENCRYPTED and UNAUTHENTICATED.\n\n" +
                    "• Every message you send or receive crosses the network in plain text.\n" +
                    "• Repeater/room login passwords cross the network in plain text.\n" +
                    "• Anyone who can reach the radio's IP and port can drive your radio.\n\n" +
                    "Only use TCP to reach a WiFi/Ethernet MeshCore node on a trusted network " +
                    "you control — never over untrusted WiFi or the open internet.\n\n" +
                    "While enabled, the connection status will keep flagging the link as " +
                    "unencrypted.",
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text("I understand the risk — enable") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** BLE scan + USB attach + TCP host:port entry, per-transport gated. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNodeSheet(vm: MeshCoreViewModel, tcpEnabled: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(vm.prefs.bleEnabled) }
    var discovered by remember { mutableStateOf(listOf<io.github.thatsfguy.meshcore.android.platform.DiscoveredDevice>()) }

    if (scanning) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching {
                BleScanner.scan(context).collectLatest { discovered = it }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Add node", style = MaterialTheme.typography.headlineSmall)

            if (vm.prefs.bleEnabled) {
                Text(
                    "Bluetooth radios nearby",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (discovered.isEmpty()) {
                    Text(
                        "Scanning… make sure the radio is powered and advertising.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(discovered, key = { it.address }) { dev ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(dev.name ?: dev.address)
                                Text(
                                    "${dev.address} · ${dev.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                vm.connectBle(dev.address, dev.name)
                                onDismiss()
                            }) { Text("Connect") }
                        }
                    }
                }
            } else {
                Text(
                    "Bluetooth transport is disabled in Settings → Transports.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            if (vm.prefs.usbEnabled) {
                Text("USB radios attached", style = MaterialTheme.typography.titleSmall)
                val usbDevices = remember { UsbDevices.attached(context) }
                if (usbDevices.isEmpty()) {
                    Text(
                        "No supported USB-serial device attached (CDC-ACM / CP210x).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (attached in usbDevices) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(attached.device.productName ?: attached.device.deviceName)
                            Text(
                                attached.driver,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            UsbDevices.requestPermission(context, attached.device) { granted ->
                                if (granted) vm.connectUsb(attached.device)
                            }
                            onDismiss()
                        }) { Text("Connect") }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }

            if (tcpEnabled) {
                Text("Network radio (TCP — unencrypted)", style = MaterialTheme.typography.titleSmall)
                var host by remember { mutableStateOf(vm.prefs.lastTcpHost ?: "192.168.40.10") }
                var port by remember {
                    mutableStateOf(vm.prefs.lastTcpPort.takeIf { it != 0 }?.toString() ?: "5000")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.width(100.dp),
                    )
                }
                TextButton(onClick = {
                    val p = port.toIntOrNull()
                    if (host.isNotBlank() && p != null && p in 1..65535) {
                        vm.connectTcp(host.trim(), p)
                        onDismiss()
                    }
                }) { Text("Connect (unencrypted)") }
            } else {
                Text(
                    "TCP transport is off. Enable it in Settings → Transports (requires " +
                        "acknowledging the plaintext warning).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeviceSection(vm: MeshCoreViewModel, onShowSelfQr: () -> Unit) {
    val self by vm.selfInfo.collectAsState()
    val info = self

    if (info == null) {
        Text(
            "Connect to a radio to edit device settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    var name by remember(info.name) { mutableStateOf(info.name) }
    var lat by remember(info.latitude) { mutableStateOf(info.latitude.toString()) }
    var lon by remember(info.longitude) { mutableStateOf(info.longitude.toString()) }
    var freq by remember(info.freqHz) { mutableStateOf(info.freqHz.toString()) }
    var bw by remember(info.bwHz) { mutableStateOf(info.bwHz.toString()) }
    var sf by remember(info.sf) { mutableStateOf(info.sf.toString()) }
    var cr by remember(info.cr) { mutableStateOf(info.cr.toString()) }
    var tx by remember(info.txPowerDbm) { mutableStateOf(info.txPowerDbm.toString()) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Advertised name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { vm.setAdvertName(name.trim()) },
                enabled = name.trim() != info.name && name.isNotBlank(),
            ) { Text("Set") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = lat, onValueChange = { lat = it },
                label = { Text("Latitude") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = lon, onValueChange = { lon = it },
                label = { Text("Longitude") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val la = lat.toDoubleOrNull()
                val lo = lon.toDoubleOrNull()
                if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                    vm.setAdvertLocation(la, lo)
                }
            }) { Text("Set") }
        }

        Text(
            "Radio parameters — must match your mesh or the node goes deaf",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = freq, onValueChange = { freq = it },
                label = { Text("Freq (Hz)") }, singleLine = true, modifier = Modifier.weight(1.2f),
            )
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = bw, onValueChange = { bw = it },
                label = { Text("BW (Hz)") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = sf, onValueChange = { sf = it },
                label = { Text("SF") }, singleLine = true, modifier = Modifier.weight(0.5f),
            )
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = cr, onValueChange = { cr = it },
                label = { Text("CR") }, singleLine = true, modifier = Modifier.weight(0.5f),
            )
        }
        TextButton(onClick = {
            val f = freq.toLongOrNull()
            val b = bw.toLongOrNull()
            val s = sf.toIntOrNull()
            val c = cr.toIntOrNull()
            if (f != null && f in 300_000..2_500_000_000 && b != null && b in 7_000..500_000 &&
                s != null && s in 5..12 && c != null && c in 5..8
            ) {
                vm.setRadioParams(f, b, s, c)
            }
        }) { Text("Apply radio params") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = tx, onValueChange = { tx = it },
                label = { Text("TX power (dBm, max ${info.maxTxPowerDbm})") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                tx.toIntOrNull()?.takeIf { it in 1..info.maxTxPowerDbm }?.let { vm.setTxPower(it) }
            }) { Text("Set") }
        }

        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.sendSelfAdvert(flood = false) }) { Text("Advert (0-hop)") }
            OutlinedButton(onClick = { vm.sendSelfAdvert(flood = true) }) { Text("Advert (flood)") }
            OutlinedButton(onClick = onShowSelfQr) { Text("Share QR") }
        }
        var rebootConfirm by remember { mutableStateOf(false) }
        TextButton(onClick = { rebootConfirm = true }) {
            Text("Reboot radio", color = MaterialTheme.colorScheme.error)
        }
        if (rebootConfirm) {
            AlertDialog(
                onDismissRequest = { rebootConfirm = false },
                title = { Text("Reboot radio?") },
                text = { Text("The connection will drop and re-establish once the radio is back up.") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.rebootRadio()
                        rebootConfirm = false
                    }) { Text("Reboot") }
                },
                dismissButton = {
                    TextButton(onClick = { rebootConfirm = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun DiagnosticsViewer(vm: MeshCoreViewModel) {
    val lines by vm.diagnosticsLines.collectAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .padding(top = 4.dp),
    ) {
        if (lines.isEmpty()) {
            Text(
                "Log is empty. Secrets (passwords, keys, PSKs) are redacted before lines land here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn {
            items(lines.size) { i ->
                Text(
                    lines[lines.size - 1 - i],
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
