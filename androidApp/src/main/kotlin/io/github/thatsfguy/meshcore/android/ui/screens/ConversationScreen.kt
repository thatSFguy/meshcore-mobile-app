package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.thatsfguy.meshcore.presentation.Inbox
import io.github.thatsfguy.meshcore.android.storage.MessageEntity
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.android.storage.MessageRepository
import io.github.thatsfguy.meshcore.android.storage.MessageStatus
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import io.github.thatsfguy.meshcore.protocol.MessageRepeats
import io.github.thatsfguy.meshcore.protocol.HeardVia
import io.github.thatsfguy.meshcore.protocol.PathCodec
import io.github.thatsfguy.meshcore.protocol.ReactionCounts
import io.github.thatsfguy.meshcore.protocol.Quoting
import io.github.thatsfguy.meshcore.protocol.Reactions
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
    // threadPaged returns newest-first, which is exactly what the
    // reversed layout below wants — index 0 is the newest bubble and is
    // drawn at the bottom.
    val contacts by vm.dbContacts.collectAsState()
    val channels by vm.dbChannels.collectAsState()

    // A room relays posts from many people, so — like a channel — each
    // bubble has to say who wrote it. The room server is not the author.
    val isRoom = contacts.firstOrNull { it.keyHex == peerKey }?.type ==
        io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_ROOM
    val showSenders = isChannel || isRoom

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

    val threadKey = Inbox.threadKey(kind, peerKey)
    var draft by remember(threadKey) { mutableStateOf(vm.draftFor(threadKey)) }
    var clearConfirm by remember { mutableStateOf(false) }
    var showContact by remember { mutableStateOf(false) }
    var showChannelEditor by remember { mutableStateOf(false) }
    var showSendersSheet by remember { mutableStateOf(false) }
    var replyingTo by remember(kind, peerKey) { mutableStateOf<MessageEntity?>(null) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var reactingTo by remember { mutableStateOf<MessageEntity?>(null) }
    var details by remember { mutableStateOf<MessageEntity?>(null) }
    val selfName = vm.selfInfo.collectAsState().value?.name
    val maxBytes = if (isChannel) {
        Frames.maxChannelMessageBytes(selfName)
    } else {
        Frames.maxContactMessageBytes()
    }
    // MeshCore has no reply field, so a quote is literally prefixed to
    // the text — and it comes out of the same ~150-byte budget. Reserve
    // it up front so the composer can't accept a message the quote will
    // push over the limit.
    val quotePrefix = replyingTo?.let { quotePrefixFor(it) }.orEmpty()
    val bodyBudget = (maxBytes - quotePrefix.encodeToByteArray().size).coerceAtLeast(0)

    var muted by remember(peerKey) {
        mutableStateOf(peerKey.toIntOrNull()?.let { vm.prefs.isChannelMuted(it) } ?: false)
    }
    val channelRegions by vm.channelRegions.collectAsState()
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
            MenuAction("Names seen…") { showSendersSheet = true },
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
                // A scoped channel goes out on a narrower flood, which
                // changes who carries it — worth seeing before sending,
                // without diluting the "not secure" part.
                subtitle = if (isChannel) {
                    val region = peerKey.toIntOrNull()?.let { channelRegions[it] }
                    if (region != null) "Obfuscated, not secure · #$region" else "Obfuscated, not secure"
                } else {
                    null
                },
                menuActions = menu,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // Newest-message visibility is structural, not a scroll
            // effect: reverseLayout anchors index 0 (the newest bubble)
            // to the bottom of the viewport. It therefore stays visible
            // through conversation entry, new arrivals, and the keyboard
            // opening — adjustResize shrinks the viewport from the
            // bottom, and the anchor moves with it. A scrollToItem pin
            // has to be re-fired on every one of those events and misses
            // whichever one you forgot; this cannot.
            //
            // It also preserves the reading position for free: someone
            // scrolled up in the scrollback is far from the anchor, so an
            // arriving message doesn't yank the view.
            ThreadList(
                threadKey = threadKey,
                messages = messages,
                total = total,
                modifier = Modifier.weight(1f),
                onLoadOlder = { pageSize += PAGE_SIZE },
            ) { m ->
                MessageBubble(
                    m,
                    showSender = showSenders,
                    showAvatar = showSenders,
                    onSwipeReply = { replyingTo = m },
                    onReact = { emoji -> vm.sendReaction(m.id, emoji) },
                    onMoreEmoji = { reactingTo = m },
                    onReply = { replyingTo = m },
                    onInfo = { details = m },
                    onDelete = { vm.deleteMessage(m.id) },
                    onResend = { vm.resendMessage(m.id) },
                    onHttpLink = { pendingUrl = it },
                    onMeshcoreLink = { vm.importContactUri(it) },
                )
            }

            replyingTo?.let { target ->
                ReplyBanner(target, onCancel = { replyingTo = null })
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        // Enforce the frame-size limit at input time.
                        if (it.encodeToByteArray().size <= bodyBudget) {
                            draft = it
                            vm.setDraft(threadKey, it)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (isChannel) "Message channel…" else "Message…") },
                    maxLines = 4,
                )
                IconButton(
                    onClick = {
                        val text = quotePrefix + draft.trim()
                        if (text.isBlank()) return@IconButton
                        if (isChannel) {
                            peerKey.toIntOrNull()?.let { vm.sendChannelMessage(it, text) }
                        } else {
                            vm.sendDirectMessage(peerKey, text)
                        }
                        draft = ""
                        vm.setDraft(threadKey, "")
                        replyingTo = null
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }

    // Render site for the Info action. This block was lost when the
    // long-press dialog became an inline action bar — `details` was still
    // being set, so Info silently did nothing.
    details?.let { m ->
        MessageInfoSheet(
            m,
            vm = vm,
            // Who it came from, for the first pin on the route map. A
            // channel message names its sender in the text; a DM's
            // sender IS the thread.
            senderLabel = m.senderName?.ifBlank { null } ?: title,
            isChannel = isChannel,
            showSender = showSenders,
            // Full key hex -> name, so the arrival route can name its
            // hops instead of showing bare hashes.
            contactNames = vm.liveContacts.collectAsState().value
                .values.associate { it.publicKeyHex to it.name },
            // Orders the candidates of an ambiguous hop nearest-first.
            // Resolved once here rather than per row: it walks the whole
            // contact list, and a route redraws on every arrival.
            metresAway = remember(details) { vm.metresFromThisRadio() },
            onDismiss = { details = null },
        )
    }

    reactingTo?.let { target ->
        ReactionPicker(
            onPick = { emoji ->
                vm.sendReaction(target.id, emoji)
                reactingTo = null
            },
            onDismiss = { reactingTo = null },
        )
    }

    pendingUrl?.let { url ->
        LeaveTheMeshDialog(url, onDismiss = { pendingUrl = null })
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("Clear this thread?") },
            text = { Text("Removes the messages from this phone only — nothing is sent to the mesh.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearThread(kind, peerKey)
                    clearConfirm = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) { Text("Cancel") }
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
    if (showSendersSheet) {
        peerKey.toIntOrNull()?.let { idx ->
            ChannelSendersSheet(vm, idx, onDismiss = { showSendersSheet = false })
        } ?: run { showSendersSheet = false }
    }
}

/**
 * Confirmation before a peer-supplied http(s) link opens.
 *
 * SECURITY: this app makes no outbound connections except map tiles. A
 * link in a message is chosen by the sender, so following it hands a
 * server of their choosing the user's IP and the fact that they're
 * online. That has to be a decision, not a mis-tap.
 */
@Composable
private fun LeaveTheMeshDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open link?") },
        text = {
            Text(
                "This opens in your browser and leaves the mesh. The site — chosen by " +
                    "the sender, not you — will see your real IP address and network.\n\n$url",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url),
                        ),
                    )
                }
                onDismiss()
            }) { Text("Open in browser") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Replying to …" strip above the composer, with a cancel affordance. */
@Composable
private fun ReplyBanner(target: MessageEntity, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(28.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Replying to ${target.senderName?.takeIf { it.isNotBlank() } ?: if (target.outgoing) "yourself" else "them"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                target.text.replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Clear, contentDescription = "Cancel reply")
        }
    }
}

