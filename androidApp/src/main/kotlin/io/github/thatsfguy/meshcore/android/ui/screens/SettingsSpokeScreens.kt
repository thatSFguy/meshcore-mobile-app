package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.storage.ChannelEntity
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.engine.EngineState
import androidx.navigation.NavController

/**
 * The spokes of [SettingsScreen] — one settings group per screen.
 *
 * The section bodies in `SettingsSections.kt` are unchanged; what
 * changed is that each now owns a screen with a title and a back
 * button instead of being the eleventh chevron on a single scroll.
 *
 * The "get"-backed ones still query the radio on arrival rather than
 * showing a cached value, so what you read is what the node reports
 * NOW — that behaviour moved from [QueryOnExpand] on expand to
 * [QueryOnExpand] on entry, which is the same contract with a better
 * trigger: you can only see the screen by opening it.
 */
@Composable
private fun SettingsSpoke(
    title: String,
    vm: MeshCoreViewModel,
    nav: NavController,
    menuActions: List<MenuAction> = emptyList(),
    scroll: Boolean = true,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            AppTopBar(title = title, vm = vm, nav = nav, menuActions = menuActions)
        },
    ) { padding ->
        val base = Modifier.fillMaxSize().padding(padding)
        Column(
            if (scroll) {
                base.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            } else {
                base.padding(horizontal = 16.dp)
            },
        ) {
            Spacer(Modifier.height(8.dp))
            content()
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * Query the radio when the screen opens, spinner until the answer
 * lands. [QueryOnExpand] already does exactly this; it is named for
 * the accordion it was written for.
 */
@Composable
private fun QueryOnEntry(
    vm: MeshCoreViewModel,
    query: suspend () -> Unit,
    content: @Composable () -> Unit,
) {
    val engineState by vm.engineState.collectAsState()
    QueryOnExpand(enabled = engineState == EngineState.Ready, query = query, content = content)
}

// --- Radio link --------------------------------------------------------

@Composable
fun SettingsConnectionScreen(vm: MeshCoreViewModel, nav: NavController) {
    var showAddNode by remember { mutableStateOf(false) }
    SettingsSpoke("Connection", vm, nav) {
        ConnectionSection(vm, onAddNode = { showAddNode = true })
    }
    if (showAddNode) {
        AddNodeSheet(vm, tcpEnabled = vm.prefs.tcpEnabled, onDismiss = { showAddNode = false })
    }
}

@Composable
fun SettingsTransportsScreen(vm: MeshCoreViewModel, nav: NavController) {
    var tcpEnabled by remember { mutableStateOf(vm.prefs.tcpEnabled) }
    var showTcpWarning by remember { mutableStateOf(false) }
    SettingsSpoke("Transports", vm, nav) {
        TransportsSection(
            vm,
            tcpEnabled = tcpEnabled,
            onTcpToggle = { wanted ->
                if (wanted && !vm.prefs.tcpWarningAccepted) {
                    showTcpWarning = true
                } else {
                    tcpEnabled = wanted
                    vm.prefs.tcpEnabled = wanted
                }
            },
        )
    }
    if (showTcpWarning) {
        TcpSternWarningDialog(
            onAccept = {
                vm.prefs.tcpWarningAccepted = true
                vm.prefs.tcpEnabled = true
                tcpEnabled = true
                showTcpWarning = false
            },
            onCancel = { showTcpWarning = false },
        )
    }
}

// --- This node ---------------------------------------------------------

@Composable
fun SettingsIdentityScreen(vm: MeshCoreViewModel, nav: NavController) {
    var showSelfQr by remember { mutableStateOf(false) }
    SettingsSpoke(
        "Identity",
        vm,
        nav,
        menuActions = listOf(
            MenuAction("Send advert (0-hop)") { vm.sendSelfAdvert(flood = false) },
            MenuAction("Send advert (flood)") { vm.sendSelfAdvert(flood = true) },
            MenuAction("Share my node QR…") { showSelfQr = true },
        ),
    ) {
        QueryOnEntry(vm, query = { vm.querySelfInfo() }) {
            IdentitySection(vm, onShowSelfQr = { showSelfQr = true })
        }
    }
    if (showSelfQr) {
        SelfQrDialog(vm, onDismiss = { showSelfQr = false })
    }
}

@Composable
fun SettingsRadioScreen(vm: MeshCoreViewModel, nav: NavController) {
    SettingsSpoke("Radio", vm, nav) {
        QueryOnEntry(vm, query = { vm.querySelfInfo() }) { RadioSection(vm) }
    }
}

@Composable
fun SettingsClockScreen(vm: MeshCoreViewModel, nav: NavController) {
    SettingsSpoke("Clock", vm, nav) { ClockSection(vm) }
}

@Composable
fun SettingsPoliciesScreen(vm: MeshCoreViewModel, nav: NavController) {
    SettingsSpoke("Mesh policies", vm, nav) {
        // Policies span two frames: SELF_INFO (telemetry/advert/multi-ack
        // bytes) and DEVICE_INFO (path-hash width).
        QueryOnEntry(
            vm,
            query = {
                vm.querySelfInfo()
                vm.queryDeviceInfo()
            },
        ) { PoliciesSection(vm) }
    }
}

@Composable
fun SettingsAutoAddScreen(vm: MeshCoreViewModel, nav: NavController) {
    SettingsSpoke("Auto-add contacts", vm, nav) {
        QueryOnEntry(vm, query = { vm.queryAutoAddConfig() }) { AutoAddSection(vm) }
    }
}

@Composable
fun SettingsCustomVarsScreen(vm: MeshCoreViewModel, nav: NavController) {
    SettingsSpoke("Custom variables", vm, nav) {
        QueryOnEntry(vm, query = { vm.queryCustomVars() }) { CustomVarsSection(vm) }
    }
}

// --- Messaging ---------------------------------------------------------

@Composable
fun SettingsChannelsScreen(vm: MeshCoreViewModel, nav: NavController) {
    var editChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    SettingsSpoke("Channels", vm, nav) {
        ChannelsSection(vm, onEdit = { editChannel = it })
    }
    editChannel?.let { ch ->
        ChannelEditSheet(vm, ch, onDismiss = { editChannel = null })
    }
}

@Composable
fun SettingsBlockingScreen(vm: MeshCoreViewModel, nav: NavController) {
    SettingsSpoke("Blocked senders", vm, nav) { BlockingSection(vm) }
}

// --- App ---------------------------------------------------------------

@Composable
fun SettingsAppearanceScreen(vm: MeshCoreViewModel, nav: NavController) {
    SettingsSpoke("Appearance", vm, nav) { AppearanceSection(vm) }
}

@Composable
fun SettingsNotificationsScreen(vm: MeshCoreViewModel, nav: NavController) {
    SettingsSpoke("Notifications", vm, nav) { NotificationsSection(vm) }
}

@Composable
fun SettingsPrivacyScreen(vm: MeshCoreViewModel, nav: NavController) {
    SettingsSpoke("Privacy and network", vm, nav) { PrivacySection(vm) }
}

@Composable
fun SettingsBackupScreen(vm: MeshCoreViewModel, nav: NavController) {
    SettingsSpoke("Backup", vm, nav) { BackupSection(vm) }
}

@Composable
fun SettingsDataScreen(vm: MeshCoreViewModel, nav: NavController) {
    SettingsSpoke("Data and storage", vm, nav) { DataSection(vm) }
}

@Composable
fun SettingsDiagnosticsScreen(vm: MeshCoreViewModel, nav: NavController) {
    // The log viewer is a LazyColumn with its own height cap, so this
    // screen must not also be verticalScroll — nested scrollables in the
    // same direction throw at measure time.
    SettingsSpoke("Diagnostics", vm, nav, scroll = false) { DiagnosticsSection(vm) }
}

@Composable
fun SettingsAboutScreen(vm: MeshCoreViewModel, nav: NavController) {
    SettingsSpoke("About", vm, nav) { AboutSection(vm) }
}
