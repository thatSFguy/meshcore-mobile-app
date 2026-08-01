package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.storage.ChannelEntity
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.engine.EngineState
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.Regions
import java.text.DateFormat
import java.util.Date

/**
 * Settings — the full companion-command surface, organized as
 * collapsible sections so every MeshCore device command has a clean
 * home:
 *
 *  Connection · Transports · Node identity (name/GPS/advert/QR) ·
 *  Radio (params/TX power) · Clock · Mesh policies (advert-location,
 *  multi-acks, telemetry permissions, path-hash mode, flood scope) ·
 *  Auto-add contacts · Custom variables · Channels · App
 *
 * Per the app-wide header contract, screen-level device actions
 * (advert, share QR, reboot) live in the top-bar ⋮ menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MeshCoreViewModel) {
    var showAddNode by remember { mutableStateOf(false) }
    var showTcpWarning by remember { mutableStateOf(false) }
    var showSelfQr by remember { mutableStateOf(false) }
    var rebootConfirm by remember { mutableStateOf(false) }
    var editChannel by remember { mutableStateOf<ChannelEntity?>(null) }

    var tcpEnabled by remember { mutableStateOf(vm.prefs.tcpEnabled) }
    val engineState by vm.engineState.collectAsState()
    val isReady = engineState == EngineState.Ready

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Settings",
                vm = vm,
                menuActions = listOf(
                    MenuAction("Send advert (0-hop)") { vm.sendSelfAdvert(flood = false) },
                    MenuAction("Send advert (flood)") { vm.sendSelfAdvert(flood = true) },
                    MenuAction("Share my node QR…") { showSelfQr = true },
                    MenuAction("Reboot radio…", destructive = true) { rebootConfirm = true },
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            ExpandableSection("Connection", initiallyExpanded = true) {
                ConnectionSection(vm, onAddNode = { showAddNode = true })
            }
            ExpandableSection("Transports") {
                TransportsSection(
                    vm,
                    tcpEnabled = tcpEnabled,
                    onTcpToggle = { wanted ->
                        if (wanted && !vm.prefs.tcpWarningAccepted) {
                            showTcpWarning = true
                        } else {
                            tcpEnabled = wanted
                            vm.prefs.tcpEnabled = wanted
                        }
                    },
                )
            }
            // "Get"-backed sections: collapsed by default, and expanding
            // queries the radio first (spinner until the answer lands) so
            // the values shown are what the node reports NOW.
            ExpandableSection("Node identity") {
                QueryOnExpand(enabled = isReady, query = { vm.querySelfInfo() }) {
                    IdentitySection(vm, onShowSelfQr = { showSelfQr = true })
                }
            }
            ExpandableSection("Radio") {
                QueryOnExpand(enabled = isReady, query = { vm.querySelfInfo() }) { RadioSection(vm) }
            }
            ExpandableSection("Clock") { ClockSection(vm) }
            ExpandableSection("Mesh policies") {
                // Policies span two frames: SELF_INFO (telemetry/advert/
                // multi-ack bytes) and DEVICE_INFO (path-hash width).
                QueryOnExpand(enabled = isReady, query = {
                    vm.querySelfInfo()
                    vm.queryDeviceInfo()
                }) { PoliciesSection(vm) }
            }
            ExpandableSection("Auto-add contacts") {
                QueryOnExpand(enabled = isReady, query = { vm.queryAutoAddConfig() }) { AutoAddSection(vm) }
            }
            ExpandableSection("Custom variables") {
                QueryOnExpand(enabled = isReady, query = { vm.queryCustomVars() }) { CustomVarsSection(vm) }
            }
            ExpandableSection("Channels") { ChannelsSection(vm, onEdit = { editChannel = it }) }
            ExpandableSection("App") { AppSection(vm) }
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
    editChannel?.let { ch ->
        ChannelEditSheet(vm, ch, onDismiss = { editChannel = null })
    }
}

// ----------------------------------------------------------------------
// Section scaffolding
// ----------------------------------------------------------------------

@Composable
private fun ConnectionSection(vm: MeshCoreViewModel, onAddNode: () -> Unit) {
    val engineState by vm.engineState.collectAsState()
    val label by vm.connectionLabel.collectAsState()
    val plaintext by vm.plaintextLink.collectAsState()
    val lastError by vm.lastError.collectAsState()
    val battery by vm.battery.collectAsState()
    var autoReconnect by remember { mutableStateOf(vm.prefs.autoReconnect) }

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
    battery?.let {
        HintText("Battery: %.2f V".format(it.batteryMillivolts / 1000.0))
    }
    lastError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(4.dp))
    ButtonFlowRow {
        OutlinedButton(onClick = onAddNode) { Text("Add / connect node") }
        if (engineState != EngineState.Detached) {
            OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
        }
    }
    SavedNodesList(vm)
    SettingRow("Auto-reconnect on launch", autoReconnect) {
        autoReconnect = it
        vm.prefs.autoReconnect = it
    }
}

@Composable
private fun TransportsSection(
    vm: MeshCoreViewModel,
    tcpEnabled: Boolean,
    onTcpToggle: (Boolean) -> Unit,
) {
    var bleEnabled by remember { mutableStateOf(vm.prefs.bleEnabled) }
    var usbEnabled by remember { mutableStateOf(vm.prefs.usbEnabled) }

    SettingRow("Bluetooth LE", bleEnabled) {
        bleEnabled = it
        vm.prefs.bleEnabled = it
    }
    SettingRow("USB serial", usbEnabled) {
        usbEnabled = it
        vm.prefs.usbEnabled = it
    }
    SettingRow("TCP (unencrypted)", tcpEnabled, onChange = onTcpToggle)
    if (tcpEnabled) {
        Text(
            "⚠ TCP links are unencrypted and unauthenticated: message text and repeater " +
                "login passwords cross the network in the clear.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    HintText("A disabled transport is never started — it never scans, connects, or parses bytes.")
}

@Composable
private fun IdentitySection(vm: MeshCoreViewModel, onShowSelfQr: () -> Unit) {
    val self by vm.selfInfo.collectAsState()
    val info = self
    if (info == null) {
        HintText("Connect to a radio to edit node identity.")
        return
    }
    var name by remember(info.name) { mutableStateOf(info.name) }
    var lat by remember(info.latitude) { mutableStateOf(info.latitude.toString()) }
    var lon by remember(info.longitude) { mutableStateOf(info.longitude.toString()) }

    Text(
        info.publicKeyHex,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    Spacer(Modifier.height(4.dp))
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
    var pickPosition by remember { mutableStateOf(false) }
    if (pickPosition) {
        PositionPickerDialog(
            vm = vm,
            initialLat = lat.toDoubleOrNull() ?: info.latitude,
            initialLon = lon.toDoubleOrNull() ?: info.longitude,
            onPick = { pickedLat, pickedLon ->
                lat = "%.5f".format(pickedLat)
                lon = "%.5f".format(pickedLon)
                pickPosition = false
            },
            onDismiss = { pickPosition = false },
        )
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
    ButtonFlowRow {
        OutlinedButton(onClick = { pickPosition = true }) { Text("Pick on map") }
        OutlinedButton(onClick = { vm.sendSelfAdvert(flood = false) }) { Text("Advert (0-hop)") }
        OutlinedButton(onClick = { vm.sendSelfAdvert(flood = true) }) { Text("Advert (flood)") }
        OutlinedButton(onClick = onShowSelfQr) { Text("Share QR") }
    }
}

@Composable
private fun RadioSection(vm: MeshCoreViewModel) {
    val self by vm.selfInfo.collectAsState()
    val info = self
    if (info == null) {
        HintText("Connect to a radio to edit radio parameters.")
        return
    }
    var freq by remember(info.freqHz) { mutableStateOf(info.freqHz.toString()) }
    var bw by remember(info.bwHz) { mutableStateOf(info.bwHz.toString()) }
    var sf by remember(info.sf) { mutableStateOf(info.sf.toString()) }
    var cr by remember(info.cr) { mutableStateOf(info.cr.toString()) }
    var tx by remember(info.txPowerDbm) { mutableStateOf(info.txPowerDbm.toString()) }

    HintText("Parameters must match your mesh or the node goes deaf.")
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
}

@Composable
private fun ClockSection(vm: MeshCoreViewModel) {
    val engineState by vm.engineState.collectAsState()
    var radioTime by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(engineState, refresh) {
        if (engineState == EngineState.Ready) {
            loading = true
            radioTime = vm.deviceTime()
        }
        loading = false
    }
    if (engineState != EngineState.Ready) {
        HintText("Connect to a radio to read its clock.")
        return
    }
    if (loading) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            HintText("Querying radio…")
        }
        return
    }
    val drift = radioTime?.let { it - System.currentTimeMillis() / 1000 }
    Text(
        "Radio clock: " + (radioTime?.let {
            DateFormat.getDateTimeInstance().format(Date(it * 1000))
        } ?: "no answer"),
    )
    drift?.let { d ->
        Text(
            "Drift: " + formatDrift(d),
            style = MaterialTheme.typography.bodyMedium,
            color = if (kotlin.math.abs(d) > 30) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
    ButtonFlowRow {
        OutlinedButton(onClick = {
            vm.syncDeviceClock()
            refresh++
        }) { Text("Sync from phone") }
        OutlinedButton(onClick = { refresh++ }) { Text("Re-read") }
    }
    HintText(
        "Radios without GPS lose their clock on power-cycle; the app auto-corrects drift " +
            ">30 s on connect. Drift matters beyond timestamps: message ordering and " +
            "reaction matching are both keyed on the sender's clock, so a skewed radio " +
            "makes other apps attach your reactions to the wrong message.",
    )
}

@Composable
private fun PoliciesSection(vm: MeshCoreViewModel) {
    val self by vm.selfInfo.collectAsState()
    val info = self
    if (info == null) {
        HintText("Connect to a radio to edit mesh policies.")
        return
    }

    // Decompose the SELF_INFO policy bytes.
    val teleBase = info.telemetryModes and 0x03
    val teleLoc = (info.telemetryModes shr 2) and 0x03
    val teleEnv = (info.telemetryModes shr 4) and 0x03

    fun apply(
        base: Int = teleBase, loc: Int = teleLoc, env: Int = teleEnv,
        locPolicy: Int = info.advertLocPolicy, multiAcks: Int = info.multiAcks,
    ) {
        vm.setOtherParams((env shl 4) or (loc shl 2) or base, locPolicy, multiAcks)
    }

    SettingRow("Include location in adverts", info.advertLocPolicy == 1) {
        apply(locPolicy = if (it) 1 else 0)
    }
    SettingRow("Multi-acks (redundant ACK copies)", info.multiAcks != 0) {
        apply(multiAcks = if (it) 1 else 0)
    }

    Spacer(Modifier.height(4.dp))
    Text("Telemetry access", style = MaterialTheme.typography.labelLarge)
    HintText("Who may read this node's telemetry: Deny / per-contact flags / everyone.")
    val teleOptions = listOf("Deny", "Flags", "All")
    Text("Base (battery)", style = MaterialTheme.typography.bodySmall)
    ChoiceChips(teleOptions, teleBase) { apply(base = it) }
    Text("Location", style = MaterialTheme.typography.bodySmall)
    ChoiceChips(teleOptions, teleLoc) { apply(loc = it) }
    Text("Environment", style = MaterialTheme.typography.bodySmall)
    ChoiceChips(teleOptions, teleEnv) { apply(env = it) }

    Spacer(Modifier.height(8.dp))
    Text("On-air path hash width", style = MaterialTheme.typography.labelLarge)
    HintText("Bytes per hop in packet paths (mode 0–3 → 1–4 bytes). All nodes on a mesh must match; firmware v10+.")
    // The active width is radio truth (DEVICE_INFO), not local state.
    val deviceInfo by vm.deviceInfo.collectAsState()
    val pathMode = ((deviceInfo?.pathHashByteWidth ?: 1) - 1).coerceIn(0, 3)
    ChoiceChips(listOf("1 B", "2 B", "3 B", "4 B"), pathMode) {
        vm.setPathHashMode(it)
    }

    Spacer(Modifier.height(8.dp))
    Text("Global flood scope", style = MaterialTheme.typography.labelLarge)
    HintText(
        "Restricts flood routing to a named region for everything this radio sends. " +
            "Blank = global. Per-channel regions (below) override it for the duration of " +
            "each send. Radio state can't be read back — this shows the last value set " +
            "from this app.",
    )
    val currentRegion by vm.floodScopeRegion.collectAsState()
    var region by remember(currentRegion) { mutableStateOf(currentRegion ?: "") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = region,
            onValueChange = { region = it },
            label = { Text("Region (e.g. bayarea)") },
            singleLine = true,
            isError = region.isNotBlank() && !Regions.isValid(region),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { vm.setFloodScope(region.trim()) },
            enabled = Regions.isValid(region),
        ) { Text("Set") }
        TextButton(onClick = {
            region = ""
            vm.setFloodScope(null)
        }) { Text("Clear") }
    }

    Spacer(Modifier.height(12.dp))
    Text("Regions", style = MaterialTheme.typography.labelLarge)
    RegionsSection(vm)
}

@Composable
private fun AutoAddSection(vm: MeshCoreViewModel) {
    val flags by vm.autoAddFlags.collectAsState()
    val current = flags
    if (current == null) {
        HintText("Connect to a radio to read the auto-add policy.")
        return
    }
    HintText("Which advert types are added to the contact list automatically when heard.")

    fun toggle(bit: Int, on: Boolean) {
        vm.setAutoAddConfig(if (on) current or bit else current and bit.inv())
    }
    SettingRow("Companions (chat)", current and Codes.AUTO_ADD_CHAT != 0) {
        toggle(Codes.AUTO_ADD_CHAT, it)
    }
    SettingRow("Repeaters", current and Codes.AUTO_ADD_REPEATER != 0) {
        toggle(Codes.AUTO_ADD_REPEATER, it)
    }
    SettingRow("Room servers", current and Codes.AUTO_ADD_ROOM != 0) {
        toggle(Codes.AUTO_ADD_ROOM, it)
    }
    SettingRow("Sensors", current and Codes.AUTO_ADD_SENSOR != 0) {
        toggle(Codes.AUTO_ADD_SENSOR, it)
    }
    SettingRow("Overwrite oldest when full", current and Codes.AUTO_ADD_OVERWRITE_OLDEST != 0) {
        toggle(Codes.AUTO_ADD_OVERWRITE_OLDEST, it)
    }
}

@Composable
private fun CustomVarsSection(vm: MeshCoreViewModel) {
    val vars by vm.customVars.collectAsState()
    val engineState by vm.engineState.collectAsState()
    if (engineState != EngineState.Ready) {
        HintText("Connect to a radio to read custom variables.")
        return
    }

    // GPS is the variable users actually flip — give it a switch.
    if (vars.containsKey("gps")) {
        SettingRow("GPS module", vars["gps"] == "1") {
            vm.setCustomVar("gps", if (it) "1" else "0")
        }
    }
    if (vars.isEmpty()) {
        HintText("No custom variables reported by this firmware.")
    } else {
        for ((k, v) in vars) {
            Text("$k: $v", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(Modifier.height(4.dp))
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { Text("Variable") }, singleLine = true, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = value, onValueChange = { value = it },
            label = { Text("Value") }, singleLine = true, modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                vm.setCustomVar(key.trim(), value.trim())
                key = ""; value = ""
            },
            enabled = key.isNotBlank(),
        ) { Text("Set") }
    }
}

@Composable
private fun ChannelsSection(vm: MeshCoreViewModel, onEdit: (ChannelEntity) -> Unit) {
    val channels by vm.dbChannels.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    if (channels.isEmpty()) {
        HintText("No channels configured yet.")
    }
    for (ch in channels) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${ch.idx}: ${ch.name.ifBlank { "(unnamed)" }}", Modifier.weight(1f))
            TextButton(onClick = { onEdit(ch) }) { Text("Edit") }
        }
    }
    ButtonFlowRow {
        OutlinedButton(onClick = { showAdd = true }) { Text("Add channel…") }
    }

    if (showAdd) {
        ChannelAddSheet(vm, onDismiss = { showAdd = false })
    }
}

@Composable
private fun AppSection(vm: MeshCoreViewModel) {
    var theme by remember { mutableStateOf(vm.prefs.theme) }
    var diagnostics by remember { mutableStateOf(vm.prefs.diagnosticsEnabled) }

    ButtonFlowRow {
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
    // Storage encryption status — if the Keystore failed, the user must
    // know the database is NOT encrypted rather than assume it is.
    val dbWarning = io.github.thatsfguy.meshcore.android.storage.DatabaseKey
        .encryptionUnavailableReason
    if (dbWarning == null) {
        HintText("Message storage is encrypted (SQLCipher, key sealed in the device keystore).")
    } else {
        Text(
            "⚠ $dbWarning",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    var tiles by remember { mutableStateOf(vm.prefs.mapTilesEnabled) }
    SettingRow("Load map tiles (network)", tiles) {
        tiles = it
        vm.prefs.mapTilesEnabled = it
    }
    HintText(
        "The map is the app's only outbound HTTP. Off = markers still plot, " +
            "but no tiles are fetched, so no third party learns where you're looking.",
    )

    var notifications by remember { mutableStateOf(vm.prefs.notificationsEnabled) }
    SettingRow("Message notifications", notifications) {
        notifications = it
        vm.prefs.notificationsEnabled = it
    }
    SettingRow("Diagnostics log (redaction-aware)", diagnostics) {
        diagnostics = it
        vm.prefs.diagnosticsEnabled = it
    }
    if (diagnostics) {
        DiagnosticsViewer(vm)
    }

    Spacer(Modifier.height(12.dp))
    Text("Backup", style = MaterialTheme.typography.labelLarge)
    BackupSection(vm)

    Spacer(Modifier.height(16.dp))
    DataSection(vm)
}

// ----------------------------------------------------------------------
// Shared pieces (also used by AddNodeSheet)
// ----------------------------------------------------------------------

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
            HintText("Log is empty. Secrets (passwords, keys, PSKs) are redacted before lines land here.")
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

/** The stern one-time warning SCOPE.md requires before TCP can be enabled. */
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
    val context = androidx.compose.ui.platform.LocalContext.current
    var discovered by remember {
        mutableStateOf(listOf<io.github.thatsfguy.meshcore.android.platform.DiscoveredDevice>())
    }

    if (vm.prefs.bleEnabled) {
        LaunchedEffect(Unit) {
            runCatching {
                io.github.thatsfguy.meshcore.android.platform.BleScanner.scan(context)
                    .collect { discovered = it }
            }
        }
    }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Add node", style = MaterialTheme.typography.headlineSmall)

            if (vm.prefs.bleEnabled) {
                Text(
                    "Bluetooth radios nearby",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (discovered.isEmpty()) {
                    HintText("Scanning… make sure the radio is powered and advertising.")
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
                HintText("Bluetooth transport is disabled in Settings → Transports.")
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            if (vm.prefs.usbEnabled) {
                Text("USB radios attached", style = MaterialTheme.typography.titleSmall)
                val usbDevices = remember {
                    io.github.thatsfguy.meshcore.android.platform.UsbDevices.attached(context)
                }
                if (usbDevices.isEmpty()) {
                    HintText("No supported USB-serial device attached (CDC-ACM / CP210x).")
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
                            io.github.thatsfguy.meshcore.android.platform.UsbDevices
                                .requestPermission(context, attached.device) { granted ->
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
                HintText(
                    "TCP transport is off. Enable it in Settings → Transports (requires " +
                        "acknowledging the plaintext warning).",
                )
            }
        }
    }
}

/**
 * Human-readable clock drift. Sign is stated in words rather than as a
 * bare "+"/"-", which nobody reads correctly under a status line.
 */
internal fun formatDrift(seconds: Long): String {
    val magnitude = kotlin.math.abs(seconds)
    val amount = when {
        magnitude == 0L -> return "none — clocks agree"
        magnitude < 60 -> "$magnitude s"
        magnitude < 3600 -> "${magnitude / 60} min ${magnitude % 60} s"
        magnitude < 86_400 -> "${magnitude / 3600} h ${(magnitude % 3600) / 60} min"
        else -> "${magnitude / 86_400} days"
    }
    return if (seconds > 0) "$amount (radio ahead)" else "$amount (radio behind)"
}