/**
 * The quote text prefixed to a reply.
 *
 * Kept deliberately short: every byte here is taken from a ~150-byte
 * message budget, and MeshCore has no reply field to carry it out of
 * band — other clients will simply see the `>` line as text.
 */
internal fun quotePrefixFor(target: MessageEntity): String {
    val who = target.senderName?.takeIf { it.isNotBlank() }?.let { "$it: " }.orEmpty()
    val body = target.text.replace('\n', ' ').trim()
    val snippet = if (body.length > QUOTE_MAX_CHARS) {
        body.take(QUOTE_MAX_CHARS).trimEnd() + "…"
    } else {
        body
    }
    return "> $who$snippet\n"
}

private const val QUOTE_MAX_CHARS = Quoting.QUOTE_MAX_CHARS

/**
 * Split a message into its leading quote lines and the reply body.
 *
 * Moved to [Quoting] in `shared` — the conversation list and the
 * reaction notification need the same split, and a copy per caller is
 * how the list came to preview the quoted message instead of the reply.
 */
internal fun splitQuote(text: String): Pair<String?, String> = Quoting.split(text)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    m: MessageEntity,
    showSender: Boolean,
    showAvatar: Boolean = false,
    onSwipeReply: () -> Unit = {},
    onReact: (String) -> Unit = {},
    onMoreEmoji: () -> Unit = {},
    onReply: () -> Unit = {},
    onInfo: () -> Unit = {},
    onDelete: () -> Unit = {},
    onResend: () -> Unit = {},
    onHttpLink: (String) -> Unit = {},
    onMeshcoreLink: (String) -> Unit = {},
) {
    // Long-press opens an inline action bar anchored on the bubble —
    // one tap to react, no dialog to read and dismiss first.
    var showActions by remember(m.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val outgoing = m.outgoing
    val (quoted, body) = remember(m.text) { splitQuote(m.text) }
    val reactions = remember(m.reactionsJson) { ReactionCounts.decode(m.reactionsJson) }
    // A reaction whose target we couldn't find still has to render as
    // something a human understands, not as "r:1a2b:00" — nor as
    // MeshCore One's "😂@[Someone]\nb0c26wb5", which is what those
    // looked like in the thread until 2026-08-23.
    val orphanReaction = remember(m.text) {
        io.github.thatsfguy.meshcore.protocol.AnyReaction.emojiOf(m.text)
    }

    // Swipe-right-to-reply: accumulate the rightward drag as a visual
    // pull, and fire on release once past the threshold.
    val density = LocalDensity.current
    val thresholdPx = with(density) { 56.dp.toPx() }
    var dragOffsetX by remember(m.id) { mutableStateOf(0f) }
    val dragState = rememberDraggableState { delta ->
        if (delta > 0f || dragOffsetX > 0f) {
            dragOffsetX = (dragOffsetX + delta).coerceIn(0f, thresholdPx * 1.5f)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(dragOffsetX.toInt(), 0) }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    if (dragOffsetX >= thresholdPx) onSwipeReply()
                    dragOffsetX = 0f
                },
            )
            .combinedClickable(onClick = {}, onLongClick = { showActions = true })
            .padding(horizontal = 8.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        // No name, no avatar: a "?" circle on every historical room post
        // (stored before authors were parsed) is noise, not information.
        if (showAvatar && !outgoing && !m.senderName.isNullOrBlank()) {
            // Seeded on the sender NAME, which is unauthenticated display
            // text — the colour is a readability aid, never identity.
            NodeAvatar(
                seed = "sender|" + m.senderName.orEmpty(),
                label = m.senderName.orEmpty(),
                size = 28.dp,
            )
            Spacer(Modifier.width(6.dp))
        }
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
                quoted?.let { QuoteBlock(it) }
                if (orphanReaction != null) {
                    Text(
                        orphanReaction + "  reacted to an earlier message",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MessageText(body, onHttpLink = onHttpLink, onMeshcoreLink = onMeshcoreLink)
                }
                if (reactions.isNotEmpty()) ReactionChips(reactions)
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
                            // Who picked this up off the air. Absent when
                            // nothing was heard — which is NOT "nobody
                            // carried it", so it renders as nothing at
                            // all rather than as a zero.
                            MessageRepeats.badge(m.repeatHopsHex, m.repeatHashWidth)
                                ?.let { append("  ·  $it") }
                        }
                        // Link quality for received traffic: how far it
                        // came and how strong it landed. Both say more
                        // about whether the mesh is healthy than the
                        // delivery tick does.
                        hopsLabel(m.hops)?.let { append("  ·  $it") }
                        m.snr?.let { append("  ·  %.1f dB".format(it)) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (showActions) {
                MessageActionBar(
                    // Reacting to yourself is a round-trip over LoRa that
                    // tells nobody anything; the sibling gates it the
                    // same way.
                    canReact = !m.outgoing,
                    canResend = m.outgoing && m.status == MessageStatus.Failed.ordinal,
                    onReact = { emoji -> showActions = false; onReact(emoji) },
                    onMoreEmoji = { showActions = false; onMoreEmoji() },
                    onReply = { showActions = false; onReply() },
                    onCopy = {
                        showActions = false
                        clipboard.setText(AnnotatedString(m.text))
                    },
                    onInfo = { showActions = false; onInfo() },
                    onDelete = { showActions = false; onDelete() },
                    onResend = { showActions = false; onResend() },
                    onDismiss = { showActions = false },
                )
            }
        }
    }
}

