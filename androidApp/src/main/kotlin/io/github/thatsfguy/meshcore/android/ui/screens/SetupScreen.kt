package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.engine.EngineState

/**
 * First-run setup (PARITY.md §1, `SetupScreen`).
 *
 * The gap this closes is that the app used to drop a new user into an
 * empty Chats list with no indication that a radio was required, let
 * alone which four numbers had to match everyone else's.
 *
 * It is a checklist, not a wizard: each step reports whether it is done
 * and offers the one action that does it. A wizard that must be
 * completed in order is the wrong shape here — plenty of users arrive
 * with a radio already configured by someone else, and should be able
 * to skip straight past.
 */
@Composable
fun SetupScreen(vm: MeshCoreViewModel, onDone: () -> Unit) {
    val engineState by vm.engineState.collectAsState()
    val self by vm.selfInfo.collectAsState()
    val connected = engineState == EngineState.Ready
    var presetSheet by remember { mutableStateOf(false) }
    var name by remember(self?.name) { mutableStateOf(self?.name.orEmpty()) }
    val mode by vm.prefs.appModeFlow.collectAsState()
    val simple = mode == io.github.thatsfguy.meshcore.presentation.AppMode.Simple
    val channelKinds by vm.channelKinds.collectAsState()
    val inAGroup = channelKinds.values.any { it == io.github.thatsfguy.meshcore.protocol.ChannelKind.Private }
    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract(),
    ) { result -> result.contents?.let { vm.importScannedCode(it) } }

    val matches = remember(self?.freqKhz, self?.bwHz, self?.sf, self?.cr) {
        val info = self
        if (info == null) {
            emptyList()
        } else {
            io.github.thatsfguy.meshcore.protocol.RadioPresets.matching(
                info.freqKhz, info.bwHz, info.sf, info.cr,
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Set up MeshCore Hardened", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        HintText(
            "This app talks to a MeshCore LoRa radio over Bluetooth or USB. Without one " +
                "there is nothing to send messages with — there is no internet fallback, " +
                "by design.",
        )

        // Asked first: it decides how much of the app the rest of setup,
        // and everything after it, shows. Changeable any time in Settings.
        Spacer(Modifier.height(16.dp))
        Text("How will you use it?", style = MaterialTheme.typography.titleSmall)
        // The whole row is the target, label included: driving this on a
        // phone, a tap on the words "Full: …" did nothing and setup
        // finished in the mode the user had just tried to leave.
        for ((choice, label) in listOf(
            io.github.thatsfguy.meshcore.presentation.AppMode.Simple to
                "Simple: chats and channels with my group",
            io.github.thatsfguy.meshcore.presentation.AppMode.Full to
                "Full: nodes, map, repeaters and radio settings",
        )) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = mode == choice,
                        role = androidx.compose.ui.semantics.Role.RadioButton,
                        onClick = { vm.prefs.appMode = choice },
                    ),
            ) {
                androidx.compose.material3.RadioButton(selected = mode == choice, onClick = null)
                Text(label)
            }
        }
        HintText("You can switch at any time in Settings. Nothing is lost either way.")

        Spacer(Modifier.height(20.dp))
        SetupStep(
            number = 1,
            title = "Connect a radio",
            done = connected,
            detail = if (connected) {
                "Connected to ${vm.connectionLabel.collectAsState().value ?: "a radio"}."
            } else {
                "Pair over Bluetooth, or plug one in over USB."
            },
        ) {
            HintText("Open Settings → Connection to pair or plug in a radio.")
        }

        SetupStep(
            number = 2,
            title = "Name this node",
            done = connected && !self?.name.isNullOrBlank(),
            detail = "Your name is broadcast in every advert — the whole mesh sees it. " +
                "Pick something you're happy being known by out there.",
        ) {
            if (!connected) {
                HintText("Connect a radio first.")
            } else {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Node name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ButtonFlowRow {
                    TextButton(
                        onClick = { vm.setAdvertName(name.trim()) },
                        enabled = name.isNotBlank() && name.trim() != self?.name,
                    ) { Text("Set name") }
                }
            }
        }

        SetupStep(
            number = 3,
            title = "Match your local radio settings",
            done = connected && matches.isNotEmpty(),
            detail = if (matches.isNotEmpty()) {
                "Currently on ${matches.joinToString(", ") { it.name }}."
            } else {
                "Every node on a mesh must share the same frequency, bandwidth, " +
                    "spreading factor and coding rate. Mismatched, you'll see an empty " +
                    "app rather than an error."
            },
        ) {
            if (!connected) {
                HintText("Connect a radio first.")
            } else {
                ButtonFlowRow {
                    TextButton(onClick = { presetSheet = true }) { Text("Pick a regional preset…") }
                }
                HintText(io.github.thatsfguy.meshcore.protocol.RadioPresets.REGULATORY_CAVEAT)
            }
        }

        if (simple) {
            SetupStep(
                number = 4,
                title = "Join your group's channel",
                done = inAGroup,
                detail = "Your group talks on its own channel. Scan the channel code someone " +
                    "in the group shows you, or tap the link they sent.",
            ) {
                if (connected) {
                    OutlinedButton(onClick = {
                        scanLauncher.launch(meshScanOptions("Scan your group's channel code"))
                    }) { Text("Scan the channel code") }
                } else {
                    HintText("Connect a radio first.")
                }
            }
        }

        SetupStep(
            number = if (simple) 5 else 4,
            title = "Know what this app protects",
            done = false,
            detail = "Direct messages are end-to-end encrypted. Channels are only " +
                "obfuscated — AES-ECB with a 2-byte MAC and a key that never changes — " +
                "so treat a channel as a room with an unlocked door, not a sealed one.",
        ) {
            HintText("The full list is in Settings → About.")
        }

        Spacer(Modifier.height(24.dp))
        ButtonFlowRow {
            OutlinedButton(onClick = {
                vm.prefs.setupComplete = true
                onDone()
            }) { Text(if (connected) "Done" else "Skip for now") }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (presetSheet) {
        RadioPresetSheet(vm, onDismiss = { presetSheet = false })
    }
}

@Composable
private fun SetupStep(
    number: Int,
    title: String,
    done: Boolean,
    detail: String,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Text(
        (if (done) "✓ " else "$number. ") + title,
        style = MaterialTheme.typography.titleSmall,
        color = if (done) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
    HintText(detail)
    if (!done) content()
}
