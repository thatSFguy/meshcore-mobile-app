package io.github.thatsfguy.meshcore.android.storage

import io.github.thatsfguy.meshcore.android.BuildConfig
import io.github.thatsfguy.meshcore.presentation.Inbox
import io.github.thatsfguy.meshcore.protocol.ReactionRouting
import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.engine.MeshEvent
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.protocol.MessageRepeats
import io.github.thatsfguy.meshcore.protocol.BlockList
import io.github.thatsfguy.meshcore.protocol.DeliveryWatch
import io.github.thatsfguy.meshcore.protocol.PathHistoryHygiene
import io.github.thatsfguy.meshcore.protocol.ReactionCounts
import io.github.thatsfguy.meshcore.protocol.ReactionNotice
import io.github.thatsfguy.meshcore.protocol.Reactions
import io.github.thatsfguy.meshcore.protocol.Retention
import io.github.thatsfguy.meshcore.protocol.SendRetry
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

    /**
     * Blocked sender keys and filtered channel names, refreshed from
     * preferences by the service. Held here because the check has to
     * happen before the row is written: a blocked message that is stored
     * and merely hidden is still on the phone, still in a backup, and
     * still there to be found.
     */
    @Volatile var blockedKeys: Set<String> = emptySet()

    @Volatile var filteredChannelNames: Set<String> = emptySet()

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
    private val seenReactionKeys = ReactionRouting.SeenKeys()

    /** Called before sending our own channel reaction, so its echo is a no-op. */
    @Synchronized
    fun noteOwnReaction(contentKey: String) {
        seenReactionKeys.remember(contentKey)
    }

    @Synchronized
    private fun firstSightOfReaction(contentKey: String?): Boolean =
        seenReactionKeys.firstSight(contentKey)

    /**
     * Invoked for each genuinely-new inbound message that is NOT in the
     * open thread — after DB insert (so channel echo/duplicate delivery
     * is already filtered) — with (kind, peerKey, senderName, text).
     * The service posts the system notification from here. CLI replies
     * (txt_type != plain) never notify.
     */
    @Volatile var onNewMessage: ((String, String, String?, String) -> Unit)? = null

    /** The attached engine, for the mesh properties writes depend on. */
    @Volatile private var engineRef: MeshCoreEngine? = null

    fun start(engine: MeshCoreEngine) {
        engineRef = engine
        scope.launch {
            // Repair the history once the radio says how wide a hop is.
            // Distinct: DEVICE_INFO is re-read on every reconnect and
            // the sweep touches every row.
            var repairedAt = PathHistoryHygiene.WIDTH_UNKNOWN
            engine.deviceInfo.collect { info ->
                val width = info?.pathHashByteWidth?.takeIf { it in 1..4 } ?: return@collect
                val self = resolveSelfKey(engine)
                if (self.isEmpty() || width == repairedAt) return@collect
                repairedAt = width
                repairPathHistory(self, width)
            }
        }
        scope.launch {
            engine.meshEvents.collect { event -> handle(engine, event) }
        }
        scope.launch {
            engine.contacts.collect { contacts ->
                val self = resolveSelfKey(engine)
                if (self.isNotEmpty() && contacts.isNotEmpty()) {
                    // Preserve local-only fields (unread, lastMessageAt)
                    // across the radio-authoritative refresh.
                    val existing = db.contacts().allOnce(self).associateBy { it.keyHex }
                    // Remember every path the radio reports so the
                    // routing sheet can rank routes it has actually seen.
                    for (c in contacts.values) {
                        // c.pathLen is the RAW packed byte — low 6 bits
                        // are the hop count, top 2 the hash width — and
                        // the Contact model says in as many words not to
                        // use it as a length. Doing so recorded a direct
                        // contact (packed 0x40) as a "64 hop" path of
                        // zero-padding, which then showed up in the
                        // routing sheet as a route you could pin.
                        val stored = c.storedPath
                        if (stored.isNotEmpty()) {
                            // The contact's OWN path_len packs the width
                            // this path was recorded at — more precise
                            // than the mesh default, and the difference
                            // is what "2 hops" vs "4 hops" turns on.
                            rememberPath(self, c.publicKeyHex, stored, c.pathInfo.hashWidth)
                        }
                    }
                    // Anything that became a contact leaves the inbox.
                    db.discovered().deleteKnown(self, contacts.keys.toList())
                    db.contacts().upsertAll(
                        contacts.values.map { c ->
                            // Everything the radio does not know about
                            // this contact has to survive the radio
                            // telling us about it. See
                            // [keepingLocalFacts] — listing the fields
                            // to preserve inline is how the announced
                            // update address, the board name and the
                            // firmware version came to be thrown away
                            // on every reconnection.
                            c.toEntity(self).keepingLocalFacts(existing[c.publicKeyHex])
                        },
                    )
                }
            }
        }
        scope.launch {
            engine.channels.collect { channels ->
                val self = resolveSelfKey(engine)
                if (self.isEmpty()) return@collect
                for (ch in channels) persistChannel(self, ch)
                // Reconcile ALWAYS, including when the radio reports no
                // channels at all. Gating this on a non-empty list left
                // a radio whose slots were all cleared showing every
                // stale row forever.
                db.channels().deleteAbsent(self, channels.map { it.index })
            }
        }
    }

    /**
     * The attached radio's key, preferring the engine's own SELF_INFO
     * over [selfKey].
     *
     * [selfKey] is assigned by the service from a *different* coroutine
     * collecting the same flow. Reading it here raced: a contact or
     * channel sync that landed first saw an empty string and returned,
     * and because those are StateFlows that then don't change again,
     * the reconcile never ran at all. That is how unconfigured channel
     * slots stayed in the database — and in the Chats list — long after
     * the engine had correctly filtered them out.
     */
    private fun resolveSelfKey(engine: MeshCoreEngine): String =
        engine.selfInfo.value?.publicKeyHex ?: selfKey

    /** Internal for tests: the vault-failure path has no other seam. */
    internal suspend fun persistChannel(self: String, ch: Channel) {
        // No keystore → no PSK at rest. That is the right call for the
        // key; it was the wrong call for the ROW, which used to be
        // dropped with it — so on a device whose Keystore is unusable
        // the channel vanished from Chats entirely rather than losing
        // only its cached key. An empty sealed blob keeps the channel
        // visible; channelPskHex already prefers live engine state and
        // returns null rather than unsealing nothing.
        val sealed = secrets.sealPsk(ch.psk) ?: ByteArray(0)
        val prev = db.channels().byIdx(self, ch.index)
        db.channels().upsert(
            ChannelEntity(
                selfKey = self, idx = ch.index, name = ch.name, pskSealed = sealed,
                unread = prev?.unread ?: 0,
                lastMessageAt = prev?.lastMessageAt ?: 0,
            ),
        )
    }

    /** Internal for tests: the self-key guard has no other seam. */
    internal suspend fun handle(engine: MeshCoreEngine, event: MeshEvent) {
        // Through resolveSelfKey, not the raw field: [selfKey] is
        // assigned by the service from a different coroutine collecting
        // the same flow, so reading it here raced — and on this path
        // losing the race does not mean a stale row, it means an
        // inbound message is dropped and never stored. The engine's own
        // SELF_INFO is authoritative and is already set by the time any
        // message event can exist.
        val self = resolveSelfKey(engine)
        if (self.isEmpty()) return
        when (event) {
            is MeshEvent.DirectMessageReceived -> {
                val peer = event.senderKeyHex ?: event.senderPrefixHex
                // Dropped before it becomes a row. An unresolved sender
                // (prefix only, no matching contact) is never treated as
                // blocked — a 6-byte prefix is 48 bits, and blocking on
                // one would silently discard messages from whoever
                // collides with it.
                // ...and only for traffic a block is ABOUT. A CLI reply
                // is the answer to a command we sent seconds ago; a
                // block that eats it silently breaks the console while
                // the settings form — which awaits the engine event and
                // never comes through here — carries on working.
                if (BlockList.isBlockableMessage(event.txtType) &&
                    BlockList.isBlockedSender(event.senderKeyHex, blockedKeys)
                ) {
                    return
                }
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
                if (event.txtType != 1) {
                    when (val r = applyReaction(self, KIND_DM, peer, storedText, null)) {
                        is ReactionOutcome.Applied -> {
                            if (Inbox.shouldBumpUnread(activeThread, KIND_DM, peer)) {
                                db.contacts().bumpUnread(self, peer, System.currentTimeMillis())
                                notifyReaction(KIND_DM, peer, event.roomAuthorLabel, r)
                            }
                            return
                        }
                        ReactionOutcome.Consumed -> return
                        ReactionOutcome.NotAReaction -> Unit
                    }
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
                        arrivalPathHex = event.arrivalPathHex,
                        arrivalHashWidth = event.arrivalHashWidth,
                    ),
                )
                if (Inbox.shouldBumpUnread(activeThread, KIND_DM, peer)) {
                    db.contacts().bumpUnread(self, peer, System.currentTimeMillis())
                    // CLI replies are console output, not messages.
                    if (event.txtType != 1) {
                        onNewMessage?.invoke(KIND_DM, peer, event.roomAuthorLabel, event.text)
                    }
                }
            }

            is MeshEvent.OwnDirectRepeatHeard ->
                noteDirectRepeat(event.destHash, event.pathHex, event.hashWidth)

            is MeshEvent.ChannelMessageReceived -> {
                // A NOISE FILTER, not a block: the name is unauthenticated
                // display text (MESHCORE_PROTOCOL §9 — a group message
                // carries no sender key), so this stops a spammer who
                // keeps their name and nothing more.
                if (BlockList.isFilteredChannelName(event.senderName, filteredChannelNames)) return
                val channelKey = event.channelIndex.toString()
                when (
                    val r = applyReaction(
                        self,
                        KIND_CHANNEL,
                        channelKey,
                        event.text,
                        event.senderName,
                        event.contentKey,
                    )
                ) {
                    is ReactionOutcome.Applied -> {
                        if (Inbox.shouldBumpUnread(activeThread, KIND_CHANNEL, channelKey)) {
                            db.channels().bumpUnread(
                                self,
                                event.channelIndex,
                                System.currentTimeMillis(),
                            )
                            notifyReaction(KIND_CHANNEL, channelKey, event.senderName, r)
                        }
                        return
                    }
                    ReactionOutcome.Consumed -> return
                    ReactionOutcome.NotAReaction -> Unit
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
                        arrivalPathHex = event.arrivalPathHex,
                        arrivalHashWidth = event.arrivalHashWidth,
                    ),
                )
                // The RX-log copy is the ONLY one carrying a route, and
                // it is also the one the unique index bounces when the
                // sync copy arrives first. Fill it in either way.
                val path = event.arrivalPathHex
                val width = event.arrivalHashWidth
                if (path != null && width != null) {
                    // An echo of a message WE sent is not an arrival —
                    // it is a repeat, and the two mean opposite
                    // directions. Route it to the repeat column instead,
                    // which is exact here because the engine decrypted
                    // this very packet and its contentKey matches a row
                    // in our own outbox.
                    val own = db.messages().outgoingByContentKey(self, event.contentKey)
                    if (own != null) {
                        noteRepeat(own, path, width)
                    } else {
                        db.messages().fillArrivalPath(self, event.contentKey, path, width)
                    }
                }
                // insert == -1 → duplicate (sync + RX-log double delivery,
                // or the echo of our own outgoing message)
                if (Inbox.shouldNotify(
                        activeThread, KIND_CHANNEL, event.channelIndex.toString(),
                        isDuplicate = inserted == -1L,
                    )
                ) {
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
    ): ReactionOutcome {
        val reaction = Reactions.parse(text) ?: return ReactionOutcome.NotAReaction
        // Consume repeats (our own echo, or double delivery) without
        // counting them again — but still consume, so the wire text
        // never lands in the thread as a message.
        if (!firstSightOfReaction(contentKey)) return ReactionOutcome.Consumed
        val recent = db.messages()
            .recentOnce(self, kind, peerKey, ReactionRouting.SEARCH_WINDOW)
        // Which message this points at is ReactionRouting's rule, not
        // this class's: it is pure, it is hostile-input facing, and iOS
        // needs the same answer. Here we only map rows to it and back.
        val hit = ReactionRouting.target(
            recent.map {
                ReactionRouting.Candidate(it.id, it.timestamp, it.senderName, it.text, it.outgoing)
            },
            reaction.targetHash,
            isChannel = kind == KIND_CHANNEL,
        ) ?: return ReactionOutcome.Consumed
        val target = recent.first { it.id == hit.id }
        addReaction(target, reaction.emoji)
        return ReactionOutcome.Applied(reaction.emoji, target)
    }

    /**
     * Notify for a reaction, but only when it lands on something WE
     * said.
     *
     * A thumbs-up on your own message is often the whole reply and is
     * worth an interruption. A reaction to a third party's message in a
     * busy channel is somebody else's conversation, and notifying on it
     * would make channels unusable.
     *
     * It goes through [onNewMessage] deliberately: that path already
     * carries the notifications-enabled check, the per-kind switches and
     * the per-thread mute, and a reaction should obey every one of them.
     * A mute that silences replies but not thumbs-ups is not a mute.
     */
    private fun notifyReaction(
        kind: String,
        peerKey: String,
        senderName: String?,
        applied: ReactionOutcome.Applied,
    ) {
        if (!ReactionRouting.shouldNotify(
                ReactionRouting.Candidate(
                    applied.target.id, applied.target.timestamp,
                    applied.target.senderName, applied.target.text, applied.target.outgoing,
                ),
            )
        ) {
            return
        }
        onNewMessage?.invoke(
            kind,
            peerKey,
            senderName,
            ReactionNotice.text(applied.emoji, applied.target.text),
        )
    }

    /**
     * What [applyReaction] did with a message.
     *
     * It used to return a Boolean meaning "consumed", which could not
     * distinguish a reaction that was newly applied from one that was a
     * duplicate or had no target — so nothing downstream could react to
     * a reaction, and they arrived in silence.
     */
    private sealed interface ReactionOutcome {
        /** Ordinary message text; carry on and insert it. */
        object NotAReaction : ReactionOutcome

        /** Swallowed: our own echo, a double delivery, or no target found. */
        object Consumed : ReactionOutcome

        data class Applied(val emoji: String, val target: MessageEntity) : ReactionOutcome
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
     *
     * The **last** attempt resets the contact's path and floods, which
     * is MeshCore's documented default — see [SendRetry] for the FAQ
     * text and the reasoning. Until this existed all three attempts went
     * down the same stored path, so a repeater that had gone away cost
     * three transmissions and taught the radio nothing.
     *
     * That the attempt byte differs per attempt matters more than it
     * looks: the firmware's ACK carries a copy of it (PR #2594, merged
     * 2026-05-21) specifically so a retry's ACK is a distinct packet and
     * is not dropped as a duplicate by the mesh's seen-table.
     */
    suspend fun sendDirectWithRetry(
        engine: MeshCoreEngine,
        peerKeyHex: String,
        text: String,
        maxAttempts: Int = SendRetry.DEFAULT_MAX_ATTEMPTS,
        floodFallbackEnabled: Boolean = true,
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

        // ONE collector for the whole send, not one wait per attempt.
        //
        // meshEvents has no replay, so anything not being collected at
        // the instant it is emitted is gone. Waiting only during an
        // attempt's own timeout dropped an ACK three ways: one arriving
        // during the backoff between attempts, one for an EARLIER
        // attempt arriving later (each attempt has its own hash, and
        // only the current one was watched), and one arriving just after
        // the final attempt gave up. All three are delivered messages
        // reported as failures.
        //
        // A StateFlow of what has been seen closes every gap: it is hot
        // for the whole send, and `first {}` re-checks the value it
        // already holds, so there is no window between waits either.
        kotlinx.coroutines.coroutineScope {
            val watch = DeliveryWatch()
            val watcher = launch {
                engine.meshEvents.collect { ev ->
                    if (ev is MeshEvent.MessageDelivered) watch.record(ev.ackHash)
                }
            }

            // Every hash this message has been sent under; an ACK for
            // any of them means it arrived.
            val ourHashes = mutableSetOf<Long>()
            suspend fun awaitAnyAck(timeoutMs: Long): Boolean =
                watch.awaitAny(ourHashes, timeoutMs)

            try {
                var attempt = 0
                while (attempt < maxAttempts) {
                    val route = SendRetry.routeFor(
                        attempt = attempt,
                        maxAttempts = maxAttempts,
                        hasStoredPath = pathHex.isNotEmpty(),
                        floodFallbackEnabled = floodFallbackEnabled,
                    )
                    if (route == SendRetry.Route.ResetAndFlood) {
                        // Clearing the path is what makes this stick: the radio
                        // floods this send AND has no dead route left to reuse,
                        // so the reply teaches it a live one.
                        runCatching { engine.resetPath(key) }
                    }

                    val sent = runCatching {
                        engine.sendDirectMessage(
                            key, text, attempt = attempt, timestampSeconds = timestamp,
                        )
                    }.getOrNull()
                    if (sent == null) {
                        // The radio didn't even accept it — no point retrying fast.
                        db.messages().updateResult(rowId, MessageStatus.Failed.ordinal, null)
                        return@coroutineScope
                    }
                    ourHashes += sent.ackHash
                    db.messages().updateResult(rowId, MessageStatus.Sent.ordinal, sent.ackHash)
                    db.messages().setAttempts(rowId, attempt + 1)

                    // Trust the radio's own airtime-derived timeout, with a floor.
                    val timeout = sent.timeoutMs.coerceIn(3_000L, 60_000L)
                    val scores = SendRetry.scoresStoredPath(route)
                    if (awaitAnyAck(timeout)) {
                        db.messages()
                            .updateResult(rowId, MessageStatus.Delivered.ordinal, sent.ackHash)
                        // A flood delivering says nothing good about the path we
                        // just threw away, so only a stored-path attempt scores.
                        if (scores && (pathHex.isNotEmpty() || attempt == 0)) {
                            scorePathDirect(peerKeyHex, pathHex, true)
                        }
                        return@coroutineScope
                    }
                    if (scores) scorePathDirect(peerKeyHex, pathHex, false)
                    attempt++
                    if (attempt < maxAttempts) {
                        kotlinx.coroutines.delay(1_000L * attempt)
                        // The backoff is not a blind spot: an ACK that
                        // landed during it is already in deliveredAcks.
                        if (awaitAnyAck(0)) {
                            db.messages().updateResult(
                                rowId, MessageStatus.Delivered.ordinal, ourHashes.last(),
                            )
                            return@coroutineScope
                        }
                    }
                }

                // Out of attempts. Failed is the honest report of what we
                // know NOW — but keep the last hash on the row and keep
                // listening, so a late ACK can correct it rather than
                // leaving a delivered message looking like a failure.
                db.messages()
                    .updateResult(rowId, MessageStatus.Failed.ordinal, ourHashes.lastOrNull())
                if (awaitAnyAck(SendRetry.LATE_ACK_GRACE_MS)) {
                    db.messages()
                        .updateResult(rowId, MessageStatus.Delivered.ordinal, ourHashes.last())
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    /** Score a path directly (retry path knows its route without an ack map). */
    private suspend fun scorePathDirect(contactKey: String, pathHex: String, delivered: Boolean) {
        val row = db.paths().get(selfKey, contactKey, pathHex) ?: newPathRow(contactKey, pathHex)
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

    /**
     * The mesh's hop-hash width, for rows written without one to hand.
     * Zero until the radio has told us; a row stamped with zero is
     * repaired later rather than guessed at now.
     */
    private fun meshHashWidth(): Int =
        engineRef?.deviceInfo?.value?.pathHashByteWidth?.takeIf { it in 1..4 }
            ?: PathHistoryHygiene.WIDTH_UNKNOWN

    /**
     * A fresh history row, with the hop count computed at the right
     * width. Every previous write site did `pathHex.length / 2`, which
     * is a BYTE count — the reason a 2-hop route read as "4 hop(s)".
     */
    private fun newPathRow(
        contactKey: String,
        pathHex: String,
        hashWidth: Int = meshHashWidth(),
        self: String = selfKey,
    ): PathHistoryEntity {
        val bytes = pathHex.length / 2
        return PathHistoryEntity(
            selfKey = self,
            contactKey = contactKey,
            pathHex = pathHex,
            hops = if (hashWidth in 1..4) PathHistoryHygiene.hopCount(bytes, hashWidth) else bytes,
            hashWidth = hashWidth,
        )
    }

    /** Upsert a path we have seen or used for [contactKey]. */
    suspend fun rememberPath(
        self: String,
        contactKey: String,
        path: ByteArray,
        hashWidth: Int = meshHashWidth(),
    ) {
        val hex = path.joinToString("") { "%02x".format(it) }
        // Don't record what we would only have to delete again. The
        // zero-padded rows an older build wrote came in through exactly
        // this door.
        if (hashWidth in 1..4 && !PathHistoryHygiene.isUsable(hex, hashWidth)) return
        val existing = db.paths().get(self, contactKey, hex)
        db.paths().upsert(
            existing?.copy(lastUsedAt = System.currentTimeMillis())
                ?: newPathRow(contactKey, hex, hashWidth, self)
                    .copy(lastUsedAt = System.currentTimeMillis()),
        )
        db.paths().prune(self, contactKey, PathHistoryHygiene.MAX_PER_CONTACT)
    }

    /**
     * Repair (or delete) history rows written before the width was
     * recorded, and drop anything the radio would not accept back.
     *
     * Called once the radio reports its hop width. Deleting is the right
     * outcome for a row we can't make sense of: the routing sheet offers
     * these as routes to PIN, so a junk row isn't cosmetic — it is a
     * route the user can select and then wonder why nothing arrives.
     */
    suspend fun repairPathHistory(self: String, hashWidth: Int) {
        if (hashWidth !in 1..4 || self.isEmpty()) return
        var deleted = 0
        var restated = 0
        for (row in db.paths().allOnce(self)) {
            val defect = PathHistoryHygiene.defect(row.pathHex, hashWidth)
            if (defect != null) {
                db.paths().delete(self, row.contactKey, row.pathHex)
                deleted++
                continue
            }
            val hops = PathHistoryHygiene.hopCount(row.pathHex.length / 2, hashWidth)
            if (row.hashWidth != hashWidth || row.hops != hops) {
                db.paths().upsert(row.copy(hops = hops, hashWidth = hashWidth))
                restated++
            }
        }
        if (deleted > 0 || restated > 0) {
            android.util.Log.i(
                "MeshCoreRepo",
                "Path history repaired at width $hashWidth: $restated restated, $deleted dropped",
            )
        }
    }

    /** Tie an outbound ack hash to the path it went out on. */
    // UNWIRED (audited 2026-08-06): no caller, so an ack is never tied
    // to the path it went out on and the routing sheet scores routes
    // only from the retry loop's own bookkeeping. Kept as evidence.
    fun attributeAck(ackHash: Long, contactKey: String, pathHex: String) {
        ackPaths[ackHash] = contactKey to pathHex
    }

    /** Credit (or debit) the path an ack was sent over. */
    suspend fun scorePath(ackHash: Long, delivered: Boolean) {
        val (contactKey, pathHex) = ackPaths.remove(ackHash) ?: return
        val row = db.paths().get(selfKey, contactKey, pathHex) ?: newPathRow(contactKey, pathHex)
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

    /**
     * Record that [message] was heard being re-broadcast over [pathHex].
     *
     * Accumulates rather than overwrites: a flood is carried by several
     * nodes and each copy that reaches us names a different part of the
     * picture. Merging is [MessageRepeats.merge], which unions on the hop
     * hash so one node re-transmitting twice stays one node.
     */
    private suspend fun noteRepeat(message: MessageEntity, pathHex: String, width: Int) {
        val merged = MessageRepeats.merge(message.repeatHopsHex, pathHex, width) ?: return
        if (merged == message.repeatHopsHex) return
        db.messages().setRepeats(message.id, merged, width)
    }

    /**
     * A repeat of one of OUR direct messages, heard off the RX log.
     *
     * Unlike the channel case this is correlation, not identification: a
     * rebroadcast DM is encrypted to its recipient, so all it offers is
     * one byte of recipient hash. We narrow to sent DMs from the last
     * [DM_REPEAT_WINDOW_MS] whose peer key starts with that byte, and
     * credit the repeat **only when exactly one fits**. Two candidates
     * means we do not know which message was carried — and a repeat
     * count on the wrong message looks exactly like a right one.
     */
    suspend fun noteDirectRepeat(destHash: Int, pathHex: String, width: Int) {
        // `selfKey ?: return` was elvis on a non-null String — the guard
        // never fired, and the query below ran against "".
        val self = selfKey
        if (self.isEmpty()) return
        val now = System.currentTimeMillis()
        val recent = db.messages().recentOutgoingDms(self, now - DM_REPEAT_WINDOW_MS)
        val credited = MessageRepeats.creditDirect(
            recent.map { MessageRepeats.SentRef(it.id, it.peerKey, it.receivedAt) },
            destHash,
            now,
        )
        // DEBUG ONLY. This line names contact key prefixes, and a
        // release build must not put those in the system log — the whole
        // posture of this app is that contact keys stay in the encrypted
        // database and the Keystore.
        if (BuildConfig.DEBUG) {
            android.util.Log.i(
                "MCH-Repeat",
                "dm repeat dest=%02x path=$pathHex candidates=${recent.size} ".format(destHash) +
                    "peers=${recent.map { it.peerKey.take(4) }} credited=${credited?.id}",
            )
        }
        val row = recent.firstOrNull { it.id == credited?.id } ?: return
        noteRepeat(row, pathHex, width)
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
         * How far back to LOOK for a sent DM a repeat might belong to.
         *
         * Only the query bound — which candidate actually gets credited
         * is `MessageRepeats.creditDirect`, on echo timing. This stays
         * wider than that rule needs so the decision is made there and
         * not silently by SQL.
         */
        const val DM_REPEAT_WINDOW_MS = 180_000L


        /** Bound on the echo-suppression set. */

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
