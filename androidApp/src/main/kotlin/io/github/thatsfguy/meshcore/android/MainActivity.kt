package io.github.thatsfguy.meshcore.android

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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.thatsfguy.meshcore.android.platform.BlePermissions
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.android.ui.screens.CONVERSATION_ROUTE
import io.github.thatsfguy.meshcore.android.ui.screens.ChatsScreen
import io.github.thatsfguy.meshcore.android.ui.screens.conversationRoute
import io.github.thatsfguy.meshcore.android.ui.screens.ConversationScreen
import io.github.thatsfguy.meshcore.android.ui.screens.MapScreen
import io.github.thatsfguy.meshcore.android.ui.screens.NodesScreen
import io.github.thatsfguy.meshcore.android.ui.screens.RepeaterConsoleScreen
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
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsIdentityScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsPoliciesScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsRadioScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SettingsTransportsScreen
import io.github.thatsfguy.meshcore.android.ui.screens.SetupScreen
import io.github.thatsfguy.meshcore.android.ui.screens.decodePrefill
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

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

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

    val tabs = listOf(
        Tab("chats", "Chats") { Icon(Icons.Filled.Email, contentDescription = "Chats") },
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
                            icon = tab.icon,
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
            composable("map") { MapScreen(vm) }
            // Settings is hub-and-spoke for the same reason repeater
            // admin is: eleven expandable sections on one scroll, one of
            // which held another eight surfaces (REBUILD-PLAYBOOK §6.2).
            composable("settings") { SettingsScreen(vm, nav) }
            composable("settings/connection") { SettingsConnectionScreen(vm, nav) }
            composable("settings/transports") { SettingsTransportsScreen(vm, nav) }
            composable("settings/identity") { SettingsIdentityScreen(vm, nav) }
            composable("settings/radio") { SettingsRadioScreen(vm, nav) }
            composable("settings/clock") { SettingsClockScreen(vm, nav) }
            composable("settings/policies") { SettingsPoliciesScreen(vm, nav) }
            composable("settings/autoadd") { SettingsAutoAddScreen(vm, nav) }
            composable("settings/customvars") { SettingsCustomVarsScreen(vm, nav) }
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
    }
}
