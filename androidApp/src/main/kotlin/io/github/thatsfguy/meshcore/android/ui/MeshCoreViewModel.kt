package io.github.thatsfguy.meshcore.android.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thatsfguy.meshcore.android.service.MeshCoreService
import io.github.thatsfguy.meshcore.android.storage.ChannelEntity
import io.github.thatsfguy.meshcore.android.storage.ContactEntity
import io.github.thatsfguy.meshcore.android.storage.MeshCoreDatabase
import io.github.thatsfguy.meshcore.android.storage.MessageEntity
import io.github.thatsfguy.meshcore.android.storage.MessageRepository
import io.github.thatsfguy.meshcore.android.storage.Preferences
import io.github.thatsfguy.meshcore.android.ui.screens.AdminSession
import io.github.thatsfguy.meshcore.engine.EngineState
import io.github.thatsfguy.meshcore.engine.MeshEvent
import io.github.thatsfguy.meshcore.model.BatteryAndStorage
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.model.DeviceInfo
import io.github.thatsfguy.meshcore.model.SelfInfo
import io.github.thatsfguy.meshcore.protocol.ChannelCrypto
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.ConfigBackup
import io.github.thatsfguy.meshcore.protocol.NodeDiscovery
import io.github.thatsfguy.meshcore.protocol.Reactions
import io.github.thatsfguy.meshcore.protocol.Regions
import io.github.thatsfguy.meshcore.protocol.ShareUri
import io.github.thatsfguy.meshcore.transport.ConnectionMemory
import io.github.thatsfguy.meshcore.transport.SavedNode
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
import io.github.thatsfguy.meshcore.util.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One row in the merged conversation list. */
data class ConversationRow(
    val kind: String,            // MessageRepository.KIND_DM / KIND_CHANNEL
    val key: String,             // contact hex / channel index
    val title: String,
    val subtitle: String,
    val timestamp: Long,
    val unread: Int,
    val isChannel: Boolean,
    val contactType: Int?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MeshCoreViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Preferences(app)

    // MeshCoreService opens the encrypted database first and the handle
    // is a singleton; resolving the key here too keeps the VM correct if
    // it ever wins the race.
    private val db = MeshCoreDatabase.get(
        app,
        kotlinx.coroutines.runBlocking {
            io.github.thatsfguy.meshcore.android.storage.DatabaseKey.passphrase(
                prefs,
                io.github.thatsfguy.meshcore.android.storage.KeystoreSecretVault(),
            )
        },
    )

    private val _service = MutableStateFlow<MeshCoreService?>(null)
    val service: StateFlow<MeshCoreService?> = _service

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            _service.value = (binder as MeshCoreService.LocalBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName) {
            _service.value = null
        }
    }

    init {
        MeshCoreService.start(app)
        app.bindService(Intent(app, MeshCoreService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unbindService(connection) }
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // Engine state proxies
    // ------------------------------------------------------------------

    val engineState: StateFlow<EngineState> = _service.flatMapLatest {
        it?.engine?.state ?: flowOf(EngineState.Detached)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EngineState.Detached)

    val selfInfo: StateFlow<SelfInfo?> = _service.flatMapLatest {
        it?.engine?.selfInfo ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val deviceInfo: StateFlow<DeviceInfo?> = _service.flatMapLatest {
        it?.engine?.deviceInfo ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val liveContacts: StateFlow<Map<String, Contact>> = _service.flatMapLatest {
        it?.engine?.contacts ?: flowOf(emptyMap())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val liveChannels: StateFlow<List<Channel>> = _service.flatMapLatest {
        it?.engine?.channels ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val battery: StateFlow<BatteryAndStorage?> = _service.flatMapLatest {
        it?.engine?.battery ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val plaintextLink: StateFlow<Boolean> = _service.flatMapLatest {
        it?.engine?.plaintextLink ?: flowOf(false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val connectionLabel: StateFlow<String?> = _service.flatMapLatest {
        it?.connectionLabel ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val lastError: StateFlow<String?> = _service.flatMapLatest {
        it?.lastError ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val diagnosticsLines: StateFlow<List<String>> = _service.flatMapLatest {
        it?.diagnostics?.lines ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Login results etc. surfaced as one-shot UI messages. */
    val transientMessage = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            _service.flatMapLatest { it?.engine?.meshEvents ?: flowOf() }.collect { ev ->
                when (ev) {
                    is MeshEvent.LoginResult ->
                        transientMessage.value =
                            if (ev.success) "Login accepted" else "Login failed"
                    else -> Unit
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // DB-backed flows (selfKey-scoped)
    // ------------------------------------------------------------------

    private val selfKey: StateFlow<String> = _service.flatMapLatest { svc ->
        svc?.engine?.selfInfo ?: flowOf(null)
    }.combine(MutableStateFlow(Unit)) { info, _ -> info?.publicKeyHex ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val dbContacts: StateFlow<List<ContactEntity>> = selfKey.flatMapLatest { key ->
        if (key.isEmpty()) flowOf(emptyList()) else db.contacts().all(key)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Discovery inbox: verified adverts that aren't contacts yet. */
    val discovered: StateFlow<List<io.github.thatsfguy.meshcore.android.storage.DiscoveredEntity>> =
        selfKey.flatMapLatest { key ->
            if (key.isEmpty()) flowOf(emptyList()) else db.discovered().all(key)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Add a discovered node as a contact by replaying its advert. */
    fun addDiscovered(keyHex: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            val row = db.discovered().get(selfKey.value, keyHex) ?: return@launch
            val blob = hexToBytesOrNull(row.advertHex)
            val ok = blob != null &&
                runCatching { svc.engine.importContact(blob) }.getOrDefault(false)
            if (ok) {
                db.discovered().delete(selfKey.value, keyHex)
                transientMessage.value = "Added ${row.name.ifBlank { keyHex.take(12) }}"
            } else {
                transientMessage.value = "Import failed (bad signature?)"
            }
        }
    }

    fun dismissDiscovered(keyHex: String) {
        viewModelScope.launch { db.discovered().delete(selfKey.value, keyHex) }
    }

    fun clearDiscovered() {
        viewModelScope.launch { db.discovered().clear(selfKey.value) }
    }

    val dbChannels: StateFlow<List<ChannelEntity>> = selfKey.flatMapLatest { key ->
        if (key.isEmpty()) flowOf(emptyList()) else db.channels().all(key)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Bumped when a private nickname changes, so [conversations] recomputes. */
    private val nicknameRevision = MutableStateFlow(0)

    val conversations: StateFlow<List<ConversationRow>> = combine(
        selfKey.flatMapLatest { key ->
            if (key.isEmpty()) flowOf(emptyList()) else db.messages().latestPerThread(key)
        },
        dbContacts,
        dbChannels,
        nicknameRevision,
    ) { latest, contacts, channels, _ ->
        val contactByKey = contacts.associateBy { it.keyHex }
        val channelByIdx = channels.associateBy { it.idx }
        val rows = ArrayList<ConversationRow>()
        val seenChannels = HashSet<Int>()
        for (m in latest) {
            if (m.kind == MessageRepository.KIND_CHANNEL) {
                val idx = m.peerKey.toIntOrNull() ?: continue
                seenChannels.add(idx)
                val ch = channelByIdx[idx]
                rows.add(
                    ConversationRow(
                        kind = m.kind, key = m.peerKey,
                        title = ch?.name?.ifBlank { "Channel $idx" } ?: "Channel $idx",
                        subtitle = (m.senderName?.plus(": ") ?: "") + previewOf(m),
                        timestamp = m.timestamp,
                        unread = ch?.unread ?: 0,
                        isChannel = true,
                        contactType = null,
                    ),
                )
            } else {
                val c = contactByKey[m.peerKey]
                // Repeater admin traffic (CLI console) lives in the admin
                // screen — repeater threads never surface in Chats.
                if (c?.type == io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_REPEATER) {
                    continue
                }
                rows.add(
                    ConversationRow(
                        kind = m.kind, key = m.peerKey,
                        title = prefs.nicknameFor(m.peerKey)
                            ?: c?.name?.ifBlank { null }
                            ?: m.peerKey.take(12),
                        subtitle = previewOf(m),
                        timestamp = m.timestamp,
                        unread = c?.unread ?: 0,
                        isChannel = false,
                        contactType = c?.type,
                    ),
                )
            }
        }
        // Channels with no messages yet still appear (so a fresh join is visible).
        for (ch in channels) {
            if (ch.idx !in seenChannels) {
                rows.add(
                    ConversationRow(
                        kind = MessageRepository.KIND_CHANNEL, key = ch.idx.toString(),
                        title = ch.name.ifBlank { "Channel ${ch.idx}" },
                        subtitle = "No messages yet",
                        timestamp = 0,
                        unread = ch.unread,
                        isChannel = true,
                        contactType = null,
                    ),
                )
            }
        }
        rows.sortedByDescending { it.timestamp }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun thread(kind: String, peerKey: String): StateFlow<List<MessageEntity>> =
        selfKey.flatMapLatest { key ->
            if (key.isEmpty()) flowOf(emptyList()) else db.messages().thread(key, kind, peerKey)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Newest-[limit] window of a thread, for paged scrollback. */
    fun threadPaged(kind: String, peerKey: String, limit: Int): StateFlow<List<MessageEntity>> =
        selfKey.flatMapLatest { key ->
            if (key.isEmpty()) flowOf(emptyList())
            else db.messages().threadPaged(key, kind, peerKey, limit)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun threadCount(kind: String, peerKey: String): StateFlow<Int> =
        selfKey.flatMapLatest { key ->
            if (key.isEmpty()) flowOf(0) else db.messages().threadCount(key, kind, peerKey)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Leave a thread marked unread (badge stays until next open). */
    fun markUnread(kind: String, peerKey: String) {
        viewModelScope.launch {
            val key = selfKey.value
            if (key.isEmpty()) return@launch
            markThreadClosed()
            if (kind == MessageRepository.KIND_CHANNEL) {
                peerKey.toIntOrNull()?.let {
                    db.channels().bumpUnread(key, it, System.currentTimeMillis())
                }
            } else {
                db.contacts().bumpUnread(key, peerKey, System.currentTimeMillis())
            }
            transientMessage.value = "Marked unread"
        }
    }

    fun markThreadOpen(kind: String, peerKey: String) {
        val svc = _service.value ?: return
        svc.repository.activeThread = "$kind|$peerKey"
        viewModelScope.launch {
            val key = selfKey.value
            if (key.isEmpty()) return@launch
            if (kind == MessageRepository.KIND_CHANNEL) {
                peerKey.toIntOrNull()?.let { db.channels().clearUnread(key, it) }
            } else {
                db.contacts().clearUnread(key, peerKey)
            }
        }
    }

    fun markThreadClosed() {
        _service.value?.repository?.activeThread = null
    }

    // ------------------------------------------------------------------
    // Connection actions
    // ------------------------------------------------------------------

    fun connectBle(address: String, name: String?) {
        prefs.saveNode(SavedNode(ConnectionMemory.KIND_BLE, address, null, name))
        _service.value?.connect(ConnectionMemory.Ble(address, name))
    }

    fun connectTcp(host: String, port: Int) {
        prefs.saveNode(SavedNode(ConnectionMemory.KIND_TCP, host, port, null))
        _service.value?.connect(ConnectionMemory.Tcp(host, port))
    }

    fun connectUsb(device: UsbDevice) {
        _service.value?.connectUsb(device)
    }

    fun connectSaved(node: SavedNode) {
        when (node.kind) {
            ConnectionMemory.KIND_BLE -> _service.value?.connect(
                ConnectionMemory.Ble(node.address, node.name),
            )
            ConnectionMemory.KIND_TCP -> node.port?.let {
                _service.value?.connect(ConnectionMemory.Tcp(node.address, it))
            }
        }
    }

    fun disconnect() {
        _service.value?.disconnect()
    }

    // ------------------------------------------------------------------
    // Messaging actions
    // ------------------------------------------------------------------

    fun sendDirectMessage(peerKeyHex: String, text: String) {
        val svc = _service.value ?: return
        // Retry lives in the service-scoped repository so it survives
        // leaving the conversation screen.
        svc.scope.launch {
            svc.repository.sendDirectWithRetry(
                svc.engine,
                peerKeyHex,
                text,
                floodFallbackEnabled = prefs.floodFallbackOnLastRetry,
            )
        }
    }

    fun sendChannelMessage(channelIndex: Int, text: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            val ts = System.currentTimeMillis() / 1000
            val selfName = svc.engine.selfInfo.value?.name ?: "me"
            // The frame timestamp and the content key must agree — the
            // radio echoes our own message back, and the echo dedups
            // against this key (first-hardware-session finding).
            val contentKey = svc.engine.channelContentKey(channelIndex, ts, selfName, text)
            svc.repository.recordOutgoingChannel(channelIndex, selfName, text, ts, contentKey)
            val accepted = runCatching {
                // A region-scoped channel sends inside a flood-scope
                // window; the engine serialises those so one channel's
                // message can never inherit another's region.
                svc.engine.sendChannelMessage(channelIndex, text, ts, prefs.channelRegion(channelIndex))
            }.getOrDefault(false)
            svc.repository.markChannelResult(contentKey, accepted)
            if (!accepted) transientMessage.value = "Radio did not accept the message"
        }
    }

    /**
     * One-line preview for the conversation list. A reaction that never
     * found its target is still a message row, so render it the way the
     * thread does rather than leaking "r:1a2b:00" into the list.
     */
    private fun previewOf(m: io.github.thatsfguy.meshcore.android.storage.MessageEntity): String {
        Reactions.parse(m.text)?.let { return it.emoji + " reacted" }
        return m.text.take(80)
    }

    /** Local-only display name for a contact; see [Preferences.nicknameFor]. */
    fun nicknameFor(keyHex: String): String? = prefs.nicknameFor(keyHex)

    fun setNickname(keyHex: String, nickname: String?) {
        prefs.setNickname(keyHex, nickname)
        // The conversations flow reads prefs, so nudge it to recompute.
        nicknameRevision.value = nicknameRevision.value + 1
    }

    /**
     * Per-thread composer drafts, held for the life of the ViewModel so
     * stepping out of a conversation (to check a node, to answer another
     * thread) doesn't discard what was half-typed.
     */
    private val drafts = mutableMapOf<String, String>()

    fun draftFor(threadKey: String): String = drafts[threadKey].orEmpty()

    fun setDraft(threadKey: String, text: String) {
        if (text.isEmpty()) drafts.remove(threadKey) else drafts[threadKey] = text
    }

    /**
     * Send a failed message again. The original row is removed once the
     * retry is queued, so the thread shows one message rather than a
     * failed copy next to a live one.
     */
    fun resendMessage(messageId: Long) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            val row = svc.repository.messageById(messageId) ?: return@launch
            svc.repository.deleteMessage(messageId)
            if (row.kind == MessageRepository.KIND_CHANNEL) {
                row.peerKey.toIntOrNull()?.let { sendChannelMessage(it, row.text) }
            } else {
                sendDirectMessage(row.peerKey, row.text)
            }
        }
    }

    /**
     * React to a message.
     *
     * The reaction goes out as an ordinary text message (`r:HHHH:II`) —
     * MeshCore has no reaction field — and is attached locally straight
     * away rather than round-tripping through our own echo, since the
     * wire text would otherwise show up as a line of gibberish in the
     * thread.
     */
    fun sendReaction(messageId: Long, emoji: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            val target = svc.repository.messageById(messageId) ?: return@launch
            val isChannel = target.kind == MessageRepository.KIND_CHANNEL
            val wire = svc.repository.reactionWireText(target, emoji, isChannel)
            if (wire == null) {
                transientMessage.value = "That emoji can't be sent as a reaction"
                return@launch
            }
            svc.repository.addReaction(target, emoji)
            val sent = runCatching {
                if (isChannel) {
                    val idx = target.peerKey.toIntOrNull() ?: return@runCatching false
                    val ts = System.currentTimeMillis() / 1000
                    val selfName = svc.engine.selfInfo.value?.name ?: "me"
                    // Register the echo before it can come back.
                    svc.repository.noteOwnReaction(
                        svc.engine.channelContentKey(idx, ts, selfName, wire),
                    )
                    svc.engine.sendChannelMessage(idx, wire, ts, prefs.channelRegion(idx))
                } else {
                    val key = hexToBytesOrNull(target.peerKey) ?: return@runCatching false
                    svc.engine.sendDirectMessage(key, wire) != null
                }
            }.getOrDefault(false)
            if (!sent) transientMessage.value = "Reaction not sent"
        }
    }

    /** Delete one message from this phone (nothing leaves the radio). */
    fun deleteMessage(messageId: Long) {
        val svc = _service.value ?: return
        viewModelScope.launch { svc.repository.deleteMessage(messageId) }
    }

    // ------------------------------------------------------------------
    // Contacts
    // ------------------------------------------------------------------

    fun removeContact(keyHex: String) {
        val svc = _service.value ?: return
        val key = hexToBytesOrNull(keyHex) ?: return
        viewModelScope.launch {
            if (svc.engine.removeContact(key)) {
                db.contacts().delete(selfKey.value, keyHex)
            }
        }
    }

    fun setFavourite(keyHex: String, favourite: Boolean) {
        val svc = _service.value ?: return
        val key = hexToBytesOrNull(keyHex) ?: return
        viewModelScope.launch {
            val ok = runCatching { svc.engine.setFavourite(key, favourite) }.getOrDefault(false)
            if (!ok) transientMessage.value = "Radio rejected the change"
        }
    }

    /**
     * Per-contact telemetry permission (PARITY §2,
     * `ContactPermissionsScreen`). Only bites when the matching global
     * policy is "Flags" — see [MeshCoreEngine.setContactFlag].
     */
    fun setContactTelemetryFlag(keyHex: String, flag: Int, enabled: Boolean) {
        val svc = _service.value ?: return
        val key = hexToBytesOrNull(keyHex) ?: return
        viewModelScope.launch {
            val ok = runCatching { svc.engine.setContactFlag(key, flag, enabled) }
                .getOrDefault(false)
            if (!ok) transientMessage.value = "Radio rejected the change"
        }
    }

    /**
     * Contact whose route the Map tab should draw, or null for none.
     * Set from the routing sheet; the map reads it.
     */
    val mapRouteContact = MutableStateFlow<String?>(null)

    /**
     * A contact's STORED route, resolved hop by hop. Null when the
     * contact is unknown or reached by flooding — there is no route to
     * draw in either case.
     */
    fun plotStoredPath(keyHex: String?): io.github.thatsfguy.meshcore.protocol.PathGeometry.Plot? {
        val contact = keyHex?.let { _service.value?.engine?.contacts?.value?.get(it) }
            ?: return null
        if (contact.storedPath.isEmpty()) return null
        return io.github.thatsfguy.meshcore.protocol.PathGeometry.plot(
            contact.storedPath,
            deviceInfo.value?.pathHashByteWidth ?: 1,
            dbContacts.value.map {
                io.github.thatsfguy.meshcore.protocol.PathGeometry.PositionedContact(
                    it.keyHex, it.name, it.latitude, it.longitude,
                )
            },
        )
    }

    /** The route to draw on the map, resolved hop by hop. */
    fun mapRoute(): io.github.thatsfguy.meshcore.protocol.PathGeometry.Plot? =
        plotStoredPath(mapRouteContact.value)

    /**
     * Where a path's hops are, as far as can honestly be said
     * (PARITY §9). Ambiguous hops come back as gaps, never as a guess.
     */
    fun plotPath(hopTokens: String): io.github.thatsfguy.meshcore.protocol.PathGeometry.Plot {
        val width = deviceInfo.value?.pathHashByteWidth ?: 1
        val path = io.github.thatsfguy.meshcore.protocol.PathCodec
            .parseHopTokens(hopTokens, width)
            ?: return io.github.thatsfguy.meshcore.protocol.PathGeometry.Plot(emptyList())
        return io.github.thatsfguy.meshcore.protocol.PathGeometry.plot(
            path,
            width,
            dbContacts.value.map {
                io.github.thatsfguy.meshcore.protocol.PathGeometry.PositionedContact(
                    it.keyHex, it.name, it.latitude, it.longitude,
                )
            },
        )
    }

    // --- Routing / paths ---------------------------------------------

    /** Routing mode the radio's contact record currently implies. */
    fun routingMode(keyHex: String): io.github.thatsfguy.meshcore.protocol.RoutingMode =
        _service.value?.engine?.routingMode(keyHex)
            ?: io.github.thatsfguy.meshcore.protocol.RoutingMode.Auto

    /**
     * The route the latest message from [keyHex] arrived on, already
     * REVERSED into a route we could send on.
     *
     * An arrival path runs from the sender outward — hop 0 is the
     * repeater nearest them, the last hop is the one that reached us. A
     * stored out-path runs the other way, so pinning an arrival path
     * unreversed produces a specific-looking route that addresses its
     * hops backwards and cannot work.
     */
    fun replyRouteFromArrival(keyHex: String): StateFlow<Pair<String, Int>?> =
        selfKey.flatMapLatest { key ->
            if (key.isEmpty()) {
                flowOf(null)
            } else {
                db.messages().latestArrival(key, "dm", keyHex).map { m ->
                    val path = m?.arrivalPathHex ?: return@map null
                    val width = m.arrivalHashWidth?.takeIf { it in 1..4 } ?: return@map null
                    io.github.thatsfguy.meshcore.protocol.HeardVia.reverseHex(path, width)
                        .takeIf { it.isNotEmpty() }
                        ?.let { it to width }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun pathHistory(keyHex: String): StateFlow<List<io.github.thatsfguy.meshcore.android.storage.PathHistoryEntity>> =
        selfKey.flatMapLatest { key ->
            if (key.isEmpty()) flowOf(emptyList()) else db.paths().forContact(key, keyHex)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Apply a routing mode; [pathHex] is required for Manual. */
    fun setRouting(
        keyHex: String,
        mode: io.github.thatsfguy.meshcore.protocol.RoutingMode,
        pathHex: String = "",
    ) {
        val svc = _service.value ?: return
        val key = hexToBytesOrNull(keyHex) ?: return
        val path = if (pathHex.isBlank()) ByteArray(0) else hexToBytesOrNull(pathHex)
        if (mode == io.github.thatsfguy.meshcore.protocol.RoutingMode.Manual &&
            (path == null || path.isEmpty())
        ) {
            transientMessage.value = "Enter at least one hop"
            return
        }
        viewModelScope.launch {
            val ok = runCatching {
                svc.engine.setRouting(key, mode, path ?: ByteArray(0))
            }.getOrDefault(false)
            if (ok && path != null && path.isNotEmpty()) {
                svc.repository.rememberPath(selfKey.value, keyHex, path)
            }
            transientMessage.value =
                if (ok) "Routing set to ${mode.name.lowercase()}" else "Radio rejected the route"
        }
    }

    fun deletePath(keyHex: String, pathHex: String) {
        viewModelScope.launch {
            val key = selfKey.value
            if (key.isNotEmpty()) db.paths().delete(key, keyHex, pathHex)
        }
    }

    fun clearPathHistory(keyHex: String) {
        viewModelScope.launch {
            val key = selfKey.value
            if (key.isNotEmpty()) db.paths().clear(key, keyHex)
        }
    }

    /**
     * Run a path trace toward [keyHex] and return the per-hop result,
     * or null on timeout.
     *
     * The route traced is the contact's stored path — that is what a
     * trace is FOR: probing the route you would actually use, so each
     * repeater on it reports back its hash and the SNR it heard. Called
     * without a contact it traced nothing in particular, which is why
     * it appeared to do nothing at all.
     */
    suspend fun tracePath(keyHex: String? = null): io.github.thatsfguy.meshcore.protocol.TraceResult? {
        val svc = _service.value ?: return null
        // Tag correlates the reply; any non-zero value works.
        val tag = (System.currentTimeMillis() and 0xFFFFFFFFL)
        val contact = keyHex?.let { svc.engine.contacts.value[it] }
        val path = contact?.storedPath ?: ByteArray(0)
        val width = deviceInfo.value?.pathHashByteWidth ?: 1
        return runCatching { svc.engine.tracePath(tag, path, width) }.getOrNull()
    }

    fun resetPath(keyHex: String) {
        val key = hexToBytesOrNull(keyHex) ?: return
        viewModelScope.launch {
            val ok = _service.value?.engine?.resetPath(key) ?: false
            transientMessage.value = if (ok) "Path reset" else "Path reset failed"
        }
    }

    fun renameContact(contact: ContactEntity, newName: String) {
        val svc = _service.value ?: return
        val key = hexToBytesOrNull(contact.keyHex) ?: return
        viewModelScope.launch {
            val live = svc.engine.contacts.value[contact.keyHex]
            val ok = runCatching {
                svc.engine.let {
                    // Re-write the radio's contact record with the new name.
                    val frame = io.github.thatsfguy.meshcore.protocol.Frames.addUpdateContact(
                        pubKey = key,
                        type = live?.type ?: contact.type,
                        flags = live?.flags ?: contact.flags,
                        pathLen = live?.pathLen ?: contact.pathLen,
                        path = live?.path ?: ByteArray(0),
                        name = newName,
                        timestampSeconds = System.currentTimeMillis() / 1000,
                        lat = live?.latitude,
                        lon = live?.longitude,
                    )
                    sendRawExpectOk(frame)
                }
            }.getOrDefault(false)
            if (ok) {
                svc.engine.syncContacts()
            } else {
                transientMessage.value = "Rename failed"
            }
        }
    }

    private suspend fun sendRawExpectOk(frame: ByteArray): Boolean {
        // Thin passthrough for commands the engine doesn't wrap 1:1.
        val svc = _service.value ?: return false
        return runCatching { svc.engine.sendRaw(frame) }.getOrDefault(false)
    }

    /**
     * Share URI for this node, in the `meshcore://contact/add?…` form the
     * mainstream MeshCore app emits and scans. Built from local state, so
     * sharing works whether or not the radio answers right now.
     */
    suspend fun selfShareUri(): String? {
        val info = selfInfo.value ?: return null
        val key = info.publicKeyHex.ifBlank { return null }
        return ShareUri.encodeContact(
            name = info.name,
            pubKeyHex = key,
            type = Codes.ADV_TYPE_CHAT,
        )
    }

    /** Share URI for an existing contact, same interoperable form. */
    fun contactShareUri(contact: ContactEntity): String =
        ShareUri.encodeContact(
            name = contact.name,
            pubKeyHex = contact.keyHex,
            type = contact.type,
        )

    /**
     * A scanned contact card awaiting the user's confirmation.
     *
     * Cards are unsigned, so the app never adds one silently: the UI
     * shows the key and asks. Signed advert blobs skip this — the radio
     * verifies those itself.
     */
    val pendingContactCard = MutableStateFlow<ShareUri.Decoded.Contact?>(null)

    /** A scanned channel key awaiting confirmation. */
    val pendingChannelShare = MutableStateFlow<ShareUri.Decoded.ChannelShare?>(null)

    /** Handle a scanned/pasted meshcore:// code, in either form. */
    fun importContactUri(text: String) {
        if (_service.value == null) return
        // Scanned QR data is entirely attacker-controlled: decode is
        // total and returns a typed result rather than throwing.
        when (val decoded = ShareUri.decode(text)) {
            is ShareUri.Decoded.Advert -> importAdvertBlob(decoded.blob)
            is ShareUri.Decoded.Contact -> pendingContactCard.value = decoded
            is ShareUri.Decoded.ChannelShare -> pendingChannelShare.value = decoded
            ShareUri.Decoded.NotAContactCode ->
                transientMessage.value = "Not a meshcore:// contact code"
            ShareUri.Decoded.TooLarge ->
                transientMessage.value = "Contact code too large"
            ShareUri.Decoded.Malformed ->
                transientMessage.value = "Malformed contact code"
        }
    }

    private fun importAdvertBlob(blob: ByteArray) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            val ok = runCatching { svc.engine.importContact(blob) }.getOrDefault(false)
            transientMessage.value =
                if (ok) "Contact imported" else "Import failed (bad signature?)"
        }
    }

    /** Commit a contact card the user has confirmed. */
    fun confirmContactCard(card: ShareUri.Decoded.Contact) {
        val svc = _service.value ?: return
        pendingContactCard.value = null
        val key = hexToBytesOrNull(card.pubKeyHex) ?: return
        viewModelScope.launch {
            val ok = runCatching {
                svc.engine.addContactFromCard(key, card.name, card.type)
            }.getOrDefault(false)
            transientMessage.value = if (ok) "Contact added" else "Add failed"
        }
    }

    fun dismissContactCard() {
        pendingContactCard.value = null
    }

    /** Commit a scanned channel key the user has confirmed. */
    fun confirmChannelShare(share: ShareUri.Decoded.ChannelShare) {
        pendingChannelShare.value = null
        addChannel(share.name.ifBlank { "Shared channel" }, share.pskHex)
    }

    fun dismissChannelShare() {
        pendingChannelShare.value = null
    }

    // ------------------------------------------------------------------
    // Channels
    // ------------------------------------------------------------------

    /** Add a channel with an explicit PSK (hex) or a derived hashtag key. */
    fun addChannel(name: String, pskHexOrEmpty: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            val crypto = io.github.thatsfguy.meshcore.platform.androidCryptoProvider()
            val psk = when {
                pskHexOrEmpty.isNotBlank() -> hexToBytesOrNull(pskHexOrEmpty.replace(" ", ""))
                name.startsWith("#") -> ChannelCrypto.hashtagPsk(crypto, name)
                else -> crypto.randomBytes(16)
            }
            if (psk == null || psk.size != 16) {
                transientMessage.value = "PSK must be 32 hex characters"
                return@launch
            }
            val idx = svc.engine.nextFreeChannelIndex()
            if (idx == null) {
                transientMessage.value = "No free channel slots on the radio"
                return@launch
            }
            val ok = runCatching { svc.engine.setChannel(idx, name, psk) }.getOrDefault(false)
            transientMessage.value = if (ok) "Channel added" else "Channel write failed"
        }
    }

    fun editChannel(index: Int, name: String, pskHex: String) {
        val svc = _service.value ?: return
        val psk = hexToBytesOrNull(pskHex.replace(" ", ""))
        if (psk == null || psk.size != 16) {
            transientMessage.value = "PSK must be 32 hex characters"
            return
        }
        viewModelScope.launch {
            val ok = runCatching { svc.engine.setChannel(index, name, psk) }.getOrDefault(false)
            transientMessage.value = if (ok) "Channel updated" else "Channel write failed"
        }
    }

    fun deleteChannel(index: Int) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            val ok = runCatching { svc.engine.clearChannel(index) }.getOrDefault(false)
            if (ok) db.channels().delete(selfKey.value, index)
            transientMessage.value = if (ok) "Channel removed" else "Channel clear failed"
        }
    }

    /** PSK hex for the channel editor (unsealed on demand, never cached). */
    suspend fun channelPskHex(channel: ChannelEntity): String? {
        val svc = _service.value ?: return null
        // Prefer live engine state; fall back to unsealing the DB row.
        svc.engine.channels.value.firstOrNull { it.index == channel.idx }?.let { return it.pskHex }
        return svc.secrets.unsealPsk(channel.pskSealed)?.toHex()
    }

    /**
     * Join a community from its QR JSON
     * (`{"v":1,"type":"meshcore_community","name":…,"k":base64url}`).
     * Stores K in the keystore and writes the community's public channel
     * to the next free slot.
     */
    fun joinCommunity(qrJson: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            try {
                val json = JSONObject(qrJson)
                require(json.optString("type") == "meshcore_community") { "Not a community code" }
                require(json.optInt("v") == 1) { "Unsupported version" }
                val name = json.getString("name")
                val secret = android.util.Base64.decode(
                    json.getString("k"),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
                )
                require(secret.size == 32) { "Invalid secret length" }

                val crypto = io.github.thatsfguy.meshcore.platform.androidCryptoProvider()
                val communityId = ChannelCrypto.communityId(crypto, secret).toHex()
                svc.secrets.storeCommunitySecret(communityId, secret)

                val psk = ChannelCrypto.communityPublicPsk(crypto, secret)
                val idx = svc.engine.nextFreeChannelIndex()
                if (idx == null) {
                    transientMessage.value = "No free channel slots on the radio"
                    return@launch
                }
                val ok = runCatching { svc.engine.setChannel(idx, name, psk) }.getOrDefault(false)
                transientMessage.value =
                    if (ok) "Joined community \"$name\"" else "Channel write failed"
            } catch (t: Throwable) {
                transientMessage.value = "Invalid community code: ${t.message}"
            }
        }
    }

    // ------------------------------------------------------------------
    // Repeater admin
    // ------------------------------------------------------------------

    /**
     * Session role per node — drives which tools the hub offers.
     *
     * Absent from the map means [AdminSession.None]: no login has been
     * attempted, which is NOT the same as a guest grant. The old
     * `Map<String, Boolean>` collapsed those two, so a node we had
     * never talked to looked exactly like one that had answered
     * "read-only", and the hub had no way to tell whether its
     * read-only surfaces would work.
     */
    private val _adminSessions = MutableStateFlow<Map<String, AdminSession>>(emptyMap())
    val adminSessions: StateFlow<Map<String, AdminSession>> = _adminSessions

    fun session(keyHex: String): AdminSession =
        _adminSessions.value[keyHex] ?: AdminSession.None

    fun isAdminSession(keyHex: String): Boolean = session(keyHex).isAdmin

    /** True while a login round-trip is in flight, per node. */
    private val _loginInFlight = MutableStateFlow<Set<String>>(emptySet())
    val loginInFlight: StateFlow<Set<String>> = _loginInFlight

    /** Last login failure per node, cleared on the next attempt. */
    private val _loginError = MutableStateFlow<Map<String, String>>(emptyMap())
    val loginError: StateFlow<Map<String, String>> = _loginError

    /**
     * Log in to a repeater/room with [password].
     *
     * There is no "log in as guest" option, because there is no such
     * choice to make: you present a password and the NODE decides what
     * it grants, reporting it in the login reply. The old `guest`
     * parameter drove a checkbox that set the session's rights from what
     * the user ticked while the node's own answer was discarded — see
     * [LoginOutcome].
     */
    fun repeaterLogin(keyHex: String, password: String, savePassword: Boolean) {
        // Say why nothing happened. These two returns used to be silent,
        // which was survivable when the password row sat on the admin
        // screen and the radio state was visible beside it; from a modal
        // sign-in dialog a dead button is all the user sees.
        val svc = _service.value
        if (svc == null) {
            _loginError.value = _loginError.value +
                (keyHex to "Not connected to a radio.")
            return
        }
        val key = hexToBytesOrNull(keyHex)
        if (key == null) {
            _loginError.value = _loginError.value +
                (keyHex to "That contact's public key is unreadable.")
            return
        }
        viewModelScope.launch {
            _loginInFlight.value = _loginInFlight.value + keyHex
            _loginError.value = _loginError.value - keyHex
            val outcome = try {
                runCatching { svc.engine.sendLogin(key, password) }
                    .getOrDefault(io.github.thatsfguy.meshcore.engine.LoginOutcome.Failed)
            } finally {
                _loginInFlight.value = _loginInFlight.value - keyHex
            }
            // Only seal a credential the node actually accepted.
            if (outcome.accepted && savePassword) {
                svc.secrets.storeLoginPassword(keyHex, password)
            }
            // What the NODE granted, straight from its reply byte.
            val granted = when {
                !outcome.accepted -> AdminSession.None
                outcome.isAdmin -> AdminSession.Admin
                else -> AdminSession.Guest
            }
            _adminSessions.value = _adminSessions.value + (keyHex to granted)
            if (granted == AdminSession.None) {
                _loginError.value = _loginError.value +
                    (keyHex to "The node rejected that password.")
            }
            transientMessage.value = when (granted) {
                AdminSession.None -> "Login failed"
                AdminSession.Admin -> "Logged in as admin"
                // Say what was GRANTED, not what was asked for. A guest
                // grant is a successful login, not a failure.
                AdminSession.Guest -> "Logged in as guest — read-only"
            }
        }
    }

    /**
     * The saved password for a node.
     *
     * Falls back to the legacy `guest_` slot: before the guest checkbox
     * was removed, a password typed with it ticked was sealed under a
     * different key, and dropping that silently would look like the
     * keystore had lost it.
     */
    suspend fun savedLoginPassword(keyHex: String): String? =
        _service.value?.secrets?.let { s ->
            s.loginPassword(keyHex) ?: s.loginPassword(keyHex, guest = true)
        }

    fun sendCli(keyHex: String, command: String) {
        val svc = _service.value ?: return
        val key = hexToBytesOrNull(keyHex) ?: return
        viewModelScope.launch {
            // The console row is durable and unencrypted, so the stored
            // copy is redacted the same way the diagnostics log is — the
            // clear text exists only in the outbound frame.
            val rowId = svc.repository.recordOutgoingDm(
                keyHex,
                io.github.thatsfguy.meshcore.android.storage.DiagnosticsLog.redact(command),
                System.currentTimeMillis() / 1000,
                txtType = 1,
            )
            val sent = runCatching { svc.engine.sendCliCommand(key, command) }.getOrNull()
            svc.repository.markOutgoingResult(rowId, sent != null, sent?.ackHash)
        }
    }

    fun requestRepeaterStatus(keyHex: String) {
        val svc = _service.value ?: return
        val key = hexToBytesOrNull(keyHex) ?: return
        viewModelScope.launch { runCatching { svc.engine.requestStatus(key) } }
    }

    suspend fun repeaterStatus(keyHex: String): io.github.thatsfguy.meshcore.protocol.RepeaterStatus? {
        val svc = _service.value ?: return null
        val key = hexToBytesOrNull(keyHex) ?: return null
        return runCatching { svc.engine.repeaterStatus(key) }.getOrNull()
    }

    suspend fun repeaterTelemetry(keyHex: String): List<io.github.thatsfguy.meshcore.protocol.TelemetryReading> {
        val svc = _service.value ?: return emptyList()
        val key = hexToBytesOrNull(keyHex) ?: return emptyList()
        return runCatching { svc.engine.requestTelemetry(key) }.getOrDefault(emptyList())
    }

    /** Awaitable CLI round-trip for the form-based remote settings:
     *  sends [command] and returns the node's text reply (or null). */
    suspend fun cliQuery(keyHex: String, command: String): String? {
        val svc = _service.value ?: return null
        val key = hexToBytesOrNull(keyHex) ?: return null
        return runCatching { svc.engine.sendCliAndAwaitReply(key, command) }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Device settings
    // ------------------------------------------------------------------

    fun setAdvertName(name: String) = deviceAction("Name updated") { it.setAdvertName(name) }

    fun setAdvertLocation(lat: Double, lon: Double) =
        deviceAction("Location updated") { it.setAdvertLatLon(lat, lon) }

    fun setRadioParams(freqKhz: Long, bwHz: Long, sf: Int, cr: Int) =
        deviceAction("Radio params updated") { it.setRadioParams(freqKhz, bwHz, sf, cr) }

    fun setTxPower(dbm: Int) = deviceAction("TX power updated") { it.setTxPower(dbm) }

    /**
     * Apply a regional preset: the four LoRa parameters, then TX power.
     *
     * Ordered deliberately. If the parameter write succeeds and the
     * power write fails, the radio is on the right mesh at the wrong
     * power — recoverable, and the user is told. The other order could
     * leave it transmitting at a power chosen for a band it is no
     * longer on.
     */
    fun applyRadioPreset(preset: io.github.thatsfguy.meshcore.protocol.RadioPresets.Preset) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            val paramsOk = runCatching {
                svc.engine.setRadioParams(
                    preset.frequencyKhz, preset.bandwidthHz,
                    preset.spreadingFactor, preset.codingRate,
                )
            }.getOrDefault(false)
            if (!paramsOk) {
                transientMessage.value = "Radio rejected the ${preset.name} parameters"
                return@launch
            }
            val powerOk = runCatching { svc.engine.setTxPower(preset.txPowerDbm) }
                .getOrDefault(false)
            transientMessage.value = if (powerOk) {
                "Applied ${preset.name}"
            } else {
                "Applied ${preset.name}, but TX power stayed unchanged — set it by hand"
            }
        }
    }

    // --- Mesh policy / advanced device settings (CLI-equivalent
    //     companion commands, Settings revamp) ---

    val autoAddFlags: StateFlow<Int?> = _service.flatMapLatest {
        it?.engine?.autoAddFlags ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val customVars: StateFlow<Map<String, String>> = _service.flatMapLatest {
        it?.engine?.customVars ?: flowOf(emptyMap())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val floodScopeRegion: StateFlow<String?> = _service.flatMapLatest {
        it?.engine?.floodScopeRegion ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setOtherParams(telemetryFlags: Int, advertLocPolicy: Int, multiAcks: Int) =
        deviceAction("Policies updated") {
            it.setOtherParams(telemetryFlags, advertLocPolicy, multiAcks)
        }

    fun setAutoAddConfig(flags: Int) =
        deviceAction("Auto-add policy updated") { it.setAutoAddConfig(flags) }

    fun setFloodScope(region: String?) {
        if (!region.isNullOrBlank() && !Regions.isValid(region)) {
            transientMessage.value =
                "Region names are lowercase letters, digits and hyphens (max ${Regions.MAX_NAME_LENGTH})"
            return
        }
        deviceAction(
            if (region.isNullOrBlank()) {
                "Flood scope cleared"
            } else {
                "Flood scope set to #${Regions.canonical(region)}"
            },
        ) { it.setFloodScope(region) }
    }

    /**
     * The region the radio may still be scoped to after a failed
     * restore. Non-null means every flood packet may be carrying it.
     */
    val floodScopeStuck: StateFlow<String?> = _service.flatMapLatest {
        it?.engine?.floodScopeStuck ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * A repeater's one-hop neighbours, each resolved against known
     * contacts. Null = no answer; empty = answered knowing nobody.
     */
    suspend fun repeaterNeighbours(
        keyHex: String,
    ): io.github.thatsfguy.meshcore.protocol.Neighbours.Table? {
        val svc = _service.value ?: return null
        val key = hexToBytesOrNull(keyHex) ?: return null
        return runCatching { svc.engine.requestNeighbours(key) }.getOrNull()
    }

    /** Names a neighbour prefix could belong to — plural stays plural. */
    fun neighbourNames(
        neighbour: io.github.thatsfguy.meshcore.protocol.Neighbours.Neighbour,
    ): List<String> = io.github.thatsfguy.meshcore.protocol.Neighbours
        .resolve(neighbour, dbContacts.value) { it.keyHex }
        .map { it.name.ifBlank { it.keyHex.take(12) } }

    // --- Repeater identity key (PARITY §6) ---

    fun generateIdentityKey(): String =
        io.github.thatsfguy.meshcore.protocol.IdentityKey.generate(crypto)

    fun publicKeyFor(seedHex: String): String? =
        io.github.thatsfguy.meshcore.protocol.IdentityKey.publicKeyHex(crypto, seedHex)

    /**
     * Read a repeater's private key. The reply is returned to the caller
     * and NOT stored, logged or cached — the diagnostics log already
     * redacts `prv.key`, and this keeps it out of everything else.
     */
    suspend fun readIdentityKey(repeaterKeyHex: String): String? {
        val reply = cliQuery(
            repeaterKeyHex,
            io.github.thatsfguy.meshcore.protocol.IdentityKey.getCommand(),
        ) ?: return null
        // Firmware answers "> <hex>"; hand back only something that
        // actually looks like a key, so a "??:" error isn't displayed as
        // if it were one.
        val value = io.github.thatsfguy.meshcore.protocol.CliReplies.extractGetValue(reply)
        return io.github.thatsfguy.meshcore.protocol.IdentityKey.canonicalHex(value)
    }

    /** Replace a repeater's identity key. Not undoable; see IdentityKey. */
    suspend fun replaceIdentityKey(repeaterKeyHex: String, newKeyHex: String): String {
        val command = runCatching {
            io.github.thatsfguy.meshcore.protocol.IdentityKey.setCommand(newKeyHex)
        }.getOrElse { return "Refused: ${it.message}" }

        val reply = cliQuery(repeaterKeyHex, command)
            ?: return "No reply — the change may or may not have applied. Check with `get prv.key`."
        return "Node replied: ${reply.trim()}. Its identity has changed; contacts must " +
            "re-add it."
    }

    // --- Blocking and filtering (PARITY §3) ---

    private val blockRevision = MutableStateFlow(0)

    /** Public keys whose direct messages are dropped — a real block. */
    val blockedKeys: StateFlow<List<String>> = blockRevision
        .map { prefs.blockedKeys.sorted() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.blockedKeys.sorted())

    /** Channel sender names hidden — a noise filter, NOT a block. */
    val filteredChannelNames: StateFlow<List<String>> = blockRevision
        .map { prefs.filteredChannelNames.sorted() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.filteredChannelNames.sorted())

    fun isBlocked(keyHex: String): Boolean = prefs.isKeyBlocked(keyHex)

    fun setBlocked(keyHex: String, blocked: Boolean) {
        if (blocked) {
            if (!prefs.blockKey(keyHex)) {
                transientMessage.value = "That isn't a full public key — can't block it"
                return
            }
        } else {
            prefs.unblockKey(keyHex)
        }
        _service.value?.refreshBlockLists()
        blockRevision.value++
        transientMessage.value = if (blocked) {
            "Blocked. Their direct messages will be dropped, not stored."
        } else {
            "Unblocked."
        }
    }

    fun setChannelNameFiltered(name: String, filtered: Boolean) {
        if (filtered) {
            if (!prefs.filterChannelName(name)) {
                transientMessage.value = "That name can't be filtered"
                return
            }
        } else {
            prefs.unfilterChannelName(name)
        }
        _service.value?.refreshBlockLists()
        blockRevision.value++
        transientMessage.value = if (filtered) {
            "Hiding messages that claim the name \"$name\""
        } else {
            "No longer hiding \"$name\""
        }
    }

    /**
     * Names seen posting on a channel — NOT a membership list; see
     * [io.github.thatsfguy.meshcore.android.storage.ChannelSender].
     */
    suspend fun channelSenders(
        channelIndex: Int,
    ): List<io.github.thatsfguy.meshcore.android.storage.ChannelSender> {
        val key = selfKeyHex()
        if (key.isEmpty()) return emptyList()
        return runCatching {
            db.messages().channelSenders(key, channelIndex.toString())
        }.getOrDefault(emptyList())
    }

    /**
     * Broadcast a discovery request and report which known repeaters
     * answered (PARITY §2 active discovery). Returns their contact
     * entries; a prefix that matches more than one contact yields all
     * of them, and the caller shows the ambiguity rather than picking.
     */
    suspend fun discoverNodes(): List<ContactEntity> {
        val svc = _service.value ?: return emptyList()
        if (engineState.value != EngineState.Ready) return emptyList()
        return runCatching {
            val prefixes = svc.engine.discoverNodePrefixes()
            val known = dbContacts.value
            prefixes.flatMap { p -> NodeDiscovery.matching(p, known) { it.keyHex } }
                .distinctBy { it.keyHex }
        }.getOrDefault(emptyList())
    }

    // --- Retention + purge (PARITY §1, §3) ---

    /** Run the retention sweep now; returns rows removed. */
    suspend fun applyRetentionNow(): Int {
        val svc = _service.value ?: return 0
        return runCatching {
            svc.repository.applyRetention(prefs.retentionPolicy, prefs.channelRetentions())
        }.getOrDefault(0)
    }

    /**
     * Delete this phone's copy of everything. [alsoSecrets] additionally
     * clears the Keystore-sealed material. Never touches the radio.
     */
    suspend fun purgeLocalData(alsoSecrets: Boolean): String {
        val svc = _service.value ?: return "Not connected — nothing to purge against."
        val counts = runCatching { svc.repository.purgeLocal() }
            .getOrElse { return "Purge failed: ${it.message}" }

        // Local-only preference state that mirrors the same history.
        prefs.pinnedThreads = emptySet()
        prefs.mutedChannels = emptySet()
        for (region in prefs.regions) prefs.removeRegion(region)
        if (alsoSecrets) prefs.clearAllSealed()
        regionRevision.value++

        return buildString {
            append("Purged ${counts.messages} message(s), ${counts.contacts} contact(s), ")
            append("${counts.channels} channel(s)")
            if (alsoSecrets) append(", and all stored keys and passwords")
            append(". The radio was not touched.")
        }
    }

    // --- Config backup / restore (PARITY §1) ---

    private val crypto by lazy {
        io.github.thatsfguy.meshcore.platform.androidCryptoProvider()
    }

    private val backupRepo by lazy {
        io.github.thatsfguy.meshcore.android.storage.ConfigBackupRepository(
            prefs,
            io.github.thatsfguy.meshcore.android.storage.SecretsRepository(
                prefs,
                io.github.thatsfguy.meshcore.android.storage.KeystoreSecretVault(),
            ),
            db,
            crypto,
        )
    }

    /** False where the platform can't write a genuinely encrypted file. */
    val supportsBackupEncryption: Boolean get() = crypto.supportsAuthenticatedEncryption

    fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    fun selfKeyHex(): String = selfInfo.value?.publicKeyHex.orEmpty()

    /** Render a backup; null on failure. [passphrase] null = no secrets. */
    suspend fun buildBackup(passphrase: String?): String? = runCatching {
        backupRepo.export(
            selfKeyHex = selfKeyHex(),
            appVersion = appVersionName(),
            nowSeconds = nowSeconds(),
            passphrase = passphrase,
        )
    }.getOrNull()

    suspend fun writeBackupFile(uri: android.net.Uri, text: String): String =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    it.write(text.encodeToByteArray())
                } ?: error("could not open the file for writing")
                "Backup written."
            }.getOrElse { "Backup failed: ${it.message}" }
        }

    /** Read and parse a picked file; null when it isn't a backup. */
    suspend fun readBackupFile(uri: android.net.Uri): ConfigBackup.Parsed? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bytes = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { stream ->
                        // A picked file is arbitrary. Read a bounded
                        // prefix rather than pulling a multi-gigabyte
                        // "backup" into memory on the user's behalf.
                        val buf = ByteArray(MAX_BACKUP_BYTES)
                        var filled = 0
                        while (filled < buf.size) {
                            val n = stream.read(buf, filled, buf.size - filled)
                            if (n <= 0) break
                            filled += n
                        }
                        buf.copyOf(filled)
                    } ?: return@runCatching null
                ConfigBackup.decode(bytes.decodeToString())
            }.getOrNull()
        }

    suspend fun applyBackup(
        parsed: ConfigBackup.Parsed,
        options: io.github.thatsfguy.meshcore.android.storage.ConfigBackupRepository.ApplyOptions,
        passphrase: String?,
    ): String {
        val result = runCatching { backupRepo.apply(parsed, options, passphrase) }
            .getOrElse { return "Restore failed: ${it.message}" }

        // Radio-side writes happen here, one at a time, because they go
        // through the same serialised command queue as everything else.
        var channelsWritten = 0
        val svc = _service.value
        if (svc != null && options.channels) {
            for (channel in parsed.plain.channels) {
                val psk = backupRepo.pendingChannelPsks[channel.index] ?: continue
                val ok = runCatching { svc.engine.setChannel(channel.index, channel.name, psk) }
                    .getOrDefault(false)
                if (ok) channelsWritten++
            }
            backupRepo.pendingChannelPsks.clear()
        }
        regionRevision.value++

        return buildString {
            append("Restored: ${result.settingsRestored} setting(s)")
            if (result.regionsRestored > 0) append(", ${result.regionsRestored} region(s)")
            if (result.secretsRestored > 0) append(", ${result.secretsRestored} secret(s)")
            if (options.channels) {
                // Channels without their key can't be written, and saying
                // "restored 4 channels" when 4 arrived empty would be a lie.
                append(", $channelsWritten of ${parsed.plain.channels.size} channel(s)")
            }
            if (result.contactsQueued > 0) {
                append(". Contacts were not written — import them from the backup's QR flow instead")
            }
            for (skip in result.skipped) append(". Skipped: $skip")
        }
    }

    private fun appVersionName(): String = runCatching {
        val ctx = getApplication<Application>()
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    // --- Regions (PARITY §8) ---

    private val regionRevision = MutableStateFlow(0)

    /** Locally known region names, canonical and sorted. */
    val regions: StateFlow<List<String>> = regionRevision
        .map { prefs.regions }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.regions)

    /** Channel slot → region, for the slots that carry one. */
    val channelRegions: StateFlow<Map<Int, String>> = regionRevision
        .map { prefs.channelRegions() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.channelRegions())

    fun regionFor(channelIndex: Int): String? = prefs.channelRegion(channelIndex)

    fun addRegion(name: String) {
        val added = prefs.addRegion(name)
        transientMessage.value = if (added == null) {
            "Region names are lowercase letters, digits and hyphens (max ${Regions.MAX_NAME_LENGTH})"
        } else {
            "Added #$added"
        }
        regionRevision.value++
    }

    fun removeRegion(name: String) {
        prefs.removeRegion(name)
        regionRevision.value++
        transientMessage.value = "Removed #${Regions.canonical(name) ?: name}"
    }

    fun setChannelRegion(channelIndex: Int, region: String?) {
        prefs.setChannelRegion(channelIndex, region)
        regionRevision.value++
    }

    /**
     * Ask nearby repeaters which regions they know.
     *
     * Two round trips: a discovery broadcast to find who is reachable,
     * then one anonymous request per matching contact. Names come back
     * from other people's nodes, so nothing is stored automatically —
     * the caller shows them and the user decides.
     */
    data class RegionDiscovery(
        val names: List<String> = emptyList(),
        /** Repeaters that answered at all — including with no regions. */
        val answered: Int = 0,
        /** Repeaters we asked. */
        val asked: Int = 0,
    )

    suspend fun discoverRegions(): RegionDiscovery {
        val svc = _service.value ?: return RegionDiscovery()
        if (engineState.value != EngineState.Ready) return RegionDiscovery()
        return runCatching {
            val prefixes = svc.engine.discoverNodePrefixes()
            val contacts = svc.engine.contacts.value.values.filter { it.isRepeater }
            val targets = prefixes
                .flatMap { p -> NodeDiscovery.matching(p, contacts) { it.publicKeyHex } }
                .distinctBy { it.publicKeyHex }
            val found = LinkedHashSet<String>()
            var answered = 0
            for (repeater in targets) {
                // The reply travels the route the request took. We never
                // rewrite the contact's stored path to force a direct
                // answer the way the reference client does — clobbering a
                // pinned route is worse than an unanswered query.
                val hops = repeater.pathInfo.hops.coerceAtLeast(0)
                // null = never answered; empty = answered knowing no
                // named regions (the global scope only). Confirmed on
                // hardware: a repeater replies '*' for that. Reporting
                // both as "nothing found" told the user the mesh was
                // silent when it had in fact replied.
                svc.engine.requestRegions(
                    repeater.publicKey,
                    replyPath = repeater.storedPath,
                    replyHopCount = hops,
                )?.let { answered++; found += it }
            }
            RegionDiscovery(found.toList().sorted(), answered, targets.size)
        }.getOrDefault(RegionDiscovery())
    }

    fun setPathHashMode(mode: Int) =
        deviceAction("Path hash mode set to $mode (${mode + 1} B/hop)") { it.setPathHashMode(mode) }

    fun setCustomVar(key: String, value: String) =
        deviceAction("$key updated") { it.setCustomVar("$key:$value") }

    fun syncDeviceClock() = deviceAction("Radio clock synced") { it.syncDeviceClock() }

    suspend fun deviceTime(): Long? = _service.value?.engine?.deviceTime()

    // Awaitable queries for the settings query-on-expand pattern: each
    // returns once the radio answered (or timed out) so the section can
    // drop its loading spinner. No-ops when not connected.
    suspend fun querySelfInfo() {
        if (engineState.value != EngineState.Ready) return
        runCatching { _service.value?.engine?.refreshSelfInfo() }
    }

    suspend fun queryDeviceInfo() {
        if (engineState.value != EngineState.Ready) return
        runCatching { _service.value?.engine?.refreshDeviceInfo() }
    }

    suspend fun queryAutoAddConfig() {
        if (engineState.value != EngineState.Ready) return
        runCatching { _service.value?.engine?.requestAutoAddConfig() }
    }

    suspend fun queryCustomVars() {
        if (engineState.value != EngineState.Ready) return
        runCatching { _service.value?.engine?.requestCustomVars() }
    }

    /**
     * Write every located node to a GPX file in the app's cache and
     * offer it via a share sheet — nothing leaves the device unless the
     * user picks a target.
     */
    fun exportGpx(@Suppress("UNUSED_PARAMETER") count: Int) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val nodes = dbContacts.value.filter { c ->
                val lat = c.latitude; val lon = c.longitude
                lat != null && lon != null && (kotlin.math.abs(lat) > 1e-6 || kotlin.math.abs(lon) > 1e-6)
            }
            if (nodes.isEmpty()) {
                transientMessage.value = "No nodes with GPS to export"
                return@launch
            }
            val gpx = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                append("<gpx version=\"1.1\" creator=\"MeshCore Mobile\" ")
                append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                for (n in nodes) {
                    val name = n.name.ifBlank { n.keyHex.take(12) }
                        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    append("  <wpt lat=\"${n.latitude}\" lon=\"${n.longitude}\">\n")
                    append("    <name>$name</name>\n")
                    append("    <desc>${n.keyHex}</desc>\n")
                    append("  </wpt>\n")
                }
                append("</gpx>\n")
            }
            val dir = java.io.File(app.cacheDir, "exports").apply {
                // Clear previous exports — an old GPX is node names, keys
                // and GPS sitting around for no reason.
                deleteRecursively()
                mkdirs()
            }
            val file = java.io.File(dir, "meshcore-nodes.gpx")
            file.writeText(gpx)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                app, "${app.packageName}.fileprovider", file,
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(Intent.createChooser(share, "Export nodes").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            transientMessage.value = "Exported ${nodes.size} nodes"
        }
    }

    fun syncContactsNow() {
        val svc = _service.value ?: return
        viewModelScope.launch { runCatching { svc.engine.syncContacts() } }
    }

    fun clearThread(kind: String, peerKey: String) {
        viewModelScope.launch {
            val key = selfKey.value
            if (key.isNotEmpty()) db.messages().clearThread(key, kind, peerKey)
        }
    }

    fun forgetLoginPassword(keyHex: String) {
        _service.value?.secrets?.forgetLoginPassword(keyHex, guest = false)
        _service.value?.secrets?.forgetLoginPassword(keyHex, guest = true)
        _adminSessions.value = _adminSessions.value - keyHex
        _loginError.value = _loginError.value - keyHex
        transientMessage.value = "Saved passwords removed"
    }

    /**
     * Drop the session without touching the sealed password — the hub's
     * "Sign out". Signing out and forgetting the credential are
     * different intentions and the destructive one stays in the menu.
     */
    fun signOutOfNode(keyHex: String) {
        _adminSessions.value = _adminSessions.value - keyHex
        _loginError.value = _loginError.value - keyHex
    }

    fun clearLoginError(keyHex: String) {
        _loginError.value = _loginError.value - keyHex
    }

    fun sendSelfAdvert(flood: Boolean) =
        deviceAction(if (flood) "Flood advert sent" else "Zero-hop advert sent") {
            it.sendSelfAdvert(flood)
        }

    fun rebootRadio() {
        viewModelScope.launch { _service.value?.engine?.reboot() }
        transientMessage.value = "Reboot sent"
    }

    private fun deviceAction(
        successMessage: String,
        action: suspend (io.github.thatsfguy.meshcore.engine.MeshCoreEngine) -> Boolean,
    ) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            val ok = runCatching { action(svc.engine) }.getOrDefault(false)
            transientMessage.value = if (ok) successMessage else "Radio rejected the change"
        }
    }

    private companion object {
        /**
         * Ceiling on a picked backup file. A real one is kilobytes; the
         * cap is what stops a hostile or mistaken pick from being read
         * into memory whole.
         */
        const val MAX_BACKUP_BYTES = 4 * 1024 * 1024
    }
}
