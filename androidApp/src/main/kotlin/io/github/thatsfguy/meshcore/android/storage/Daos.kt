package io.github.thatsfguy.meshcore.android.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One (kind, peerKey) thread identity — the unit retention works on. */
data class ThreadKey(val kind: String, val peerKey: String)

/**
 * A name seen posting on a channel. Deliberately not called a
 * "participant" or "member": the name is unauthenticated display text,
 * so this counts appearances, not people.
 */
data class ChannelSender(val name: String, val messageCount: Int, val lastSeenAt: Long)

@Dao
interface MessageDao {
    /** IGNORE + the unique (selfKey, contentKey) index = channel dedup. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Query(
        "SELECT * FROM messages WHERE selfKey = :selfKey AND kind = :kind AND peerKey = :peerKey " +
            "ORDER BY timestamp ASC, receivedAt ASC",
    )
    fun thread(selfKey: String, kind: String, peerKey: String): Flow<List<MessageEntity>>

    /**
     * The admin console: CLI traffic ONLY, in the order it happened
     * here.
     *
     * Two things this fixes, both from the console having reused the
     * whole DM thread.
     *
     * **`txtType = 1` and nothing else.** Our own commands are recorded
     * as type 1 too, so this is the exact complement of the chat
     * filter below — the two together partition the thread. Reading it
     * unfiltered put a room's actual posts ("hey", "hi", "check" —
     * TXT_TYPE_SIGNED, 2) into the room's console.
     *
     * **Ordered by `receivedAt`, not `timestamp`.** `timestamp` is
     * sender-claimed, and a repeater or room server has no GPS and
     * usually no correct clock. Sorting a console that way interleaves
     * OUR commands (stamped by the phone, correctly) with THEIR replies
     * (stamped hours off), so a reply lands nowhere near the command
     * that caused it. `receivedAt` is local millis in both directions,
     * which is the only clock that orders a conversation we took part
     * in. `id` breaks ties within a millisecond.
     */
    @Query(
        "SELECT * FROM messages WHERE selfKey = :selfKey AND kind = :kind AND peerKey = :peerKey " +
            "AND txtType = 1 ORDER BY receivedAt ASC, id ASC",
    )
    fun cliThread(selfKey: String, kind: String, peerKey: String): Flow<List<MessageEntity>>

    /**
     * Clear the console WITHOUT touching conversation. On a room server
     * the CLI and the chat share one thread, so the blanket
     * [clearThread] silently took the room's messages with it.
     */
    @Query(
        "DELETE FROM messages WHERE selfKey = :selfKey AND kind = :kind " +
            "AND peerKey = :peerKey AND txtType = 1",
    )
    suspend fun clearCliThread(selfKey: String, kind: String, peerKey: String)

    /** Newest [limit] messages of a thread (paging window). */
    // Everything EXCEPT CLI replies (txt_type 1). Those are remote-admin
    // console output, not conversation, and pouring `get radio` into the
    // chat is noise. They stay in the table — the console reads exactly
    // the rows this excludes, via `cliThread()` above.
    //
    // Note this must exclude ONLY type 1: room-server posts arrive as
    // TXT_TYPE_SIGNED (2), so a `txtType = 0` filter silently empties
    // every room thread.
    @Query(
        "SELECT * FROM messages WHERE selfKey = :selfKey AND kind = :kind AND peerKey = :peerKey " +
            "AND txtType != 1 ORDER BY timestamp DESC, receivedAt DESC LIMIT :limit",
    )
    fun threadPaged(selfKey: String, kind: String, peerKey: String, limit: Int): Flow<List<MessageEntity>>

    @Query(
        "SELECT COUNT(*) FROM messages WHERE selfKey = :selfKey AND kind = :kind " +
            "AND peerKey = :peerKey AND txtType != 1",
    )
    fun threadCount(selfKey: String, kind: String, peerKey: String): Flow<Int>

