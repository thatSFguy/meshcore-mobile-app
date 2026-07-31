package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.thatsfguy.meshcore.android.storage.MessageEntity
import io.github.thatsfguy.meshcore.android.storage.MessageRepository
import io.github.thatsfguy.meshcore.android.storage.MessageStatus
import io.github.thatsfguy.meshcore.protocol.Frames
import java.text.DateFormat
import java.util.Date

/**
 * A single thread — direct messages or a channel. Channel threads show
 * the (unauthenticated) sender name on each inbound bubble and an
 * "obfuscated, not secure" banner per SCOPE.md; DMs show delivery ticks
 * driven by RESP_CODE_SENT / PUSH_CODE_SEND_CONFIRMED.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    vm: io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel,
    nav: NavController,
    kind: String,
    peerKey: String,
) {
    val isChannel = kind == MessageRepository.KIND_CHANNEL
    val messages by remember(kind, peerKey) { vm.thread(kind, peerKey) }.collectAsState()
    val contacts by vm.dbContacts.collectAsState()
    val channels by vm.dbChannels.collectAsState()

    val title = if (isChannel) {
        val idx = peerKey.toIntOrNull()
        "# " + (channels.firstOrNull { it.idx == idx }?.name?.ifBlank { "Channel $idx" }
            ?: "Channel $idx")
    } else {
        contacts.firstOrNull { it.keyHex == peerKey }?.name?.ifBlank { null }
            ?: peerKey.take(12)
    }

    DisposableEffect(kind, peerKey) {
        vm.markThreadOpen(kind, peerKey)
        onDispose { vm.markThreadClosed() }
    }

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val maxBytes = if (isChannel) {
        Frames.maxChannelMessageBytes(vm.selfInfo.collectAsState().value?.name)
    } else {
        Frames.maxContactMessageBytes()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        if (isChannel) {
                            Text(
                                "Channel crypto is obfuscation, not security",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages, key = { it.id }) { m ->
                    MessageBubble(m, showSender = isChannel)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        // Enforce the frame-size limit at input time.
                        if (it.encodeToByteArray().size <= maxBytes) draft = it
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (isChannel) "Message channel…" else "Message…") },
                    maxLines = 4,
                )
                IconButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isEmpty()) return@IconButton
                        if (isChannel) {
                            peerKey.toIntOrNull()?.let { vm.sendChannelMessage(it, text) }
                        } else {
                            vm.sendDirectMessage(peerKey, text)
                        }
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: MessageEntity, showSender: Boolean) {
    val outgoing = m.outgoing
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = if (outgoing) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(
                        topStart = 12.dp, topEnd = 12.dp,
                        bottomStart = if (outgoing) 12.dp else 2.dp,
                        bottomEnd = if (outgoing) 2.dp else 12.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                if (showSender && !outgoing && !m.senderName.isNullOrBlank()) {
                    Text(
                        m.senderName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(m.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append(
                            DateFormat.getTimeInstance(DateFormat.SHORT)
                                .format(Date(m.timestamp * 1000)),
                        )
                        if (outgoing) {
                            append(
                                when (m.status) {
                                    MessageStatus.Delivered.ordinal -> "  ✓✓"
                                    MessageStatus.Sent.ordinal -> "  ✓"
                                    MessageStatus.Failed.ordinal -> "  ✗"
                                    else -> "  …"
                                },
                            )
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
