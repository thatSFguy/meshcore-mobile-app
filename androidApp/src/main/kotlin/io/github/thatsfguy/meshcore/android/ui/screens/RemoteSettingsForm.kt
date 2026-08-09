package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.storage.ContactEntity
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.engine.EngineState
import io.github.thatsfguy.meshcore.protocol.CliReplies
import io.github.thatsfguy.meshcore.protocol.CliFormFields
import io.github.thatsfguy.meshcore.protocol.CliIds
import io.github.thatsfguy.meshcore.protocol.CliValues
import io.github.thatsfguy.meshcore.protocol.NodeRole
import io.github.thatsfguy.meshcore.protocol.PathHashMode
import io.github.thatsfguy.meshcore.protocol.RadioUnits
import kotlinx.coroutines.launch

/**
 * Remote (repeater/room) settings — the SAME surface as the local
 * Settings tab: collapsible [ExpandableSection]s that query their
 * values on expand ([QueryOnExpand]) and share every row/field/chip
 * component with it ([SettingsComponents]). Only the transport
 * differs: local settings speak companion frames, these speak the
 * text CLI (`get x` → `> value`, `set x v`).
 *
 * Save sends `set` commands only for fields you changed; switches
 * apply immediately; passwords are write-only.
 */
@Composable
fun RemoteSettingsForm(
    vm: MeshCoreViewModel,
    keyHex: String,
    contact: ContactEntity?,
    role: NodeRole,
    isAdmin: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val engineState by vm.engineState.collectAsState()
    val isReady = engineState == EngineState.Ready

    val values = remember { mutableStateMapOf<String, String>() }
    val dirty = remember { mutableStateMapOf<String, Boolean>() }
    var confirmAction by remember { mutableStateOf<Pair<String, String>?>(null) }
    var presetSheet by remember { mutableStateOf(false) }

    /**
     * A settings QR scanned while administering THIS node.
     *
     * Deliberately not routed through the app-wide scan handler: that
     * one applies a code to the radio in your hand, which is the right
     * default from Chats or Nodes but exactly wrong here, where the
     * whole screen is about a node across the mesh. Scanning from the
     * repeater's own Radio panel means the repeater.
     */
    var scannedConfig by remember {
        mutableStateOf<io.github.thatsfguy.meshcore.protocol.ShareUri.Decoded.RadioConfig?>(null)
    }
    var scanError by remember { mutableStateOf<String?>(null) }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        // Same decoder, same refusals — a code is no more trustworthy
        // for being scanned on an admin screen.
        //
        // Only a SETTINGS code is claimed here, because only that one
        // means something different on this screen than elsewhere: it
        // targets the node being administered rather than the radio in
        // your hand. Everything else falls through to the app-wide
        // handler, so scanning a contact card at this button still adds
        // the contact instead of complaining about the screen you chose
        // — which is the rule a test enforces after a scanner wired to
        // one decoder answered "Invalid community code" for a perfectly
        // good repeater card.
        when (val d = io.github.thatsfguy.meshcore.protocol.ShareUri.decode(text)) {
            is io.github.thatsfguy.meshcore.protocol.ShareUri.Decoded.RadioConfig ->
                scannedConfig = d
            is io.github.thatsfguy.meshcore.protocol.ShareUri.Decoded.UnsupportedVersion ->
                scanError = "That settings code needs a newer version of the app (v${d.version})."
            else ->
                vm.importScannedCode(text)
        }
    }

    /**
     * Set to the name of what was just saved when the node needs a
     * restart before it takes effect. `set radio` writes prefs only —
     * the firmware literally replies "OK - reboot to apply" — so
     * without this the operator gets a success and no change, which
     * reads as the feature being broken.
     */
    var pendingReboot by remember { mutableStateOf<String?>(null) }

    // Seed from the cached contact record so the form is never empty.
    remember(contact?.keyHex) {
        if (contact != null) {
            values.putIfAbsent(CliIds.NAME, contact.name)
            contact.latitude?.let { values.putIfAbsent(CliIds.LAT, it.toString()) }
            contact.longitude?.let { values.putIfAbsent(CliIds.LON, it.toString()) }
        }
        true
    }

    fun edit(id: String, v: String) {
        values[id] = v
        dirty[id] = true
    }

    /** Fetch [ids] one at a time into the form; clears their dirty flags. */
    suspend fun fetch(ids: List<String>): Int {
        var ok = 0
        for (id in ids) {
            val value = vm.cliQuery(keyHex, "get $id")?.let { CliReplies.extractGetValue(it) }
                ?: continue
            if (id == CliIds.RADIO) {
                CliReplies.parseRadioCsv(value)?.let { r ->
                    // The node stores frequency as a 32-bit float and
                    // prints it at full precision, so 910.525 comes back
                    // as 910.5250244 — the nearest float32, rendered
                    // honestly. tidyDecimal shortens it ONLY when the
                    // shorter form is the same number to the radio, so
                    // anything genuinely finer than a kHz survives.
                    values[CliFormFields.RADIO_FREQ] = RadioUnits.tidyDecimal(r.freqMhz.toString())
                    values[CliFormFields.RADIO_BW] = RadioUnits.tidyDecimal(r.bwKhz.toString())
                    values[CliFormFields.RADIO_SF] = r.sf.toString()
                    values[CliFormFields.RADIO_CR] = r.cr.toString()
                    CliFormFields.RADIO_FIELDS
                        .forEach { dirty[it] = false }
                    ok++
                }
            } else {
                // Firmware encodes newlines in owner.info as '|'.
                values[id] =
                    if (id == CliIds.OWNER_INFO) CliValues.decodeOwnerInfo(value).replace('\n', ' ')
                    else value
                dirty[id] = false
                ok++
            }
        }
        return ok
    }

    suspend fun send(commands: List<String>): Int =
        commands.count { vm.cliQuery(keyHex, it) != null }

    /** Immediate-apply switch (matches the local Settings tab's switches). */
    fun applySwitch(id: String, on: Boolean, onText: String = "on", offText: String = "off") {
        if (!isAdmin) return
        val word = if (on) onText else offText
        values[id] = word
        scope.launch { vm.cliQuery(keyHex, "set $id $word") }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        if (!isReady) {
            HintText("Connect to your radio to administer this node.")
        }
        if (!isAdmin) {
            HintText(
                "Read-only session: values can be fetched, but saving and destructive " +
                    "actions need an admin login.",
            )
        }

        RemoteSection(
            title = "Basic",
            isReady = isReady,
            fetchIds = listOf(CliIds.NAME, CliIds.OWNER_INFO),
            fetch = ::fetch,
            saveEnabled = isAdmin && (dirty[CliIds.NAME] == true || dirty[CliIds.OWNER_INFO] == true),
            buildSaves = {
                buildList {
                    values[CliIds.NAME]?.trim()?.takeIf { it.isNotEmpty() && dirty[CliIds.NAME] == true }
                        ?.let { add("set name $it") }
                    if (dirty[CliIds.OWNER_INFO] == true) {
                        add("set owner.info ${values[CliIds.OWNER_INFO].orEmpty().trim()}")
                    }
                }
            },
            send = ::send,
            clearDirty = { listOf(CliIds.NAME, CliIds.OWNER_INFO).forEach { dirty[it] = false } },
        ) {
            SettingsTextField("Name", values[CliIds.NAME].orEmpty()) { edit(CliIds.NAME, it) }
            SettingsTextField("Owner info", values[CliIds.OWNER_INFO].orEmpty()) { edit(CliIds.OWNER_INFO, it) }
        }

        RemoteSection(
            title = "Radio",
            isReady = isReady,
            fetchIds = listOf(CliIds.RADIO, CliIds.TX, CliIds.PATH_HASH_MODE),
            fetch = ::fetch,
            saveEnabled = isAdmin && listOf(
                CliFormFields.RADIO_FREQ,
                CliFormFields.RADIO_BW,
                CliFormFields.RADIO_SF,
                CliFormFields.RADIO_CR,
                CliIds.TX,
            )
                .any { dirty[it] == true },
            buildSaves = {
                buildList {
                    val f = values[CliFormFields.RADIO_FREQ]?.trim()?.toDoubleOrNull()
                    val bw = values[CliFormFields.RADIO_BW]?.trim()?.toDoubleOrNull()
                    val sf = values[CliFormFields.RADIO_SF]?.trim()?.toIntOrNull()
                    val cr = values[CliFormFields.RADIO_CR]?.trim()?.toIntOrNull()
                    val radioEdited = CliFormFields.RADIO_FIELDS
                        .any { dirty[it] == true }
                    if (radioEdited && f != null && bw != null && sf in 5..12 && cr in 5..8) {
                        add("set radio ${CliReplies.RadioCsv(f, bw, sf!!, cr!!).toCsv()}")
                        // Same as the preset path: the node writes these
                        // and keeps running on the old ones until it
                        // restarts. Typing them by hand is no different.
                        pendingReboot = "The radio settings you saved"
                    }
                    values[CliIds.TX]?.trim()?.toIntOrNull()?.let {
                        if (dirty[CliIds.TX] == true) add("set tx $it")
                    }
                }
            },
            send = ::send,
            clearDirty = {
                listOf(
                    CliFormFields.RADIO_FREQ,
                    CliFormFields.RADIO_BW,
                    CliFormFields.RADIO_SF,
                    CliFormFields.RADIO_CR,
                    CliIds.TX,
                )
                    .forEach { dirty[it] = false }
            },
        ) {
            HintText("Parameters must match your mesh or the node goes deaf.")
            if (isAdmin) {
                ButtonFlowRow {
                    TextButton(
                        enabled = isReady,
                        onClick = { presetSheet = true },
                    ) { Text("Use a regional preset…") }
                    TextButton(
                        enabled = isReady,
                        onClick = { scanLauncher.launch(ScanOptions().setBeepEnabled(false)) },
                    ) { Text("Scan settings QR…") }
                }
            }
            Row {
                SettingsTextField(
                    "Freq (MHz)", values[CliFormFields.RADIO_FREQ].orEmpty(), Modifier.weight(1.2f),
                ) { edit(CliFormFields.RADIO_FREQ, it) }
                Spacer(Modifier.width(6.dp))
                SettingsTextField("BW (kHz)", values[CliFormFields.RADIO_BW].orEmpty(), Modifier.weight(1f)) {
                    edit(CliFormFields.RADIO_BW, it)
                }
            }
            Row {
                SettingsTextField("SF", values[CliFormFields.RADIO_SF].orEmpty(), Modifier.weight(1f)) {
                    edit(CliFormFields.RADIO_SF, it)
                }
                Spacer(Modifier.width(6.dp))
                SettingsTextField("CR", values[CliFormFields.RADIO_CR].orEmpty(), Modifier.weight(1f)) {
                    edit(CliFormFields.RADIO_CR, it)
                }
                Spacer(Modifier.width(6.dp))
                SettingsTextField("TX (dBm)", values[CliIds.TX].orEmpty(), Modifier.weight(1f)) {
                    edit(CliIds.TX, it)
                }
            }

            // Applies on tap rather than on Save, like the switches: the
            // chips ARE the committed value, and a chip that shows a
            // selection the node has not been told about is a lie.
            Spacer(Modifier.height(8.dp))
            Text("On-air path hash width", style = MaterialTheme.typography.labelLarge)
            HintText("Bytes per hop in packet paths. Every node on the mesh must match.")
            // Blank until the node answers `get path.hash.mode` — the
            // width is mesh truth and guessing a default would show a
            // selection nobody chose. -1 selects nothing.
            val hashMode = values[CliIds.PATH_HASH_MODE]?.trim()?.toIntOrNull()
                ?.takeIf { PathHashMode.isValid(it) }
            ChoiceChips(
                PathHashMode.LABELS,
                selected = hashMode ?: -1,
                enabled = isAdmin && isReady,
            ) { mode ->
                values[CliIds.PATH_HASH_MODE] = mode.toString()
                scope.launch { vm.cliQuery(keyHex, "set ${CliIds.PATH_HASH_MODE} $mode") }
            }
            if (hashMode == null) {
                HintText("Fetch to read the node's current width.")
            }
        }

        RemoteSection(
            title = "Position",
            isReady = isReady,
            fetchIds = listOf(CliIds.LAT, CliIds.LON),
            fetch = ::fetch,
            saveEnabled = isAdmin && (dirty[CliIds.LAT] == true || dirty[CliIds.LON] == true),
            buildSaves = {
                buildList {
                    values[CliIds.LAT]?.trim()?.toDoubleOrNull()?.takeIf {
                        dirty[CliIds.LAT] == true && it in -90.0..90.0
                    }?.let { add("set lat $it") }
                    values[CliIds.LON]?.trim()?.toDoubleOrNull()?.takeIf {
                        dirty[CliIds.LON] == true && it in -180.0..180.0
                    }?.let { add("set lon $it") }
                }
            },
            send = ::send,
            clearDirty = { listOf(CliIds.LAT, CliIds.LON).forEach { dirty[it] = false } },
        ) {
            Row {
                SettingsTextField("Latitude", values[CliIds.LAT].orEmpty(), Modifier.weight(1f)) {
                    edit(CliIds.LAT, it)
                }
                Spacer(Modifier.width(6.dp))
                SettingsTextField("Longitude", values[CliIds.LON].orEmpty(), Modifier.weight(1f)) {
                    edit(CliIds.LON, it)
                }
            }
        }

        RemoteSection(
            title = "Adverts",
            isReady = isReady,
            fetchIds = listOf(CliIds.ADVERT_INTERVAL, CliIds.FLOOD_ADVERT_INTERVAL),
            fetch = ::fetch,
            saveEnabled = isAdmin && (
                dirty[CliIds.ADVERT_INTERVAL] == true || dirty[CliIds.FLOOD_ADVERT_INTERVAL] == true
                ),
            buildSaves = {
                buildList {
                    for (id in listOf(CliIds.ADVERT_INTERVAL, CliIds.FLOOD_ADVERT_INTERVAL)) {
                        values[id]?.trim()?.toIntOrNull()?.let {
                            if (dirty[id] == true) add("set $id $it")
                        }
                    }
                }
            },
            send = ::send,
            clearDirty = {
                listOf(
                    CliIds.ADVERT_INTERVAL,
                    CliIds.FLOOD_ADVERT_INTERVAL,
                ).forEach { dirty[it] = false }
            },
            extraActions = { setStatus ->
                TextButton(onClick = {
                    scope.launch {
                        vm.cliQuery(keyHex, CliIds.ADVERT)
                        setStatus("Advert sent")
                    }
                }) { Text("Send advert now") }
            },
        ) {
            Row {
                SettingsTextField(
                    "Zero-hop (mins)", values[CliIds.ADVERT_INTERVAL].orEmpty(), Modifier.weight(1f),
                ) { edit(CliIds.ADVERT_INTERVAL, it) }
                Spacer(Modifier.width(6.dp))
                SettingsTextField(
                    "Flood (hours)", values[CliIds.FLOOD_ADVERT_INTERVAL].orEmpty(), Modifier.weight(1f),
                ) { edit(CliIds.FLOOD_ADVERT_INTERVAL, it) }
            }
        }

        if (role == NodeRole.Repeater) {
            RemoteSection(
                title = "Packet forwarding",
                isReady = isReady,
                fetchIds = listOf(
                    CliIds.REPEAT,
                    CliIds.FLOOD_MAX,
                    CliIds.RXDELAY,
                    CliIds.TXDELAY,
                    CliIds.DIRECT_TXDELAY,
                ),
                fetch = ::fetch,
                saveEnabled = isAdmin && listOf(
                    CliIds.FLOOD_MAX,
                    CliIds.RXDELAY,
                    CliIds.TXDELAY,
                    CliIds.DIRECT_TXDELAY,
                )
                    .any { dirty[it] == true },
                buildSaves = {
                    buildList {
                        for (id in listOf(
                            CliIds.FLOOD_MAX,
                            CliIds.RXDELAY,
                            CliIds.TXDELAY,
                            CliIds.DIRECT_TXDELAY,
                        )) {
                            values[id]?.trim()?.takeIf { it.isNotEmpty() && dirty[id] == true }
                                ?.let { add("set $id $it") }
                        }
                    }
                },
                send = ::send,
                clearDirty = {
                    listOf(CliIds.FLOOD_MAX, CliIds.RXDELAY, CliIds.TXDELAY, CliIds.DIRECT_TXDELAY)
                        .forEach { dirty[it] = false }
                },
            ) {
                SettingRow(
                    "Repeat (forward packets)",
                    CliReplies.isTruthy(values[CliIds.REPEAT].orEmpty()),
                ) { applySwitch(CliIds.REPEAT, it) }
                Row {
                    SettingsTextField(
                        "Flood max hops", values[CliIds.FLOOD_MAX].orEmpty(), Modifier.weight(1f),
                    ) { edit(CliIds.FLOOD_MAX, it) }
                    Spacer(Modifier.width(6.dp))
                    SettingsTextField("RX delay", values[CliIds.RXDELAY].orEmpty(), Modifier.weight(1f)) {
                        edit(CliIds.RXDELAY, it)
                    }
                }
                Row {
                    SettingsTextField("TX delay", values[CliIds.TXDELAY].orEmpty(), Modifier.weight(1f)) {
                        edit(CliIds.TXDELAY, it)
                    }
                    Spacer(Modifier.width(6.dp))
                    SettingsTextField(
                        "Direct TX delay", values[CliIds.DIRECT_TXDELAY].orEmpty(), Modifier.weight(1f),
                    ) { edit(CliIds.DIRECT_TXDELAY, it) }
                }
            }
        }

        RemoteSection(
            title = "Access & security",
            isReady = isReady,
            fetchIds = if (role == NodeRole.Room) listOf(CliIds.ALLOW_READ_ONLY) else emptyList(),
            fetch = ::fetch,
            saveEnabled = isAdmin && (
                !values[CliFormFields.PASSWORD_NEW].isNullOrBlank() ||
                    !values[CliFormFields.GUEST_PASSWORD_NEW].isNullOrBlank()
                ),
            buildSaves = {
                buildList {
                    values[CliFormFields.PASSWORD_NEW]?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { add("password $it") }
                    values[CliFormFields.GUEST_PASSWORD_NEW]?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { add("set guest.password $it") }
                }
            },
            send = ::send,
            clearDirty = {
                values.remove(CliFormFields.PASSWORD_NEW)
                values.remove(CliFormFields.GUEST_PASSWORD_NEW)
            },
        ) {
            if (role == NodeRole.Room) {
                SettingRow(
                    "Allow read-only (guest) logins",
                    CliReplies.isTruthy(values[CliIds.ALLOW_READ_ONLY].orEmpty()),
                ) { applySwitch(CliIds.ALLOW_READ_ONLY, it) }
            }
            // Passwords are write-only — blank fields that only send
            // when filled.
            SettingsTextField(
                "New admin password", values[CliFormFields.PASSWORD_NEW].orEmpty(), sensitive = true,
            ) { values[CliFormFields.PASSWORD_NEW] = it }
            SettingsTextField(
                "New guest password", values[CliFormFields.GUEST_PASSWORD_NEW].orEmpty(), sensitive = true,
            ) { values[CliFormFields.GUEST_PASSWORD_NEW] = it }
            HintText("Passwords are never read back from the node and stay out of the log.")
        }

        RemoteSection(
            title = "Advanced",
            isReady = isReady,
            fetchIds = listOf(
                CliIds.RADIO_RXGAIN,
                CliIds.DUTYCYCLE,
                CliIds.MULTI_ACKS,
                CliIds.INT_THRESH,
                CliIds.LOOP_DETECT,
            ),
            fetch = ::fetch,
            saveEnabled = isAdmin && listOf(CliIds.DUTYCYCLE, CliIds.INT_THRESH).any { dirty[it] == true },
            buildSaves = {
                buildList {
                    // Coercions live in CliValues (unit-tested, positive
                    // and negative) because a bad one sends a malformed
                    // `set` to somebody's repeater.
                    if (dirty[CliIds.DUTYCYCLE] == true) {
                        CliValues.parsePercent(values[CliIds.DUTYCYCLE].orEmpty())
                            ?.let { add("set dutycycle $it") }
                    }
                    if (dirty[CliIds.INT_THRESH] == true) {
                        CliValues.parseInt(values[CliIds.INT_THRESH].orEmpty())
                            ?.let { add("set int.thresh $it") }
                    }
                }
            },
            send = ::send,
            clearDirty = {
                listOf(CliIds.DUTYCYCLE, CliIds.INT_THRESH).forEach { dirty[it] = false }
            },
        ) {
            SettingRow(
                "Multi-acks (redundant ACKs)",
                CliReplies.isTruthy(values[CliIds.MULTI_ACKS].orEmpty()),
            ) {
                applySwitch(
                    CliIds.MULTI_ACKS, it,
                    onText = CliValues.oneZero(true), offText = CliValues.oneZero(false),
                )
            }
            // RX gain is a BOOLEAN in firmware (LNA boost on/off), not a
            // numeric gain value — a text field here would send garbage.
            SettingRow(
                "RX gain boost (LNA)",
                CliReplies.isTruthy(values[CliIds.RADIO_RXGAIN].orEmpty()),
            ) { applySwitch(CliIds.RADIO_RXGAIN, it) }
            Row {
                SettingsTextField(
                    "Duty cycle %", values[CliIds.DUTYCYCLE].orEmpty().trimEnd('%'), Modifier.weight(1f),
                ) { edit(CliIds.DUTYCYCLE, it) }
                Spacer(Modifier.width(6.dp))
                SettingsTextField(
                    "Int. thresh", values[CliIds.INT_THRESH].orEmpty(), Modifier.weight(1f),
                ) { edit(CliIds.INT_THRESH, it) }
            }
            // loop.detect is an enumerated mode, not free text.
            Text("Loop detection", style = MaterialTheme.typography.labelMedium)
            val loopModes = CliValues.LOOP_DETECT_MODES
            // An unrecognised reply selects nothing rather than falsely
            // showing "off".
            val loopSelected = CliValues.parseLoopDetect(values[CliIds.LOOP_DETECT].orEmpty()) ?: -1
            ChoiceChips(
                loopModes.map { it.replaceFirstChar { c -> c.uppercase() } },
                loopSelected,
                enabled = isAdmin,
            ) { i ->
                values[CliIds.LOOP_DETECT] = loopModes[i]
                scope.launch { vm.cliQuery(keyHex, "set loop.detect ${loopModes[i]}") }
            }
            if (loopSelected < 0 && !values[CliIds.LOOP_DETECT].isNullOrBlank()) {
                HintText("Node reported an unrecognised mode: ${values[CliIds.LOOP_DETECT]}")
            }
        }

        ExpandableSection("Maintenance") {
            HintText("Replies appear in the Console tab.")
            ButtonFlowRow {
                for ((label, command) in buildList {
                    add("Clock" to CliIds.CLOCK)
                    add("Clock sync" to "clock sync")
                    add("Version" to CliIds.VER)
                    add("Board" to CliIds.BOARD)
                    if (role == NodeRole.Repeater) add("Neighbors" to CliIds.NEIGHBORS)
                    add("ACL" to "get acl")
                    add("Log start" to "log start")
                    add("Log stop" to "log stop")
                }) {
                    TextButton(onClick = { scope.launch { vm.cliQuery(keyHex, command) } }) {
                        Text(label)
                    }
                }
            }
            if (isAdmin) ButtonFlowRow {
                for ((label, command) in listOf(
                    "Reboot" to CliIds.REBOOT,
                    "Clear stats" to "clear stats",
                    "Log erase" to "log erase",
                    "Factory erase" to CliIds.ERASE,
                    "Start OTA" to "start ota",
                )) {
                    TextButton(onClick = { confirmAction = label to command }) {
                        Text(label, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    if (presetSheet) {
        RadioPresetSheet(
            vm = vm,
            onDismiss = { presetSheet = false },
            targetName = contact?.name?.ifBlank { null } ?: keyHex.take(12),
            onApply = { preset ->
                scope.launch {
                    vm.cliQuery(keyHex, "set ${CliIds.TX} ${preset.txPowerDbm}")
                    vm.cliQuery(keyHex, "set ${CliIds.RADIO} ${preset.toRadioCsv()}")
                    // `set radio` over the CLI only writes prefs — the
                    // firmware answers "OK - reboot to apply" and keeps
                    // running on the old parameters until it restarts
                    // (CommonCLI.cpp:571). So the node is still on the
                    // air and still reachable, and re-reading it now
                    // would show the OLD values, which is why the form
                    // shows what was asked for instead.
                    values[CliFormFields.RADIO_FREQ] =
                        RadioUnits.khzToMhzText(preset.frequencyKhz)
                    values[CliFormFields.RADIO_BW] = RadioUnits.hzToKhzText(preset.bandwidthHz)
                    values[CliFormFields.RADIO_SF] = preset.spreadingFactor.toString()
                    values[CliFormFields.RADIO_CR] = preset.codingRate.toString()
                    values[CliIds.TX] = preset.txPowerDbm.toString()
                    CliFormFields.RADIO_FIELDS.forEach { dirty[it] = false }
                    dirty[CliIds.TX] = false
                    // Nothing has changed on air yet. Saying so — and
                    // offering the reboot — is the difference between a
                    // preset that works and one that looks like it did
                    // nothing.
                    pendingReboot = preset.name
                }
            },
        )
    }

    scanError?.let { message ->
        AlertDialog(
            onDismissRequest = { scanError = null },
            title = { Text("Can't use that code") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { scanError = null }) { Text("OK") } },
        )
    }

    scannedConfig?.let { config ->
        val node = contact?.name?.ifBlank { null } ?: keyHex.take(12)
        AlertDialog(
            onDismissRequest = { scannedConfig = null },
            title = { Text("Apply these settings to $node?") },
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
                        "This is a remote node. The settings save now and take effect when " +
                            "$node reboots; after that this radio must be on the same " +
                            "settings to reach it again.",
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
                        Text(
                            "It also names flood region \"$it\", which is not written here — " +
                                "regions are added on the node under Regions.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = config
                    scannedConfig = null
                    scope.launch {
                        // TX is untouched: it is not in the code, and it
                        // is a local legal limit rather than a property
                        // of the mesh.
                        val csv = RadioUnits.khzToMhzText(c.frequencyKhz) + "," +
                            RadioUnits.hzToKhzText(c.bandwidthHz) + "," +
                            c.spreadingFactor + "," + c.codingRate
                        vm.cliQuery(keyHex, "set ${CliIds.RADIO} $csv")
                        vm.cliQuery(keyHex, "set ${CliIds.PATH_HASH_MODE} ${c.pathHashMode}")
                        values[CliFormFields.RADIO_FREQ] =
                            RadioUnits.khzToMhzText(c.frequencyKhz)
                        values[CliFormFields.RADIO_BW] = RadioUnits.hzToKhzText(c.bandwidthHz)
                        values[CliFormFields.RADIO_SF] = c.spreadingFactor.toString()
                        values[CliFormFields.RADIO_CR] = c.codingRate.toString()
                        values[CliIds.PATH_HASH_MODE] = c.pathHashMode.toString()
                        CliFormFields.RADIO_FIELDS.forEach { dirty[it] = false }
                        pendingReboot = c.name.ifBlank { "The scanned settings" }
                    }
                }) { Text("Apply", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { scannedConfig = null }) { Text("Cancel") }
            },
        )
    }

    pendingReboot?.let { what ->
        val node = contact?.name?.ifBlank { null } ?: keyHex.take(12)
        AlertDialog(
            onDismissRequest = { pendingReboot = null },
            title = { Text("Reboot $node to apply?") },
            text = {
                Column {
                    Text(
                        "$what is saved on $node, but the node is still running on its old " +
                            "radio settings. It applies them when it restarts.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "After the reboot this radio must be on the same settings to reach " +
                            "$node again. If they do not match, you will need physical " +
                            "access to the node.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { vm.cliQuery(keyHex, CliIds.REBOOT) }
                    pendingReboot = null
                    vm.transientMessage.value = "Reboot sent to $node"
                }) { Text("Reboot now", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReboot = null }) { Text("Later") }
            },
        )
    }

    confirmAction?.let { (label, command) ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(label) },
            text = { Text("This action is destructive or hard to undo. Send \"$command\"?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { vm.cliQuery(keyHex, command) }
                    confirmAction = null
                }) { Text("Send", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * One remote settings section: same collapsible chrome as the local
 * Settings tab, with the CLI fetch wired to the expand gesture and a
 * Save row for the dirty fields.
 */
@Composable
private fun RemoteSection(
    title: String,
    isReady: Boolean,
    fetchIds: List<String>,
    fetch: suspend (List<String>) -> Int,
    saveEnabled: Boolean,
    buildSaves: () -> List<String>,
    send: suspend (List<String>) -> Int,
    clearDirty: () -> Unit,
    extraActions: (@Composable (setStatus: (String) -> Unit) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    ExpandableSection(title) {
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }

        QueryOnExpand(
            enabled = isReady && fetchIds.isNotEmpty(),
            query = {
                val ok = fetch(fetchIds)
                status = if (ok > 0) null else "No reply — connected and logged in?"
            },
        ) {
            content()
            ButtonFlowRow {
                extraActions?.invoke { status = it }
                TextButton(
                    enabled = isReady && fetchIds.isNotEmpty() && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            status = null
                            val ok = fetch(fetchIds)
                            status = if (ok > 0) "Refreshed $ok value(s)" else "No reply"
                            busy = false
                        }
                    },
                ) { Text("Refresh") }
                TextButton(
                    enabled = saveEnabled && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            status = null
                            val commands = buildSaves()
                            val ok = send(commands)
                            status = when {
                                commands.isEmpty() -> "Nothing valid to save"
                                ok == commands.size -> "Saved"
                                else -> "Saved $ok/${commands.size} — check Console"
                            }
                            if (ok == commands.size) clearDirty()
                            busy = false
                        }
                    },
                ) { Text("Save") }
            }
            if (busy) SectionSpinner("Talking to node…")
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
