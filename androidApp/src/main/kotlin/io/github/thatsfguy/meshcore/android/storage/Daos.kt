package io.github.thatsfguy.meshcore.android.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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

    @Query(
        "SELECT * FROM messages WHERE selfKey = :selfKey GROUP BY kind, peerKey " +
            "HAVING MAX(timestamp) ORDER BY MAX(timestamp) DESC",
    )
    fun latestPerThread(selfKey: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET status = :status WHERE selfKey = :selfKey AND ackHash = :ackHash")
    suspend fun updateStatusByAck(selfKey: String, ackHash: Long, status: Int): Int

    @Query("UPDATE messages SET status = :status, ackHash = :ackHash WHERE id = :id")
    suspend fun updateResult(id: Long, status: Int, ackHash: Long?)

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

    @Query("DELETE FROM messages WHERE selfKey = :selfKey AND kind = :kind AND peerKey = :peerKey")
    suspend fun clearThread(selfKey: String, kind: String, peerKey: String)

    @Query("DELETE FROM messages WHERE selfKey = :selfKey")
    suspend fun clearAll(selfKey: String)
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
