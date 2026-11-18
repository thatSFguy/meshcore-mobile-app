package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.engine.MeshEvent
import io.github.thatsfguy.meshcore.presentation.Inbox
import io.github.thatsfguy.meshcore.protocol.AckTimeout
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
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
    suspend fun sendChannel(index: Int, text: String, timestampSeconds: Long): Boolean
    fun channelContentKey(index: Int, timestampSeconds: Long, senderName: String, text: String): String
}

/** A contact's routing, as the retry ladder needs it. */
data class ContactRoute(val hasStoredPath: Boolean, val storedHops: Int?)

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
                )
                after(outcome, key, event.roomAuthorLabel, event.text)
            }
            is MeshEvent.ChannelMessageReceived -> {
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
                )
                after(outcome, key, event.senderName, event.text)
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
        val ladderRadio = object : DirectSend.Radio {
            override suspend fun send(text: String, attempt: Int, timestampSeconds: Long) =
                radio.sendDirect(keyHex, text, attempt, timestampSeconds)
            override suspend fun resetPath() = radio.resetPath(keyHex)
            override val deliveries: Flow<Long> =
                radio.events.filterIsInstance<MeshEvent.MessageDelivered>().map { it.ackHash }
        }
        scope.launch {
            DirectSend.run(
                radio = ladderRadio,
                text = body,
                timestampSeconds = timestamp,
                hasStoredPath = route?.hasStoredPath == true,
                knownHops = knownHops,
            ) { update ->
                book.updateStatus(id, update.status, update.ackHash, update.attempts)
                onChanged?.invoke()
            }
        }
        return id
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
            val ok = runCatching { radio.sendChannel(index, body, timestamp) }.getOrDefault(false)
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

    override suspend fun sendChannel(index: Int, text: String, timestampSeconds: Long): Boolean =
        engine.sendChannelMessage(index, text, timestampSeconds)

    override fun channelContentKey(index: Int, timestampSeconds: Long, senderName: String, text: String): String =
        engine.channelContentKey(index, timestampSeconds, senderName, text)
}