/**
 * Inline action bar for a message: quick reactions on top, text actions
 * below. A popup anchored on the bubble rather than a modal dialog —
 * reacting should be one tap, not read-dialog-then-tap.
 */
@Composable
private fun MessageActionBar(
    canReact: Boolean,
    canResend: Boolean,
    onReact: (String) -> Unit,
    onMoreEmoji: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    onResend: () -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, -140),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (canReact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                for (emoji in Reactions.QUICK) {
                    Text(
                        emoji,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .clickable { onReact(emoji) }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                }
                // Overflow to the full list. Unlike the sibling's client
                // we can't offer the system emoji keyboard: the wire
                // format is an index into a fixed list, so anything
                // outside it is unsendable.
                Text(
                    "＋",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { onMoreEmoji() }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionLabel("Reply", onReply)
                ActionLabel("Copy", onCopy)
                ActionLabel("Info", onInfo)
                if (canResend) ActionLabel("Resend", onResend)
                ActionLabel("Delete", onDelete, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ActionLabel(
    text: String,
    onClick: () -> Unit,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** The `>` lines of a reply, rendered as a quote rather than as text. */
@Composable
private fun QuoteBlock(quoted: String) {
    Row(Modifier.padding(bottom = 4.dp)) {
        Box(
            Modifier
                .width(3.dp)
                .height(if (quoted.length > 40) 32.dp else 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            quoted,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Message body with tappable links (plain [Text] when there are none). */
@Composable
private fun MessageText(
    text: String,
    onHttpLink: (String) -> Unit,
    onMeshcoreLink: (String) -> Unit,
) {
    if (!MessageLinks.hasLinks(text)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        MessageLinks.annotate(
            text = text,
            linkColor = linkColor,
            onHttpLink = onHttpLink,
            onMeshcoreLink = onMeshcoreLink,
        )
    }
    Text(annotated, style = MaterialTheme.typography.bodyMedium)
}


/** Reaction counts under a bubble; tapping adds one of your own. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReactionChips(counts: Map<String, Int>) {
    Row(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((emoji, count) in counts.entries.sortedByDescending { it.value }) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    if (count > 1) "$emoji $count" else emoji,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Emoji palette for reacting.
 *
 * Only emoji from [Reactions.ALL] can be offered: the wire format is a
 * positional index into that exact list, so anything else is unsendable
 * rather than merely unusual.
 */
@Composable
private fun ReactionPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("React") },
        text = {
            Column {
                // Six quick emoji don't fit a dialog's width as buttons
                // with default padding — lay them out on the same grid as
                // the rest so none fall off the edge.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(Reactions.QUICK) { emoji ->
                        TextButton(
                            onClick = { onPick(emoji) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    modifier = Modifier.height(220.dp),
                ) {
                    items(Reactions.ALL.drop(Reactions.QUICK.size)) { emoji ->
                        TextButton(
                            onClick = { onPick(emoji) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) { Text(emoji) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


/** Everything known about one message, on its own sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageInfoSheet(
    m: MessageEntity,
    vm: MeshCoreViewModel,
    senderLabel: String,
    isChannel: Boolean,
    showSender: Boolean,
    contactNames: Map<String, String>,
    metresAway: (String) -> Double?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Scrollable since the route map arrived: the sheet gained
        // ~250dp of map and legend, and a Column that overflows a sheet
        // simply loses whatever is past the bottom — here the hop list,
        // which is the part the map cannot replace.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Message info", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            InfoRow("Direction", if (m.outgoing) "Sent" else "Received")
            InfoRow(
                "Time",
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                    .format(Date(m.timestamp * 1000)),
            )
            if (m.outgoing) {
                InfoRow(
                    "State",
                    when (m.status) {
                        MessageStatus.Delivered.ordinal -> "Delivered (end-to-end ACK)"
                        MessageStatus.Sent.ordinal -> "Accepted by radio, no ACK yet"
                        MessageStatus.Failed.ordinal -> "Failed"
                        else -> "Pending"
                    },
                )
                if (m.attempts > 0) InfoRow("Attempts", m.attempts.toString())
                m.ackHash?.let { InfoRow("Ack hash", "%08x".format(it), mono = true) }
            }
            m.snr?.let { InfoRow("SNR", "%.1f dB".format(it)) }
            // "Hops", not "Path": this is a COUNT. The inbound message
            // frame carries path_len only (MESHCORE_PROTOCOL §9) — the
            // radio keeps the route to itself — so calling it "Path"
            // implied we knew which repeaters carried it. We don't.
            hopsLabel(m.hops)?.let { InfoRow("Hops travelled", it) }
            if (!m.outgoing) {
                MessagePathMap(vm, m, senderLabel)
                ArrivalRoute(m, contactNames, metresAway)
            } else {
                RepeatedBy(m, contactNames)
            }
            if (showSender) {
                InfoRow(
                    "Sender name",
                    (m.senderName?.takeIf { it.isNotBlank() } ?: "(none)") +
                        " — unauthenticated",
                )
            }
            InfoRow("Reaction id", Reactions.targetHash(
                m.timestamp,
                if (isChannel) m.senderName.orEmpty() else null,
                m.text,
            ), mono = true)
            InfoRow("Length", m.text.encodeToByteArray().size.toString() + " bytes")
        }
    }
}

/**
 * "How did this get to me" — the route the message arrived on, in the
 * order it travelled: the sender at the top, this radio at the bottom.
 *
 * Only shown when the route is actually known. The message frame states
 * a hop COUNT and nothing more, so this comes from the RX-log packet —
 * exactly for a channel message, by correlation for a direct one — and
 * when no single packet could be credited the honest answer is a
 * sentence saying so, not an empty diagram (see HeardVia).
 */
@Composable
private fun ArrivalRoute(
    m: MessageEntity,
    contactNames: Map<String, String>,
    metresAway: (String) -> Double?,
) {
    val width = m.arrivalHashWidth?.takeIf { it in 1..4 }
    val path = m.arrivalPathHex?.takeIf { it.isNotEmpty() && width != null }
    Spacer(Modifier.height(8.dp))
    Text("Arrived via", style = MaterialTheme.typography.titleSmall)
    if (path == null || width == null) {
        Text(
            HeardVia.summary(m.hops, null, 1),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val bytes = io.github.thatsfguy.meshcore.util.hexToBytesOrNull(path) ?: return
    val hops = PathCodec.resolveHops(bytes, width, contactNames, metresAway)
    Text(
        HeardVia.summary(m.hops, path, width),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    // The sender first, then each repeater in the order it carried the
    // message, then us. Reading it top-to-bottom IS the journey.
    RouteStep("↑", "sender", dim = true)
    hops.forEachIndexed { i, hop ->
        // An ambiguous hop names every node it could be, nearest
        // first, and never picks one. A 2-byte hop is 16 bits: nodes
        // share one honestly and a collision is cheap to manufacture, so
        // naming a winner would be a guess presented as a fact (PARITY
        // §12) — but naming the whole set is just the truth, and it is
        // what the reader came to the sheet for.
        RouteStep("${i + 1}.", hop.label)
    }
    RouteStep("↓", "this radio", dim = true)
}

/**
 * "Who picked this up" — nodes heard re-broadcasting a message we sent.
 *
 * The outgoing counterpart to [ArrivalRoute], and deliberately NOT
 * called a route: these are nodes observed carrying the message, in the
 * order we first heard them, not an ordered path it travelled.
 *
 * Silence is stated rather than left blank, because an empty section
 * here reads as "nobody repeated it" when what it means is "no repeat
 * reached this radio" — a node that relayed the message away from us
 * never sends a copy back.
 */
@Composable
private fun RepeatedBy(m: MessageEntity, contactNames: Map<String, String>) {
    val relays = MessageRepeats.relays(m.repeatHopsHex, m.repeatHashWidth)
    Spacer(Modifier.height(8.dp))
    Text("Repeated by", style = MaterialTheme.typography.titleSmall)
    Text(
        MessageRepeats.summary(m.repeatHopsHex, m.repeatHashWidth),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (relays.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    val width = m.repeatHashWidth ?: return
    for ((i, hash) in relays.withIndex()) {
        // Named through the shared resolver, so an ambiguous hash stays
        // "(N matches)" here exactly as it does everywhere else. A
        // 2-byte hop is 16 bits; naming a winner would be a guess
        // wearing a fact's clothes (PARITY §12).
        val bytes = io.github.thatsfguy.meshcore.util.hexToBytesOrNull(hash)
        val label = bytes
            ?.let { PathCodec.resolveHops(it, width, contactNames).singleOrNull()?.label }
            ?: hash
        RouteStep("${i + 1}.", label)
    }
}

@Composable
private fun RouteStep(marker: String, label: String, dim: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            marker,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(28.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = if (dim) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(120.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
        )
    }
}


/**
 * "3 hops" / "direct" / "flood" — null when the message predates hop
 * recording or we sent it ourselves.
 */
internal fun hopsLabel(hops: Int?): String? = when {
    hops == null -> null
    hops < 0 -> "flood"
    hops == 0 -> "direct"
    hops == 1 -> "1 hop"
    else -> "$hops hops"
}

/** Newest-N window; "Load older" grows it. */
private const val PAGE_SIZE = 50
