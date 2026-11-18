package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.presentation.Inbox
import io.github.thatsfguy.meshcore.protocol.ReactionRouting
import io.github.thatsfguy.meshcore.protocol.Reactions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Same states, same order, as Android's `MessageStatus`. */
@Serializable
enum class MessageStatus { Pending, Sent, Delivered, Failed }

/**
 * One message in one thread.
 *
 * [threadKey] is [Inbox.threadKey] — `dm|<pubkey hex>` or `ch|<index>`.
 * [timestamp] is the SENDER's clock (epoch seconds), which is what the
 * dedup and reaction keys are built from; [receivedAtMs] is ours.
 */
@Serializable
data class BookMessage(
    val id: Long,
    val threadKey: String,
    val outgoing: Boolean,
    /** Channel posts and room posts name an author; a plain DM does not. */
    val senderName: String? = null,
    val text: String,
    val timestamp: Long,
    val receivedAtMs: Long,
    val status: MessageStatus,
    val ackHash: Long? = null,
    val attempts: Int = 0,
    val contentKey: String? = null,
    val snr: Double? = null,
    val hops: Int? = null,
    /** How many copies of this message arrived (retries, sync + RX log). */
    val copies: Int = 1,
    /** Emoji → count, from reactions that matched this message. */
    val reactions: Map<String, Int> = emptyMap(),
    /** When the recipient's ACK arrived (our clock); outgoing DMs only. */
    val deliveredAtMs: Long? = null,
)

/** One row of the Chats list. */
data class ThreadSummary(
    val threadKey: String,
    val last: BookMessage?,
    val unread: Int,
)

/** Everything persisted. Versioned so a later format can migrate. */
@Serializable
data class BookSnapshot(
    val version: Int = 1,
    val nextId: Long = 1,
    val messages: List<BookMessage> = emptyList(),
    val unread: Map<String, Int> = emptyMap(),
)

/**
 * The message store the iOS app keeps, as a pure in-memory model.
 *
 * Android stores the same facts in Room; iOS has no database yet, so
 * this holds them and serialises to one JSON document the platform
 * writes to disk ([toJson] / [fromJson]). Every rule that decides what
 * becomes a row lives here, where commonTest can reach it:
 *
 *  - a message carrying a [BookMessage.contentKey] already held is a
 *    COPY, not a new row — a sender's retries, the sync and RX-log
 *    paths, and the echo of our own channel post all arrive this way;
 *  - a reaction (`r:<hash>:<idx>`) is attached to its target, never
 *    stored as a line of text;
 *  - unread counts move only for a thread that is not on screen.
 *
 * Not thread-safe by design: the platform drives it from one thread
 * (iOS: the main dispatcher).
 */
