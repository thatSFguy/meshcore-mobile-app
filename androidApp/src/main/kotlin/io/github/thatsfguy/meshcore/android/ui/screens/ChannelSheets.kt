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
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel

/**
 * Channel add sheet: manual name+PSK (blank PSK → random for private
 * channels, hashtag names derive their PSK), plus the community QR join
 * flow (SCOPE.md "community QR join").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelAddSheet(vm: MeshCoreViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var pskHex by remember { mutableStateOf("") }

    val communityScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.joinCommunity(it) }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Add channel", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (#hashtag derives its key)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pskHex,
                onValueChange = { pskHex = it },
                label = { Text("PSK hex (blank = derive/generate)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Channels use AES-ECB with a 2-byte MAC — treat them as obfuscated, not secure. " +
                    "#hashtag channel keys are derivable by anyone who knows the name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        vm.addChannel(name.trim(), pskHex.trim())
                        onDismiss()
                    }
                },
            ) { Text("Add channel") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Community", style = MaterialTheme.typography.titleMedium)
            Text(
                "Join a community by scanning its QR code. The community secret is stored in the " +
                    "device keystore and the community's channel is written to a free slot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                communityScanLauncher.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan a MeshCore community QR")
                        .setBeepEnabled(false)
                        .setCaptureActivity(PortraitCaptureActivity::class.java),
                )
            }) { Text("Scan community QR") }
        }
    }
}

/** Channel editor (rename / change PSK / remove) for Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelEditSheet(vm: MeshCoreViewModel, channel: ChannelEntity, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(channel.name) }
    var pskHex by remember { mutableStateOf("") }
    var pskLoaded by remember { mutableStateOf(false) }

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
            TextButton(onClick = {
                vm.editChannel(channel.idx, name.trim(), pskHex.trim())
                onDismiss()
            }) { Text("Save") }
            TextButton(onClick = {
                vm.deleteChannel(channel.idx)
                onDismiss()
            }) { Text("Remove channel", color = MaterialTheme.colorScheme.error) }
        }
    }
}
