package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.navigation.NavController
import io.github.thatsfguy.meshcore.android.ui.FirmwareTargetKind
import io.github.thatsfguy.meshcore.android.ui.FirmwareUi
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.firmware.DfuProgress
import io.github.thatsfguy.meshcore.firmware.FirmwareAsset
import io.github.thatsfguy.meshcore.firmware.FirmwareRole
import io.github.thatsfguy.meshcore.firmware.FirmwareVersion
import io.github.thatsfguy.meshcore.firmware.Recovery
import io.github.thatsfguy.meshcore.firmware.NodeIdentityReplies
import io.github.thatsfguy.meshcore.firmware.VersionOrder
import io.github.thatsfguy.meshcore.firmware.deviceIdentityLine

/**
 * Firmware updates over Bluetooth.
 *
 * Every screen in this app can be backed out of except this one's last
 * step: once the image starts, a node that is interrupted stays in
 * update mode until it is flashed again. The design follows from that.
 *
 * - The confirmation names the **board**, not the file, because a wrong
 *   file for the right board is recoverable and the reverse is not.
 * - The package's hash is always shown, and whether it matched what the
 *   release published.
 * - The progress screen keeps itself awake and says, in as many words,
 *   not to walk away — the transfer is BLE and the phone leaving range
 *   is the commonest way it fails.
 * - A failure says what state the node is in. "It is waiting, not
 *   bricked" is the difference between a retry and a ladder.
 */
