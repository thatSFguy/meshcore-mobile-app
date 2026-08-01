package io.github.thatsfguy.meshcore.android.storage

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.engine.MeshEvent
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.protocol.ReactionCounts
import io.github.thatsfguy.meshcore.protocol.Reactions
import io.github.thatsfguy.meshcore.protocol.Retention
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
     * Content keys of channel reactions already accounted for.
     *
     * A channel reaction reaches us up to three times: once as our own
     * local application when we send it, then as the radio's echo of our
     * own message, then again via the RX log. Ordinary messages dedup on
     * the unique (selfKey, contentKey) index, but a reaction never
     * becomes a row, so it needs its own guard — without it one tap
     * counted as three.
     */
    private val seenReactionKeys = object : LinkedHashSet<String>() {
        fun remember(key: String): Boolean {
            val fresh = add(key)
            while (size > MAX_SEEN_REACTIONS) remove(first())
            return fresh
        }
    }

    /** Called before sending our own channel reaction, so its echo is a no-op. */
    @Synchronized
    fun noteOwnReaction(contentKey: String) {
        seenReactionKeys.remember(contentKey)
    }

    @Synchronized
    private fun firstSightOfReaction(contentKey: String?): Boolean =
        contentKey == null || seenReactionKeys.remember(contentKey)

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
                    // Remember every path the radio reports so the
                    // routing sheet can rank routes it has actually seen.
                    for (c in contacts.values) {
                        val len = c.pathLen
                        if (len in 1..64 && len <= c.path.size) {
                            rememberPath(self, c.publicKeyHex, c.path.copyOfRange(0, len))
                        }
                    }
                    // Anything that became a contact leaves the inbox.
                    db.discovered().deleteKnown(self, contacts.keys.toList())
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
                // CLI replies can echo secrets (`get guest.password`,
                // `get prv.key`); redact before they become durable rows.
                val storedText = if (event.txtType == 1) {
                    DiagnosticsLog.redact(event.text)
                } else {
                    event.text
                }
                // A reaction is an ordinary text message by the time it
                // reaches us; attach it to its target instead of letting
                // "r:1a2b:00" land in the thread as a line of text.
                if (event.txtType != 1 && applyReaction(self, KIND_DM, peer, storedText, null)) {
                    return
                }
                db.messages().insert(
                    MessageEntity(
                        selfKey = self,
                        kind = KIND_DM,
                        peerKey = peer,
                        // Room posts name their author; a plain DM has no
                        // sender name (the thread IS the sender).
                        senderName = event.roomAuthorLabel,
                        text = storedText,
                        timestamp = event.timestamp,
                        receivedAt = System.currentTimeMillis(),
                        outgoing = false,
                        status = MessageStatus.Delivered.ordinal,
                        ackHash = null,
                        contentKey = null,
                        snr = event.snr,
                        txtType = event.txtType,
                        hops = event.hops,
                    ),
                )
                if (activeThread != "$KIND_DM|$peer") {
                    db.contacts().bumpUnread(self, peer, System.currentTimeMillis())
                    // CLI replies are console output, not messages.
                    if (event.txtType != 1) {
                        onNewMessage?.invoke(KIND_DM, peer, event.roomAuthorLabel, event.text)
                    }
                }
            }

            is MeshEvent.ChannelMessageReceived -> {
                if (applyReaction(
                        self,
                        KIND_CHANNEL,
                        event.channelIndex.toString(),
                        event.text,
                        event.senderName,
                        event.contentKey,
                    )
                ) {
                    return
                }
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
                        snr = event.snr,
                        hops = event.hops,
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

            is MeshEvent.VerifiedAdvertHeard -> {
                val keyHex = event.advert.publicKeyHex
                // Only nodes the radio hasn't accepted as contacts.
                if (engine.contacts.value.containsKey(keyHex)) return
                val now = System.currentTimeMillis()
                val prev = db.discovered().get(self, keyHex)
                db.discovered().upsert(
                    DiscoveredEntity(
                        selfKey = self,
                        keyHex = keyHex,
                        name = event.advert.name,
                        type = event.advert.type,
                        latitude = event.advert.latitude,
                        longitude = event.advert.longitude,
                        firstHeardAt = prev?.firstHeardAt ?: now,
                        lastHeardAt = now,
                        snr = event.snr,
                        rssi = event.rssi,
                        advertHex = event.payload.joinToString("") { "%02x".format(it) },
                    ),
                )
            }

            is MeshEvent.MessageDelivered -> {
                db.messages().updateStatusByAck(self, event.ackHash, MessageStatus.Delivered.ordinal)
                scorePath(event.ackHash, delivered = true)
            }

            else -> Unit
        }
    }

    // ------------------------------------------------------------------
    // Reactions
    // ------------------------------------------------------------------

    /**
     * If [text] is a reaction, attach it to the message it targets and
     * return true (the caller then skips inserting a message row).
     *
     * Matching is by the format's 16-bit hash over a recent window of the
     * thread, so it is a best guess: a collision attaches the emoji to
     * the wrong message, and a reaction to something older than the
     * window won't match at all. Both failure modes are visible rather
     * than silent — an unmatched reaction is still stored, and the UI
     * renders it as a reaction chip instead of raw wire text.
     */
    private suspend fun applyReaction(
        self: String,
        kind: String,
        peerKey: String,
        text: String,
        senderName: String?,
        contentKey: String? = null,
    ): Boolean {
        val reaction = Reactions.parse(text) ?: return false
        // Consume repeats (our own echo, or double delivery) without
        // counting them again — but still consume, so the wire text
        // never lands in the thread as a message.
        if (!firstSightOfReaction(contentKey)) return true
        val recent = db.messages().recentOnce(self, kind, peerKey, REACTION_SEARCH_WINDOW)
        val target = recent.firstOrNull { candidate ->
            // Their reaction targets OUR view of the message: for a
            // channel the hash includes the message's own sender name,
            // for a DM it doesn't.
            val hashSender = if (kind == KIND_CHANNEL) candidate.senderName.orEmpty() else null
            Reactions.targetHash(candidate.timestamp, hashSender, candidate.text) ==
                reaction.targetHash
        } ?: return false
        addReaction(target, reaction.emoji)
        return true
    }

    /** Merge one emoji into a message's reaction counts. */
    suspend fun addReaction(target: MessageEntity, emoji: String) {
        val counts = ReactionCounts.decode(target.reactionsJson).toMutableMap()
        counts[emoji] = (counts[emoji] ?: 0) + 1
        db.messages().setReactions(target.id, ReactionCounts.encode(counts))
    }

    /** The wire text for reacting to [target], or null if unencodable. */
    fun reactionWireText(target: MessageEntity, emoji: String, isChannel: Boolean): String? {
        val hashSender = if (isChannel) target.senderName.orEmpty() else null
        val hash = Reactions.targetHash(target.timestamp, hashSender, target.text)
        return Reactions.encode(hash, emoji)
    }

    suspend fun messageById(id: Long): MessageEntity? = db.messages().byId(id)

    suspend fun deleteMessage(id: Long) = db.messages().deleteById(id)

    // ------------------------------------------------------------------
    // Retention (PARITY §3) — history that isn't kept can't leak
    // ------------------------------------------------------------------

    /**
     * Apply [defaultPolicy] to every thread, with [channelOverrides]
     * taking precedence for the channel slots that have one. Returns how
     * many rows were removed.
     *
     * Deliberately does nothing when the policy is unbounded: a sweep
     * that "keeps everything" should not be reading every thread key on
     * every launch.
     */
    suspend fun applyRetention(
        defaultPolicy: Retention.Policy,
        channelOverrides: Map<Int, Retention.Policy> = emptyMap(),
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Int {
        val key = selfKey
        if (key.isEmpty()) return 0
        if (!defaultPolicy.isBounded && channelOverrides.values.none { it.isBounded }) return 0

        var removed = 0
        for (thread in db.messages().threadKeys(key)) {
            val policy = channelOverrides[thread.peerKey.toIntOrNull()]
                ?.takeIf { thread.kind == KIND_CHANNEL }
                ?: defaultPolicy
            if (!policy.isBounded) continue

            policy.cutoffSeconds(nowSeconds)?.let { cutoff ->
                removed += db.messages()
                    .deleteThreadOlderThan(key, thread.kind, thread.peerKey, cutoff)
            }
            policy.keepPerThread()?.let { keep ->
                removed += db.messages().trimThreadTo(key, thread.kind, thread.peerKey, keep)
            }
        }
        return removed
    }

    suspend fun messageCount(): Int =
        if (selfKey.isEmpty()) 0 else db.messages().countAll(selfKey)

    // ------------------------------------------------------------------
    // Purge (PARITY §1)
    // ------------------------------------------------------------------

    /** What a purge removed, so the UI can report facts rather than "done". */
    data class PurgeCounts(
        val messages: Int = 0,
        val contacts: Int = 0,
        val channels: Int = 0,
        val paths: Int = 0,
        val discovered: Int = 0,
    )

    /**
     * Delete every local row for the attached radio.
     *
     * This is the local mirror ONLY. The radio keeps its own contact
     * list, channel slots and identity — purging here does not reach
     * them, and the UI must not imply it does. Nor does it touch the
     * Keystore; secrets are handled separately so that "forget my
     * history" and "forget my keys" stay distinct decisions.
     */
    suspend fun purgeLocal(): PurgeCounts {
        val key = selfKey
        if (key.isEmpty()) return PurgeCounts()
        val counts = PurgeCounts(
            messages = db.messages().countAll(key),
            contacts = db.contacts().allOnce(key).size,
            channels = db.channels().allOnce(key).size,
            paths = 0,
            discovered = 0,
        )
        db.messages().clearAll(key)
        for (contact in db.contacts().allOnce(key)) db.contacts().delete(key, contact.keyHex)
        for (channel in db.channels().allOnce(key)) db.channels().delete(key, channel.idx)
        db.discovered().clear(key)
        return counts
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

    /**
     * Send a direct message with automatic retry — LoRa loses first
     * attempts routinely, so a single failed ACK is not a failed
     * message. Each attempt carries an incrementing `attempt` byte (the
     * receiving radio dedups on it), waits the radio's own suggested
     * ACK timeout, and backs off before retrying. Path success/failure
     * is scored per attempt so the routing sheet learns which routes
     * actually work.
     */
    suspend fun sendDirectWithRetry(
        engine: MeshCoreEngine,
        peerKeyHex: String,
        text: String,
        maxAttempts: Int = 3,
    ) {
        val self = selfKey
        val key = peerKeyHex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        if (key.size < 6) return
        val timestamp = System.currentTimeMillis() / 1000
        val rowId = recordOutgoingDm(peerKeyHex, text, timestamp)

        // The path in force right now — what we credit or blame.
        val contact = engine.contacts.value[peerKeyHex]
        val pathHex = if (contact != null && contact.storedPath.isNotEmpty()) {
            contact.storedPath.joinToString("") { "%02x".format(it) }
        } else {
            ""
        }

        var attempt = 0
        while (attempt < maxAttempts) {
            val sent = runCatching {
                engine.sendDirectMessage(key, text, attempt = attempt, timestampSeconds = timestamp)
            }.getOrNull()
            if (sent == null) {
                // The radio didn't even accept it — no point retrying fast.
                db.messages().updateResult(rowId, MessageStatus.Failed.ordinal, null)
                return
            }
            db.messages().updateResult(rowId, MessageStatus.Sent.ordinal, sent.ackHash)
            db.messages().setAttempts(rowId, attempt + 1)

            // Trust the radio's own airtime-derived timeout, with a floor.
            val timeout = sent.timeoutMs.coerceIn(3_000L, 60_000L)
            if (engine.awaitDelivery(sent.ackHash, timeout)) {
                db.messages().updateResult(rowId, MessageStatus.Delivered.ordinal, sent.ackHash)
                if (pathHex.isNotEmpty() || attempt == 0) scorePathDirect(peerKeyHex, pathHex, true)
                return
            }
            scorePathDirect(peerKeyHex, pathHex, false)
            attempt++
            if (attempt < maxAttempts) kotlinx.coroutines.delay(1_000L * attempt)
        }
        db.messages().updateResult(rowId, MessageStatus.Failed.ordinal, null)
    }

    /** Score a path directly (retry path knows its route without an ack map). */
    private suspend fun scorePathDirect(contactKey: String, pathHex: String, delivered: Boolean) {
        val row = db.paths().get(selfKey, contactKey, pathHex) ?: PathHistoryEntity(
            selfKey = selfKey, contactKey = contactKey, pathHex = pathHex,
            hops = pathHex.length / 2,
        )
        db.paths().upsert(
            if (delivered) {
                row.copy(successes = row.successes + 1, lastWorkedAt = System.currentTimeMillis())
            } else {
                row.copy(failures = row.failures + 1)
            },
        )
    }

    // --- Path attribution -------------------------------------------
    // Which path each in-flight ack was sent over, so a delivery (or a
    // failure) can be credited to the route that carried it.
    private val ackPaths = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, String>>()

    /** Upsert a path we have seen or used for [contactKey]. */
    suspend fun rememberPath(self: String, contactKey: String, path: ByteArray) {
        val hex = path.joinToString("") { "%02x".format(it) }
        val existing = db.paths().get(self, contactKey, hex)
        db.paths().upsert(
            existing?.copy(lastUsedAt = System.currentTimeMillis())
                ?: PathHistoryEntity(
                    selfKey = self,
                    contactKey = contactKey,
                    pathHex = hex,
                    hops = path.size,
                    lastUsedAt = System.currentTimeMillis(),
                ),
        )
    }

    /** Tie an outbound ack hash to the path it went out on. */
    fun attributeAck(ackHash: Long, contactKey: String, pathHex: String) {
        ackPaths[ackHash] = contactKey to pathHex
    }

    /** Credit (or debit) the path an ack was sent over. */
    suspend fun scorePath(ackHash: Long, delivered: Boolean) {
        val (contactKey, pathHex) = ackPaths.remove(ackHash) ?: return
        val row = db.paths().get(selfKey, contactKey, pathHex) ?: PathHistoryEntity(
            selfKey = selfKey, contactKey = contactKey, pathHex = pathHex,
            hops = pathHex.length / 2,
        )
        db.paths().upsert(
            if (delivered) {
                row.copy(
                    successes = row.successes + 1,
                    lastWorkedAt = System.currentTimeMillis(),
                )
            } else {
                row.copy(failures = row.failures + 1)
            },
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

        /**
         * How far back a reaction may reach. The wire format carries no
         * thread position, only a 16-bit hash, so a wider window means
         * more chances to collide with an unrelated message; 200 covers
         * any realistic "react to something in view" while keeping the
         * collision odds negligible.
         */
        private const val REACTION_SEARCH_WINDOW = 200

        /** Bound on the echo-suppression set. */
        private const val MAX_SEEN_REACTIONS = 512

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
