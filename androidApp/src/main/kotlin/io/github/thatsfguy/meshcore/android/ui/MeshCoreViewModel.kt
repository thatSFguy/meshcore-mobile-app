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
import io.github.thatsfguy.meshcore.engine.EngineState
import io.github.thatsfguy.meshcore.engine.MeshEvent
import io.github.thatsfguy.meshcore.model.BatteryAndStorage
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.model.DeviceInfo
import io.github.thatsfguy.meshcore.model.SelfInfo
import io.github.thatsfguy.meshcore.protocol.ChannelCrypto
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    private val db = MeshCoreDatabase.get(app)
    val prefs = Preferences(app)

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

    val conversations: StateFlow<List<ConversationRow>> = combine(
        selfKey.flatMapLatest { key ->
            if (key.isEmpty()) flowOf(emptyList()) else db.messages().latestPerThread(key)
        },
        dbContacts,
        dbChannels,
    ) { latest, contacts, channels ->
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
                        subtitle = "${m.senderName?.plus(": ") ?: ""}${m.text}".take(80),
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
                        title = c?.name?.ifBlank { null } ?: m.peerKey.take(12),
                        subtitle = m.text.take(80),
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
            svc.repository.sendDirectWithRetry(svc.engine, peerKeyHex, text)
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
                svc.engine.sendChannelMessage(channelIndex, text, ts)
            }.getOrDefault(false)
            svc.repository.markChannelResult(contentKey, accepted)
            if (!accepted) transientMessage.value = "Radio did not accept the message"
        }
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

    // --- Routing / paths ---------------------------------------------

    /** Routing mode the radio's contact record currently implies. */
    fun routingMode(keyHex: String): io.github.thatsfguy.meshcore.protocol.RoutingMode =
        _service.value?.engine?.routingMode(keyHex)
            ?: io.github.thatsfguy.meshcore.protocol.RoutingMode.Auto

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

    /** Run a path trace; returns the per-hop result or null on timeout. */
    suspend fun tracePath(): io.github.thatsfguy.meshcore.protocol.TraceResult? {
        val svc = _service.value ?: return null
        // Tag correlates the reply; any non-zero value works.
        val tag = (System.currentTimeMillis() and 0xFFFFFFFFL)
        return runCatching { svc.engine.tracePath(tag) }.getOrNull()
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

    /** Self advert blob for the share-QR (meshcore://<hex>). */
    suspend fun selfShareUri(): String? {
        val svc = _service.value ?: return null
        val blob = runCatching { svc.engine.exportContact() }.getOrNull() ?: return null
        return "meshcore://${blob.toHex()}"
    }

    /** Import a scanned/pasted meshcore:// contact URI. */
    fun importContactUri(text: String) {
        val svc = _service.value ?: return
        val trimmed = text.trim()
        if (!trimmed.startsWith("meshcore://")) {
            transientMessage.value = "Not a meshcore:// contact code"
            return
        }
        val hex = trimmed.removePrefix("meshcore://")
        // Untrusted input: bound the size before decoding (the reference
        // client applies the same guard).
        if (hex.length > 4096) {
            transientMessage.value = "Contact code too large"
            return
        }
        val blob = hexToBytesOrNull(hex)
        if (blob == null) {
            transientMessage.value = "Malformed contact code"
            return
        }
        viewModelScope.launch {
            val ok = runCatching { svc.engine.importContact(blob) }.getOrDefault(false)
            transientMessage.value =
                if (ok) "Contact imported" else "Import failed (bad signature?)"
        }
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

    /** Session role per node — drives which commands the admin UI offers. */
    private val _adminSessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val adminSessions: StateFlow<Map<String, Boolean>> = _adminSessions

    fun isAdminSession(keyHex: String): Boolean = _adminSessions.value[keyHex] ?: false

    fun repeaterLogin(
        keyHex: String,
        password: String,
        savePassword: Boolean,
        guest: Boolean = false,
    ) {
        val svc = _service.value ?: return
        val key = hexToBytesOrNull(keyHex) ?: return
        viewModelScope.launch {
            if (savePassword) svc.secrets.storeLoginPassword(keyHex, password, guest)
            val ok = runCatching { svc.engine.sendLogin(key, password) }.getOrDefault(false)
            // Only an accepted ADMIN login unlocks state-changing commands;
            // a guest session stays read-only in the UI regardless.
            _adminSessions.value = _adminSessions.value + (keyHex to (ok && !guest))
            transientMessage.value = when {
                !ok -> "Login failed"
                guest -> "Guest (read-only) session"
                else -> "Admin session"
            }
        }
    }

    suspend fun savedLoginPassword(keyHex: String, guest: Boolean = false): String? =
        _service.value?.secrets?.loginPassword(keyHex, guest)

    fun sendCli(keyHex: String, command: String) {
        val svc = _service.value ?: return
        val key = hexToBytesOrNull(keyHex) ?: return
        viewModelScope.launch {
            val rowId = svc.repository.recordOutgoingDm(
                keyHex, command, System.currentTimeMillis() / 1000, txtType = 1,
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

    fun setRadioParams(freqHz: Long, bwHz: Long, sf: Int, cr: Int) =
        deviceAction("Radio params updated") { it.setRadioParams(freqHz, bwHz, sf, cr) }

    fun setTxPower(dbm: Int) = deviceAction("TX power updated") { it.setTxPower(dbm) }

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

    fun setFloodScope(region: String?) =
        deviceAction(
            if (region.isNullOrBlank()) "Flood scope cleared" else "Flood scope set to #${region.removePrefix("#")}",
        ) { it.setFloodScope(region) }

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
        transientMessage.value = "Saved passwords removed"
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
}
