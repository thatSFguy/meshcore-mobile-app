package io.github.thatsfguy.meshcore.android

import io.github.thatsfguy.meshcore.presentation.decodePrefill
import io.github.thatsfguy.meshcore.presentation.Inbox
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.thatsfguy.meshcore.android.platform.BlePermissions
import io.github.thatsfguy.meshcore.android.ui.FirmwareTargetKind
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.android.ui.screens.FirmwareScreen
import io.github.thatsfguy.meshcore.firmware.FirmwareRole
import io.github.thatsfguy.meshcore.android.ui.screens.HEARD_REPEATS_ROUTE
import io.github.thatsfguy.meshcore.android.ui.screens.HeardRepeatsScreen
import io.github.thatsfguy.meshcore.android.ui.screens.CONVERSATION_ROUTE
import io.github.thatsfguy.meshcore.android.ui.screens.ChatsScreen
import io.github.thatsfguy.meshcore.android.ui.screens.conversationRoute
import io.github.thatsfguy.meshcore.android.ui.screens.ConversationScreen
import io.github.thatsfguy.meshcore.android.ui.screens.MapScreen
import io.github.thatsfguy.meshcore.android.ui.screens.NodesScreen
import io.github.thatsfguy.meshcore.android.ui.screens.OsmdroidSetup
import io.github.thatsfguy.meshcore.android.ui.screens.RepeaterConsoleScreen
import io.github.thatsfguy.meshcore.android.ui.screens.ScanConfirmations
import io.github.thatsfguy.meshcore.android.ui.screens.RepeaterFirmwareScreen
import io.github.thatsfguy.meshcore.android.ui.screens.RepeaterHelpScreen
import io.github.thatsfguy.meshcore.android.ui.screens.RepeaterHubScreen
import io.github.thatsfguy.meshcore.android.ui.screens.RepeaterIdentityScreen
import io.github.thatsfguy.meshcore.android.ui.screens.RepeaterRegionsScreen
import io.github.thatsfguy.meshcore.android.ui.screens.RepeaterSettingsScreen
import io.github.thatsfguy.meshcore.android.ui.screens.RepeaterStatusScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsAboutScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsAppScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsAutoAddScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsBackupScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsBlockingScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsChannelsScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsClockScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsConnectionScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsCustomVarsScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsDataScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsDevicePinScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsIdentityScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsPoliciesScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsRadioScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsTransportsScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SetupScreen
import io.github.thatsfguy.meshcore.android.ui.theme.MeshCoreTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    /**
     * A conversation the user asked for by tapping its notification.
     *
     * Held as state rather than read directly in the composable because
     * it arrives twice over: once in the Intent that starts the activity
     * cold, and again through [onNewIntent] when the app is already
     * running — which, with `launchMode=singleTask`, is the common case.
     */
    private val openThread = MutableStateFlow<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BlePermissions.allGranted(this)) {
            permissionLauncher.launch(BlePermissions.required())
        }
        // osmdroid's configuration is a process-wide singleton, and a
        // MapView built before it is set never receives a tile — it
        // shows an empty grid indistinguishable from "tiles are off".
        // Doing it here means no screen can be the one that forgot;
        // relying on each map to configure itself is what left the
        // message-route map blank unless the Map tab had been opened
        // first.
        OsmdroidSetup.apply(this)
        openThread.value = threadFrom(intent)
        setContent {
            val vm: MeshCoreViewModel = viewModel()
            val theme by vm.prefs.themeFlow.collectAsState()
            MeshCoreTheme(
                darkTheme = when (theme) {
                    "dark" -> true
                    "light" -> false
                    else -> androidx.compose.foundation.isSystemInDarkTheme()
                },
            ) {
                AppShell(vm, openThread)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() current, or a later configuration change
        // replays whichever notification started the activity.
        setIntent(intent)
        openThread.value = threadFrom(intent)
    }

    private fun threadFrom(intent: Intent?): Pair<String, String>? {
        val kind = intent?.getStringExtra(EXTRA_THREAD_KIND)?.takeIf { it.isNotBlank() }
        val peer = intent?.getStringExtra(EXTRA_THREAD_PEER)?.takeIf { it.isNotBlank() }
        return if (kind != null && peer != null) kind to peer else null
    }

    companion object {
        const val EXTRA_THREAD_KIND = "thread_kind"
        const val EXTRA_THREAD_PEER = "thread_peer"
    }
}

