package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
    // Paged scrollback: long threads load newest-first in windows.
    var pageSize by remember(kind, peerKey) { mutableStateOf(PAGE_SIZE) }
    val total by remember(kind, peerKey) { vm.threadCount(kind, peerKey) }.collectAsState()
    val messages by remember(kind, peerKey, pageSize) {
        vm.threadPaged(kind, peerKey, pageSize)
    }.collectAsState()
    // threadPaged returns newest-first; render oldest-first.
    val ordered = remember(messages) { messages.reversed() }
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
    var clearConfirm by remember { mutableStateOf(false) }
    var showContact by remember { mutableStateOf(false) }
    var showChannelEditor by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<MessageEntity?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(ordered.size) {
        if (ordered.isNotEmpty()) listState.animateScrollToItem(ordered.size - 1)
    }

    val maxBytes = if (isChannel) {
        Frames.maxChannelMessageBytes(vm.selfInfo.collectAsState().value?.name)
    } else {
        Frames.maxContactMessageBytes()
    }

    var muted by remember(peerKey) {
        mutableStateOf(peerKey.toIntOrNull()?.let { vm.prefs.isChannelMuted(it) } ?: false)
    }
    val menu = if (isChannel) {
        listOf(
            MenuAction("Mute notifications", checked = muted) {
                peerKey.toIntOrNull()?.let {
                    muted = !muted
                    vm.prefs.setChannelMuted(it, muted)
                }
            },
            MenuAction("Mark unread") { vm.markUnread(kind, peerKey); nav.popBackStack() },
            MenuAction("Channel settings…") { showChannelEditor = true },
            MenuAction("Clear thread…", destructive = true) { clearConfirm = true },
        )
    } else {
        listOf(
            MenuAction("Mark unread") { vm.markUnread(kind, peerKey); nav.popBackStack() },
            MenuAction("Contact details…") { showContact = true },
            MenuAction("Routing / paths…") { showContact = true },
            MenuAction("Clear thread…", destructive = true) { clearConfirm = true },
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = title,
                vm = vm,
                nav = nav,
                subtitle = if (isChannel) "Obfuscated, not secure" else null,
                menuActions = menu,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (total > ordered.size) {
                    item(key = "load_older") {
                        androidx.compose.material3.TextButton(
                            onClick = { pageSize += PAGE_SIZE },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Load older (${total - ordered.size} more)") }
                    }
                }
                items(ordered, key = { it.id }) { m ->
                    MessageBubble(
                        m,
                        showSender = isChannel,
                        onLongPress = { selected = m },
                    )
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

    selected?.let { m ->
        MessageActionsDialog(
            message = m,
            isChannel = isChannel,
            onDismiss = { selected = null },
            onCopy = { clipboard ->
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.text))
                selected = null
            },
            onQuote = {
                // Minimal reply: quote the line into the draft.
                val who = m.senderName?.let { "$it: " } ?: ""
                draft = "> $who${m.text.take(60)}\n"
                selected = null
            },
        )
    }

    if (clearConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("Clear this thread?") },
            text = { Text("Removes the messages from this phone only — nothing is sent to the mesh.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.clearThread(kind, peerKey)
                    clearConfirm = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { clearConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showContact) {
        contacts.firstOrNull { it.keyHex == peerKey }?.let { contact ->
            ContactDetailSheet(
                vm = vm,
                contact = contact,
                onDismiss = { showContact = false },
                onOpenChat = { showContact = false },
                onOpenAdmin = {
                    showContact = false
                    nav.navigate("repeater/$peerKey")
                },
            )
        } ?: run { showContact = false }
    }

    if (showChannelEditor) {
        channels.firstOrNull { it.idx == peerKey.toIntOrNull() }?.let { ch ->
            ChannelEditSheet(vm, ch, onDismiss = { showChannelEditor = false })
        } ?: run { showChannelEditor = false }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    m: MessageEntity,
    showSender: Boolean,
    onLongPress: () -> Unit = {},
) {
    val outgoing = m.outgoing
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 8.dp),
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
                            // Surface retries so a struggling route is visible.
                            if (m.attempts > 1) append(" (try ${m.attempts})")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Newest-N window; "Load older" grows it. */
private const val PAGE_SIZE = 50

/** Long-press actions on a message: copy, quote-reply, and details. */
@Composable
private fun MessageActionsDialog(
    message: MessageEntity,
    isChannel: Boolean,
    onDismiss: () -> Unit,
    onCopy: (androidx.compose.ui.platform.ClipboardManager) -> Unit,
    onQuote: () -> Unit,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message") },
        text = {
            Column {
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(message.timestamp * 1000)),
                        )
                        message.senderName?.let { append(" · $it") }
                        message.snr?.let { append(" · %.1f dB".format(it)) }
                        if (message.outgoing && message.attempts > 0) {
                            append(" · ${message.attempts} attempt(s)")
                        }
                        message.ackHash?.let { append(" · ack %08x".format(it)) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onCopy(clipboard) }) { Text("Copy") }
        },
        dismissButton = {
            Row {
                if (isChannel) {
                    androidx.compose.material3.TextButton(onClick = onQuote) { Text("Quote") }
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
