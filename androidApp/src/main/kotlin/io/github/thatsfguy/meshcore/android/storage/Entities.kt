package io.github.thatsfguy.meshcore.android.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One message row — direct or channel. All rows are scoped by
 * [selfKey] (the attached radio's pubkey hex) so switching radios
 * switches history, mirroring MeshCore Open's per-node stores.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["selfKey", "kind", "peerKey", "timestamp"]),
        // Channel dedup: the same message arrives via companion sync AND
        // the RX log; insert with IGNORE bounces the duplicate.
        Index(value = ["selfKey", "contentKey"], unique = true),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val selfKey: String,
    /** "dm" or "ch". */
    val kind: String,
    /** DM: contact pubkey hex. Channel: the channel index as a string. */
    val peerKey: String,
    /** Channel messages only — UNAUTHENTICATED display name. */
    val senderName: String?,
    val text: String,
    /** Epoch seconds (sender-claimed for inbound). */
    val timestamp: Long,
    /** Epoch millis when this row was written — stable local ordering. */
    val receivedAt: Long,
    val outgoing: Boolean,
    /** MessageStatus ordinal. */
    val status: Int,
    /** Radio's expected-ack hash for outgoing messages. */
    val ackHash: Long?,
    /** Dedup key (channel messages); null for DMs. */
    val contentKey: String?,
    val snr: Double?,
    /** txt_type — CLI replies (1) render differently in repeater admin. */
    val txtType: Int = 0,
    /** Send attempts made so far (retry service). */
    val attempts: Int = 0,
    /**
     * Reactions attached to this message, as a JSON object of
     * emoji -> count. Null/blank when there are none.
     *
     * Reactions arrive as separate `r:HHHH:II` text messages matched by
     * a 16-bit hash (see Reactions), so this is a best-effort attachment,
     * not an authenticated fact about who reacted.
     */
    val reactionsJson: String? = null,
    /**
     * Hops this message travelled. -1 (MeshCoreEngine.FLOOD_HOPS) means
     * it flooded; null means unknown — outgoing rows, or anything
     * received before this was recorded.
     */
    val hops: Int? = null,
)

enum class MessageStatus { Pending, Sent, Delivered, Failed }

/**
 * Cached contact record (the radio owns the authoritative list; this
 * cache renders instantly on launch and feeds the map offline).
 */
@Entity(
    tableName = "contacts",
    primaryKeys = ["selfKey", "keyHex"],
)
data class ContactEntity(
    val selfKey: String,
    val keyHex: String,
    val name: String,
    val type: Int,
    val flags: Int,
    val pathLen: Int,
    val latitude: Double?,
    val longitude: Double?,
    val lastSeen: Long,       // advert timestamp, epoch seconds
    val lastModified: Long,
    val unread: Int = 0,
    val lastMessageAt: Long = 0,
)

/**
 * Cached channel slot. The PSK is SEALED through [SecretVault] before
 * it touches this row — never plaintext at rest (SCOPE.md security
 * carry-over).
 */
@Entity(
    tableName = "channels",
    primaryKeys = ["selfKey", "idx"],
)
data class ChannelEntity(
    val selfKey: String,
    val idx: Int,
    val name: String,
    val pskSealed: ByteArray,
    val unread: Int = 0,
    val lastMessageAt: Long = 0,
) {
    override fun equals(other: Any?): Boolean =
        other is ChannelEntity && selfKey == other.selfKey && idx == other.idx &&
            name == other.name && pskSealed.contentEquals(other.pskSealed) &&
            unread == other.unread && lastMessageAt == other.lastMessageAt

    override fun hashCode(): Int = selfKey.hashCode() * 31 + idx
}

/**
 * A path to a contact we have observed or configured, with its
 * success/failure record — what makes "Auto" routing informed and lets
 * the routing sheet rank known routes (mirrors the reference client's
 * path history).
 */
@Entity(
    tableName = "path_history",
    primaryKeys = ["selfKey", "contactKey", "pathHex"],
)
data class PathHistoryEntity(
    val selfKey: String,
    val contactKey: String,
    /** Hop hashes, hex; empty string = the flood route. */
    val pathHex: String,
    val hops: Int,
    val successes: Int = 0,
    val failures: Int = 0,
    /** Epoch millis of the last confirmed delivery over this path. */
    val lastWorkedAt: Long = 0,
    val lastUsedAt: Long = 0,
)

/**
 * A node whose signature-verified advert we heard over the air but that
 * is NOT in the radio's contact list — the discovery inbox. Keeping the
 * advert blob means "Add" can import it immediately instead of waiting
 * to hear the node again.
 */
@Entity(tableName = "discovered", primaryKeys = ["selfKey", "keyHex"])
data class DiscoveredEntity(
    val selfKey: String,
    val keyHex: String,
    val name: String,
    val type: Int,
    val latitude: Double?,
    val longitude: Double?,
    val firstHeardAt: Long,
    val lastHeardAt: Long,
    val snr: Double,
    val rssi: Int,
    /** Raw advert payload, hex — replayed to CMD_IMPORT_CONTACT. */
    val advertHex: String,
)
