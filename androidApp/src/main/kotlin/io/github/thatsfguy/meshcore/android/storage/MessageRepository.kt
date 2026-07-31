package io.github.thatsfguy.meshcore.android.storage

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.engine.MeshEvent
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.model.Contact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Persists engine events into Room, scoped by the attached radio's
 * pubkey. The engine holds live truth; this repository is the durable
 * mirror that survives restarts and disconnects.
 */
class MessageRepository(
    private val db: MeshCoreDatabase,
    private val secrets: SecretsRepository,
    private val scope: CoroutineScope,
) {
    /** The radio currently attached — set by the service on SELF_INFO. */
    @Volatile var selfKey: String = ""

    /** Thread the UI currently displays ("dm|<key>" / "ch|<idx>") —
     *  suppresses unread bumps for the open conversation. */
    @Volatile var activeThread: String? = null

    /**
     * Invoked for each genuinely-new inbound message that is NOT in the
     * open thread — after DB insert (so channel echo/duplicate delivery
     * is already filtered) — with (kind, peerKey, senderName, text).
     * The service posts the system notification from here. CLI replies
     * (txt_type != plain) never notify.
     */
    @Volatile var onNewMessage: ((String, String, String?, String) -> Unit)? = null

    fun start(engine: MeshCoreEngine) {
        scope.launch {
            engine.meshEvents.collect { event -> handle(engine, event) }
        }
        scope.launch {
            engine.contacts.collect { contacts ->
                val self = selfKey
                if (self.isNotEmpty() && contacts.isNotEmpty()) {
                    // Preserve local-only fields (unread, lastMessageAt)
                    // across the radio-authoritative refresh.
                    val existing = db.contacts().allOnce(self).associateBy { it.keyHex }
                    db.contacts().upsertAll(
                        contacts.values.map { c ->
                            val prev = existing[c.publicKeyHex]
                            c.toEntity(self).copy(
                                unread = prev?.unread ?: 0,
                                lastMessageAt = prev?.lastMessageAt ?: 0,
                            )
                        },
                    )
                }
            }
        }
        scope.launch {
            engine.channels.collect { channels ->
                val self = selfKey
                if (self.isEmpty()) return@collect
                for (ch in channels) persistChannel(self, ch)
                if (channels.isNotEmpty()) {
                    db.channels().deleteAbsent(self, channels.map { it.index })
                }
            }
        }
    }

    private suspend fun persistChannel(self: String, ch: Channel) {
        val sealed = secrets.sealPsk(ch.psk) ?: return // no keystore → no PSK at rest
        val prev = db.channels().byIdx(self, ch.index)
        db.channels().upsert(
            ChannelEntity(
                selfKey = self, idx = ch.index, name = ch.name, pskSealed = sealed,
                unread = prev?.unread ?: 0,
                lastMessageAt = prev?.lastMessageAt ?: 0,
            ),
        )
    }

    private suspend fun handle(engine: MeshCoreEngine, event: MeshEvent) {
        val self = selfKey
        if (self.isEmpty()) return
        when (event) {
            is MeshEvent.DirectMessageReceived -> {
                val peer = event.senderKeyHex ?: event.senderPrefixHex
                db.messages().insert(
                    MessageEntity(
                        selfKey = self,
                        kind = KIND_DM,
                        peerKey = peer,
                        senderName = null,
                        text = event.text,
                        timestamp = event.timestamp,
                        receivedAt = System.currentTimeMillis(),
                        outgoing = false,
                        status = MessageStatus.Delivered.ordinal,
                        ackHash = null,
                        contentKey = null,
                        snr = event.snr,
                        txtType = event.txtType,
                    ),
                )
                if (activeThread != "$KIND_DM|$peer") {
                    db.contacts().bumpUnread(self, peer, System.currentTimeMillis())
                    // CLI replies are console output, not messages.
                    if (event.txtType == 0) {
                        onNewMessage?.invoke(KIND_DM, peer, null, event.text)
                    }
                }
            }

            is MeshEvent.ChannelMessageReceived -> {
                val inserted = db.messages().insert(
                    MessageEntity(
                        selfKey = self,
                        kind = KIND_CHANNEL,
                        peerKey = event.channelIndex.toString(),
                        senderName = event.senderName,
                        text = event.text,
                        timestamp = event.timestamp,
                        receivedAt = System.currentTimeMillis(),
                        outgoing = false,
                        status = MessageStatus.Delivered.ordinal,
                        ackHash = null,
                        contentKey = event.contentKey,
                        snr = null,
                    ),
                )
                // insert == -1 → duplicate (sync + RX-log double delivery,
                // or the echo of our own outgoing message)
                if (inserted != -1L && activeThread != "$KIND_CHANNEL|${event.channelIndex}") {
                    db.channels().bumpUnread(self, event.channelIndex, System.currentTimeMillis())
                    onNewMessage?.invoke(
                        KIND_CHANNEL, event.channelIndex.toString(),
                        event.senderName, event.text,
                    )
                }
            }

            is MeshEvent.MessageDelivered -> {
                db.messages().updateStatusByAck(self, event.ackHash, MessageStatus.Delivered.ordinal)
            }

            else -> Unit
        }
    }

    /**
     * Optimistic outgoing DM row (status Pending) — insert BEFORE the
     * radio round-trip so the bubble appears instantly; resolve with
     * [markOutgoingResult] once the radio answers.
     */
    suspend fun recordOutgoingDm(
        peerKeyHex: String,
        text: String,
        timestamp: Long,
        txtType: Int = 0,
    ): Long {
        // Any outgoing traffic marks the node as interacted-with (drives
        // the Nodes tab's recency sort).
        db.contacts().touchLastMessage(selfKey, peerKeyHex, System.currentTimeMillis())
        return db.messages().insert(
            MessageEntity(
                selfKey = selfKey,
                kind = KIND_DM,
                peerKey = peerKeyHex,
                senderName = null,
                text = text,
                timestamp = timestamp,
                receivedAt = System.currentTimeMillis(),
                outgoing = true,
                status = MessageStatus.Pending.ordinal,
                ackHash = null,
                contentKey = null,
                snr = null,
                txtType = txtType,
            ),
        )
    }

    suspend fun markOutgoingResult(rowId: Long, accepted: Boolean, ackHash: Long?) {
        db.messages().updateResult(
            rowId,
            if (accepted) MessageStatus.Sent.ordinal else MessageStatus.Failed.ordinal,
            ackHash,
        )
    }

    /**
     * Optimistic outgoing channel row. [contentKey] MUST be the engine's
     * channelContentKey for the same (idx, timestamp, selfName, text) —
     * the radio echoes our own channel messages back through the sync
     * path, and the unique (selfKey, contentKey) index swallows that
     * echo only if the outgoing row is already there under the same key.
     */
    suspend fun recordOutgoingChannel(
        channelIndex: Int,
        selfName: String,
        text: String,
        timestamp: Long,
        contentKey: String,
    ): Long {
        db.channels().touchLastMessage(selfKey, channelIndex, System.currentTimeMillis())
        return db.messages().insert(
            MessageEntity(
                selfKey = selfKey,
                kind = KIND_CHANNEL,
                peerKey = channelIndex.toString(),
                senderName = selfName,
                text = text,
                timestamp = timestamp,
                receivedAt = System.currentTimeMillis(),
                outgoing = true,
                status = MessageStatus.Pending.ordinal,
                ackHash = null,
                contentKey = contentKey,
                snr = null,
            ),
        )
    }

    suspend fun markChannelResult(contentKey: String, accepted: Boolean) {
        db.messages().updateStatusByContentKey(
            selfKey, contentKey,
            if (accepted) MessageStatus.Sent.ordinal else MessageStatus.Failed.ordinal,
        )
    }

    companion object {
        const val KIND_DM = "dm"
        const val KIND_CHANNEL = "ch"

        fun Contact.toEntity(selfKey: String): ContactEntity = ContactEntity(
            selfKey = selfKey,
            keyHex = publicKeyHex,
            name = name,
            type = type,
            flags = flags,
            pathLen = pathLen,
            latitude = latitude,
            longitude = longitude,
            lastSeen = timestamp,
            lastModified = lastModified,
        )
    }
}
