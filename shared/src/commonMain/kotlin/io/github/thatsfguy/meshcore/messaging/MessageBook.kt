package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.presentation.Inbox
import io.github.thatsfguy.meshcore.protocol.MessageRepeats
import io.github.thatsfguy.meshcore.protocol.ReactionRouting
import io.github.thatsfguy.meshcore.protocol.Reactions
import io.github.thatsfguy.meshcore.protocol.Retention
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
    /**
     * Hops travelled: 0 direct, N flooded across N, and -1
     * ([io.github.thatsfguy.meshcore.protocol.PathCodec.HOPS_ROUTED]) when
     * it came along a route the sender already had, so no count exists.
     */
    val hops: Int? = null,
    /** Inbound: the repeaters it came through, in travel order (hex), when known. */
    val arrivalPathHex: String? = null,
    val arrivalHashWidth: Int? = null,
    /** Outgoing: distinct relays heard re-broadcasting it (hex, first-seen order). */
    val repeatHopsHex: String? = null,
    val repeatHashWidth: Int? = null,
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

    /**
     * Mark [threadKey] unread by hand ("come back to this"): at least one
     * unread, never fewer than it already had. Not for the thread on
     * screen, which never accrues unread.
     */
    fun markUnread(threadKey: String) {
        if (threadKey == activeThread) return
        val now = _unread.value[threadKey] ?: 0
        _unread.value = _unread.value + (threadKey to maxOf(now, 1))
    }

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
        arrivalPathHex: String? = null,
        arrivalHashWidth: Int? = null,
    ): Inbound {
        val reaction = Reactions.parse(text)
        if (reaction != null) return applyReaction(threadKey, isChannel, reaction, contentKey)

        if (contentKey != null) {
            val held = _messages.value.indexOfFirst { it.threadKey == threadKey && it.contentKey == contentKey }
            if (held >= 0) {
                val rows = _messages.value.toMutableList()
                val old = rows[held]
                val path = arrivalPathHex?.takeIf { it.isNotEmpty() && arrivalHashWidth in 1..4 }
                // Our own channel post heard back through the mesh: the
                // path names the repeaters that re-broadcast it.
                val repeats = if (old.outgoing && path != null) {
                    MessageRepeats.merge(old.repeatHopsHex, path, arrivalHashWidth!!)
                } else {
                    null
                }
                rows[held] = old.copy(
                    // Our own channel post echoing back is not a "copy"
                    // anyone sent; it proves the radio put it on the air.
                    copies = if (old.outgoing) old.copies else old.copies + 1,
                    // A real count beats "routed"; nothing beats a real count.
                    hops = when {
                        old.hops == null -> hops
                        old.hops < 0 && hops != null && hops >= 0 -> hops
                        else -> old.hops
                    },
                    snr = old.snr ?: snr,
                    // A known route is never overwritten by a later copy's.
                    arrivalPathHex = if (old.outgoing) old.arrivalPathHex else old.arrivalPathHex ?: path,
                    arrivalHashWidth = if (old.outgoing) old.arrivalHashWidth else old.arrivalHashWidth ?: path?.let { arrivalHashWidth },
                    repeatHopsHex = repeats ?: old.repeatHopsHex,
                    repeatHashWidth = if (repeats != null) arrivalHashWidth else old.repeatHashWidth,
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
                hops = hops,
                arrivalPathHex = arrivalPathHex?.takeIf { it.isNotEmpty() && arrivalHashWidth in 1..4 },
                arrivalHashWidth = arrivalHashWidth?.takeIf { !arrivalPathHex.isNullOrEmpty() && it in 1..4 },
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

    /**
     * Count a reaction WE sent on message [id]. [contentKey] is the key
     * our own post will echo back under (channels only); remembering it
     * here is what stops the echo counting the same reaction twice.
     */
    fun addOwnReaction(id: Long, emoji: String, contentKey: String?) {
        contentKey?.let { seenReactions.remember(it) }
        val rows = _messages.value
        val i = rows.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = rows[i]
        _messages.value = rows.toMutableList().also {
            it[i] = old.copy(reactions = old.reactions + (emoji to (old.reactions[emoji] ?: 0) + 1))
        }
    }

    fun message(id: Long): BookMessage? = _messages.value.firstOrNull { it.id == id }

    /**
     * Credit a heard re-broadcast of one of OUR direct messages to the
     * sent row it most plausibly belongs to. A rebroadcast DM exposes one
     * byte of its recipient, so the decision is [MessageRepeats.creditDirect]'s:
     * the newest send to a matching recipient within the echo window,
     * and none at all when two sends are too close to tell apart.
     * Returns whether a row was credited.
     */
    fun noteDirectRepeat(destHash: Int, pathHex: String, hashWidth: Int): Boolean {
        val now = nowMs()
        val window = now - DM_REPEAT_WINDOW_MS
        val candidates = _messages.value
            .filter { it.outgoing && it.threadKey.startsWith("${Inbox.KIND_DM}|") && it.receivedAtMs >= window }
            .sortedByDescending { it.receivedAtMs }
            .take(8)
            .map { MessageRepeats.SentRef(it.id, it.threadKey.substringAfter('|'), it.receivedAtMs) }
        val credited = MessageRepeats.creditDirect(candidates, destHash, now) ?: return false
        val rows = _messages.value.toMutableList()
        val i = rows.indexOfFirst { it.id == credited.id }
        if (i < 0) return false
        val merged = MessageRepeats.merge(rows[i].repeatHopsHex, pathHex, hashWidth) ?: return false
        rows[i] = rows[i].copy(repeatHopsHex = merged, repeatHashWidth = hashWidth)
        _messages.value = rows
        return true
    }

    /**
     * Names that have posted in [threadKey], newest first, each once —
     * who a channel mention can be offered for. Unauthenticated names:
     * the mention is a convenience, not an address.
     */
    fun senderNames(threadKey: String): List<String> =
        _messages.value.filter { it.threadKey == threadKey && !it.outgoing && !it.senderName.isNullOrBlank() }
            .sortedByDescending { it.receivedAtMs }
            .mapNotNull { it.senderName?.trim() }
            .distinct()

    /** The newest inbound message in [threadKey] whose route is known. */
    fun latestArrival(threadKey: String): BookMessage? =
        _messages.value.filter { it.threadKey == threadKey && !it.outgoing && !it.arrivalPathHex.isNullOrEmpty() }
            .maxByOrNull { it.receivedAtMs }

    fun delete(id: Long) {
        _messages.value = _messages.value.filterNot { it.id == id }
    }

    fun deleteThread(threadKey: String) {
        _messages.value = _messages.value.filterNot { it.threadKey == threadKey }
        markRead(threadKey)
    }

    /**
     * Apply a user retention [policy] to every thread; returns how many
     * rows went. Does nothing for an unbounded policy.
     *
     * Age is judged by [BookMessage.receivedAtMs] — OUR clock — not the
     * sender's [BookMessage.timestamp]. The sender's clock is
     * unauthenticated and often wrong: a node whose RTC reset to 2020
     * would have every message it sends deleted the moment it arrived
     * under "keep 30 days". (Android judges by the sender's timestamp;
     * see IOS_DECISIONS.md.) Our own outgoing rows are judged the same
     * way, so a thread is trimmed evenly.
     */
    fun applyRetention(policy: Retention.Policy, nowMs: Long): Int {
        if (!policy.isBounded) return 0
        val before = _messages.value
        var rows = before
        policy.cutoffSeconds(nowMs / 1000)?.let { cutoff ->
            rows = rows.filter { it.receivedAtMs / 1000 >= cutoff }
        }
        policy.keepPerThread()?.let { keep ->
            val drop = rows.groupBy { it.threadKey }.values.flatMap { thread ->
                thread.sortedWith(compareByDescending<BookMessage> { it.receivedAtMs }.thenByDescending { it.id })
                    .drop(keep)
            }.map { it.id }.toSet()
            rows = rows.filterNot { it.id in drop }
        }
        if (rows.size == before.size) return 0
        _messages.value = rows
        // A thread that emptied has nothing left to be unread.
        val live = rows.map { it.threadKey }.toSet()
        _unread.value = _unread.value.filterKeys { it in live }
        return before.size - rows.size
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
        /** How far back a heard DM repeat may reach for its original (Android's window). */
        const val DM_REPEAT_WINDOW_MS: Long = 180_000

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
