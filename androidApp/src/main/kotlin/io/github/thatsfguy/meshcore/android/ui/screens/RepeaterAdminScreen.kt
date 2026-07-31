package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.thatsfguy.meshcore.android.storage.MessageRepository
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.Codes

/**
 * Repeater/room administration: login (password → keystore, never
 * plaintext prefs), raw CLI console, and a quick settings editor built
 * on CLI get/set. The highest-surface v1 piece (SCOPE.md) — the CLI
 * thread reuses the DM pipeline (txt_type=1 replies), and the
 * diagnostics log redacts `set prv.key` / passwords upstream.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeaterAdminScreen(vm: MeshCoreViewModel, nav: NavController, keyHex: String) {
    val contacts by vm.dbContacts.collectAsState()
    val contact = contacts.firstOrNull { it.keyHex == keyHex }
    val title = contact?.name?.ifBlank { null } ?: keyHex.take(12)

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

    var cli by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(
                            if (contact?.type == Codes.ADV_TYPE_ROOM) "Room server admin" else "Repeater admin",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
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

            // --- Quick settings / status chips (CLI-backed) ---
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = false, onClick = { vm.sendCli(keyHex, "ver") },
                    label = { Text("ver") })
                FilterChip(selected = false, onClick = { vm.sendCli(keyHex, "get radio") },
                    label = { Text("get radio") })
                FilterChip(selected = false, onClick = { vm.sendCli(keyHex, "get name") },
                    label = { Text("get name") })
                FilterChip(selected = false, onClick = { vm.sendCli(keyHex, "clock") },
                    label = { Text("clock") })
                FilterChip(selected = false, onClick = { vm.requestRepeaterStatus(keyHex) },
                    label = { Text("status") })
            }

            // --- CLI console (reuses the DM thread; replies come back as
            //     contact messages from this repeater) ---
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
}
