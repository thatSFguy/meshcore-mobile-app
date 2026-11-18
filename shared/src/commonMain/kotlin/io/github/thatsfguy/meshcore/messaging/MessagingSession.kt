package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.engine.MeshEvent
import io.github.thatsfguy.meshcore.presentation.Inbox
import io.github.thatsfguy.meshcore.protocol.AckTimeout
import io.github.thatsfguy.meshcore.protocol.BlockList
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.SendRetry
import io.github.thatsfguy.meshcore.protocol.Reactions
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
import io.github.thatsfguy.meshcore.util.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What [MessagingSession] needs from the radio, so the session is
 * testable without frames. [EngineMessagingRadio] is the real one.
 */
interface MessagingRadio {
    val events: Flow<MeshEvent>
    fun nowSeconds(): Long
    fun selfName(): String?

    /** Null when [keyHex] is not a contact the radio holds. */
    fun route(keyHex: String): ContactRoute?

    suspend fun sendDirect(keyHex: String, text: String, attempt: Int, timestampSeconds: Long): DirectSend.Receipt?
    suspend fun resetPath(keyHex: String): Boolean
    /** [region] null = the radio's own flood scope; else this one send is scoped to it. */
    suspend fun sendChannel(index: Int, text: String, timestampSeconds: Long, region: String?): Boolean
    fun channelContentKey(index: Int, timestampSeconds: Long, senderName: String, text: String): String
}

/** A contact's routing, as the retry ladder needs it. */
data class ContactRoute(
    val hasStoredPath: Boolean,
    val storedHops: Int?,
    /** The stored route as hex, for scoring it; "" when flooding. */
    val storedPathHex: String = "",
    /** The contact's own hop-hash width (the route's, not necessarily the mesh's). */
    val hashWidth: Int = 1,
)

/**
 * Engine events in, [MessageBook] rows out, plus the two send paths.
 *
 * This is the part of Android's `MessageRepository` that decides what a
 * message IS, without the parts that are Android's (Room, notifications,
 * path history). iOS drives it directly; see [MessageBook] for the
 * dedup and reaction rules.
 *
 * What is deliberately NOT done here yet (iOS has no screen for it):
 * CLI replies are dropped rather than stored — they belong in a repeater
 * console — and there is no block list.
 */
