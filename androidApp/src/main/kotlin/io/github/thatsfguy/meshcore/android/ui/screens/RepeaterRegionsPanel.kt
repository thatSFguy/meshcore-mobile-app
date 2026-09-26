package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.presentation.RegionAdmin
import io.github.thatsfguy.meshcore.presentation.RegionAdmin.Action
import io.github.thatsfguy.meshcore.presentation.RegionAdmin.Untagged
import io.github.thatsfguy.meshcore.protocol.Regions
import kotlinx.coroutines.launch

/**
 * Region administration on a repeater (PARITY.md §8, second row).
 *
 * The firmware keeps a tree of named regions and a per-region flood
 * permission; this is the `region …` CLI surface with the guesswork
 * taken out. The rules — what a region offers, where a new one can hang,
 * what each untagged-traffic choice sends — are [RegionAdmin], tested in
 * `shared`. Two things it deliberately does not do:
 *
 *  - **`region load`** is not offered. It puts the node into a
 *    multi-line mode where each following line is a region name; a
 *    one-shot CLI message would strand it there.
 *  - **Nothing is auto-saved.** `region save` is the only thing that
 *    persists region edits across a reboot, and running it silently after
 *    every change would make an experiment permanent. The panel says so
 *    instead. (`region default` and `set flood.max.unscoped` save
 *    themselves — that is the firmware's choice, and the banner follows
 *    it.)
 *
 * After every change the node is read again, so the screen shows what
 * the node now holds rather than what the app expects it to.
 */
