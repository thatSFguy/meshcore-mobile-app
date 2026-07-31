package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.thatsfguy.meshcore.android.ui.ConversationRow
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.engine.EngineState
import io.github.thatsfguy.meshcore.protocol.Codes
import java.text.DateFormat
import java.util.Date

/**
 * Merged conversation list: direct-message threads and channels, newest
 * first, with unread badges. FAB opens the channel-add / community-join
 * sheet; DM threads start from the Nodes tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(vm: MeshCoreViewModel, nav: NavController) {
    val conversations by vm.conversations.collectAsState()
    val engineState by vm.engineState.collectAsState()
    val plaintext by vm.plaintextLink.collectAsState()
    var showChannelSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Chats")
                    ConnectionStatusLine(vm)
                }
            })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showChannelSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add channel")
            }
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            EmptyHint(
                modifier = Modifier.padding(padding),
                text = if (engineState == EngineState.Ready) {
                    "No conversations yet.\nAdd a channel with +, or message a contact from the Nodes tab."
                } else {
                    "Not connected to a radio.\nConnect in Settings → Connection."
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(conversations, key = { "${it.kind}|${it.key}" }) { row ->
                    ConversationRowItem(row) {
                        nav.navigate("conversation/${row.kind}/${row.key}")
                    }
                }
            }
        }
    }

    if (showChannelSheet) {
        ChannelAddSheet(vm, onDismiss = { showChannelSheet = false })
    }
}

@Composable
fun ConnectionStatusLine(vm: MeshCoreViewModel) {
    val engineState by vm.engineState.collectAsState()
    val label by vm.connectionLabel.collectAsState()
    val plaintext by vm.plaintextLink.collectAsState()
    val text = when (engineState) {
        EngineState.Ready -> "Connected · ${label ?: "radio"}" +
            if (plaintext) " ⚠ unencrypted link" else ""
        EngineState.Handshaking -> "Handshaking…"
        EngineState.Connecting -> "Connecting…"
        EngineState.Detached -> "Not connected"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (plaintext && engineState == EngineState.Ready) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun ConversationRowItem(row: ConversationRow, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (if (row.isChannel) "# " else typePrefix(row.contactType)) + row.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (row.unread > 0) FontWeight.Bold else FontWeight.Normal,
                )
            }
            Text(
                row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (row.timestamp > 0) {
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(Date(row.timestamp * 1000)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.unread > 0) {
                Badge { Text(row.unread.toString()) }
            }
        }
    }
}

private fun typePrefix(type: Int?): String = when (type) {
    Codes.ADV_TYPE_REPEATER -> "⛰ "
    Codes.ADV_TYPE_ROOM -> "🏠 "
    Codes.ADV_TYPE_SENSOR -> "🌡 "
    else -> ""
}

@Composable
fun EmptyHint(modifier: Modifier = Modifier, text: String) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