class MessagingSession(
    private val radio: MessagingRadio,
    val book: MessageBook,
    private val scope: CoroutineScope,
) {
    /** Called for a new inbound message the user should hear about. */
    var onNewMessage: ((threadKey: String, sender: String?, text: String) -> Unit)? = null

    /** Called after any change worth persisting. */
    var onChanged: (() -> Unit)? = null

    /**
     * Public keys whose direct messages are dropped — a real block,
     * because a DM's sender is the key that signed it. Canonical lower
     * hex ([BlockList.canonicalKey]); the platform persists the set.
     */
    var blockedKeys: Set<String> = emptySet()

    /**
     * Channel sender names hidden — a NOISE FILTER, not a block: a
     * channel post carries an unauthenticated name, so this stops a
     * spammer who keeps their name and nothing more
     * ([BlockList.CHANNEL_FILTER_CAVEAT]).
     */
    var filteredNames: Set<String> = emptySet()

    /**
     * Diagnostics: one line per send step and per arrival — status,
     * attempt, ACK, hops, SNR, a 12-hex key prefix. NEVER message text
     * or a channel sender's name: the log is for "did it go and did it
     * come back", and gets pasted into issues. The platform redacts and
     * gates it (off by default, SCOPE.md).
     */
    var log: ((String) -> Unit)? = null

    /**
     * Flood on the last DM attempt, resetting the path first. MeshCore's
     * documented default is ON and it is a real user setting (CLAUDE.md:
     * the retry rule earned from the firmware FAQ).
     */
    var floodFallback: Boolean = true

    /**
     * Path history for this radio, scored by each DM attempt; null = not
     * kept. Android's rule: an attempt over the stored route counts for
     * or against that route; the flood fallback scores nothing, because
     * it cannot say which route the reply proved.
     */
    var paths: PathHistoryBook? = null

    /**
     * Per-channel regions (slot → region): a post on that channel is
     * flooded only through repeaters carrying the region, for that send
     * alone — the radio's own scope is restored after (the engine's
     * rule). A channel with no entry uses the app-wide scope.
     */
    var channelRegions: Map<Int, String> = emptyMap()

    private fun note(line: String) {
        log?.let { runCatching { it(line) } }
    }

    private var collector: Job? = null

    fun start() {
        if (collector != null) return
        collector = scope.launch { radio.events.collect { handle(it) } }
    }

    fun stop() {
        collector?.cancel()
        collector = null
    }

    internal fun handle(event: MeshEvent) {
        when (event) {
            is MeshEvent.DirectMessageReceived -> {
                if (event.txtType == Codes.TXT_TYPE_CLI_DATA) return
                // Only a RESOLVED sender can be blocked: a 6-byte prefix is
                // 48 bits, and blocking on one would silently drop whoever
                // collides with it (same rule as Android).
                if (BlockList.isBlockableMessage(event.txtType) &&
                    BlockList.isBlockedSender(event.senderKeyHex, blockedKeys)
                ) {
                    note("DM from ${event.senderPrefixHex.take(12)} dropped: blocked")
                    return
                }
                val peer = event.senderKeyHex ?: event.senderPrefixHex
                val key = Inbox.threadKey(Inbox.KIND_DM, peer)
                val outcome = book.receive(
                    threadKey = key,
                    isChannel = false,
                    senderName = event.roomAuthorLabel,
                    text = event.text,
                    timestamp = event.timestamp,
                    contentKey = event.contentKey,
                    snr = event.snr,
                    hops = event.hops,
                    arrivalPathHex = event.arrivalPathHex,
                    arrivalHashWidth = event.arrivalHashWidth,
                )
                note(
                    "DM from ${peer.take(12)}${if (event.senderKeyHex == null) " (unresolved)" else ""}: " +
                        "$outcome, hops=${event.hops ?: "?"} snr=${event.snr ?: "?"}",
                )
                after(outcome, key, event.roomAuthorLabel, event.text)
            }
            is MeshEvent.ChannelMessageReceived -> {
                if (BlockList.isFilteredChannelName(event.senderName, filteredNames)) {
                    note("Channel ${event.channelIndex} post dropped: sender name filtered")
                    return
                }
                val key = Inbox.threadKey(Inbox.KIND_CHANNEL, event.channelIndex.toString())
                val outcome = book.receive(
                    threadKey = key,
                    isChannel = true,
                    senderName = event.senderName,
                    text = event.text,
                    timestamp = event.timestamp,
                    contentKey = event.contentKey,
                    snr = event.snr,
                    hops = event.hops,
                    arrivalPathHex = event.arrivalPathHex,
                    arrivalHashWidth = event.arrivalHashWidth,
                )
                note("Channel ${event.channelIndex} post: $outcome, hops=${event.hops ?: "?"} snr=${event.snr ?: "?"}")
                after(outcome, key, event.senderName, event.text)
            }
            is MeshEvent.OwnDirectRepeatHeard -> {
                // A node re-broadcasting one of our DMs: credit it to the
                // send it most plausibly belongs to, or to none.
                if (book.noteDirectRepeat(event.destHash, event.pathHex, event.hashWidth)) {
                    note("Own DM repeat heard via ${event.pathHex.take(16)}: credited")
                    onChanged?.invoke()
                }
            }
            else -> Unit
        }
    }

    private fun after(outcome: MessageBook.Inbound, key: String, sender: String?, text: String) {
        onChanged?.invoke()
        if (outcome == MessageBook.Inbound.Added && key != book.activeThread) {
            onNewMessage?.invoke(key, sender, text)
        }
    }

    /**
     * Send a direct message with the full retry ladder. The row appears
     * at once as Pending; returns its id. Blank text, or a key that is
     * not hex, sends nothing and returns null.
     */
    fun sendDirect(keyHex: String, text: String): Long? {
        val body = text.trim()
        if (body.isEmpty() || hexToBytesOrNull(keyHex) == null) return null
        val threadKey = Inbox.threadKey(Inbox.KIND_DM, keyHex)
        val timestamp = radio.nowSeconds()
        val id = book.addOutgoing(threadKey, null, body, timestamp)
        onChanged?.invoke()

        // Read the route once, BEFORE the last attempt resets it.
        val route = radio.route(keyHex)
        val knownHops = AckTimeout.estimateHops(
            storedPathHops = route?.storedHops,
            lastHeardHops = book.thread(threadKey).lastOrNull { !it.outgoing && it.hops != null }?.hops,
        )
        val who = keyHex.take(12)
        note(
            "DM #$id to $who: queued, " +
                (if (route?.hasStoredPath == true) "stored path ${route.storedHops} hops" else "no stored path (flood)") +
                ", est hops=${knownHops ?: "?"}",
        )
        val ladderRadio = object : DirectSend.Radio {
            override suspend fun send(text: String, attempt: Int, timestampSeconds: Long): DirectSend.Receipt? {
                val receipt = radio.sendDirect(keyHex, text, attempt, timestampSeconds)
                note(
                    "DM #$id attempt ${attempt + 1}: " + (
                        receipt?.let { "radio accepted, ack=${it.ackHash.toString(16)} wait=${it.timeoutMs}ms flood=${it.isFlood}" }
                            ?: "radio refused"
                        ),
                )
                return receipt
            }
            override suspend fun resetPath(): Boolean =
                radio.resetPath(keyHex).also { note("DM #$id: path reset before flood ${if (it) "ok" else "refused"}") }
            override val deliveries: Flow<Long> =
                radio.events.filterIsInstance<MeshEvent.MessageDelivered>().map { it.ackHash }
        }
        scope.launch {
            val final = DirectSend.run(
                radio = ladderRadio,
                text = body,
                timestampSeconds = timestamp,
                hasStoredPath = route?.hasStoredPath == true,
                knownHops = knownHops,
                floodFallbackEnabled = floodFallback,
                onAttempt = { attempt, used, delivered ->
                    val pathHex = route?.storedPathHex.orEmpty()
                    if (SendRetry.scoresStoredPath(used) && (!delivered || pathHex.isNotEmpty() || attempt == 0)) {
                        paths?.score(keyHex, pathHex, delivered, route?.hashWidth ?: 1)
                        onChanged?.invoke()
                    }
                },
            ) { update ->
                book.updateStatus(id, update.status, update.ackHash, update.attempts)
                onChanged?.invoke()
            }
            note("DM #$id to $who: $final")
        }
        return id
    }

    /**
     * React to message [id] with [emoji] — one of [Reactions.ALL]. The
     * wire form is the ordinary text message `r:<hash>:<idx>`, where the
     * hash is computed exactly as an incoming reaction is matched
     * ([ReactionRouting]): a channel hash folds in the post's sender
     * name, a DM hash does not. Returns false when nothing was sent.
     *
     * Counted locally at once. A DM reaction goes through the same retry
     * ladder as a message (its status is not shown — a reaction has no
     * bubble); a channel reaction is one flood, like any channel post.
     */
    fun react(id: Long, emoji: String): Boolean {
        val target = book.message(id) ?: return false
        val (kind, peer) = target.threadKey.split('|', limit = 2).let {
            if (it.size != 2) return false
            it[0] to it[1]
        }
        val isChannel = kind == Inbox.KIND_CHANNEL
        val hash = Reactions.targetHash(
            target.timestamp,
            if (isChannel) target.senderName.orEmpty() else null,
            target.text,
        )
        val wire = Reactions.encode(hash, emoji) ?: return false
        val timestamp = radio.nowSeconds()
        if (isChannel) {
            val index = peer.toIntOrNull() ?: return false
            val self = radio.selfName().orEmpty()
            book.addOwnReaction(id, emoji, radio.channelContentKey(index, timestamp, self, wire))
            onChanged?.invoke()
            scope.launch { runCatching { radio.sendChannel(index, wire, timestamp, channelRegions[index]) } }
        } else {
            if (hexToBytesOrNull(peer) == null) return false
            book.addOwnReaction(id, emoji, null)
            onChanged?.invoke()
            val route = radio.route(peer)
            val ladderRadio = object : DirectSend.Radio {
                override suspend fun send(text: String, attempt: Int, timestampSeconds: Long) =
                    radio.sendDirect(peer, text, attempt, timestampSeconds)
                override suspend fun resetPath() = radio.resetPath(peer)
                override val deliveries: Flow<Long> =
                    radio.events.filterIsInstance<MeshEvent.MessageDelivered>().map { it.ackHash }
            }
            scope.launch {
                DirectSend.run(
                    ladderRadio, wire, timestamp,
                    hasStoredPath = route?.hasStoredPath == true,
                    knownHops = route?.storedHops,
                ) { }
            }
        }
        return true
    }

    /**
     * Send a channel post. A flood group message has no end-to-end ACK,
     * so "the radio accepted it" (Sent) is its terminal good state.
     */
    fun sendChannel(index: Int, text: String): Long? {
        val body = text.trim()
        if (body.isEmpty() || index < 0) return null
        val threadKey = Inbox.threadKey(Inbox.KIND_CHANNEL, index.toString())
        val timestamp = radio.nowSeconds()
        val self = radio.selfName().orEmpty()
        // Same key the engine computes for the echo, so it lands as ours.
        val contentKey = radio.channelContentKey(index, timestamp, self, body)
        val id = book.addOutgoing(threadKey, self, body, timestamp, contentKey)
        onChanged?.invoke()
        scope.launch {
            val region = channelRegions[index]
            val ok = runCatching { radio.sendChannel(index, body, timestamp, region) }.getOrDefault(false)
            note("Channel $index post #$id${region?.let { " (region #$it)" } ?: ""}: ${if (ok) "radio accepted" else "radio refused"}")
            book.updateStatus(id, if (ok) MessageStatus.Sent else MessageStatus.Failed)
            onChanged?.invoke()
        }
        return id
    }
}

/** [MessagingRadio] over the real engine. */
class EngineMessagingRadio(
    private val engine: MeshCoreEngine,
    private val clock: () -> Long,
) : MessagingRadio {
    override val events: Flow<MeshEvent> get() = engine.meshEvents
    override fun nowSeconds(): Long = clock()
    override fun selfName(): String? = engine.selfInfo.value?.name

    override fun route(keyHex: String): ContactRoute? {
        val c = engine.contacts.value[keyHex] ?: return null
        val info = c.pathInfo
        return ContactRoute(
            hasStoredPath = c.storedPath.isNotEmpty(),
            storedHops = info.takeIf { !it.isFlood }?.hops,
            storedPathHex = c.storedPath.toHex(),
            hashWidth = info.hashWidth,
        )
    }

    override suspend fun sendDirect(
        keyHex: String,
        text: String,
        attempt: Int,
        timestampSeconds: Long,
    ): DirectSend.Receipt? {
        val key = hexToBytesOrNull(keyHex) ?: return null
        val sent = engine.sendDirectMessage(key, text, attempt, timestampSeconds) ?: return null
        return DirectSend.Receipt(sent.ackHash, sent.timeoutMs, sent.isFlood)
    }

    override suspend fun resetPath(keyHex: String): Boolean {
        val key = hexToBytesOrNull(keyHex) ?: return false
        return engine.resetPath(key)
    }

    override suspend fun sendChannel(index: Int, text: String, timestampSeconds: Long, region: String?): Boolean =
        engine.sendChannelMessage(index, text, timestampSeconds, region)

    override fun channelContentKey(index: Int, timestampSeconds: Long, senderName: String, text: String): String =
        engine.channelContentKey(index, timestampSeconds, senderName, text)
}
