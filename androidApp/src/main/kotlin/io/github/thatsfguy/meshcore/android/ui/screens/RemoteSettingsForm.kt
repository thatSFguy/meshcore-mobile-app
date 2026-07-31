package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.storage.ContactEntity
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.CliReplies
import io.github.thatsfguy.meshcore.protocol.NodeRole
import kotlinx.coroutines.launch

/**
 * Form-based remote settings for a repeater/room — the reference
 * client's UX: fields pre-filled from the cached contact record, a ↻
 * per section that fetches live values over CLI (`get x` → `> value`)
 * into the form, and Save that sends `set` commands for the fields you
 * actually changed. Sections are role-filtered (repeater vs room).
 */
@Composable
fun RemoteSettingsForm(
    vm: MeshCoreViewModel,
    keyHex: String,
    contact: ContactEntity?,
    role: NodeRole,
) {
    val scope = rememberCoroutineScope()

    // Field state keyed by CLI variable id; dirty = user-edited since
    // last refresh/save.
    val values = remember { mutableStateMapOf<String, String>() }
    val dirty = remember { mutableStateMapOf<String, Boolean>() }

    // Seed from the cached contact record (like the reference does).
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

    /** Fetch [ids] one at a time; fill fields; clear their dirty flags. */
    suspend fun fetch(ids: List<String>): Int {
        var ok = 0
        for (id in ids) {
            val reply = vm.cliQuery(keyHex, "get $id") ?: continue
            val value = CliReplies.extractGetValue(reply) ?: continue
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
                values[id] = value
                dirty[id] = false
                ok++
            }
        }
        return ok
    }

    /** Send `set` commands for the dirty ids among [ids]. */
    suspend fun save(commands: List<Pair<String, String>>): Int {
        var ok = 0
        for ((_, command) in commands) {
            if (vm.cliQuery(keyHex, command) != null) ok++
        }
        return ok
    }

    var confirmAction by remember { mutableStateOf<Pair<String, String>?>(null) }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {

        // ---------------- Basic ----------------
        item(key = "basic") {
            FormSection(
                title = "Basic",
                fetchIds = listOf("name", "owner.info"),
                fetch = ::fetch,
                saveEnabled = dirty["name"] == true || dirty["owner.info"] == true,
                buildSaves = {
                    buildList {
                        if (dirty["name"] == true && !values["name"].isNullOrBlank()) {
                            add("name" to "set name ${values["name"]!!.trim()}")
                        }
                        if (dirty["owner.info"] == true) {
                            add("owner.info" to "set owner.info ${values["owner.info"].orEmpty().trim()}")
                        }
                    }
                },
                save = ::save,
                clearDirty = { listOf("name", "owner.info").forEach { dirty[it] = false } },
            ) {
                FormTextField("Name", values["name"].orEmpty()) { edit("name", it) }
                FormTextField("Owner info", values["owner.info"].orEmpty()) { edit("owner.info", it) }
            }
        }

        // ---------------- Radio ----------------
        item(key = "radio") {
            val radioDirty = listOf("radio.freq", "radio.bw", "radio.sf", "radio.cr")
                .any { dirty[it] == true } || dirty["tx"] == true
            FormSection(
                title = "Radio",
                fetchIds = listOf("radio", "tx"),
                fetch = ::fetch,
                saveEnabled = radioDirty,
                buildSaves = {
                    buildList {
                        val f = values["radio.freq"]?.trim()?.toDoubleOrNull()
                        val bw = values["radio.bw"]?.trim()?.toDoubleOrNull()
                        val sf = values["radio.sf"]?.trim()?.toIntOrNull()
                        val cr = values["radio.cr"]?.trim()?.toIntOrNull()
                        val radioEdited = listOf("radio.freq", "radio.bw", "radio.sf", "radio.cr")
                            .any { dirty[it] == true }
                        if (radioEdited && f != null && bw != null && sf in 5..12 && cr in 5..8) {
                            add("radio" to "set radio ${CliReplies.RadioCsv(f, bw, sf!!, cr!!).toCsv()}")
                        }
                        val tx = values["tx"]?.trim()?.toIntOrNull()
                        if (dirty["tx"] == true && tx != null) add("tx" to "set tx $tx")
                    }
                },
                save = ::save,
                clearDirty = {
                    listOf("radio.freq", "radio.bw", "radio.sf", "radio.cr", "tx")
                        .forEach { dirty[it] = false }
                },
                warning = "Radio params must match your mesh or the node goes deaf.",
            ) {
                Row {
                    FormTextField(
                        "Freq (MHz)", values["radio.freq"].orEmpty(),
                        Modifier.weight(1.2f),
                    ) { edit("radio.freq", it) }
                    Spacer(Modifier.width(6.dp))
                    FormTextField("BW (kHz)", values["radio.bw"].orEmpty(), Modifier.weight(1f)) {
                        edit("radio.bw", it)
                    }
                }
                Row {
                    FormTextField("SF", values["radio.sf"].orEmpty(), Modifier.weight(1f)) {
                        edit("radio.sf", it)
                    }
                    Spacer(Modifier.width(6.dp))
                    FormTextField("CR", values["radio.cr"].orEmpty(), Modifier.weight(1f)) {
                        edit("radio.cr", it)
                    }
                    Spacer(Modifier.width(6.dp))
                    FormTextField("TX (dBm)", values["tx"].orEmpty(), Modifier.weight(1f)) {
                        edit("tx", it)
                    }
                }
            }
        }

        // ---------------- Position ----------------
        item(key = "position") {
            FormSection(
                title = "Position",
                fetchIds = listOf("lat", "lon"),
                fetch = ::fetch,
                saveEnabled = dirty["lat"] == true || dirty["lon"] == true,
                buildSaves = {
                    buildList {
                        val lat = values["lat"]?.trim()?.toDoubleOrNull()
                        val lon = values["lon"]?.trim()?.toDoubleOrNull()
                        if (dirty["lat"] == true && lat != null && lat in -90.0..90.0) {
                            add("lat" to "set lat $lat")
                        }
                        if (dirty["lon"] == true && lon != null && lon in -180.0..180.0) {
                            add("lon" to "set lon $lon")
                        }
                    }
                },
                save = ::save,
                clearDirty = { listOf("lat", "lon").forEach { dirty[it] = false } },
            ) {
                Row {
                    FormTextField("Latitude", values["lat"].orEmpty(), Modifier.weight(1f)) {
                        edit("lat", it)
                    }
                    Spacer(Modifier.width(6.dp))
                    FormTextField("Longitude", values["lon"].orEmpty(), Modifier.weight(1f)) {
                        edit("lon", it)
                    }
                }
            }
        }

        // ---------------- Adverts ----------------
        item(key = "adverts") {
            FormSection(
                title = "Adverts",
                fetchIds = listOf("advert.interval", "flood.advert.interval"),
                fetch = ::fetch,
                saveEnabled = dirty["advert.interval"] == true ||
                    dirty["flood.advert.interval"] == true,
                buildSaves = {
                    buildList {
                        values["advert.interval"]?.trim()?.toIntOrNull()?.let {
                            if (dirty["advert.interval"] == true) {
                                add("advert.interval" to "set advert.interval $it")
                            }
                        }
                        values["flood.advert.interval"]?.trim()?.toIntOrNull()?.let {
                            if (dirty["flood.advert.interval"] == true) {
                                add("flood.advert.interval" to "set flood.advert.interval $it")
                            }
                        }
                    }
                },
                save = ::save,
                clearDirty = {
                    listOf("advert.interval", "flood.advert.interval").forEach { dirty[it] = false }
                },
                extraActions = { doneToast ->
                    TextButton(onClick = {
                        scope.launch {
                            vm.cliQuery(keyHex, "advert")
                            doneToast("Advert sent")
                        }
                    }) { Text("Send advert now") }
                },
            ) {
                Row {
                    FormTextField(
                        "Zero-hop (mins)", values["advert.interval"].orEmpty(),
                        Modifier.weight(1f),
                    ) { edit("advert.interval", it) }
                    Spacer(Modifier.width(6.dp))
                    FormTextField(
                        "Flood (hours)", values["flood.advert.interval"].orEmpty(),
                        Modifier.weight(1f),
                    ) { edit("flood.advert.interval", it) }
                }
            }
        }

        // ---------------- Forwarding (repeater only) ----------------
        if (role == NodeRole.Repeater) {
            item(key = "forwarding") {
                FormSection(
                    title = "Packet forwarding",
                    fetchIds = listOf("repeat", "flood.max", "rxdelay", "txdelay", "direct.txdelay"),
                    fetch = ::fetch,
                    saveEnabled = listOf("flood.max", "rxdelay", "txdelay", "direct.txdelay")
                        .any { dirty[it] == true },
                    buildSaves = {
                        buildList {
                            for (id in listOf("flood.max", "rxdelay", "txdelay", "direct.txdelay")) {
                                val v = values[id]?.trim()
                                if (dirty[id] == true && !v.isNullOrBlank()) add(id to "set $id $v")
                            }
                        }
                    },
                    save = ::save,
                    clearDirty = {
                        listOf("flood.max", "rxdelay", "txdelay", "direct.txdelay")
                            .forEach { dirty[it] = false }
                    },
                ) {
                    FormSwitch(
                        "Repeat (forward packets)",
                        CliReplies.isTruthy(values["repeat"].orEmpty()),
                    ) { on ->
                        // Switches apply immediately, like the reference.
                        values["repeat"] = if (on) "on" else "off"
                        scope.launch { vm.cliQuery(keyHex, "set repeat ${if (on) "on" else "off"}") }
                    }
                    Row {
                        FormTextField(
                            "Flood max hops", values["flood.max"].orEmpty(),
                            Modifier.weight(1f),
                        ) { edit("flood.max", it) }
                        Spacer(Modifier.width(6.dp))
                        FormTextField("RX delay", values["rxdelay"].orEmpty(), Modifier.weight(1f)) {
                            edit("rxdelay", it)
                        }
                    }
                    Row {
                        FormTextField("TX delay", values["txdelay"].orEmpty(), Modifier.weight(1f)) {
                            edit("txdelay", it)
                        }
                        Spacer(Modifier.width(6.dp))
                        FormTextField(
                            "Direct TX delay", values["direct.txdelay"].orEmpty(),
                            Modifier.weight(1f),
                        ) { edit("direct.txdelay", it) }
                    }
                }
            }
        }

        // ---------------- Access / security ----------------
        item(key = "security") {
            FormSection(
                title = "Access & security",
                fetchIds = if (role == NodeRole.Room) listOf("allow.read.only") else emptyList(),
                fetch = ::fetch,
                saveEnabled = !values["password.new"].isNullOrBlank() ||
                    !values["guest.password.new"].isNullOrBlank(),
                buildSaves = {
                    buildList {
                        values["password.new"]?.takeIf { it.isNotBlank() }?.let {
                            add("password" to "password ${it.trim()}")
                        }
                        values["guest.password.new"]?.takeIf { it.isNotBlank() }?.let {
                            add("guest.password" to "set guest.password ${it.trim()}")
                        }
                    }
                },
                save = ::save,
                clearDirty = {
                    values.remove("password.new")
                    values.remove("guest.password.new")
                },
            ) {
                if (role == NodeRole.Room) {
                    FormSwitch(
                        "Allow read-only (guest) logins",
                        CliReplies.isTruthy(values["allow.read.only"].orEmpty()),
                    ) { on ->
                        values["allow.read.only"] = if (on) "on" else "off"
                        scope.launch {
                            vm.cliQuery(keyHex, "set allow.read.only ${if (on) "on" else "off"}")
                        }
                    }
                }
                // Passwords are write-only (never readable back) — blank
                // fields that only send when filled, like the reference.
                FormTextField(
                    "New admin password", values["password.new"].orEmpty(),
                    sensitive = true,
                ) { values["password.new"] = it }
                FormTextField(
                    "New guest password", values["guest.password.new"].orEmpty(),
                    sensitive = true,
                ) { values["guest.password.new"] = it }
            }
        }

        // ---------------- Advanced ----------------
        item(key = "advanced") {
            FormSection(
                title = "Advanced",
                fetchIds = listOf("radio.rxgain", "dutycycle", "multi.acks", "int.thresh"),
                fetch = ::fetch,
                saveEnabled = listOf("radio.rxgain", "dutycycle", "int.thresh")
                    .any { dirty[it] == true },
                buildSaves = {
                    buildList {
                        for (id in listOf("radio.rxgain", "dutycycle", "int.thresh")) {
                            val v = values[id]?.trim()
                            if (dirty[id] == true && !v.isNullOrBlank()) add(id to "set $id $v")
                        }
                    }
                },
                save = ::save,
                clearDirty = {
                    listOf("radio.rxgain", "dutycycle", "int.thresh").forEach { dirty[it] = false }
                },
            ) {
                FormSwitch(
                    "Multi-acks (redundant ACKs)",
                    CliReplies.isTruthy(values["multi.acks"].orEmpty()),
                ) { on ->
                    values["multi.acks"] = if (on) "1" else "0"
                    scope.launch { vm.cliQuery(keyHex, "set multi.acks ${if (on) "1" else "0"}") }
                }
                Row {
                    FormTextField(
                        "RX gain", values["radio.rxgain"].orEmpty(), Modifier.weight(1f),
                    ) { edit("radio.rxgain", it) }
                    Spacer(Modifier.width(6.dp))
                    FormTextField(
                        "Duty cycle %", values["dutycycle"].orEmpty(), Modifier.weight(1f),
                    ) { edit("dutycycle", it) }
                    Spacer(Modifier.width(6.dp))
                    FormTextField(
                        "Int. thresh", values["int.thresh"].orEmpty(), Modifier.weight(1f),
                    ) { edit("int.thresh", it) }
                }
            }
        }

        // ---------------- Maintenance ----------------
        item(key = "maintenance") {
            MaintenanceSection(
                role = role,
                onRun = { label, command -> scope.launch { vm.cliQuery(keyHex, command) } },
                onConfirm = { label, command -> confirmAction = label to command },
            )
        }
        item(key = "footer") {
            Text(
                "Fetch (↻) reads live values from the node; Save sends only the fields you " +
                    "changed. Replies also appear in the Console tab. Log in first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
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

// ----------------------------------------------------------------------

/**
 * One settings card: title, ↻ fetch (spinner while querying), content
 * fields, Save (enabled when dirty). Mirrors the reference client's
 * per-section refresh + dirty-field save.
 */
@Composable
private fun FormSection(
    title: String,
    fetchIds: List<String>,
    fetch: suspend (List<String>) -> Int,
    saveEnabled: Boolean,
    buildSaves: () -> List<Pair<String, String>>,
    save: suspend (List<Pair<String, String>>) -> Int,
    clearDirty: () -> Unit,
    warning: String? = null,
    extraActions: (@Composable (toast: (String) -> Unit) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (refreshing || saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (fetchIds.isNotEmpty()) {
                    IconButton(onClick = {
                        scope.launch {
                            refreshing = true
                            status = null
                            val ok = fetch(fetchIds)
                            status = if (ok > 0) "Fetched $ok value(s)" else "No reply — logged in?"
                            refreshing = false
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Fetch from node")
                    }
                }
            }
            warning?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                extraActions?.invoke { msg -> status = msg }
                TextButton(
                    enabled = saveEnabled && !saving,
                    onClick = {
                        scope.launch {
                            saving = true
                            status = null
                            val commands = buildSaves()
                            val ok = save(commands)
                            status = when {
                                commands.isEmpty() -> "Nothing valid to save"
                                ok == commands.size -> "Saved"
                                else -> "Saved $ok/${commands.size} — check Console"
                            }
                            if (ok == commands.size) clearDirty()
                            saving = false
                        }
                    },
                ) { Text("Save") }
            }
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

@Composable
private fun FormTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sensitive: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (sensitive) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

@Composable
private fun FormSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaintenanceSection(
    role: NodeRole,
    onRun: (label: String, command: String) -> Unit,
    onConfirm: (label: String, command: String) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Maintenance", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onRun("Read clock", "clock") }) { Text("Clock") }
                TextButton(onClick = { onRun("Sync clock", "clock sync") }) { Text("Clock sync") }
                TextButton(onClick = { onRun("Version", "ver") }) { Text("Version") }
                TextButton(onClick = { onRun("Board", "board") }) { Text("Board") }
                if (role == NodeRole.Repeater) {
                    TextButton(onClick = { onRun("Neighbors", "neighbors") }) { Text("Neighbors") }
                }
                TextButton(onClick = { onRun("Access list", "get acl") }) { Text("ACL") }
                TextButton(onClick = { onRun("Log start", "log start") }) { Text("Log start") }
                TextButton(onClick = { onRun("Log stop", "log stop") }) { Text("Log stop") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, command) in listOf(
                    "Reboot" to "reboot",
                    "Clear stats" to "clear stats",
                    "Log erase" to "log erase",
                    "Factory erase" to "erase",
                    "Start OTA" to "start ota",
                )) {
                    TextButton(onClick = { onConfirm(label, command) }) {
                        Text(label, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
