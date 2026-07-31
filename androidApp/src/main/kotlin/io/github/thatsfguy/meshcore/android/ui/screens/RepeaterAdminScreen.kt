package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.thatsfguy.meshcore.android.storage.MessageRepository
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.CliCatalog
import io.github.thatsfguy.meshcore.protocol.CliCommand
import io.github.thatsfguy.meshcore.protocol.CliKind
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.NodeRole

/**
 * Repeater/room administration: login (password → keystore), raw CLI
 * console, and a settings editor generated from [CliCatalog] — the
 * command list is filtered by the target's role, so a repeater shows
 * repeater commands (repeat, flood.max, bridge.*, neighbors…) and a
 * room server shows room commands (allow.read.only, guest.password…).
 * Sensitive commands mask input; destructive ones confirm first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeaterAdminScreen(vm: MeshCoreViewModel, nav: NavController, keyHex: String) {
    val contacts by vm.dbContacts.collectAsState()
    val contact = contacts.firstOrNull { it.keyHex == keyHex }
    val title = contact?.name?.ifBlank { null } ?: keyHex.take(12)
    val isRoom = contact?.type == Codes.ADV_TYPE_ROOM
    val role = if (isRoom) NodeRole.Room else NodeRole.Repeater

    val messages by remember(keyHex) {
        vm.thread(MessageRepository.KIND_DM, keyHex)
    }.collectAsState()

    var password by remember { mutableStateOf("") }
    var savePassword by remember { mutableStateOf(true) }
    var passwordPrefilled by remember { mutableStateOf(false) }
    LaunchedEffect(keyHex) {
        vm.savedLoginPassword(keyHex)?.let {
            password = it
            passwordPrefilled = true
        }
    }

    var tab by remember { mutableIntStateOf(0) } // 0 = Console, 1 = Settings
    var pendingCommand by remember { mutableStateOf<CliCommand?>(null) }
    var pendingIsSet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = title,
                vm = vm,
                nav = nav,
                subtitle = if (isRoom) "Room server admin" else "Repeater admin",
                menuActions = listOf(
                    MenuAction("Request status") { vm.requestRepeaterStatus(keyHex) },
                    MenuAction("Sync clock from phone") {
                        vm.sendCli(keyHex, "time ${System.currentTimeMillis() / 1000}")
                    },
                    MenuAction("Clear console…", destructive = true) {
                        vm.clearThread("dm", keyHex)
                    },
                    MenuAction("Forget saved password", destructive = true) {
                        vm.forgetLoginPassword(keyHex)
                    },
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {

            // --- Login row ---
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (passwordPrefilled) "Password (saved)" else "Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    vm.repeaterLogin(keyHex, password, savePassword)
                }) { Text("Login") }
            }
            Row(
                Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = savePassword, onCheckedChange = { savePassword = it })
                Text("Keep password in device keystore", style = MaterialTheme.typography.bodySmall)
            }

            // --- Console / Settings switch ---
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                SegmentedButton(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Console") }
                SegmentedButton(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Settings") }
            }

            if (tab == 0) {
                CliConsole(vm, keyHex, messages)
            } else {
                CliSettingsEditor(
                    role = role,
                    onGet = { vm.sendCli(keyHex, it.getCommand()) },
                    onInvoke = { cmd, asSet ->
                        when (cmd.kind) {
                            CliKind.Action ->
                                if (cmd.requiresConfirm) {
                                    pendingCommand = cmd; pendingIsSet = false
                                } else {
                                    vm.sendCli(keyHex, cmd.buildCommand())
                                }
                            else -> {
                                pendingCommand = cmd
                                pendingIsSet = asSet
                            }
                        }
                    },
                )
            }
        }
    }

    pendingCommand?.let { cmd ->
        CliCommandDialog(
            command = cmd,
            onDismiss = { pendingCommand = null },
            onSend = { value ->
                vm.sendCli(keyHex, cmd.buildCommand(value))
                pendingCommand = null
            },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CliConsole(
    vm: MeshCoreViewModel,
    keyHex: String,
    messages: List<io.github.thatsfguy.meshcore.android.storage.MessageEntity>,
) {
    var cli by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

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

/** Catalog-driven settings editor, grouped by category for [role]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CliSettingsEditor(
    role: NodeRole,
    onGet: (CliCommand) -> Unit,
    onInvoke: (CliCommand, Boolean) -> Unit,
) {
    val grouped = remember(role) { CliCatalog.forRoleByCategory(role) }
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        for ((category, commands) in grouped) {
            item(key = "cat_$category") {
                Text(
                    category,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(commands, key = { it.id }) { cmd ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(cmd.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        cmd.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (cmd.kind) {
                            CliKind.Action -> {
                                TextButton(onClick = { onInvoke(cmd, false) }) {
                                    Text(
                                        "Run",
                                        color = if (cmd.requiresConfirm) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                }
                            }
                            CliKind.GetOnly -> {
                                TextButton(onClick = { onGet(cmd) }) { Text("Get") }
                            }
                            CliKind.GetSet -> {
                                TextButton(onClick = { onGet(cmd) }) { Text("Get") }
                                TextButton(onClick = { onInvoke(cmd, true) }) {
                                    Text(
                                        "Set…",
                                        color = if (cmd.requiresConfirm) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                }
                            }
                            CliKind.ActionWithArg -> {
                                TextButton(onClick = { onInvoke(cmd, true) }) { Text("Run…") }
                            }
                        }
                    }
                }
            }
        }
        item(key = "footer") {
            Text(
                "Replies appear in the Console tab. Log in first — most commands need an " +
                    "authenticated session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

/** Value/confirmation dialog for Set…/Run…/destructive commands. */
@Composable
private fun CliCommandDialog(
    command: CliCommand,
    onDismiss: () -> Unit,
    onSend: (String?) -> Unit,
) {
    val needsValue = command.kind == CliKind.GetSet || command.kind == CliKind.ActionWithArg
    var value by remember(command.id) {
        mutableStateOf(
            // Prefill the one arg we can know better than the user.
            if (command.id == "time") (System.currentTimeMillis() / 1000).toString() else "",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(command.label) },
        text = {
            Column {
                Text(command.description, style = MaterialTheme.typography.bodySmall)
                if (command.requiresConfirm) {
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        "⚠ This action is destructive / hard to undo.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (needsValue) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(command.argHint ?: "value") },
                        singleLine = true,
                        visualTransformation = if (command.sensitive) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                    )
                }
                if (command.sensitive) {
                    Text(
                        "Sensitive value — it is never written to the diagnostics log.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(if (needsValue) value.trim() else null) },
                enabled = !needsValue || value.isNotBlank(),
            ) {
                Text(
                    "Send",
                    color = if (command.requiresConfirm) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
