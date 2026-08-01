package io.github.thatsfguy.meshcore.android.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One (kind, peerKey) thread identity — the unit retention works on. */
data class ThreadKey(val kind: String, val peerKey: String)

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

    /** Newest [limit] messages of a thread (paging window). */
    // Everything EXCEPT CLI replies (txt_type 1). Those are remote-admin
    // console output, not conversation, and pouring `get radio` into the
    // chat is noise. They stay in the table — the admin screen's
    // `thread()` above reads them unfiltered.
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
    suspend fun failStalePending(
        selfKey: String,
        olderThanMillis: Long,
        pending: Int = MessageStatus.Sent.ordinal,
        failed: Int = MessageStatus.Failed.ordinal,
    ): Int

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
}

@Dao
interface ContactDao {
    @Upsert
    suspend fun upsert(contact: ContactEntity)

    @Upsert
    suspend fun upsertAll(contacts: List<ContactEntity>)

    @Query("SELECT * FROM contacts WHERE selfKey = :selfKey ORDER BY name COLLATE NOCASE")
    fun all(selfKey: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE selfKey = :selfKey AND keyHex = :keyHex")
    suspend fun byKey(selfKey: String, keyHex: String): ContactEntity?

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