@Composable
fun RepeaterRegionsPanel(vm: MeshCoreViewModel, keyHex: String, isAdmin: Boolean) {
    val scope = rememberCoroutineScope()
    var tree by remember(keyHex) { mutableStateOf<Regions.RegionTree?>(null) }
    var rawReply by remember(keyHex) { mutableStateOf<String?>(null) }
    var defaultScope by remember(keyHex) { mutableStateOf<String?>(null) }
    var defaultKnown by remember(keyHex) { mutableStateOf(false) }
    /** Null until `ver` has been read; the hop limit depends on it. */
    var limitSupported by remember(keyHex) { mutableStateOf<Boolean?>(null) }
    var hopLimit by remember(keyHex) { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var pendingEdits by remember(keyHex) { mutableStateOf(false) }

    var chosenMode by remember(keyHex) { mutableStateOf<Untagged?>(null) }
    var chosenHops by remember(keyHex) { mutableIntStateOf(RegionAdmin.SUGGESTED_HOPS) }
    var actionsFor by remember { mutableStateOf<Regions.RegionEntry?>(null) }
    var addUnder by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<PendingRegionAction?>(null) }

    suspend fun read() {
        val listing = vm.cliQuery(keyHex, Regions.tree())
        rawReply = listing
        tree = Regions.parseRegionTree(listing)
        val default = vm.cliQuery(keyHex, Regions.default())
        defaultScope = Regions.parseDefaultScope(default)
        defaultKnown = default != null
        if (limitSupported == null) {
            limitSupported = RegionAdmin.supportsHopLimit(vm.cliQuery(keyHex, "ver"))
        }
        hopLimit = if (limitSupported == true) {
            RegionAdmin.parseHopLimit(vm.cliQuery(keyHex, RegionAdmin.GET_HOP_LIMIT))
        } else {
            null
        }
        tree?.let { t ->
            val mode = RegionAdmin.untaggedMode(t.wildcardFloodAllowed, hopLimit)
            chosenMode = mode
            hopLimit?.takeIf { it in RegionAdmin.NEARBY_HOPS }?.let { chosenHops = it }
        }
        if (listing == null) note = "No reply — logged in and in range?"
    }

    fun refresh() {
        scope.launch {
            loading = true
            note = null
            read()
            loading = false
        }
    }

    /**
     * Send [commands] in order, stopping at the first the node refuses or
     * doesn't answer, then read the node again.
     */
    fun run(commands: List<String>, describe: String) {
        if (commands.isEmpty()) return
        scope.launch {
            loading = true
            note = null
            var failed: String? = null
            for (command in commands) {
                val reply = vm.cliQuery(keyHex, command)
                when {
                    reply == null -> failed = "No reply to `$command` — logged in and in range?"
                    reply.trim().startsWith("Err", ignoreCase = true) ->
                        failed = "`$command`: ${reply.trim()}"
                    RegionAdmin.savesRegions(command) -> pendingEdits = false
                    RegionAdmin.needsRegionSave(command) -> pendingEdits = true
                }
                if (failed != null) break
            }
            note = failed ?: describe
            read()
            loading = false
        }
    }

    fun run(command: String, describe: String) = run(listOf(command), describe)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        ExpandableHint(
            "Decides what this repeater floods onward — routing, not access control.",
        ) {
            DetailText(
                "A region the repeater refuses to flood can still be heard by anyone in " +
                    "radio range.",
            )
        }

        ButtonFlowRow {
            TextButton(enabled = !loading, onClick = { refresh() }) { Text("Fetch regions") }
            TextButton(
                enabled = !loading && isAdmin,
                onClick = { run(Regions.save(), describe = "Saved") },
            ) { Text("Save to node") }
        }
        if (loading) SectionSpinner("Asking the node…")
        note?.let { HintText(it) }

        if (pendingEdits) {
            Text(
                "Unsaved: region edits live in RAM until \"Save to node\" runs. A reboot " +
                    "before then discards them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // --- Untagged traffic -----------------------------------------------
        val parsed = tree
        if (parsed != null) {
            Spacer(Modifier.height(12.dp))
            Text("Untagged traffic", style = MaterialTheme.typography.titleSmall)
            HintText("Traffic carrying no region — what a mesh that doesn't use regions sends.")
            UntaggedChoices(
                current = RegionAdmin.untaggedMode(parsed.wildcardFloodAllowed, hopLimit),
                currentHops = hopLimit,
                chosen = chosenMode,
                hops = chosenHops,
                limitSupported = limitSupported == true,
                enabled = isAdmin && !loading,
                onChoose = { chosenMode = it },
                onHops = { chosenHops = it },
            )
            if (limitSupported == false) {
                HintText("Relaying only nearby traffic needs repeater firmware 1.16 or newer.")
            }
            val target = chosenMode
            if (isAdmin && target != null) {
                val commands = runCatching {
                    RegionAdmin.commandsFor(
                        target,
                        chosenHops,
                        parsed.wildcardFloodAllowed,
                        hopLimit,
                        limitSupported == true,
                    )
                }.getOrDefault(emptyList())
                TextButton(
                    enabled = !loading && commands.isNotEmpty(),
                    onClick = {
                        if (target == Untagged.Refuse) {
                            confirm = PendingRegionAction(
                                title = "Refuse untagged traffic?",
                                body = "This repeater will stop relaying flood traffic that " +
                                    "carries no region — including anyone nearby whose " +
                                    "radio isn't set to one of the regions below. Traffic " +
                                    "tagged with an allowed region is still relayed.",
                                confirmLabel = "Refuse",
                                destructive = true,
                                commands = commands,
                                describe = "Untagged traffic refused",
                            )
                        } else {
                            run(commands, describe = "Untagged traffic updated")
                        }
                    },
                ) { Text("Apply") }
            }
        }

        // --- Default scope --------------------------------------------------
        Spacer(Modifier.height(12.dp))
        Text("Default region", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                HintText(
                    when {
                        !defaultKnown -> "Not read yet."
                        defaultScope == Regions.GLOBAL_SELECTOR ->
                            "None — this repeater's own adverts and replies go out untagged."
                        defaultScope != null -> "#$defaultScope — tap a region below to change it."
                        // A reply we couldn't parse is unknown, never "cleared":
                        // the two lead to opposite decisions.
                        else -> "The node's answer wasn't recognised — use the console."
                    },
                )
            }
            if (isAdmin && defaultScope != null && defaultScope != Regions.GLOBAL_SELECTOR) {
                TextButton(enabled = !loading, onClick = {
                    confirm = PendingRegionAction(
                        title = "Clear the default region?",
                        body = "This repeater's own adverts and replies go back to carrying " +
                            "no region.",
                        confirmLabel = "Clear",
                        destructive = false,
                        commands = listOf(Regions.setDefault(null)),
                        describe = "Default region cleared",
                    )
                }) { Text("Clear") }
            }
        }

        // --- Region tree ----------------------------------------------------
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Regions",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (isAdmin && parsed != null) {
                TextButton(enabled = !loading, onClick = { addUnder = Regions.GLOBAL_SELECTOR }) {
                    Text("Add region")
                }
            }
        }
        if (parsed == null && rawReply != null) {
            HintText(
                "The node's answer wasn't a region tree. Firmware without regions answers " +
                    "\"??: region\" — its own words:",
            )
            rawReply?.lineSequence()?.filter { it.isNotBlank() }?.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (parsed != null && parsed.regions.isEmpty()) HintText("No regions defined.")
        if (parsed != null && isAdmin && parsed.regions.isNotEmpty()) {
            HintText("Tap a region to make it the default, add one under it, or change it.")
        }
        for (entry in parsed?.regions.orEmpty()) {
            val tappable = isAdmin && entry.actionable && !loading
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = tappable) { actionsFor = entry }
                    .padding(start = (16 * (entry.depth - 1)).dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(entry.name, fontFamily = FontFamily.Monospace)
                HintText(
                    listOfNotNull(
                        "default".takeIf { entry.name == defaultScope },
                        "home".takeIf { entry.home },
                        if (entry.floodAllowed) "flood allowed" else "flood denied",
                    ).joinToString(" · "),
                )
                if (!entry.actionable) {
                    HintText("Not lowercase letters, digits and dashes — edit it from the console.")
                }
            }
        }
        if (parsed?.truncated == true) {
            HintText(
                "The list filled the node's reply, so regions past the end are missing. " +
                    "`region list allowed` in the console lists every name.",
            )
        }
        if (!isAdmin) {
            Spacer(Modifier.height(12.dp))
            HintText("Log in as admin to add, remove or re-scope regions.")
        }
        Spacer(Modifier.height(24.dp))
    }

    // --- Dialogs -------------------------------------------------------------

    actionsFor?.let { entry ->
        val t = tree
        val actions = if (t == null) emptyList() else RegionAdmin.actionsFor(entry, t, defaultScope)
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(entry.name, fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    for (action in actions) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                actionsFor = null
                                when (action) {
                                    Action.MakeDefault -> run(
                                        Regions.setDefault(entry.name),
                                        describe = "#${entry.name} is the default region",
                                    )
                                    Action.SetHome -> run(
                                        Regions.setHome(entry.name),
                                        describe = "#${entry.name} is the home region",
                                    )
                                    Action.AddChild -> addUnder = entry.name
                                    Action.AllowFlood -> run(
                                        Regions.allowFlood(entry.name),
                                        describe = "Flood allowed for #${entry.name}",
                                    )
                                    Action.DenyFlood -> confirm = PendingRegionAction(
                                        title = "Deny flood for ${entry.name}?",
                                        body = "This repeater will stop relaying flood traffic " +
                                            "tagged #${entry.name}. Nothing becomes unreadable — " +
                                            "it just stops being carried by this node.",
                                        confirmLabel = "Deny",
                                        destructive = true,
                                        commands = listOf(Regions.denyFlood(entry.name)),
                                        describe = "Flood denied for #${entry.name}",
                                    )
                                    Action.Remove -> confirm = PendingRegionAction(
                                        title = "Remove ${entry.name}?",
                                        body = "Removes the region from this repeater, which " +
                                            "then stops relaying traffic tagged with it.",
                                        confirmLabel = "Remove",
                                        destructive = true,
                                        commands = listOf(Regions.remove(entry.name)),
                                        describe = "Removed #${entry.name}",
                                    )
                                }
                            },
                        ) {
                            Text(
                                actionLabel(action),
                                modifier = Modifier.fillMaxWidth(),
                                color = if (action == Action.Remove) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionsFor = null }) { Text("Close") } },
        )
    }

    addUnder?.let { preset ->
        AddRegionDialog(
            choices = RegionAdmin.parentChoices(tree),
            initialParent = preset,
            onDismiss = { addUnder = null },
            onAdd = { name, parent ->
                addUnder = null
                run(Regions.put(name, parent), describe = "Added #${Regions.canonical(name)}")
            },
        )
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(action.title) },
            text = { Text(action.body) },
            confirmButton = {
                TextButton(onClick = {
                    run(action.commands, describe = action.describe)
                    confirm = null
                }) {
                    Text(
                        action.confirmLabel,
                        color = if (action.destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private fun actionLabel(action: Action): String = when (action) {
    Action.MakeDefault -> "Make default region"
    Action.SetHome -> "Make home region"
    Action.AddChild -> "Add a region under this"
    Action.AllowFlood -> "Allow flood"
    Action.DenyFlood -> "Deny flood"
    Action.Remove -> "Remove"
}

/** Relay all · only nearby (N hops) · refuse — one control for two firmware settings. */
@Composable
private fun UntaggedChoices(
    current: Untagged,
    currentHops: Int?,
    chosen: Untagged?,
    hops: Int,
    limitSupported: Boolean,
    enabled: Boolean,
    onChoose: (Untagged) -> Unit,
    onHops: (Int) -> Unit,
) {
    val options = buildList {
        add(Untagged.RelayAll)
        if (limitSupported) add(Untagged.Nearby)
        add(Untagged.Refuse)
    }
    for (option in options) {
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = chosen == option,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { onChoose(option) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = chosen == option, onClick = null, enabled = enabled)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (option) {
                        Untagged.RelayAll -> "Relay all"
                        Untagged.Nearby -> "Relay only nearby"
                        Untagged.Refuse -> "Refuse"
                    } + if (option == current) "  (now)" else "",
                )
                if (option == Untagged.Nearby && (chosen == option || current == option)) {
                    HintText(RegionAdmin.describeNearby(if (chosen == option) hops else currentHops ?: hops))
                }
            }
        }
        if (option == Untagged.Nearby && chosen == option && enabled) {
            Row(
                Modifier.padding(start = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = hops > RegionAdmin.NEARBY_HOPS.first,
                    onClick = { onHops(hops - 1) },
                ) { Text("−") }
                Text("$hops hop${if (hops == 1) "" else "s"}")
                TextButton(
                    enabled = hops < RegionAdmin.NEARBY_HOPS.last,
                    onClick = { onHops(hops + 1) },
                ) { Text("+") }
            }
        }
    }
}

/** Name, plus a parent chosen from the tree rather than retyped. */
@Composable
private fun AddRegionDialog(
    choices: List<RegionAdmin.ParentChoice>,
    initialParent: String,
    onDismiss: () -> Unit,
    onAdd: (name: String, parent: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf(initialParent) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add region") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = VERBATIM_KEYBOARD,
                    isError = name.isNotBlank() && !Regions.isValid(name),
                    supportingText = { Text("Lowercase letters, digits and dashes") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Under", style = MaterialTheme.typography.titleSmall)
                for (choice in choices) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = parent == choice.name,
                                role = Role.RadioButton,
                                onClick = { parent = choice.name },
                            )
                            .padding(start = (16 * choice.depth).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = parent == choice.name, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (choice.name == Regions.GLOBAL_SELECTOR) "Top level" else choice.name,
                            fontFamily = if (choice.name == Regions.GLOBAL_SELECTOR) {
                                null
                            } else {
                                FontFamily.Monospace
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = Regions.isValid(name),
                onClick = { onAdd(name, parent) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Region commands held back until the user confirms them. */
private data class PendingRegionAction(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val destructive: Boolean,
    val commands: List<String>,
    val describe: String,
)