class MessageBook(
    snapshot: BookSnapshot = BookSnapshot(),
    /** Newest messages kept per thread; older ones are dropped on insert. */
    private val keepPerThread: Int = DEFAULT_KEEP_PER_THREAD,
    private val nowMs: () -> Long,
) {
    private var nextId = snapshot.nextId
    private val _messages = MutableStateFlow(snapshot.messages)
    private val _unread = MutableStateFlow(snapshot.unread.filterValues { it > 0 })
    private val seenReactions = ReactionRouting.SeenKeys()

    /** Every message, oldest first within each thread. */
    val messages: StateFlow<List<BookMessage>> = _messages.asStateFlow()
    val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()

    /** The thread on screen, which never accrues unread. */
    var activeThread: String? = null
        set(value) {
            field = value
            if (value != null) markRead(value)
        }

    fun thread(threadKey: String): List<BookMessage> =
        _messages.value.filter { it.threadKey == threadKey }

    /** Threads with traffic, most recent first. */
    fun summaries(): List<ThreadSummary> {
        val unread = _unread.value
        return _messages.value
            .groupBy { it.threadKey }
            .map { (key, rows) -> ThreadSummary(key, rows.maxByOrNull { it.receivedAtMs }, unread[key] ?: 0) }
            .sortedByDescending { it.last?.receivedAtMs ?: 0L }
    }

    fun unreadTotal(): Int = Inbox.unreadTotal(_unread.value.values.toList())

    fun markRead(threadKey: String) {
        if ((_unread.value[threadKey] ?: 0) == 0) return
        _unread.value = _unread.value - threadKey
    }

    /** What happened to an inbound message. */
    enum class Inbound {
        /** A new row. The caller may notify. */
        Added,
        /** Another copy of one already held; counted, never notified. */
        Duplicate,
        /** A reaction that matched a message and was attached to it. */
        Reacted,
        /** A reaction already counted, or one that matched nothing. */
        Consumed,
    }

    /**
     * Record an inbound message. [isChannel] decides how a reaction's
     * target hash is computed — a channel hash folds in the sender name.
     */
    fun receive(
        threadKey: String,
        isChannel: Boolean,
        senderName: String?,
        text: String,
        timestamp: Long,
        contentKey: String?,
        snr: Double? = null,
        hops: Int? = null,
    ): Inbound {
        val reaction = Reactions.parse(text)
        if (reaction != null) return applyReaction(threadKey, isChannel, reaction, contentKey)

        if (contentKey != null) {
            val held = _messages.value.indexOfFirst { it.threadKey == threadKey && it.contentKey == contentKey }
            if (held >= 0) {
                val rows = _messages.value.toMutableList()
                val old = rows[held]
                rows[held] = old.copy(
                    // Our own channel post echoing back is not a "copy"
                    // anyone sent; it proves the radio put it on the air.
                    copies = if (old.outgoing) old.copies else old.copies + 1,
                    hops = old.hops ?: hops?.takeIf { it >= 0 },
                    snr = old.snr ?: snr,
                    status = if (old.outgoing && old.status == MessageStatus.Pending) {
                        MessageStatus.Sent
                    } else {
                        old.status
                    },
                )
                _messages.value = rows
                return Inbound.Duplicate
            }
        }
        insert(
            BookMessage(
                id = nextId++,
                threadKey = threadKey,
                outgoing = false,
                senderName = senderName,
                text = text,
                timestamp = timestamp,
                receivedAtMs = nowMs(),
                status = MessageStatus.Delivered,
                contentKey = contentKey,
                snr = snr,
                hops = hops?.takeIf { it >= 0 },
            ),
        )
        bumpUnread(threadKey)
        return Inbound.Added
    }

    /**
     * Record a message we are about to send, shown at once as Pending.
     * A channel post must carry the engine's channelContentKey so its
     * echo is recognised as ours ([receive] → Duplicate).
     */
    fun addOutgoing(
        threadKey: String,
        senderName: String?,
        text: String,
        timestamp: Long,
        contentKey: String? = null,
    ): Long {
        val id = nextId++
        insert(
            BookMessage(
                id = id,
                threadKey = threadKey,
                outgoing = true,
                senderName = senderName,
                text = text,
                timestamp = timestamp,
                receivedAtMs = nowMs(),
                status = MessageStatus.Pending,
                contentKey = contentKey,
            ),
        )
        return id
    }

    /**
     * Move an outgoing message's status. Never moves Delivered backwards:
     * an ACK is the only proof the protocol offers, and a later timeout
     * report must not erase it.
     */
    fun updateStatus(id: Long, status: MessageStatus, ackHash: Long? = null, attempts: Int? = null) {
        val rows = _messages.value
        val i = rows.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = rows[i]
        if (old.status == MessageStatus.Delivered && status != MessageStatus.Delivered) return
        _messages.value = rows.toMutableList().also {
            it[i] = old.copy(
                status = status,
                ackHash = ackHash ?: old.ackHash,
                attempts = attempts ?: old.attempts,
                deliveredAtMs = old.deliveredAtMs
                    ?: if (status == MessageStatus.Delivered) nowMs() else null,
            )
        }
    }

    fun delete(id: Long) {
        _messages.value = _messages.value.filterNot { it.id == id }
    }

    fun deleteThread(threadKey: String) {
        _messages.value = _messages.value.filterNot { it.threadKey == threadKey }
        markRead(threadKey)
    }

    fun snapshot(): BookSnapshot = BookSnapshot(
        nextId = nextId,
        messages = _messages.value,
        unread = _unread.value,
    )

    fun toJson(): String = JSON.encodeToString(BookSnapshot.serializer(), snapshot())

    private fun insert(message: BookMessage) {
        val rows = _messages.value + message
        val inThread = rows.count { it.threadKey == message.threadKey }
        _messages.value = if (inThread > keepPerThread) {
            // Drop the oldest of THIS thread only; a busy channel must not
            // evict a quiet DM.
            val drop = rows.filter { it.threadKey == message.threadKey }
                .sortedBy { it.receivedAtMs }
                .take(inThread - keepPerThread)
                .map { it.id }
                .toSet()
            rows.filterNot { it.id in drop }
        } else {
            rows
        }
    }

    private fun bumpUnread(threadKey: String) {
        if (!Inbox.shouldBumpUnread(activeThread, threadKey.substringBefore('|'), threadKey.substringAfter('|'))) {
            return
        }
        val now = _unread.value[threadKey] ?: 0
        if (now == Int.MAX_VALUE) return
        _unread.value = _unread.value + (threadKey to now + 1)
    }

    private fun applyReaction(
        threadKey: String,
        isChannel: Boolean,
        reaction: Reactions.Reaction,
        contentKey: String?,
    ): Inbound {
        // A retried reaction is the same reaction; count it once.
        if (!seenReactions.firstSight(contentKey)) return Inbound.Consumed
        val rows = _messages.value
        val candidates = rows.asReversed()
            .asSequence()
            .filter { it.threadKey == threadKey }
            .take(ReactionRouting.SEARCH_WINDOW)
            .map { ReactionRouting.Candidate(it.id, it.timestamp, it.senderName, it.text, it.outgoing) }
            .toList()
        val target = ReactionRouting.target(candidates, reaction.targetHash, isChannel)
            ?: return Inbound.Consumed
        val i = rows.indexOfFirst { it.id == target.id }
        val old = rows[i]
        val count = (old.reactions[reaction.emoji] ?: 0) + 1
        _messages.value = rows.toMutableList().also {
            it[i] = old.copy(reactions = old.reactions + (reaction.emoji to count))
        }
        bumpUnread(threadKey)
        return Inbound.Reacted
    }

    companion object {
        /** Generous: a year of a busy channel at a few hundred bytes a row. */
        const val DEFAULT_KEEP_PER_THREAD = 2_000

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /**
         * Whether [json] can be restored. Nothing at all (null or blank)
         * counts as readable — there is no history to lose. The platform
         * asks before loading so an unreadable file can be kept aside
         * instead of being overwritten by the next save.
         */
        fun isReadable(json: String?): Boolean =
            json.isNullOrBlank() ||
                runCatching { JSON.decodeFromString(BookSnapshot.serializer(), json) }.isSuccess

        /**
         * Restore from [toJson] output. Anything unreadable yields an
         * empty book rather than a crash: losing history is bad, a client
         * that will not start is worse, and the platform keeps the bad
         * file aside for inspection.
         */
        fun fromJson(json: String?, nowMs: () -> Long, keepPerThread: Int = DEFAULT_KEEP_PER_THREAD): MessageBook {
            val snapshot = json?.let {
                runCatching { JSON.decodeFromString(BookSnapshot.serializer(), it) }.getOrNull()
            } ?: BookSnapshot()
            val maxId = snapshot.messages.maxOfOrNull { it.id } ?: 0L
            // A Pending row outlived the send that owned it: the app died
            // before the radio answered, so nothing will ever resolve it.
            // Failed is the truth. Sent stays Sent — the radio took it,
            // and whether it arrived is genuinely unknown.
            val settled = snapshot.messages.map {
                if (it.outgoing && it.status == MessageStatus.Pending) it.copy(status = MessageStatus.Failed) else it
            }
            return MessageBook(
                snapshot.copy(nextId = maxOf(snapshot.nextId, maxId + 1), messages = settled),
                keepPerThread,
                nowMs,
            )
        }
    }
}