private data class Tab(
    val route: String,
    val label: String,
    /** "" for no badge — [Inbox.badgeLabel] decides, not the caller. */
    val badge: String = "",
    val icon: @Composable () -> Unit,
)

@Composable
private fun AppShell(
    vm: MeshCoreViewModel,
    openThread: MutableStateFlow<Pair<String, String>?>,
) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }

    // Tapping a notification lands on the conversation it was about.
    // popUpTo("chats") so Back leaves you in the app on the message
    // list, rather than dropping you out of it or onto whatever screen
    // happened to be open when the message arrived.
    val pendingThread by openThread.collectAsState()
    LaunchedEffect(pendingThread) {
        val (kind, peer) = pendingThread ?: return@LaunchedEffect
        nav.navigate(conversationRoute(kind, peer)) {
            popUpTo("chats")
            launchSingleTop = true
        }
        openThread.value = null
    }

    val transient by vm.transientMessage.collectAsState()
    LaunchedEffect(transient) {
        transient?.let {
            snackbar.showSnackbar(it)
            vm.transientMessage.value = null
        }
    }

    // The count the Chats tab carries. Every row's badge already exists
    // inside the list; what was missing is the one number visible from
    // the other three tabs, which is the only place it changes anyone's
    // behaviour — a message that arrives while you are on Map or Nodes
    // was otherwise invisible until you happened to look.
    val conversations by vm.conversations.collectAsState()
    val unreadBadge = Inbox.badgeLabel(Inbox.unreadTotal(conversations.map { it.unread }))

    val tabs = listOf(
        Tab("chats", "Chats", unreadBadge) { Icon(Icons.Filled.Email, contentDescription = "Chats") },
        Tab("nodes", "Nodes") { Icon(Icons.Filled.Person, contentDescription = "Nodes") },
        Tab("map", "Map") { Icon(Icons.Filled.LocationOn, contentDescription = "Map") },
        Tab("settings", "Settings") { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val backStack by nav.currentBackStackEntryAsState()
            val currentRoute = backStack?.destination?.route
            // Hide the bar inside conversation/repeater routes.
            if (tabs.any { it.route == currentRoute }) {
                NavigationBar {
                    for (tab in tabs) {
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (tab.badge.isEmpty()) {
                                    tab.icon()
                                } else {
                                    BadgedBox(
                                        badge = {
                                            Badge {
                                                Text(
                                                    tab.badge,
                                                    // The number is on the icon, which
                                                    // already names the tab; a screen
                                                    // reader saying "Chats, 3" beats
                                                    // "Chats, 3, Chats".
                                                    modifier = Modifier.semantics {
                                                        contentDescription =
                                                            "${tab.badge} unread"
                                                    },
                                                )
                                            }
                                        },
                                    ) { tab.icon() }
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            // First run lands on setup rather than an empty Chats list
            // with no hint that a radio is required (PARITY §1).
            startDestination = if (vm.prefs.setupComplete) "chats" else "setup",
            modifier = Modifier.padding(padding),
        ) {
            composable("setup") {
                SetupScreen(vm) {
                    nav.navigate("chats") { popUpTo("setup") { inclusive = true } }
                }
            }
            composable("chats") { ChatsScreen(vm, nav) }
            composable("nodes") { NodesScreen(vm, nav) }
            // Reached from Nodes ⋮ — it is about the repeaters around
            // this node, which is what that tab is for.
            composable(HEARD_REPEATS_ROUTE) { HeardRepeatsScreen(vm, nav) }
            composable("map") { MapScreen(vm) }
            // Settings is hub-and-spoke for the same reason repeater
            // admin is: eleven expandable sections on one scroll, one of
            // which held another eight surfaces (REBUILD-PLAYBOOK §6.2).
            composable("settings") { SettingsScreen(vm, nav) }
            composable("settings/connection") { SettingsConnectionScreen(vm, nav) }
            composable("settings/transports") { SettingsTransportsScreen(vm, nav) }
            composable("settings/pin") { SettingsDevicePinScreen(vm, nav) }
            composable("settings/identity") { SettingsIdentityScreen(vm, nav) }
            composable("settings/radio") { SettingsRadioScreen(vm, nav) }
            composable("settings/clock") { SettingsClockScreen(vm, nav) }
            composable("settings/policies") { SettingsPoliciesScreen(vm, nav) }
            composable("settings/autoadd") { SettingsAutoAddScreen(vm, nav) }
            composable("settings/customvars") { SettingsCustomVarsScreen(vm, nav) }
            composable("settings/firmware") { FirmwareScreen(vm, nav) }
            // The repeater path reaches the same screen with a different
            // target: that node is already in update mode, so nothing
            // here may reboot the phone's own radio.
            composable(
                "firmware/node?role={role}&mac={mac}&board={board}&fw={fw}&node={node}",
                arguments = listOf(
                    navArgument("role") { defaultValue = "repeater" },
                    navArgument("mac") { defaultValue = "" },
                    // Hex-encoded like the console prefill: a board name
                    // has spaces and brackets in it.
                    navArgument("board") { defaultValue = "" },
                    navArgument("fw") { defaultValue = "" },
                    navArgument("node") { defaultValue = "" },
                ),
            ) { entry ->
                FirmwareScreen(
                    vm,
                    nav,
                    FirmwareTargetKind.NodeInUpdateMode,
                    // The address the node reported in its own reply to
                    // `start ota`, when we caught it.
                    otaAddress = entry.arguments?.getString("mac")?.ifBlank { null },
                    nodeBoard = decodePrefill(entry.arguments?.getString("board") ?: "")
                        .ifBlank { null },
                    nodeVersion = decodePrefill(entry.arguments?.getString("fw") ?: "")
                        .ifBlank { null },
                    nodeKey = entry.arguments?.getString("node")?.ifBlank { null },
                    // A repeater and a room server run different builds
                    // on identical hardware, so the role travels with
                    // the route rather than being inferred.
                    role = when (entry.arguments?.getString("role")) {
                        "room" -> FirmwareRole.RoomServer
                        else -> FirmwareRole.Repeater
                    },
                )
            }
            composable("settings/channels") { SettingsChannelsScreen(vm, nav) }
            composable("settings/blocking") { SettingsBlockingScreen(vm, nav) }
            composable("settings/app") { SettingsAppScreen(vm, nav) }
            composable("settings/backup") { SettingsBackupScreen(vm, nav) }
            composable("settings/data") { SettingsDataScreen(vm, nav) }
            composable("settings/about") { SettingsAboutScreen(vm, nav) }
            composable(CONVERSATION_ROUTE) { entry ->
                ConversationScreen(
                    vm = vm,
                    nav = nav,
                    kind = entry.arguments?.getString("kind") ?: "dm",
                    peerKey = entry.arguments?.getString("peer") ?: "",
                )
            }
            // Repeater/room/sensor admin is hub-and-spoke: `repeater/{key}`
            // is the hub, and each tool owns a route under it. This was
            // one screen with six tabs (REBUILD-PLAYBOOK §6.2) — the
            // spokes exist so that "back" means something and so each
            // tool's app bar can say which node it is acting on.
            composable("repeater/{key}") { entry ->
                RepeaterHubScreen(vm, nav, entry.arguments?.getString("key") ?: "")
            }
            composable("repeater/{key}/status") { entry ->
                RepeaterStatusScreen(vm, nav, entry.arguments?.getString("key") ?: "")
            }
            composable("repeater/{key}/settings") { entry ->
                RepeaterSettingsScreen(vm, nav, entry.arguments?.getString("key") ?: "")
            }
            composable("repeater/{key}/regions") { entry ->
                RepeaterRegionsScreen(vm, nav, entry.arguments?.getString("key") ?: "")
            }
            composable("repeater/{key}/identity") { entry ->
                RepeaterIdentityScreen(vm, nav, entry.arguments?.getString("key") ?: "")
            }
            composable("repeater/{key}/help") { entry ->
                RepeaterHelpScreen(vm, nav, entry.arguments?.getString("key") ?: "")
            }
            composable("repeater/{key}/firmware") { entry ->
                RepeaterFirmwareScreen(vm, nav, entry.arguments?.getString("key") ?: "")
            }
            composable(
                "repeater/{key}/console?prefill={prefill}",
                arguments = listOf(
                    navArgument("prefill") { defaultValue = "" },
                ),
            ) { entry ->
                RepeaterConsoleScreen(
                    vm = vm,
                    nav = nav,
                    keyHex = entry.arguments?.getString("key") ?: "",
                    prefill = decodePrefill(entry.arguments?.getString("prefill") ?: ""),
                )
            }
        }

        // Scan confirmations live HERE, once, rather than on whichever
        // screen happens to host a scanner.
        //
        // They used to be composed only by NodesScreen, so a code
        // scanned from the Chats button set the pending state and
        // nothing ever drew it: the scan appeared to do nothing at all.
        // That is the same failure as the old "Show route on map" — a
        // flag set for a screen that was not listening — and it applies
        // to contact cards, channel shares and settings codes alike.
        ScanConfirmations(vm)
    }
}
