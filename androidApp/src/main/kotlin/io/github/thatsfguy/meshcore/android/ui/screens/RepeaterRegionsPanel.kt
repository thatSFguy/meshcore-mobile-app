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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.Regions
import kotlinx.coroutines.launch

/**
 * Region administration on a repeater (PARITY.md §8, second row).
 *
 * The firmware keeps a tree of named regions and a per-region flood
 * permission; this is the `region …` CLI surface with the guesswork
 * taken out. Two things it deliberately does not do:
 *
 *  - **`region load`** is not offered. It puts the node into a
 *    multi-line mode where each following line is a region name; a
 *    one-shot CLI message would strand it there.
 *  - **Nothing is auto-saved.** `region save` is the only thing that
 *    persists edits across a reboot, and running it silently after every
 *    change would make an experiment permanent. The panel says so
 *    instead.
 *
 * Every name is validated before it becomes part of a command — see
 * [Regions] — because a name with whitespace in it is a second command,
 * not a formatting problem.
 */
@Composable
fun RepeaterRegionsPanel(vm: MeshCoreViewModel, keyHex: String, isAdmin: Boolean) {
    val scope = rememberCoroutineScope()
    var entries by remember(keyHex) { mutableStateOf<List<Regions.RegionEntry>>(emptyList()) }
    var rawReply by remember(keyHex) { mutableStateOf<String?>(null) }
    var defaultScope by remember(keyHex) { mutableStateOf<String?>(null) }
    var defaultKnown by remember(keyHex) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var pendingEdits by remember(keyHex) { mutableStateOf(false) }

    var newName by remember { mutableStateOf("") }
    var newParent by remember { mutableStateOf(Regions.GLOBAL_SELECTOR) }
    var confirm by remember { mutableStateOf<PendingRegionAction?>(null) }

    /** Run one CLI command, then note whether it needs `region save`. */
    fun run(command: String, mutating: Boolean, describe: String) {
        scope.launch {
            loading = true
            note = null
            val reply = vm.cliQuery(keyHex, command)
            note = when {
                reply == null -> "No reply to `$command` — logged in and in range?"
                else -> "$describe: ${reply.trim()}"
            }
            if (mutating && reply != null) pendingEdits = true
            loading = false
        }
    }

    fun refresh() {
        scope.launch {
            loading = true
            note = null
            val listing = vm.cliQuery(keyHex, Regions.get(Regions.GLOBAL_SELECTOR))
            rawReply = listing
            entries = Regions.parseRegionListing(listing)
            val default = vm.cliQuery(keyHex, Regions.default())
            defaultScope = Regions.parseDefaultScope(default)
            defaultKnown = default != null
            if (listing == null) note = "No reply — logged in and in range?"
            loading = false
        }
    }

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
                onClick = { run(Regions.save(), mutating = false, describe = "Saved") },
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

        // --- Default scope ------------------------------------------------
        Spacer(Modifier.height(12.dp))
        Text("Default region scope", style = MaterialTheme.typography.titleSmall)
        HintText(
            when {
                !defaultKnown -> "Not read yet."
                defaultScope == Regions.GLOBAL_SELECTOR ->
                    "Global scope (*) — the widest setting there is."
                defaultScope != null -> "#$defaultScope"
                // A reply we couldn't parse is unknown, never "cleared":
                // the two lead to opposite decisions.
                else -> "The node's answer wasn't in a form we recognise — see the raw " +
                    "reply below, or use the console."
            },
        )

        // --- Region list --------------------------------------------------
        Spacer(Modifier.height(12.dp))
        Text("Regions", style = MaterialTheme.typography.titleSmall)
        if (entries.isEmpty() && rawReply != null) {
            HintText(
                "No region lines in the reply. Firmware without region support answers " +
                    "\"??: region\" — the node's own words are below.",
            )
        }
        for (entry in entries) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, fontFamily = FontFamily.Monospace)
                    HintText(
                        "parent " + (entry.parent ?: "—") +
                            " · flood " + if (entry.floodAllowed) "allowed" else "denied",
                    )
                }
                if (isAdmin) {
                    if (entry.floodAllowed) {
                        TextButton(onClick = {
                            confirm = PendingRegionAction(
                                title = "Deny flood for ${entry.name}?",
                                body = "This repeater will stop forwarding flood traffic " +
                                    "tagged with #${entry.name}. Traffic already in flight " +
                                    "is unaffected, and nothing becomes unreadable — it " +
                                    "just stops being carried by this node.",
                                confirmLabel = "Deny",
                                destructive = true,
                                command = Regions.denyFlood(entry.name),
                                describe = "Denied flood for ${entry.name}",
                            )
                        }) { Text("Deny flood") }
                    } else {
                        TextButton(onClick = {
                            run(
                                Regions.allowFlood(entry.name),
                                mutating = true,
                                describe = "Allowed flood for ${entry.name}",
                            )
                        }) { Text("Allow flood") }
                    }
                    TextButton(onClick = {
                        confirm = PendingRegionAction(
                            title = "Remove ${entry.name}?",
                            body = "Removes the region definition from this repeater. It " +
                                "must have no child regions, and the name must match " +
                                "exactly.",
                            confirmLabel = "Remove",
                            destructive = true,
                            command = Regions.remove(entry.name),
                            describe = "Removed ${entry.name}",
                        )
                    }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        // Anything the parser didn't recognise, in the node's own words —
        // rendering an unparsed reply as an empty list would read as
        // "this repeater has no regions", which is a different claim.
        rawReply?.lineSequence()
            ?.filter { it.isNotBlank() && Regions.parseRegionListing(it).isEmpty() }
            ?.forEach { line ->
                Text(
                    line.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        // --- Add / set (admin only) ---------------------------------------
        if (!isAdmin) {
            Spacer(Modifier.height(12.dp))
            HintText("Log in as admin to add, remove or re-scope regions.")
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        Spacer(Modifier.height(16.dp))
        Text("Add or update a region", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text("Name") },
            singleLine = true,
            isError = newName.isNotBlank() && !Regions.isValid(newName),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = newParent,
            onValueChange = { newParent = it },
            label = { Text("Parent (* = global scope)") },
            singleLine = true,
            isError = Regions.canonicalSelector(newParent) == null,
            modifier = Modifier.fillMaxWidth(),
        )
        ButtonFlowRow {
            TextButton(
                enabled = !loading && Regions.isValid(newName) &&
                    Regions.canonicalSelector(newParent) != null,
                onClick = {
                    run(
                        Regions.put(newName, newParent),
                        mutating = true,
                        describe = "Added ${Regions.canonical(newName)}",
                    )
                    newName = ""
                },
            ) { Text("Add / update") }
            TextButton(
                enabled = !loading && Regions.isValid(newName),
                onClick = {
                    run(
                        Regions.setDefault(newName),
                        mutating = true,
                        describe = "Default scope set",
                    )
                },
            ) { Text("Make default scope") }
            TextButton(
                enabled = !loading,
                onClick = {
                    confirm = PendingRegionAction(
                        title = "Clear the default region scope?",
                        body = "New traffic on this repeater goes back to the global scope.",
                        confirmLabel = "Clear",
                        destructive = false,
                        command = Regions.setDefault(null),
                        describe = "Default scope cleared",
                    )
                },
            ) { Text("Clear default") }
            TextButton(
                enabled = !loading && Regions.isValid(newName),
                onClick = {
                    run(Regions.setHome(newName), mutating = true, describe = "Home region set")
                },
            ) { Text("Set home") }
        }
        HintText(
            "The firmware warns against denying flood on the global scope (*) — it stops " +
                "this repeater forwarding flood traffic entirely. That one is left to the " +
                "console on purpose.",
        )
        Spacer(Modifier.height(24.dp))
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(action.title) },
            text = { Text(action.body) },
            confirmButton = {
                TextButton(onClick = {
                    run(action.command, mutating = true, describe = action.describe)
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

/** A region command held back until the user confirms it. */
private data class PendingRegionAction(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val destructive: Boolean,
    val command: String,
    val describe: String,
)
