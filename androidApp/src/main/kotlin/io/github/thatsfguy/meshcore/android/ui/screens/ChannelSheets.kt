package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.thatsfguy.meshcore.android.platform.PortraitCaptureActivity
import io.github.thatsfguy.meshcore.android.storage.ChannelEntity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import io.github.thatsfguy.meshcore.android.platform.Qr
import io.github.thatsfguy.meshcore.protocol.ShareUri
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.presentation.ChannelSetup
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight

/** The add sheet's choices, in the order Liam's MeshCore app offers them. */
private enum class AddChannelChoice { Public, CreatePrivate, JoinPrivate, Hashtag }

/**
 * Channel add sheet: four named choices, each a short form of its own,
 * plus the community QR scan.
 *
 * It was one form with two hidden rules — a name starting with '#' derived
 * the key, and a blank key field meant "make a random one" — and nothing
 * said that the KEY is the channel. That is how two radios end up holding
 * one channel under two names without anyone knowing. The choices and
 * their one-line descriptions follow the mainstream MeshCore app; the
 * security wording stays ours (obfuscated, not secure).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelAddSheet(vm: MeshCoreViewModel, onDismiss: () -> Unit) {
    var choice by remember { mutableStateOf<AddChannelChoice?>(null) }

    val communityScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.importScannedCode(it) }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            when (val c = choice) {
                null -> {
                    Text("Add a channel", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A channel is a group conversation across the mesh. It is its secret key: " +
                            "everyone with the same key is in the same channel, whatever each of " +
                            "them calls it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    ChoiceRow("Join the Public Channel", "Anyone can join this channel.") {
                        choice = AddChannelChoice.Public
                    }
                    ChoiceRow("Create a Private Channel", "Only people you give the key to can join.") {
                        choice = AddChannelChoice.CreatePrivate
                    }
                    ChoiceRow("Join a Private Channel", "Enter a secret key someone gave you.") {
                        choice = AddChannelChoice.JoinPrivate
                    }
                    ChoiceRow("Join a Hashtag Channel", "Anyone can join hashtag channels.") {
                        choice = AddChannelChoice.Hashtag
                    }
                    ChoiceRow("Scan a channel or community QR", "Join from a code someone shows you.") {
                        communityScanLauncher.launch(
                            meshScanOptions("Scan a MeshCore QR — contact, channel or community"),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Channels are obfuscated, not secure (AES-ECB with a 2-byte MAC). Don't " +
                            "send anything you couldn't bear to be read.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> AddChannelForm(vm, c, onBack = { choice = null }, onDone = onDismiss)
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, description: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddChannelForm(
    vm: MeshCoreViewModel,
    choice: AddChannelChoice,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    var name by remember(choice) { mutableStateOf("") }
    var key by remember(choice) { mutableStateOf("") }

    val title = when (choice) {
        AddChannelChoice.Public -> "Join the Public Channel"
        AddChannelChoice.CreatePrivate -> "Create a Private Channel"
        AddChannelChoice.JoinPrivate -> "Join a Private Channel"
        AddChannelChoice.Hashtag -> "Join a Hashtag Channel"
    }
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))

    // Validation runs as you type, but only complains once there is
    // something to complain about.
    val nameResult = when (choice) {
        AddChannelChoice.Hashtag -> ChannelSetup.hashtag(name)
        AddChannelChoice.CreatePrivate, AddChannelChoice.JoinPrivate -> ChannelSetup.privateName(name)
        AddChannelChoice.Public -> null
    }
    val keyResult = if (choice == AddChannelChoice.JoinPrivate) ChannelSetup.privateKey(key) else null
    fun problem(r: ChannelSetup.Result<*>?, typed: String) =
        (r as? ChannelSetup.Result.Problem)?.message?.takeIf { typed.isNotBlank() }

    when (choice) {
        AddChannelChoice.Public -> Explain(
            "The Public channel uses a key every MeshCore radio knows. Anyone in range can read " +
                "and post in it.",
        )
        AddChannelChoice.Hashtag -> Explain(
            "Hashtag channels are public. Anyone can join by entering the same name, because the " +
                "key is made from the name. Only a–z, 0–9 and hyphens.",
        )
        AddChannelChoice.CreatePrivate -> Explain(
            "A new random key is made on this phone. Only people you give it to can join. After " +
                "creating it, share it from the channel's settings → Share channel QR.",
        )
        AddChannelChoice.JoinPrivate -> Explain(
            "The name can be anything you like — it stays on this phone. Only the secret key " +
                "must match.",
        )
    }

    if (choice != AddChannelChoice.Public) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(if (choice == AddChannelChoice.Hashtag) "Channel name" else "Name") },
            prefix = if (choice == AddChannelChoice.Hashtag) ({ Text("#") }) else null,
            singleLine = true,
            isError = problem(nameResult, name) != null,
            supportingText = problem(nameResult, name)?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (choice == AddChannelChoice.JoinPrivate) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Secret key (32 hex characters)") },
            singleLine = true,
            isError = problem(keyResult, key) != null,
            supportingText = problem(keyResult, key)?.let { { Text(it) } },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val okName = (nameResult as? ChannelSetup.Result.Ok)?.value
    val okKey = (keyResult as? ChannelSetup.Result.Ok)?.value
    val ready = when (choice) {
        AddChannelChoice.Public -> true
        AddChannelChoice.JoinPrivate -> okName != null && okKey != null
        else -> okName != null
    }
    androidx.compose.foundation.layout.Row {
        TextButton(onClick = onBack) { Text("Back") }
        Spacer(Modifier.weight(1f))
        TextButton(
            enabled = ready,
            onClick = {
                when (choice) {
                    AddChannelChoice.Public -> vm.joinPublicChannel()
                    AddChannelChoice.Hashtag -> vm.joinHashtagChannel(okName!!)
                    AddChannelChoice.CreatePrivate -> vm.createPrivateChannel(okName!!)
                    AddChannelChoice.JoinPrivate -> vm.joinPrivateChannel(okName!!, okKey!!)
                }
                onDone()
            },
        ) { Text(if (choice == AddChannelChoice.CreatePrivate) "Create" else "Join") }
    }
}

@Composable
private fun Explain(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
}

/** Channel editor (rename / change PSK / remove) for Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelEditSheet(vm: MeshCoreViewModel, channel: ChannelEntity, onDismiss: () -> Unit) {
    // See the confirmation below for why sharing needs one.
    var name by remember { mutableStateOf(channel.name) }
    var pskHex by remember { mutableStateOf("") }
    var pskLoaded by remember { mutableStateOf(false) }
    var shareConfirm by remember { mutableStateOf(false) }
    var shareQr by remember { mutableStateOf(false) }

    LaunchedEffect(channel.idx) {
        vm.channelPskHex(channel)?.let { pskHex = it }
        pskLoaded = true
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Channel ${channel.idx}", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pskHex,
                onValueChange = { pskHex = it },
                label = { Text(if (pskLoaded) "PSK (32 hex chars)" else "Loading PSK…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "The PSK is stored sealed in the device keystore and on the radio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Default,
            )
            Spacer(Modifier.height(12.dp))
            // The region is local-only routing state (PARITY §8): it
            // scopes which repeaters carry this channel's traffic and is
            // never written to the radio's channel slot.
            ChannelRegionPicker(vm, channel.idx)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                vm.editChannel(channel.idx, name.trim(), pskHex.trim())
                onDismiss()
            }) { Text("Save") }
            TextButton(
                onClick = { shareConfirm = true },
                enabled = pskLoaded && pskHex.isNotBlank(),
            ) { Text("Share channel QR…") }
            TextButton(onClick = {
                vm.deleteChannel(channel.idx)
                onDismiss()
            }) { Text("Remove channel", color = MaterialTheme.colorScheme.error) }
        }
    }

    // Sharing a channel hands over its key, and the key IS the channel.
    // Make that a decision, not a tap.
    if (shareConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { shareConfirm = false },
            title = { Text("Share this channel?") },
            text = {
                Text(
                    "The code contains the channel's secret key. Anyone who scans it can " +
                        "read everything on this channel — including messages sent before " +
                        "they scanned, because the key never changes and the cipher has no " +
                        "forward secrecy.\n\nThere is no way to revoke it: undoing a share " +
                        "means changing the key on every device that uses the channel.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { shareConfirm = false; shareQr = true }) {
                    Text("Show the code", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { shareConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (shareQr) {
        ChannelQrDialog(
            name = name.ifBlank { "Channel ${channel.idx}" },
            pskHex = pskHex.trim(),
            // Share the scope along with the key: a code that omits it
            // hands someone a channel that behaves differently from the
            // one you are running, and neither of you can see why.
            regionScope = vm.prefs.channelRegion(channel.idx).orEmpty(),
            onDismiss = { shareQr = false },
        )
    }
}

/** QR for `meshcore://channel/add?…` — the form other MeshCore apps scan. */
@Composable
private fun ChannelQrDialog(
    name: String,
    pskHex: String,
    regionScope: String,
    onDismiss: () -> Unit,
) {
    val uri = remember(name, pskHex, regionScope) {
        ShareUri.encodeChannel(name, pskHex, regionScope)
    }
    val qr = remember(uri) { runCatching { Qr.encode(uri) }.getOrNull() }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share $name", maxLines = 2) },
        text = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                qr?.let {
                    androidx.compose.foundation.Image(
                        it.asImageBitmap(),
                        contentDescription = "Channel QR",
                        modifier = Modifier.size(280.dp),
                    )
                }
                Text(
                    "Anyone who scans this can read the channel. Show it only to people " +
                        "you mean to give that to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(uri))
            }) { Text("Copy link") }
        },
    )
}
