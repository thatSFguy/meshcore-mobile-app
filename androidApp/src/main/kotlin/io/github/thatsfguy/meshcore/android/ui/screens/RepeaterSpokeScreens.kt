package io.github.thatsfguy.meshcore.android.ui.screens

import io.github.thatsfguy.meshcore.presentation.AdminSession
import io.github.thatsfguy.meshcore.presentation.encodePrefill
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.thatsfguy.meshcore.android.storage.MessageEntity
import io.github.thatsfguy.meshcore.android.storage.MessageRepository
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.NodeRole

/**
 * The spokes of [RepeaterHubScreen] — one tool per screen.
 *
 * Each was a tab on the old `RepeaterAdminScreen`. The panels
 * themselves are unchanged; what changed is that each now owns a
 * screen, has a back button to the hub, and states in its own app bar
 * which node it is acting on — which the shared tab strip could not do
 * (REBUILD-PLAYBOOK §6.2).
 */

/** Everything a spoke needs to resolve about its target node. */
private data class SpokeContext(
    val name: String,
    val role: NodeRole,
    val session: AdminSession,
)

@Composable
private fun rememberSpokeContext(vm: MeshCoreViewModel, keyHex: String): SpokeContext {
    val contacts by vm.dbContacts.collectAsState()
    val contact = contacts.firstOrNull { it.keyHex == keyHex }
    val sessions by vm.adminSessions.collectAsState()
    return SpokeContext(
        name = contact?.name?.ifBlank { null } ?: keyHex.take(12),
        role = when (contact?.type) {
            Codes.ADV_TYPE_ROOM -> NodeRole.Room
            Codes.ADV_TYPE_SENSOR -> NodeRole.Sensor
            else -> NodeRole.Repeater
        },
        session = sessions[keyHex] ?: AdminSession.None,
    )
}

/**
 * A spoke's chrome: title on the left, the node it acts on as the
 * subtitle, back to the hub.
 */
@Composable
private fun SpokeScaffold(
    title: String,
    vm: MeshCoreViewModel,
    nav: NavController,
    nodeName: String,
    menuActions: List<MenuAction> = emptyList(),
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            AppTopBar(
                title = title,
                vm = vm,
                nav = nav,
                subtitle = nodeName,
                menuActions = menuActions,
            )
        },
    ) { padding ->
        content(Modifier.fillMaxSize().padding(padding))
    }
}

@Composable
fun RepeaterStatusScreen(vm: MeshCoreViewModel, nav: NavController, keyHex: String) {
    val ctx = rememberSpokeContext(vm, keyHex)
    SpokeScaffold(
        title = "Status",
        vm = vm,
        nav = nav,
        nodeName = ctx.name,
        menuActions = listOf(
            MenuAction("Request status") { vm.requestRepeaterStatus(keyHex) },
        ),
    ) { modifier ->
        Box(modifier) { RepeaterStatusPanel(vm, keyHex) }
    }
}

@Composable
fun RepeaterSettingsScreen(vm: MeshCoreViewModel, nav: NavController, keyHex: String) {
    val ctx = rememberSpokeContext(vm, keyHex)
    val contacts by vm.dbContacts.collectAsState()
    val contact = contacts.firstOrNull { it.keyHex == keyHex }
    SpokeScaffold(
        title = "Settings",
        vm = vm,
        nav = nav,
        nodeName = ctx.name,
    ) { modifier ->
        Box(modifier) {
            RemoteSettingsForm(vm, keyHex, contact, ctx.role, ctx.session.isAdmin)
        }
    }
}

@Composable
fun RepeaterRegionsScreen(vm: MeshCoreViewModel, nav: NavController, keyHex: String) {
    val ctx = rememberSpokeContext(vm, keyHex)
    SpokeScaffold(
        title = "Regions",
        vm = vm,
        nav = nav,
        nodeName = ctx.name,
    ) { modifier ->
        Box(modifier) { RepeaterRegionsPanel(vm, keyHex, ctx.session.isAdmin) }
    }
}

@Composable
fun RepeaterIdentityScreen(vm: MeshCoreViewModel, nav: NavController, keyHex: String) {
    val ctx = rememberSpokeContext(vm, keyHex)
    SpokeScaffold(
        title = "Identity",
        vm = vm,
        nav = nav,
        nodeName = ctx.name,
    ) { modifier ->
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            RepeaterIdentityPanel(vm, keyHex, ctx.session.isAdmin)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The command catalogue. Choosing one lands it in the console ready to
 * edit rather than running it — plenty of these are destructive, and
 * the placeholders still need filling in.
 */
@Composable
fun RepeaterHelpScreen(vm: MeshCoreViewModel, nav: NavController, keyHex: String) {
    val ctx = rememberSpokeContext(vm, keyHex)
    SpokeScaffold(
        title = "Command help",
        vm = vm,
        nav = nav,
        nodeName = ctx.name,
    ) { modifier ->
        Box(modifier) {
            CliHelpPanel(role = ctx.role, session = ctx.session) { usage ->
                nav.navigate("repeater/$keyHex/console?prefill=${encodePrefill(usage)}")
            }
        }
    }
}

@Composable
fun RepeaterConsoleScreen(
    vm: MeshCoreViewModel,
    nav: NavController,
    keyHex: String,
    prefill: String = "",
) {
    val ctx = rememberSpokeContext(vm, keyHex)
    val messages by remember(keyHex) {
        vm.thread(MessageRepository.KIND_DM, keyHex)
    }.collectAsState()

    SpokeScaffold(
        title = "Console",
        vm = vm,
        nav = nav,
        nodeName = ctx.name,
        menuActions = listOf(
            MenuAction("Command help") { nav.navigate("repeater/$keyHex/help") },
            MenuAction("Clear console", destructive = true) { vm.clearThread("dm", keyHex) },
        ),
    ) { modifier ->
        CliConsole(
            modifier = modifier.imePadding(),
            vm = vm,
            keyHex = keyHex,
            messages = messages,
            prefill = prefill,
        )
    }
}

@Composable
private fun CliConsole(
    modifier: Modifier,
    vm: MeshCoreViewModel,
    keyHex: String,
    messages: List<MessageEntity>,
    prefill: String,
) {
    var cli by remember { mutableStateOf("") }
    // A command chosen from Help arrives here ready to edit — the arg
    // placeholders still need filling in, so it is never auto-sent.
    LaunchedEffect(prefill) {
        if (prefill.isNotBlank()) cli = prefill
    }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(messages, key = { it.id }) { m ->
                Text(
                    text = (if (m.outgoing) "> " else "") + m.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (m.outgoing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = cli,
                onValueChange = { cli = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("CLI command (e.g. get freq)") },
                singleLine = true,
            )
            IconButton(
                onClick = {
                    val command = cli.trim()
                    if (command.isNotEmpty()) {
                        vm.sendCli(keyHex, command)
                        cli = ""
                    }
                },
                enabled = cli.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send CLI command")
            }
        }
    }
}
