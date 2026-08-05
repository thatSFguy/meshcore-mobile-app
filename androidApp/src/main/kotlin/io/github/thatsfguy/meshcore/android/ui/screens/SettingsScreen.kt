package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.thatsfguy.meshcore.android.storage.DatabaseKey
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.engine.EngineState

/**
 * Settings — a hub of grouped tiles, each opening one screen.
 *
 * It was eleven expandable sections on a single scroll, with the App
 * section holding another eight surfaces inside it. Nothing designed
 * that; it accumulated, one feature at a time, because adding a
 * section to an existing screen costs nothing and making a screen
 * feels like work (LESSONS §13, REBUILD-PLAYBOOK §6.2).
 *
 * The structure is [settingsGroups]; the tiles report their current
 * value so the common question — *is TCP on? is the map fetching
 * tiles? what frequency am I on?* — is answered without opening
 * anything.
 *
 * Per the app-wide header contract, device actions with no natural
 * home live in the ⋮ menu. Adverts and the node QR are NOT among them
 * any more: they belong to Identity, and now have a screen to live on.
 */
@Composable
fun SettingsScreen(vm: MeshCoreViewModel, nav: NavController) {
    var rebootConfirm by remember { mutableStateOf(false) }

    val engineState by vm.engineState.collectAsState()
    val connectionLabel by vm.connectionLabel.collectAsState()
    val self by vm.selfInfo.collectAsState()
    val channels by vm.dbChannels.collectAsState()
    val blocked by vm.blockedKeys.collectAsState()
    val isReady = engineState == EngineState.Ready

    // Read once per composition — these are plain prefs, not flows, and
    // the hub recomposes when it is returned to.
    val subtitles: Map<String, String> = mapOf(
        "connection" to connectionSubtitle(engineState, connectionLabel),
        "transports" to transportsSubtitle(
            ble = vm.prefs.bleEnabled,
            usb = vm.prefs.usbEnabled,
            tcp = vm.prefs.tcpEnabled,
        ),
        "identity" to if (isReady) identitySubtitle(self?.name) else "Connect to a radio",
        "radio" to radioSubtitle(self?.freqKhz, self?.sf, self?.txPowerDbm),
        "channels" to channelsSubtitle(channels.size),
        "blocking" to blockingSubtitle(blocked.size),
        "app" to appSubtitle(
            theme = vm.prefs.theme,
            notificationsEnabled = vm.prefs.notificationsEnabled,
            storageEncrypted = DatabaseKey.encryptionUnavailableReason == null,
        ),
    )

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Settings",
                vm = vm,
                menuActions = listOf(
                    MenuAction("Reboot radio…", destructive = true) { rebootConfirm = true },
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            for ((index, group) in settingsGroups().withIndex()) {
                if (index > 0) HorizontalDivider(Modifier.padding(top = 8.dp))
                GroupLabel(group.title)
                for (tile in group.tiles) {
                    SettingsTileRow(
                        title = tile.title,
                        subtitle = subtitles[tile.route] ?: tile.subtitle,
                        // NOT dimmed. Driving this disconnected, six of the
                        // ten rows on the first screen were greyed — the
                        // majority of the app's Settings reading as broken
                        // before you have done anything wrong. The subtitle
                        // already says "Connect to a radio"; that is the
                        // whole message, and it does not need the row to
                        // look disabled to carry it.
                        dimmed = false,
                        onClick = { nav.navigate("settings/${tile.route}") },
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
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
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsTileRow(
    title: String,
    subtitle: String,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (dimmed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (subtitle.startsWith("⚠")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
