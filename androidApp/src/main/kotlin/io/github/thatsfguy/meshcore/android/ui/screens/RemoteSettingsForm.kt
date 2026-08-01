package io.github.thatsfguy.meshcore.android.ui.screens

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
import io.github.thatsfguy.meshcore.protocol.NodeRole
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

    // Seed from the cached contact record so the form is never empty.
    remember(contact?.keyHex) {
        if (contact != null) {
            values.putIfAbsent("name", contact.name)
            contact.latitude?.let { values.putIfAbsent("lat", it.toString()) }
            contact.longitude?.let { values.putIfAbsent("lon", it.toString()) }
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
            if (id == "radio") {
                CliReplies.parseRadioCsv(value)?.let { r ->
                    values["radio.freq"] = r.freqMhz.toString()
                    values["radio.bw"] = r.bwKhz.toString()
                    values["radio.sf"] = r.sf.toString()
                    values["radio.cr"] = r.cr.toString()
                    listOf("radio.freq", "radio.bw", "radio.sf", "radio.cr")
                        .forEach { dirty[it] = false }
                    ok++
                }
            } else {
                // Firmware encodes newlines in owner.info as '|'.
                values[id] = if (id == "owner.info") value.replace('|', ' ') else value
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
        values[id] = if (on) onText else offText
        scope.launch { vm.cliQuery(keyHex, "set $id ${if (on) onText else offText}") }
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
            fetchIds = listOf("name", "owner.info"),
            fetch = ::fetch,
            saveEnabled = isAdmin && (dirty["name"] == true || dirty["owner.info"] == true),
            buildSaves = {
                buildList {
                    values["name"]?.trim()?.takeIf { it.isNotEmpty() && dirty["name"] == true }
                        ?.let { add("set name $it") }
                    if (dirty["owner.info"] == true) {
                        add("set owner.info ${values["owner.info"].orEmpty().trim()}")
                    }
                }
            },
            send = ::send,
            clearDirty = { listOf("name", "owner.info").forEach { dirty[it] = false } },
        ) {
            SettingsTextField("Name", values["name"].orEmpty()) { edit("name", it) }
            SettingsTextField("Owner info", values["owner.info"].orEmpty()) { edit("owner.info", it) }
        }

        RemoteSection(
            title = "Radio",
            isReady = isReady,
            fetchIds = listOf("radio", "tx"),
            fetch = ::fetch,
            saveEnabled = isAdmin && listOf("radio.freq", "radio.bw", "radio.sf", "radio.cr", "tx")
                .any { dirty[it] == true },
            buildSaves = {
                buildList {
                    val f = values["radio.freq"]?.trim()?.toDoubleOrNull()
                    val bw = values["radio.bw"]?.trim()?.toDoubleOrNull()
                    val sf = values["radio.sf"]?.trim()?.toIntOrNull()
                    val cr = values["radio.cr"]?.trim()?.toIntOrNull()
                    val radioEdited = listOf("radio.freq", "radio.bw", "radio.sf", "radio.cr")
                        .any { dirty[it] == true }
                    if (radioEdited && f != null && bw != null && sf in 5..12 && cr in 5..8) {
                        add("set radio ${CliReplies.RadioCsv(f, bw, sf!!, cr!!).toCsv()}")
                    }
                    values["tx"]?.trim()?.toIntOrNull()?.let {
                        if (dirty["tx"] == true) add("set tx $it")
                    }
                }
            },
            send = ::send,
            clearDirty = {
                listOf("radio.freq", "radio.bw", "radio.sf", "radio.cr", "tx")
                    .forEach { dirty[it] = false }
            },
        ) {
            HintText("Parameters must match your mesh or the node goes deaf.")
            Row {
                SettingsTextField(
                    "Freq (MHz)", values["radio.freq"].orEmpty(), Modifier.weight(1.2f),
                ) { edit("radio.freq", it) }
                Spacer(Modifier.width(6.dp))
                SettingsTextField("BW (kHz)", values["radio.bw"].orEmpty(), Modifier.weight(1f)) {
                    edit("radio.bw", it)
                }
            }
            Row {
                SettingsTextField("SF", values["radio.sf"].orEmpty(), Modifier.weight(1f)) {
                    edit("radio.sf", it)
                }
                Spacer(Modifier.width(6.dp))
                SettingsTextField("CR", values["radio.cr"].orEmpty(), Modifier.weight(1f)) {
                    edit("radio.cr", it)
                }
                Spacer(Modifier.width(6.dp))
                SettingsTextField("TX (dBm)", values["tx"].orEmpty(), Modifier.weight(1f)) {
                    edit("tx", it)
                }
            }
        }

        RemoteSection(
            title = "Position",
            isReady = isReady,
            fetchIds = listOf("lat", "lon"),
            fetch = ::fetch,
            saveEnabled = isAdmin && (dirty["lat"] == true || dirty["lon"] == true),
            buildSaves = {
                buildList {
                    values["lat"]?.trim()?.toDoubleOrNull()?.takeIf {
                        dirty["lat"] == true && it in -90.0..90.0
                    }?.let { add("set lat $it") }
                    values["lon"]?.trim()?.toDoubleOrNull()?.takeIf {
                        dirty["lon"] == true && it in -180.0..180.0
                    }?.let { add("set lon $it") }
                }
            },
            send = ::send,
            clearDirty = { listOf("lat", "lon").forEach { dirty[it] = false } },
        ) {
            Row {
                SettingsTextField("Latitude", values["lat"].orEmpty(), Modifier.weight(1f)) {
                    edit("lat", it)
                }
                Spacer(Modifier.width(6.dp))
                SettingsTextField("Longitude", values["lon"].orEmpty(), Modifier.weight(1f)) {
                    edit("lon", it)
                }
            }
        }

        RemoteSection(
            title = "Adverts",
            isReady = isReady,
            fetchIds = listOf("advert.interval", "flood.advert.interval"),
            fetch = ::fetch,
            saveEnabled = isAdmin && (
                dirty["advert.interval"] == true || dirty["flood.advert.interval"] == true
                ),
            buildSaves = {
                buildList {
                    for (id in listOf("advert.interval", "flood.advert.interval")) {
                        values[id]?.trim()?.toIntOrNull()?.let {
                            if (dirty[id] == true) add("set $id $it")
                        }
                    }
                }
            },
            send = ::send,
            clearDirty = {
                listOf("advert.interval", "flood.advert.interval").forEach { dirty[it] = false }
            },
            extraActions = { setStatus ->
                TextButton(onClick = {
                    scope.launch {
                        vm.cliQuery(keyHex, "advert")
                        setStatus("Advert sent")
                    }
                }) { Text("Send advert now") }
            },
        ) {
            Row {
                SettingsTextField(
                    "Zero-hop (mins)", values["advert.interval"].orEmpty(), Modifier.weight(1f),
                ) { edit("advert.interval", it) }
                Spacer(Modifier.width(6.dp))
                SettingsTextField(
                    "Flood (hours)", values["flood.advert.interval"].orEmpty(), Modifier.weight(1f),
                ) { edit("flood.advert.interval", it) }
            }
        }

        if (role == NodeRole.Repeater) {
            RemoteSection(
                title = "Packet forwarding",
                isReady = isReady,
                fetchIds = listOf("repeat", "flood.max", "rxdelay", "txdelay", "direct.txdelay"),
                fetch = ::fetch,
                saveEnabled = isAdmin && listOf("flood.max", "rxdelay", "txdelay", "direct.txdelay")
                    .any { dirty[it] == true },
                buildSaves = {
                    buildList {
                        for (id in listOf("flood.max", "rxdelay", "txdelay", "direct.txdelay")) {
                            values[id]?.trim()?.takeIf { it.isNotEmpty() && dirty[id] == true }
                                ?.let { add("set $id $it") }
                        }
                    }
                },
                send = ::send,
                clearDirty = {
                    listOf("flood.max", "rxdelay", "txdelay", "direct.txdelay")
                        .forEach { dirty[it] = false }
                },
            ) {
                SettingRow(
                    "Repeat (forward packets)",
                    CliReplies.isTruthy(values["repeat"].orEmpty()),
                ) { applySwitch("repeat", it) }
                Row {
                    SettingsTextField(
                        "Flood max hops", values["flood.max"].orEmpty(), Modifier.weight(1f),
                    ) { edit("flood.max", it) }
                    Spacer(Modifier.width(6.dp))
                    SettingsTextField("RX delay", values["rxdelay"].orEmpty(), Modifier.weight(1f)) {
                        edit("rxdelay", it)
                    }
                }
                Row {
                    SettingsTextField("TX delay", values["txdelay"].orEmpty(), Modifier.weight(1f)) {
                        edit("txdelay", it)
                    }
                    Spacer(Modifier.width(6.dp))
                    SettingsTextField(
                        "Direct TX delay", values["direct.txdelay"].orEmpty(), Modifier.weight(1f),
                    ) { edit("direct.txdelay", it) }
                }
            }
        }

        RemoteSection(
            title = "Access & security",
            isReady = isReady,
            fetchIds = if (role == NodeRole.Room) listOf("allow.read.only") else emptyList(),
            fetch = ::fetch,
            saveEnabled = isAdmin && (
                !values["password.new"].isNullOrBlank() ||
                    !values["guest.password.new"].isNullOrBlank()
                ),
            buildSaves = {
                buildList {
                    values["password.new"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { add("password $it") }
                    values["guest.password.new"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { add("set guest.password $it") }
                }
            },
            send = ::send,
            clearDirty = {
                values.remove("password.new")
                values.remove("guest.password.new")
            },
        ) {
            if (role == NodeRole.Room) {
                SettingRow(
                    "Allow read-only (guest) logins",
                    CliReplies.isTruthy(values["allow.read.only"].orEmpty()),
                ) { applySwitch("allow.read.only", it) }
            }
            // Passwords are write-only — blank fields that only send
            // when filled.
            SettingsTextField(
                "New admin password", values["password.new"].orEmpty(), sensitive = true,
            ) { values["password.new"] = it }
            SettingsTextField(
                "New guest password", values["guest.password.new"].orEmpty(), sensitive = true,
            ) { values["guest.password.new"] = it }
            HintText("Passwords are never read back from the node and stay out of the log.")
        }

        RemoteSection(
            title = "Advanced",
            isReady = isReady,
            fetchIds = listOf("radio.rxgain", "dutycycle", "multi.acks", "int.thresh", "loop.detect"),
            fetch = ::fetch,
            saveEnabled = isAdmin && listOf("dutycycle", "int.thresh").any { dirty[it] == true },
            buildSaves = {
                buildList {
                    // dutycycle is a whole percent on the wire even though
                    // the firmware REPLIES "50.0%".
                    values["dutycycle"]?.trim()?.trimEnd('%')?.substringBefore('.')
                        ?.toIntOrNull()?.takeIf { dirty["dutycycle"] == true && it in 1..100 }
                        ?.let { add("set dutycycle $it") }
                    values["int.thresh"]?.trim()?.takeIf {
                        it.isNotEmpty() && dirty["int.thresh"] == true
                    }?.let { add("set int.thresh $it") }
                }
            },
            send = ::send,
            clearDirty = {
                listOf("dutycycle", "int.thresh").forEach { dirty[it] = false }
            },
        ) {
            SettingRow(
                "Multi-acks (redundant ACKs)",
                CliReplies.isTruthy(values["multi.acks"].orEmpty()),
            ) { applySwitch("multi.acks", it, onText = "1", offText = "0") }
            // RX gain is a BOOLEAN in firmware (LNA boost on/off), not a
            // numeric gain value — a text field here would send garbage.
            SettingRow(
                "RX gain boost (LNA)",
                CliReplies.isTruthy(values["radio.rxgain"].orEmpty()),
            ) { applySwitch("radio.rxgain", it) }
            Row {
                SettingsTextField(
                    "Duty cycle %", values["dutycycle"].orEmpty().trimEnd('%'), Modifier.weight(1f),
                ) { edit("dutycycle", it) }
                Spacer(Modifier.width(6.dp))
                SettingsTextField(
                    "Int. thresh", values["int.thresh"].orEmpty(), Modifier.weight(1f),
                ) { edit("int.thresh", it) }
            }
            // loop.detect is an enumerated mode, not free text.
            Text("Loop detection", style = MaterialTheme.typography.labelMedium)
            val loopModes = listOf("off", "minimal", "moderate", "strict")
            ChoiceChips(
                loopModes.map { it.replaceFirstChar { c -> c.uppercase() } },
                loopModes.indexOf(values["loop.detect"]?.trim()?.lowercase()).coerceAtLeast(0),
                enabled = isAdmin,
            ) { i ->
                values["loop.detect"] = loopModes[i]
                scope.launch { vm.cliQuery(keyHex, "set loop.detect ${loopModes[i]}") }
            }
        }

        ExpandableSection("Maintenance") {
            HintText("Replies appear in the Console tab.")
            ButtonFlowRow {
                for ((label, command) in buildList {
                    add("Clock" to "clock")
                    add("Clock sync" to "clock sync")
                    add("Version" to "ver")
                    add("Board" to "board")
                    if (role == NodeRole.Repeater) add("Neighbors" to "neighbors")
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
                    "Reboot" to "reboot",
                    "Clear stats" to "clear stats",
                    "Log erase" to "log erase",
                    "Factory erase" to "erase",
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