    @Query(
        "SELECT * FROM messages WHERE selfKey = :selfKey AND txtType != 1 " +
            "GROUP BY kind, peerKey HAVING MAX(timestamp) ORDER BY MAX(timestamp) DESC",
    )
    fun latestPerThread(selfKey: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET status = :status WHERE selfKey = :selfKey AND ackHash = :ackHash")
    suspend fun updateStatusByAck(selfKey: String, ackHash: Long, status: Int): Int

    @Query("UPDATE messages SET status = :status, ackHash = :ackHash WHERE id = :id")
    suspend fun updateResult(id: Long, status: Int, ackHash: Long?)

    @Query("UPDATE messages SET attempts = :attempts WHERE id = :id")
    suspend fun setAttempts(id: Long, attempts: Int)

    @Query(
        "UPDATE messages SET status = :status WHERE selfKey = :selfKey AND contentKey = :contentKey " +
            "AND outgoing = 1",
    )
    suspend fun updateStatusByContentKey(selfKey: String, contentKey: String, status: Int): Int

    @Query(
        "UPDATE messages SET status = :failed WHERE selfKey = :selfKey AND status = :pending " +
            "AND receivedAt < :olderThanMillis",
    )
    // UNWIRED (audited 2026-08-06): nothing calls this, so an outbound
    // message whose ACK never arrives stays "pending" for ever rather
    // than being failed. Kept because deleting it would erase the
    // evidence that the feature is unfinished, not because it is used.
    suspend fun failStalePending(
        selfKey: String,
        olderThanMillis: Long,
        pending: Int = MessageStatus.Sent.ordinal,
        failed: Int = MessageStatus.Failed.ordinal,
    ): Int

    /**
     * Fill in the route a channel message arrived on.
     *
     * A channel message reaches us twice — once through companion sync
     * (no path) and once through the RX log (full path) — and whichever
     * lands second is bounced by the unique contentKey index. Without
     * this the path is lost exactly when the sync copy wins the race.
     *
     * `arrivalPathHex IS NULL` so a known route is never overwritten:
     * the first packet we actually decoded is the one we saw.
     */
    @Query(
        "UPDATE messages SET arrivalPathHex = :pathHex, arrivalHashWidth = :width " +
            "WHERE selfKey = :selfKey AND contentKey = :contentKey AND arrivalPathHex IS NULL",
    )
    suspend fun fillArrivalPath(
        selfKey: String,
        contentKey: String,
        pathHex: String,
        width: Int,
    ): Int

    /**
     * The outgoing row a channel echo belongs to, if that echo is of
     * something WE sent.
     *
     * `outgoing = 1` is the whole check that makes this safe: an echo
     * whose contentKey matches an INBOUND row is somebody else's message
     * arriving twice, and crediting it as a repeat of ours would be an
     * invented fact.
     */
    @Query(
        "SELECT * FROM messages WHERE selfKey = :selfKey AND contentKey = :contentKey " +
            "AND outgoing = 1 LIMIT 1",
    )
    suspend fun outgoingByContentKey(selfKey: String, contentKey: String): MessageEntity?

    /**
     * The candidate sent DMs a heard repeat could belong to.
     *
     * A rebroadcast direct message is opaque — one byte of recipient
     * hash is all it offers — so the caller narrows by time and credits
     * the repeat only when exactly one row fits.
     */
    @Query(
        "SELECT * FROM messages WHERE selfKey = :selfKey AND kind = 'dm' AND outgoing = 1 " +
            "AND receivedAt >= :sinceMillis ORDER BY receivedAt DESC LIMIT 8",
    )
    suspend fun recentOutgoingDms(selfKey: String, sinceMillis: Long): List<MessageEntity>

    /** Store the accumulated repeat set for one message. */
    @Query(
        "UPDATE messages SET repeatHopsHex = :hopsHex, repeatHashWidth = :width " +
            "WHERE id = :id",
    )
    suspend fun setRepeats(id: Long, hopsHex: String, width: Int): Int

    /**
     * The most recent inbound message from this peer whose arrival route
     * we know — the basis for "reply the way they reached me".
     */
    @Query(
        "SELECT * FROM messages WHERE selfKey = :selfKey AND kind = :kind " +
            "AND peerKey = :peerKey AND outgoing = 0 AND arrivalPathHex IS NOT NULL " +
            "ORDER BY receivedAt DESC LIMIT 1",
    )
    fun latestArrival(selfKey: String, kind: String, peerKey: String): Flow<MessageEntity?>

    @Query("UPDATE messages SET reactionsJson = :json WHERE id = :id")
    suspend fun setReactions(id: Long, json: String?)

    /** Newest-first window used to resolve a reaction's target hash. */
    @Query(
        "SELECT * FROM messages WHERE selfKey = :selfKey AND kind = :kind AND peerKey = :peerKey " +
            "ORDER BY timestamp DESC, receivedAt DESC LIMIT :limit",
    )
    suspend fun recentOnce(
        selfKey: String,
        kind: String,
        peerKey: String,
        limit: Int,
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE selfKey = :selfKey AND kind = :kind AND peerKey = :peerKey")
    suspend fun clearThread(selfKey: String, kind: String, peerKey: String)

    @Query("DELETE FROM messages WHERE selfKey = :selfKey")
    suspend fun clearAll(selfKey: String)

    /** Retention: drop anything older than [cutoffSeconds]. */
    @Query("DELETE FROM messages WHERE selfKey = :selfKey AND timestamp < :cutoffSeconds")
    suspend fun deleteOlderThan(selfKey: String, cutoffSeconds: Long): Int

    /** Retention: same, for one thread. */
    @Query(
        "DELETE FROM messages WHERE selfKey = :selfKey AND kind = :kind " +
            "AND peerKey = :peerKey AND timestamp < :cutoffSeconds",
    )
    suspend fun deleteThreadOlderThan(
        selfKey: String,
        kind: String,
        peerKey: String,
        cutoffSeconds: Long,
    ): Int

    /**
     * Retention: keep only the newest [keep] rows of one thread.
     * Ordered by receivedAt (local arrival), not timestamp — an inbound
     * message carries a SENDER-CLAIMED time, so trimming by it would let
     * a peer with a wrong (or chosen) clock decide which of your
     * messages survive.
     */
    @Query(
        "DELETE FROM messages WHERE selfKey = :selfKey AND kind = :kind AND peerKey = :peerKey " +
            "AND id NOT IN (SELECT id FROM messages WHERE selfKey = :selfKey AND kind = :kind " +
            "AND peerKey = :peerKey ORDER BY receivedAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun trimThreadTo(selfKey: String, kind: String, peerKey: String, keep: Int): Int

    /** Distinct threads with at least one row, for a retention sweep. */
    @Query("SELECT DISTINCT kind, peerKey FROM messages WHERE selfKey = :selfKey")
    suspend fun threadKeys(selfKey: String): List<ThreadKey>

    @Query("SELECT COUNT(*) FROM messages WHERE selfKey = :selfKey")
    suspend fun countAll(selfKey: String): Int

    /**
     * Distinct sender names seen on a channel, newest first.
     *
     * NOT a membership list and the UI must never present it as one: a
     * channel message carries no sender key (MESHCORE_PROTOCOL §9), so
     * this is "names that have appeared", which anyone holding the PSK
     * can add to at will.
     */
    @Query(
        "SELECT senderName AS name, COUNT(*) AS messageCount, MAX(receivedAt) AS lastSeenAt " +
            "FROM messages WHERE selfKey = :selfKey AND kind = 'ch' AND peerKey = :channelIdx " +
            "AND senderName IS NOT NULL AND senderName != '' AND outgoing = 0 " +
            "GROUP BY senderName ORDER BY lastSeenAt DESC",
    )
    suspend fun channelSenders(selfKey: String, channelIdx: String): List<ChannelSender>
}

@Dao
interface ContactDao {
    @Upsert
    suspend fun upsert(contact: ContactEntity)

    @Upsert
    suspend fun upsertAll(contacts: List<ContactEntity>)

    @Query("SELECT * FROM contacts WHERE selfKey = :selfKey ORDER BY name COLLATE NOCASE")
    fun all(selfKey: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE selfKey = :selfKey")
    suspend fun allOnce(selfKey: String): List<ContactEntity>

    @Query("DELETE FROM contacts WHERE selfKey = :selfKey AND keyHex = :keyHex")
    suspend fun delete(selfKey: String, keyHex: String)

    @Query("UPDATE contacts SET unread = unread + 1, lastMessageAt = :at WHERE selfKey = :selfKey AND keyHex = :keyHex")
    suspend fun bumpUnread(selfKey: String, keyHex: String, at: Long)

    @Query("UPDATE contacts SET unread = 0 WHERE selfKey = :selfKey AND keyHex = :keyHex")
    suspend fun clearUnread(selfKey: String, keyHex: String)

    @Query("UPDATE contacts SET lastMessageAt = :at WHERE selfKey = :selfKey AND keyHex = :keyHex")
    suspend fun touchLastMessage(selfKey: String, keyHex: String, at: Long)
}

@Dao
interface ChannelDao {
    @Upsert
    suspend fun upsert(channel: ChannelEntity)

    @Query("SELECT * FROM channels WHERE selfKey = :selfKey ORDER BY idx")
    fun all(selfKey: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE selfKey = :selfKey ORDER BY idx")
    suspend fun allOnce(selfKey: String): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE selfKey = :selfKey AND idx = :idx")
    suspend fun byIdx(selfKey: String, idx: Int): ChannelEntity?

    @Query("DELETE FROM channels WHERE selfKey = :selfKey AND idx = :idx")
    suspend fun delete(selfKey: String, idx: Int)

    @Query("DELETE FROM channels WHERE selfKey = :selfKey AND idx NOT IN (:liveIndices)")
    suspend fun deleteAbsent(selfKey: String, liveIndices: List<Int>)

    @Query("UPDATE channels SET unread = unread + 1, lastMessageAt = :at WHERE selfKey = :selfKey AND idx = :idx")
    suspend fun bumpUnread(selfKey: String, idx: Int, at: Long)

    @Query("UPDATE channels SET unread = 0 WHERE selfKey = :selfKey AND idx = :idx")
    suspend fun clearUnread(selfKey: String, idx: Int)

    @Query("UPDATE channels SET lastMessageAt = :at WHERE selfKey = :selfKey AND idx = :idx")
    suspend fun touchLastMessage(selfKey: String, idx: Int, at: Long)
}

@Dao
interface PathHistoryDao {
    @Upsert
    suspend fun upsert(path: PathHistoryEntity)

    @Query(
        "SELECT * FROM path_history WHERE selfKey = :selfKey AND contactKey = :contactKey " +
            "ORDER BY lastWorkedAt DESC, successes DESC",
    )
    fun forContact(selfKey: String, contactKey: String): Flow<List<PathHistoryEntity>>

    @Query("SELECT * FROM path_history WHERE selfKey = :selfKey AND contactKey = :contactKey AND pathHex = :pathHex")
    suspend fun get(selfKey: String, contactKey: String, pathHex: String): PathHistoryEntity?

    @Query("DELETE FROM path_history WHERE selfKey = :selfKey AND contactKey = :contactKey AND pathHex = :pathHex")
    suspend fun delete(selfKey: String, contactKey: String, pathHex: String)

    @Query("DELETE FROM path_history WHERE selfKey = :selfKey AND contactKey = :contactKey")
    suspend fun clear(selfKey: String, contactKey: String)

    /** Every row for this radio — the repair pass reads them all once. */
    @Query("SELECT * FROM path_history WHERE selfKey = :selfKey")
    suspend fun allOnce(selfKey: String): List<PathHistoryEntity>

    /**
     * Drop everything past the best [keep] paths for one contact.
     *
     * Ranked the way the sheet ranks them, with one addition: the FLOOD
     * route (empty pathHex) is pinned first and so is never pruned. It
     * is the route that works when every learned one has gone stale —
     * dropping it because it has no delivery record yet would remove the
     * only fallback.
     */
    @Query(
        "DELETE FROM path_history WHERE selfKey = :selfKey AND contactKey = :contactKey " +
            "AND pathHex NOT IN (" +
            "  SELECT pathHex FROM path_history " +
            "  WHERE selfKey = :selfKey AND contactKey = :contactKey " +
            "  ORDER BY (pathHex = '') DESC, lastWorkedAt DESC, successes DESC, lastUsedAt DESC " +
            "  LIMIT :keep)",
    )
    suspend fun prune(selfKey: String, contactKey: String, keep: Int)
}

@Dao
interface DiscoveredDao {
    @Upsert
    suspend fun upsert(node: DiscoveredEntity)

    @Query("SELECT * FROM discovered WHERE selfKey = :selfKey ORDER BY lastHeardAt DESC")
    fun all(selfKey: String): Flow<List<DiscoveredEntity>>

    @Query("SELECT * FROM discovered WHERE selfKey = :selfKey AND keyHex = :keyHex")
    suspend fun get(selfKey: String, keyHex: String): DiscoveredEntity?

    @Query("DELETE FROM discovered WHERE selfKey = :selfKey AND keyHex = :keyHex")
    suspend fun delete(selfKey: String, keyHex: String)

    @Query("DELETE FROM discovered WHERE selfKey = :selfKey")
    suspend fun clear(selfKey: String)

    /** Drop anything that has since become a real contact. */
    @Query("DELETE FROM discovered WHERE selfKey = :selfKey AND keyHex IN (:contactKeys)")
    suspend fun deleteKnown(selfKey: String, contactKeys: List<String>)
}
