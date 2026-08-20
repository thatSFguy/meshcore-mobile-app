package io.github.thatsfguy.meshcore.android.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.thatsfguy.meshcore.presentation.NodeListItem
import io.github.thatsfguy.meshcore.protocol.PathHistoryHygiene

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
    /**
     * The route this message ARRIVED on, hex, in travel order — hop 0 is
     * the repeater nearest the sender, the last hop is the one that
     * reached us.
     *
     * Null means the route isn't known, which is NOT the same as
     * "direct" — the message frame carries a hop count only, so this is
     * filled from the RX-log packet (exactly for channel messages,
     * by correlation for direct ones — see HeardVia).
     *
     * ⚠ This is an ARRIVAL path. Reverse it hop-by-hop before ever
     * offering it as a route to reply on.
     */
    val arrivalPathHex: String? = null,
    val arrivalHashWidth: Int? = null,
    /**
     * Outgoing messages: nodes heard re-broadcasting THIS message,
     * accumulated as concatenated hop hashes (see `MessageRepeats`).
     *
     * Deliberately not reusing arrivalPath*: that column means "the
     * route this reached me by", which an outgoing row does not have.
     * One field carrying two opposite directions is how a screen ends
     * up confidently wrong.
     */
    val repeatHopsHex: String? = null,
    val repeatHashWidth: Int? = null,
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
    override val keyHex: String,
    override val name: String,
    val type: Int,
    override val flags: Int,
    override val pathLen: Int,
    val latitude: Double?,
    val longitude: Double?,
    override val lastSeen: Long,       // advert timestamp, epoch seconds
    val lastModified: Long,
    override val unread: Int = 0,
    override val lastMessageAt: Long = 0,
    /**
     * The BLE address this node reported when it last took `start ota`.
     *
     * A repeater is reached over the mesh, so the app never otherwise
     * learns its BLE address — and the moment it enters update mode is
     * the moment the mesh stops being a way to reach it. Keeping the
     * address it announced (`"OK - mac: …"`) is what makes a stuck node
     * recoverable later, from a different screen, on a different day.
     *
     * **A durable fact about the hardware, like [boardName].** A radio's
     * BLE address does not change — not across a reboot, a firmware
     * update, or a reflash over USB — so this is only ever added to, and
     * is never cleared to express that something has happened to the
     * node. What the node is *doing* lives in [updateModeSince].
     */
    val otaAddress: String? = null,
    /** When [otaAddress] was reported, epoch seconds. */
    val otaAnnouncedAt: Long = 0,
    /**
     * When this node last told us it had entered update mode, epoch
     * seconds; 0 when it is not believed to be in update mode.
     *
     * A state, kept deliberately separate from [otaAddress] — which is a
     * fact about the hardware and outlives every state it was learned
     * in. Deriving the state from the address instead made the claim
     * permanent: nothing cleared the address, because nothing should, so
     * a node that entered update mode once was described as being in it
     * for ever — through a reboot, a completed update, and a reflash
     * over USB.
     *
     * Set and cleared by named events, never inferred:
     *
     * - **set** when the node answers `start ota` with `"OK - mac: …"`;
     * - **cleared** when a transfer to it finishes, when it accepts a
     *   restart out of its bootloader, and when the operator says so —
     *   a node reflashed over USB is invisible from here, and that is
     *   the one case only a human can report.
     *
     * There is deliberately no timeout. `start ota` leaves the node
     * running and repeating, so it can sit advertising for days quite
     * legitimately, and ageing the flag out would be a guess wearing the
     * clothes of a fact.
     */
    val updateModeSince: Long = 0,
    /**
     * The `receivedAt` of the newest `start ota` reply this app has
     * acted on, epoch millis.
     *
     * The console thread is PERSISTED, so "the node said `OK - mac: …`"
     * is not a thing that happens once — it is a row that sits in the
     * database for ever and is re-read on every render. Setting the flag
     * from the presence of that row would be the same defect as setting
     * it from the presence of an address, one table along: permanent,
     * and immune to being corrected.
     *
     * This is the watermark that turns the row back into an event. A
     * reply only sets [updateModeSince] if it is newer than this, and
     * anything that clears the flag stamps this to now — so a correction
     * cannot be undone by history.
     *
     * `receivedAt` rather than `timestamp` deliberately: the latter is
     * sender-claimed, and a node with a wrong clock could otherwise
     * write a reply that is permanently "newer" than any correction.
     */
    val otaReplyHandledAt: Long = 0,
    /**
     * What the node last said it was: `getManufacturerName()` from its
     * `board` reply, and its `ver` reply.
     *
     * Remembered rather than asked for on demand, because the one time
     * this is needed most — choosing firmware for a node stuck in update
     * mode — the node is in its bootloader and cannot answer anything.
     */
    val boardName: String? = null,
    val firmwareVersion: String? = null,
) : NodeListItem

/**
 * Carry the app's own knowledge across a radio-authoritative refresh.
 *
 * The radio owns a contact's name, type, flags, path and position, and
 * its list is re-read on every connection. Everything else on this row
 * was learned **here** — from a console reply, from a transfer, from
 * the operator — and the radio has no idea any of it exists. Rebuilding
 * the row from the radio alone therefore does not lose incidental data;
 * it loses precisely the fields that are stored because they cannot be
 * fetched again.
 *
 * That is not hypothetical. The refresh preserved `unread` and
 * `lastMessageAt` and nothing else, so every reconnection silently
 * cleared the announced update address, the update-mode flag and its
 * watermark, the board name and the firmware version. The failure it
 * produced is the worst-shaped one available: a repeater announced
 * `OK - mac: FF:5C:EF:28:2A:92`, the app stored it and showed it, the
 * transfer failed, the companion radio reconnected — and the recovery
 * dialog for a node now sitting in its bootloader, unable to answer
 * anything, said no address had ever been recorded.
 *
 * Newer wins on both sides: this runs against a row read a moment
 * earlier, and the console watcher can write between the read and the
 * upsert.
 */
fun ContactEntity.keepingLocalFacts(previous: ContactEntity?): ContactEntity {
    if (previous == null) return this
    return copy(
        unread = if (unread != 0) unread else previous.unread,
        lastMessageAt = maxOf(lastMessageAt, previous.lastMessageAt),
        otaAddress = otaAddress ?: previous.otaAddress,
        otaAnnouncedAt = maxOf(otaAnnouncedAt, previous.otaAnnouncedAt),
        updateModeSince = maxOf(updateModeSince, previous.updateModeSince),
        otaReplyHandledAt = maxOf(otaReplyHandledAt, previous.otaReplyHandledAt),
        boardName = boardName ?: previous.boardName,
        firmwareVersion = firmwareVersion ?: previous.firmwareVersion,
    )
}

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
    /**
     * HOPS, not bytes. A hop hash is 1–4 bytes wide depending on the
     * mesh, so `pathHex.length / 2` is a byte count — storing that is
     * how a 2-hop route came to be displayed as "4 hop(s)".
     */
    val hops: Int,
    /**
     * Bytes per hop hash for this path, or
     * [PathHistoryHygiene.WIDTH_UNKNOWN] for rows written before the
     * column existed. The width cannot be recovered from the hex alone,
     * so unknown rows are repaired against the mesh's width once
     * DEVICE_INFO arrives — and deleted if they still make no sense.
     */
    val hashWidth: Int = PathHistoryHygiene.WIDTH_UNKNOWN,
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