@Composable
fun FirmwareScreen(
    vm: MeshCoreViewModel,
    nav: NavController,
    target: FirmwareTargetKind = FirmwareTargetKind.ConnectedRadio,
    /**
     * Which release series to offer. A repeater and a room server run
     * different firmware on the same board, so this cannot be inferred
     * from the hardware — offering the wrong one would not brick the
     * node but would quietly turn it into a different kind of node.
     */
    role: FirmwareRole = FirmwareRole.Companion,
    /** The address the node reported when it took `start ota`, if we saw it. */
    otaAddress: String? = null,
    /**
     * The board and version of the node being updated, when it is not
     * the radio this app is connected to. A repeater reports both over
     * its admin session (`board`, `ver`); without them the picker cannot
     * narrow forty boards down to one and has to show them all.
     */
    nodeBoard: String? = null,
    nodeVersion: String? = null,
    /**
     * The node's contact key, when it is not the connected radio.
     *
     * Its console thread is read live so that a `board` reply arriving
     * after this screen opened still narrows the firmware list. Reading
     * it only from the route would mean a slow mesh — the normal case
     * for a distant repeater — silently fell back to showing every
     * board.
     */
    nodeKey: String? = null,
) {
    val context = LocalContext.current
    val state by vm.firmware.state.collectAsState()
    val deviceInfo by vm.deviceInfo.collectAsState()
    val capable = vm.firmwareUpdatesSupported()

    // A remote node's identity comes from its own console replies and
    // may land after this screen opens, so it is read live rather than
    // frozen into the route.
    val nodeReplies by remember(nodeKey) {
        if (nodeKey.isNullOrBlank()) MutableStateFlow(emptyList()) else vm.cliThread(nodeKey)
    }.collectAsState()
    val liveIdentity = remember(nodeReplies) {
        NodeIdentityReplies.from(nodeReplies.map { it.outgoing to it.text })
    }
    // What the node last said it was, from the contact record. This is
    // the only source that still works once it is in its bootloader and
    // has stopped answering the mesh entirely.
    val storedContact = vm.dbContacts.collectAsState().value
        .firstOrNull { nodeKey != null && it.keyHex == nodeKey }
    val remoteBoard = liveIdentity.board ?: nodeBoard ?: storedContact?.boardName
    val remoteVersion = liveIdentity.version ?: nodeVersion ?: storedContact?.firmwareVersion

    // For the connected radio the board comes from DEVICE_INFO; for
    // another node it is whatever it said when asked (`board`).
    val board = if (target == FirmwareTargetKind.ConnectedRadio) {
        deviceInfo?.boardName
    } else {
        remoteBoard
    }
    val nodeIdentity =
        listOfNotNull(remoteBoard, remoteVersion).joinToString(" · ").ifBlank { null }

    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "firmware.zip"
        if (bytes == null) {
            vm.firmware.useLocalPackage(ByteArray(0), name)
        } else {
            vm.firmware.useLocalPackage(bytes, name)
        }
    }

    // The whole flow belongs to one target; a screen opened from the
    // repeater hub must not reboot the phone's own radio.
    // The stored address wins over the route argument: it is updated
    // whenever the node is found, so it stays right as the node moves
    // between its own address and its bootloader's.
    val effectiveAddress = otaAddress ?: storedContact?.otaAddress
    remember(target, effectiveAddress) { vm.firmware.aimAt(target, effectiveAddress); target }

    Scaffold(
        topBar = { AppTopBar(title = "Firmware", vm = vm, nav = nav, menuActions = emptyList()) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            when (val current = state) {
                is FirmwareUi.Idle -> IdlePanel(
                    vm = vm,
                    target = target,
                    role = role,
                    capable = capable,
                    identity = if (target == FirmwareTargetKind.ConnectedRadio) {
                        deviceIdentityLine(deviceInfo)
                    } else {
                        nodeIdentity
                    },
                    boardName = board,
                    currentVersion = if (target == FirmwareTargetKind.ConnectedRadio) {
                        deviceInfo?.firmwareVersion
                    } else {
                        remoteVersion
                    },
                    message = current.message,
                    onPickFile = { openFile.launch(arrayOf("application/zip", "*/*")) },
                    onFlashOtherNode = { nav.navigate("firmware/node") },
                )

                FirmwareUi.Checking -> SectionSpinner("Reading the MeshCore release list…")

                is FirmwareUi.ChoosingVersion -> VersionPanel(
                    state = current.copy(boardName = board ?: current.boardName),
                    onChoose = { vm.firmware.chooseVersion(it, board) },
                )

                is FirmwareUi.Choosing -> ChoosePanel(
                    state = current,
                    // Picking from the full list means the user is
                    // telling us what the board is. Remember it: this
                    // node cannot be asked while it is in update mode,
                    // which is exactly when it is being flashed.
                    onChoose = { asset ->
                        val identified = current.suggested.isEmpty()
                        if (identified && nodeKey != null) {
                            vm.rememberHardware(nodeKey, asset.boardPrefix, null)
                        }
                        vm.firmware.choose(asset, remember = identified)
                    },
                    onPickFile = { openFile.launch(arrayOf("application/zip", "*/*")) },
                )

                is FirmwareUi.Downloading -> SectionSpinner("Downloading ${current.name}…")

                is FirmwareUi.Confirm -> ConfirmPanel(
                    state = current,
                    boardName = board,
                    onFlash = { vm.firmware.flash() },
                    onCancel = { vm.firmware.reset() },
                )

                is FirmwareUi.Running -> RunningPanel(current.progress)

                is FirmwareUi.Finished -> {
                    // The node has rebooted into new firmware, so it is
                    // out of update mode. The FLAG goes; the address
                    // stays — it is the node's Bluetooth address, which
                    // an update does not change, and it is what makes
                    // this node findable the next time.
                    LaunchedEffect(nodeKey) {
                        nodeKey?.let { vm.setUpdateMode(it, false) }
                    }
                    Text("Update complete", style = MaterialTheme.typography.titleMedium)
                    HintText(
                        "The node is rebooting into " +
                            (current.version?.let { "$it." } ?: "the new firmware.") +
                            " Reconnecting automatically.",
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.firmware.reset() }) { Text("Done") }
                }

                is FirmwareUi.WeakSignal -> {
                    Text("Too far away to risk it", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${current.peer} is reachable at ${current.rssi} dBm — near the " +
                            "floor of what a ${current.pkg.imageSize / 1024} KB transfer " +
                            "can be expected to survive.\n\nThat matters because of the " +
                            "order the node does things in: it erases its firmware before " +
                            "writing the new copy, so a transfer that stops half way leaves " +
                            "it with nothing to run until someone reaches it.\n\nNothing " +
                            "has been written. If you can get closer, do — and if you " +
                            "cannot, it is still worth trying.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    ButtonFlowRow {
                        Button(onClick = { vm.firmware.flash() }) { Text("Try again") }
                        OutlinedButton(onClick = { vm.firmware.reset() }) { Text("Stop") }
                    }
                    Spacer(Modifier.height(8.dp))
                    ExpandableHint("Go ahead anyway") {
                        DetailText(
                            "Nodes on masts and roofs cannot be approached, and this is the " +
                                "signal they have. A weak link often carries a whole image " +
                                "regardless — the transfer is slow rather than doomed.\n\n" +
                                "What it costs when it does not finish is another attempt " +
                                "from the same spot, and the node waiting in update mode " +
                                "until one succeeds. It does not become unrecoverable; it " +
                                "becomes unreachable over the mesh until it is flashed.",
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { vm.firmware.retryOverWeakSignal() }) {
                            Text("Transfer anyway")
                        }
                    }
                }

                is FirmwareUi.Failed -> {
                    Text("Update failed", style = MaterialTheme.typography.titleMedium)
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    current.recovery?.let {
                        Spacer(Modifier.height(8.dp))
                        HintText(it)
                    }
                    Spacer(Modifier.height(8.dp))
                    ButtonFlowRow {
                        // Offered only for the failure it actually fixes.
                        if (current.recovery == Recovery.TOO_FAST) {
                            Button(onClick = { vm.firmware.retrySlowly() }) {
                                Text("Retry more slowly")
                            }
                        }
                        OutlinedButton(onClick = { vm.firmware.reset() }) { Text("Back") }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun IdlePanel(
    vm: MeshCoreViewModel,
    target: FirmwareTargetKind,
    role: FirmwareRole,
    capable: Boolean,
    identity: String?,
    boardName: String?,
    currentVersion: String?,
    message: String?,
    onPickFile: () -> Unit,
    onFlashOtherNode: () -> Unit,
) {
    identity?.let {
        Text(it, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
    }
    message?.let {
        HintText(it)
        Spacer(Modifier.height(8.dp))
    }

    if (target == FirmwareTargetKind.ConnectedRadio && !capable) {
        Text(
            "This radio cannot be updated over the air.",
            style = MaterialTheme.typography.titleSmall,
        )
        HintText("It offers no firmware-update service over this link.")
        Spacer(Modifier.height(8.dp))
        // Not a dead end. The radio in your hand having no DFU service
        // says nothing about the node you are standing next to, and
        // reaching that node needs no mesh, no admin session and no
        // contact record — only Bluetooth range.
        OutlinedButton(onClick = onFlashOtherNode) {
            Text("Flash a node that is in update mode")
        }
        Spacer(Modifier.height(8.dp))
        ExpandableHint("What can be updated over the air") {
            DetailText(
                "Over-the-air updates need an nRF52 board running companion firmware " +
                    "v1.15 or newer, connected over Bluetooth — that is where the update " +
                    "service lives.\n\n" +
                    "ESP32 boards (Heltec V3 and similar) update over USB, or over their " +
                    "own WiFi hotspot after `start ota`: connect to \"MeshCore OTA\" and " +
                    "open http://192.168.4.1/update in a browser. That path is deliberately " +
                    "not built into this app — a browser does it properly, and this app " +
                    "does not use WiFi.",
            )
        }
        return
    }

    if (target == FirmwareTargetKind.NodeInUpdateMode) {
        HintText("You have to be within Bluetooth range — this does not go over the mesh.")
        Spacer(Modifier.height(8.dp))
        ExpandableHint("What happens to the node") {
            DetailText(
                "A node that has taken `start ota` is still running its own firmware and " +
                    "still repeating — it has simply switched its Bluetooth on and is " +
                    "advertising for an update.\n\n" +
                    "It reboots into its bootloader at the moment this app connects and " +
                    "starts the transfer, not before. Up to that point nothing has been " +
                    "committed and walking away costs nothing.",
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    ExpandableHint("Before updating a node you cannot easily reach") {
        DetailText(
            "A failed transfer leaves the node in update mode, waiting — not bricked. It " +
                "can be flashed again from close by. What it cannot do is go back to " +
                "running MeshCore on its own.\n\n" +
                "The stock bootloader on most boards erases flash before it starts, so a " +
                "node interrupted mid-update has no application to fall back on. The " +
                "OTAFIX bootloader (github.com/oltaco/Adafruit_nRF52_Bootloader_OTAFIX) " +
                "returns to update mode by itself when that happens, and is strongly " +
                "recommended before updating anything on a mast.",
        )
    }

    Spacer(Modifier.height(8.dp))
    ButtonFlowRow {
        Button(onClick = {
            vm.firmware.checkForFirmware(
                boardName = boardName,
                role = role,
                currentVersion = currentVersion,
            )
        }) { Text("Check for firmware") }
        OutlinedButton(onClick = onPickFile) { Text("Open a .zip…") }
    }
    HintText("Checking fetches the MeshCore release list from GitHub, only when you ask.")
    if (target == FirmwareTargetKind.ConnectedRadio) {
        Spacer(Modifier.height(8.dp))
        // A node already in update mode is off the mesh, so there is no
        // admin session to reach it through and possibly no contact
        // record either. It is found by scanning, which needs none of
        // that — so this must not be reachable only from the node's own
        // hub, which is precisely where it cannot be reached.
        TextButton(onClick = onFlashOtherNode) {
            Text("Another node is already in update mode…")
        }
    }
}

/**
 * Which version to install.
 *
 * Newest first, but nothing is preselected and the newest is not
 * treated as the answer: MeshCore ships releases that turn out not to
 * be ready, and someone updating a node they have to climb to reach is
 * usually choosing a version they have already run somewhere else.
 */
@Composable
private fun VersionPanel(
    state: FirmwareUi.ChoosingVersion,
    onChoose: (FirmwareVersion) -> Unit,
) {
    Text("Choose a version", style = MaterialTheme.typography.titleMedium)
    HintText(
        state.boardName?.let { "For $it." }
            ?: "The board could not be read, so every build will be listed.",
    )
    Spacer(Modifier.height(8.dp))
    for (version in state.versions) {
        // A node reports "v1.16.0-07a3ca9"; the tag is "v1.16.0". Compare
        // the numbers, not the strings.
        val isCurrent = state.currentVersion != null &&
            VersionOrder.key(version.version) == VersionOrder.key(state.currentVersion) &&
            VersionOrder.key(version.version) > 0
        val isNewest = version == state.versions.first()
        SettingsTileRow(
            title = version.version,
            subtitle = when {
                isCurrent && isNewest -> "Installed now · newest"
                isCurrent -> "Installed now"
                isNewest -> "Newest"
                else -> "Older"
            },
            dimmed = false,
            onClick = { onChoose(version) },
        )
    }
    Spacer(Modifier.height(8.dp))
    ExpandableHint("Which version should I install?") {
        DetailText(
            "Newest is not automatically best. MeshCore releases often and a release is " +
                "sometimes withdrawn or followed quickly by a fix, so the version worth " +
                "installing on a node you cannot easily reach is usually one that has " +
                "already been running somewhere you can.\n\n" +
                "Going backwards is allowed: the bootloader does not check that a version " +
                "is newer than the one installed.",
        )
    }
}

@Composable
private fun ChoosePanel(
    state: FirmwareUi.Choosing,
    onChoose: (FirmwareAsset) -> Unit,
    onPickFile: () -> Unit,
) {
    if (state.suggested.isEmpty()) {
        Text("Pick the build for this board", style = MaterialTheme.typography.titleMedium)
        HintText(
            "Pick the one for this node — it will be remembered for next time.",
        )
        ExpandableHint("Why is it asking?") {
            DetailText(
                "This node did not tell the app what board it is. A repeater only answers " +
                    "that over the mesh, and a node in update mode has left the mesh — so " +
                    "the one time it matters most is the one time it cannot be asked.\n\n" +
                    "Choose from the list and it is stored against the node, so this is a " +
                    "one-off. Check it against flasher.meshcore.io if you are unsure: the " +
                    "package cannot tell you which board it is for, because every nRF52 " +
                    "board declares the same device type.",
            )
        }
    } else {
        Text("${state.boardName} · ${state.version}", style = MaterialTheme.typography.titleMedium)
        HintText(
            if (state.suggested.size > 1) {
                "This board ships more than one build, so check the name."
            } else {
                "The build for this board in ${state.version}."
            },
        )
    }
    Spacer(Modifier.height(8.dp))
    for (asset in state.suggested) {
        AssetRow(asset, onChoose)
    }
    if (state.others.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        ExpandableHint("Show every board in this release (${state.others.size})") {
            Column {
                for (asset in state.others) AssetRow(asset, onChoose)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onPickFile) { Text("Open a .zip instead…") }
}

@Composable
private fun AssetRow(asset: FirmwareAsset, onChoose: (FirmwareAsset) -> Unit) {
    SettingsTileRow(
        title = asset.boardPrefix,
        subtitle = "${asset.version} · ${asset.sizeBytes / 1024} KB" +
            if (asset.sha256 == null) " · no published checksum" else "",
        dimmed = false,
        onClick = { onChoose(asset) },
    )
}

@Composable
private fun ConfirmPanel(
    state: FirmwareUi.Confirm,
    boardName: String?,
    onFlash: () -> Unit,
    onCancel: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    Text("Ready to flash", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(state.source.sourceDescription, style = MaterialTheme.typography.bodyLarge)
    HintText("${state.pkg.imageSize / 1024} KB image")
    Spacer(Modifier.height(8.dp))

    Text("SHA-256", style = MaterialTheme.typography.labelMedium)
    Text(
        state.source.sha256,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    HintText(
        if (state.source.verifiedAgainstRelease) {
            "Matches the checksum published with the release."
        } else {
            "No checksum was published for this file, so this is what arrived — not proof " +
                "of what it should be. Compare it against flasher.meshcore.io if it matters."
        },
    )

    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Text(
        "The package cannot tell you which board it is for: every nRF52 board declares the " +
            "same device type, so the filename is the only thing that says. Flashing a " +
            "build for a different board leaves the node reachable only over USB.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))
    ButtonFlowRow {
        Button(onClick = { confirming = true }) { Text("Flash this to the node") }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Flash ${boardName ?: "this node"}?") },
            text = {
                Text(
                    "This writes ${state.source.sourceDescription} to " +
                        (boardName?.let { "a node reporting itself as \"$it\"" }
                            ?: "the node") +
                        ".\n\nKeep the phone next to it and this screen open until it " +
                        "finishes. If it is interrupted the node stays in update mode " +
                        "and has to be flashed again from close by.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onFlash()
                }) { Text("Flash") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RunningPanel(progress: DfuProgress) {
    KeepScreenOn()
    val label = when (progress) {
        DfuProgress.Preparing -> "Preparing…"
        DfuProgress.EnteringBootloader -> "Asking the radio to restart in update mode…"
        DfuProgress.FindingNode -> "Looking for the node in update mode…"
        is DfuProgress.Connecting -> "Connecting to ${progress.peer.name ?: progress.peer.address}…"
        is DfuProgress.Transferring -> "Sending firmware…"
        DfuProgress.Verifying -> "Verifying the image on the node…"
        DfuProgress.Finished -> "Done"
        is DfuProgress.SignalTooWeak -> "Too far away"
        // Said out loud rather than hidden behind a progress bar that
        // silently starts again: the node is erased at this point, and
        // "it is trying again more slowly" is the difference between
        // waiting and reaching for a cable.
        is DfuProgress.Retrying ->
            "${progress.reason} Trying again at ${progress.receiptInterval} " +
                "packets per acknowledgement…"

        is DfuProgress.Failed -> progress.message
    }
    Text(label, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    if (progress is DfuProgress.Transferring) {
        LinearProgressIndicator(
            progress = { progress.fraction.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        HintText("${progress.bytesSent / 1024} of ${progress.totalBytes / 1024} KB")
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(12.dp))
    HintText(
        "Stay next to the node and leave this screen open. The screen is being kept awake " +
            "for the transfer.",
    )
}
