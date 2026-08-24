package io.github.thatsfguy.meshcore.android.ui

import io.github.thatsfguy.meshcore.presentation.Inbox
import io.github.thatsfguy.meshcore.model.ChannelList
import io.github.thatsfguy.meshcore.presentation.AdminSession
import io.github.thatsfguy.meshcore.presentation.ChannelScopeJoin
import io.github.thatsfguy.meshcore.presentation.NeighbourFetch
import io.github.thatsfguy.meshcore.presentation.NeighbourRecord
import io.github.thatsfguy.meshcore.presentation.StaleNodes
import io.github.thatsfguy.meshcore.presentation.neighbourWrite
import io.github.thatsfguy.meshcore.protocol.PathCodec
import io.github.thatsfguy.meshcore.protocol.BinaryRequestBudget
import io.github.thatsfguy.meshcore.protocol.PathRecovery
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
import io.github.thatsfguy.meshcore.android.storage.NeighbourEntity
import io.github.thatsfguy.meshcore.android.storage.Preferences
import io.github.thatsfguy.meshcore.engine.EngineState
import io.github.thatsfguy.meshcore.engine.MeshEvent
import io.github.thatsfguy.meshcore.model.BatteryAndStorage
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.model.DeviceInfo
import io.github.thatsfguy.meshcore.model.SelfInfo
import io.github.thatsfguy.meshcore.protocol.HeardRepeats
import io.github.thatsfguy.meshcore.protocol.CliIds
import io.github.thatsfguy.meshcore.protocol.IdentityKeygen
import io.github.thatsfguy.meshcore.protocol.ChannelCrypto
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.ConfigBackup
import io.github.thatsfguy.meshcore.protocol.NodeDiscovery
import io.github.thatsfguy.meshcore.protocol.Quoting
import io.github.thatsfguy.meshcore.protocol.Reactions
import io.github.thatsfguy.meshcore.firmware.writeExpectingAReboot
import io.github.thatsfguy.meshcore.protocol.Regions
import io.github.thatsfguy.meshcore.protocol.ScannedCode
import io.github.thatsfguy.meshcore.protocol.SendRetry
import io.github.thatsfguy.meshcore.protocol.ShareUri
import io.github.thatsfguy.meshcore.transport.ConnectionMemory
import io.github.thatsfguy.meshcore.transport.SavedNode
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
import io.github.thatsfguy.meshcore.util.haversineMetres
import io.github.thatsfguy.meshcore.util.isPlausiblePosition
import io.github.thatsfguy.meshcore.util.toHex
import kotlinx.coroutines.Dispatchers
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
import io.github.thatsfguy.meshcore.presentation.RekeyFlow
import io.github.thatsfguy.meshcore.util.haversineMetres
import io.github.thatsfguy.meshcore.util.isPlausiblePosition
import io.github.thatsfguy.meshcore.protocol.IdentityKey
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

    /**
     * Firmware updates. Its own state machine and its own class: the
     * sequence has seven states of its own and nothing else in this
     * ViewModel touches it.
     */
    val firmware = FirmwareUpdateController(
        context = app,
        scope = viewModelScope,
        crypto = io.github.thatsfguy.meshcore.platform.androidCryptoProvider(),
        serviceProvider = { _service.value },
    )

    /**
     * Whether the connected radio exposes the app-mode DFU service.
     * Read from the live link rather than inferred from the board name —
     * see `BootloaderCapableTransport.offersFirmwareUpdates`.
     */
    fun firmwareUpdatesSupported(): Boolean = _service.value?.offersFirmwareUpdates() == true

    /**
     * Remember the BLE address a node announced when it took
     * `start ota`.
     *
     * This is the only moment the app ever learns a repeater's BLE
     * address — it is reached over the mesh, and the mesh is precisely
     * what it stops answering once it enters update mode. Recording it
     * is what makes a node that gets stuck recoverable afterwards,
     * without standing over it with a serial cable.
     */
    fun rememberOtaAddress(keyHex: String, address: String) {
        val self = selfKey.value
        if (self.isEmpty()) return
        viewModelScope.launch {
            db.contacts().rememberOtaAddress(
                self,
                keyHex,
                address,
                System.currentTimeMillis() / 1000,
            )
        }
    }

    /**
     * Remember what a node said it is.
     *
     * Kept on the contact because the moment this matters most — picking
     * firmware for a node stuck in update mode — the node is sitting in
     * its bootloader and can no longer answer `board`. Asking it live is
     * exactly the thing that stops working when it is needed.
     */
    fun rememberHardware(keyHex: String, board: String?, firmware: String?) {
        if (board == null && firmware == null) return
        val self = selfKey.value
        if (self.isEmpty()) return
        viewModelScope.launch {
            db.contacts().rememberHardware(self, keyHex, board, firmware)
        }
    }

    fun forgetOtaAddress(keyHex: String) {
        val self = selfKey.value
        if (self.isEmpty()) return
        viewModelScope.launch { db.contacts().forgetOtaAddress(self, keyHex) }
    }

    /**
     * Record whether a node is in update mode.
     *
     * The flag and the node's BLE address are separate on purpose: the
     * address is hardware and survives everything, the flag is what the
     * node is doing. See [ContactEntity.updateModeSince].
     *
     * [handledAt] carries the `receivedAt` of the `start ota` reply this
     * is acting on, so the same persisted reply cannot set the flag
     * twice. Clearing stamps the watermark to now, which is what makes a
     * correction stick against a console thread that still holds the
     * reply. See [ContactEntity.otaReplyHandledAt].
     */
    fun setUpdateMode(keyHex: String, inUpdateMode: Boolean, handledAt: Long? = null) {
        val self = selfKey.value
        if (self.isEmpty()) return
        viewModelScope.launch {
            db.contacts().setUpdateMode(
                selfKey = self,
                keyHex = keyHex,
                since = if (inUpdateMode) nowSeconds() else 0L,
                handledAt = handledAt ?: System.currentTimeMillis(),
            )
        }
    }

    /**
     * Take a node out of update mode over BLE.
     *
     * DFU op code 6 is the bootloader's "system reset"
     * (`dfu_transport_ble.c`: `BLE_DFU_SYS_RESET` closes the transport
     * and calls `dfu_reset()`), which boots the application image when
     * there is a valid one. That is the common stuck case: a node that
     * took the jump and then lost the transfer before anything was
     * erased.
     *
     * It cannot rescue a node whose application was already erased — a
     * reset there simply re-enters the bootloader, which is the correct
     * behaviour and the reason the OTAFIX bootloader exists.
     */
    fun exitUpdateMode(keyHex: String, onResult: (String) -> Unit) {
        val svc = _service.value ?: return onResult("The radio service is not running.")
        val contact = dbContacts.value.firstOrNull { it.keyHex == keyHex }
        val announced = contact?.otaAddress
        viewModelScope.launch {
            svc.withRadioHandedOver {
                val scanner = io.github.thatsfguy.meshcore.platform.AndroidDfuScanner(
                    getApplication(),
                ) { svc.diagnostics.log("Firmware", it) }
                // With a recorded address the node is identified exactly:
                // it is advertising either on that address or on the +1
                // its bootloader uses. Without one, fall back to whatever
                // is advertising for an update — `choose` declines when
                // more than one candidate is indistinguishable, so this
                // never picks between two nodes.
                val peer = if (announced != null) {
                    scanner.findBootloader(
                        io.github.thatsfguy.meshcore.firmware.BootloaderExpectation(
                            companionAddress = announced,
                            nameHint = contact.boardName,
                        ),
                        20_000,
                    ) ?: scanner.findBootloader(
                        io.github.thatsfguy.meshcore.firmware.BootloaderExpectation(
                            exactAddress = announced,
                        ),
                        10_000,
                    )
                } else {
                    scanner.findBootloader(
                        io.github.thatsfguy.meshcore.firmware.BootloaderExpectation(
                            nameHint = contact?.boardName,
                        ),
                        20_000,
                    )
                }
                if (peer == null) {
                    onResult(
                        "Nothing in update mode was found nearby. The node has to be within " +
                            "Bluetooth range, and if two are in update mode at once neither " +
                            "is picked — power one down and try again.",
                    )
                    return@withRadioHandedOver
                }
                // Remember it, so the next attempt identifies this node
                // by address rather than by what happened to answer —
                // but only when the address is the NODE's.
                //
                // `otaAddress` means the address the node itself
                // advertises on; everything downstream derives the
                // bootloader's from it by adding one. A peer calling
                // itself AdaDFU is already the bootloader, so recording
                // its address here poisons that arithmetic: the next
                // scan looks one address too high, and the
                // already-in-the-bootloader test says no for a node that
                // plainly is.
                if (!io.github.thatsfguy.meshcore.firmware.BootloaderPeer
                        .isCertainlyBootloader(peer.name)
                ) {
                    rememberOtaAddress(keyHex, peer.address)
                }
                val client = io.github.thatsfguy.meshcore.platform.AndroidDfuGattClient(
                    context = getApplication(),
                    peer = peer,
                    scanned = scanner.deviceFor(peer.address),
                    log = { svc.diagnostics.log("Firmware", it) },
                )
                // Reaching the node is the part that can fail and be
                // reported. The reset write itself cannot be
                // acknowledged — `BLE_DFU_SYS_RESET` closes the
                // transport and reboots inside the handler — so its
                // write callback failing with status 133 is the node
                // OBEYING. Reporting that as "it did not accept the
                // restart" is the same mistake the jump write made, and
                // it printed a failure for every successful restart.
                val result = runCatching {
                    client.connect()
                    client.subscribeToControlPoint()
                    client.writeExpectingAReboot(
                        io.github.thatsfguy.meshcore.firmware.LegacyDfu.SYSTEM_RESET,
                    )
                }
                runCatching { client.close() }
                // It took the reset, so it is on its way out of update
                // mode. The address stays — that is hardware.
                if (result.isSuccess) setUpdateMode(keyHex, false)
                onResult(
                    if (result.isSuccess) {
                        "Told ${peer.name ?: peer.address} (${peer.address}) to restart. If " +
                            "its firmware is intact it will rejoin the mesh shortly; if it " +
                            "was already erased it comes back in update mode, ready to be " +
                            "flashed."
                    } else {
                        "Reached ${peer.name ?: peer.address} (${peer.address}) but it " +
                            "refused the restart: " +
                            (result.exceptionOrNull()?.message ?: "no reason given") + "."
                    },
                )
            }
        }
    }

    /**
     * Copies of our own signed advert that came back off the mesh — the
     * evidence behind "who repeats me" (see [HeardRepeats]).
     */
    val heardRepeats: StateFlow<List<HeardRepeats.Echo>> = _service.flatMapLatest {
        it?.engine?.heardRepeats ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The engine's monotonic clock, for ageing [heardRepeats].
     *
     * Those stamps are monotonic-since-engine-start, so they must be
     * compared against this and never against the wall clock.
     */
    fun engineNowMillis(): Long = _service.value?.engine?.nowMillis() ?: 0L

    fun clearHeardRepeats() {
        _service.value?.engine?.clearHeardRepeats()
    }

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

    /**
     * Every repeater neighbour reading kept for this radio.
     *
     * All repeaters at once because the map draws from it, and the whole
     * store is a few dozen rows — a repeater commonly reports two or
     * three neighbours, and `MAX_NEIGHBOURS` is 50 on every shipped
     * variant.
     */
    val neighbourRecords: StateFlow<List<NeighbourRecord>> = selfKey.flatMapLatest { key ->
        if (key.isEmpty()) flowOf(emptyList()) else db.neighbours().all(key)
    }.map { rows ->
        rows.map {
            NeighbourRecord(
                repeaterKeyHex = it.repeaterKey,
                keyPrefixHex = it.keyPrefixHex,
                snr = it.snr,
                heardSecondsAgo = it.heardSecondsAgo,
                collectedAt = it.collectedAt,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    /**
     * The admin console for a node: CLI traffic only, ordered by local
     * arrival. See `MessageDao.cliThread` for why neither half of that
     * is the same as [thread].
     */
    fun cliThread(peerKey: String): StateFlow<List<MessageEntity>> =
        selfKey.flatMapLatest { key ->
            if (key.isEmpty()) flowOf(emptyList())
            else db.messages().cliThread(key, MessageRepository.KIND_DM, peerKey)
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
        svc.repository.activeThread = Inbox.threadKey(kind, peerKey)
        // Reading it IS dismissing it.
        svc.clearMessageNotification(kind, peerKey)
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

    // The service records the saved-list entry; see MeshCoreService.connect.
    fun connectBle(address: String, name: String?) {
        _service.value?.connect(ConnectionMemory.Ble(address, name))
    }

    fun connectTcp(host: String, port: Int) {
        _service.value?.connect(ConnectionMemory.Tcp(host, port))
    }

    fun connectUsb(device: UsbDevice) {
        _service.value?.connectUsb(device)
    }

    /**
     * Forget a saved node: drop it from the list, stop reconnecting to
     * it, and disconnect if it is the one currently attached.
     *
     * All three, because any two of them leave the app in the state
     * that was reported — a radio connected, absent from Saved nodes,
     * and coming back on its own.
     */
    fun forgetNode(node: SavedNode) {
        val wasCurrent = prefs.reconnectKey() == node.key
        prefs.forgetNode(node.key)
        if (wasCurrent && engineState.value != EngineState.Detached) {
            disconnect()
            transientMessage.value = "Forgotten and disconnected"
        } else {
            transientMessage.value = "Forgotten"
        }
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
        io.github.thatsfguy.meshcore.protocol.AnyReaction.emojiOf(m.text)
            ?.let { return "$it reacted" }
        // A quote-reply's text BEGINS with the quoted message, so
        // take(80) showed what was being replied to and cut off the
        // reply itself — the list read as though everyone were
        // repeating each other.
        return Quoting.previewBody(m.text).take(80)
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
                // Its neighbour table goes with it. Keeping the rows
                // would leave lines drawn from a node the map no longer
                // has a pin for.
                db.neighbours().clear(selfKey.value, keyHex)
            }
        }
    }

    /**
     * Remove every node stale at [olderThanDays] — the Nodes-tab sweep.
     *
     * Deliberately goes through the RADIO, one `removeContact` at a
     * time, exactly as removing a single node does. The radio owns the
     * contact list: deleting rows from our cache alone would look like
     * it worked and then be undone by the next contact sync, which is
     * the kind of failure that teaches a user not to trust the button.
     * The cost is a command round trip per node, which is why the
     * dialog says how many it is about to do.
     *
     * A refusal is counted, never swallowed — see [StaleNodes.outcome].
     * What gets swept (and what is spared) is [StaleNodes.sweep]'s
     * decision, so favourites are safe by a rule that has tests rather
     * than by a filter written twice.
     */
    suspend fun removeStaleNodes(olderThanDays: Int): String {
        val svc = _service.value ?: return "Not connected to a radio."
        val self = selfKey.value
        val sweep = StaleNodes.sweep(
            dbContacts.value,
            olderThanDays,
            System.currentTimeMillis(),
        )
        var removed = 0
        var failed = 0
        for (node in sweep.remove) {
            val key = hexToBytesOrNull(node.keyHex)
            val gone = key != null &&
                runCatching { svc.engine.removeContact(key) }.getOrDefault(false)
            if (gone) {
                db.contacts().delete(self, node.keyHex)
                // Its neighbour readings go too — the same rule as a
                // single removal, and lines drawn from a node with no
                // pin are worse than no lines.
                db.neighbours().clear(self, node.keyHex)
                removed++
            } else {
                failed++
            }
        }
        return StaleNodes.outcome(removed, failed)
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

    /**
     * The route a received message took, laid out for a map.
     *
     * Endpoints come first and last: the sender (whose position we know
     * only if they are a contact who advertises one — a companion node
     * usually is not) and this radio. Returns null when there is no
     * recorded route to draw, which is the honest answer for a flooded
     * message: flood is not "direct", and drawing a straight line from
     * sender to here would invent one.
     */
    fun sketchArrival(
        m: io.github.thatsfguy.meshcore.android.storage.MessageEntity,
        senderLabel: String,
    ): io.github.thatsfguy.meshcore.protocol.PathSketch.Sketch? {
        val width = m.arrivalHashWidth?.takeIf { it in 1..4 } ?: return null
        val pathHex = m.arrivalPathHex?.takeIf { it.isNotEmpty() } ?: return null
        val bytes = io.github.thatsfguy.meshcore.util.hexToBytesOrNull(pathHex) ?: return null
        val contacts = dbContacts.value
        val plot = io.github.thatsfguy.meshcore.protocol.PathGeometry.plot(
            bytes,
            width,
            contacts.map {
                io.github.thatsfguy.meshcore.protocol.PathGeometry.PositionedContact(
                    it.keyHex, it.name, it.latitude, it.longitude,
                )
            },
        )
        if (plot.hops.isEmpty()) return null

        val sender = contacts.firstOrNull { it.keyHex == m.peerKey }
        val chain = buildList {
            add(
                io.github.thatsfguy.meshcore.protocol.PathSketch.Waypoint(
                    label = senderLabel,
                    latitude = sender?.latitude,
                    longitude = sender?.longitude,
                    isEndpoint = true,
                ),
            )
            for (hop in plot.hops) {
                add(
                    io.github.thatsfguy.meshcore.protocol.PathSketch.Waypoint(
                        label = hop.name ?: hop.hashHex,
                        latitude = hop.latitude,
                        longitude = hop.longitude,
                        // An ambiguous or unmatched hop is not merely
                        // position-less: we do not know WHO it was, so it
                        // must never be placed, even approximately.
                        unidentifiedReason = hop.gap?.takeIf {
                            it != io.github.thatsfguy.meshcore.protocol.PathGeometry.Gap.NoPosition
                        }?.let {
                            io.github.thatsfguy.meshcore.protocol.PathGeometry.gapReason(it)
                        },
                    ),
                )
            }
            add(
                io.github.thatsfguy.meshcore.protocol.PathSketch.Waypoint(
                    label = selfInfo.value?.name?.ifBlank { null } ?: "This radio",
                    latitude = selfInfo.value?.latitude,
                    longitude = selfInfo.value?.longitude,
                    isEndpoint = true,
                ),
            )
        }
        return io.github.thatsfguy.meshcore.protocol.PathSketch.build(chain)
    }

    /**
     * A contact's STORED outbound route, laid out for the same map the
     * message info sheet draws.
     *
     * Runs this radio → each hop → the contact, which is the direction
     * the route is used in. When the last hop IS the destination — the
     * usual case for a repeater you route to — it is marked as the
     * endpoint rather than repeated, so the line does not end in a
     * doubled pin.
     *
     * Width comes from the contact's own `pathInfo`, not from
     * DEVICE_INFO: this path was recorded at that width, and the hop
     * width is the defect this codebase keeps re-learning.
     */
    fun sketchStoredPath(
        keyHex: String,
    ): io.github.thatsfguy.meshcore.protocol.PathSketch.Sketch? {
        val live = liveContacts.value[keyHex] ?: return null
        val path = live.storedPath.takeIf { it.isNotEmpty() } ?: return null
        val contacts = dbContacts.value
        val plot = io.github.thatsfguy.meshcore.protocol.PathGeometry.plot(
            path,
            live.pathInfo.hashWidth,
            contacts.map {
                io.github.thatsfguy.meshcore.protocol.PathGeometry.PositionedContact(
                    it.keyHex, it.name, it.latitude, it.longitude,
                )
            },
        )
        if (plot.hops.isEmpty()) return null

        val dest = contacts.firstOrNull { it.keyHex == keyHex }
        return io.github.thatsfguy.meshcore.protocol.PathSketch.build(
            io.github.thatsfguy.meshcore.protocol.PathSketch.outboundChain(
                selfLabel = selfInfo.value?.name?.ifBlank { null } ?: "This radio",
                selfLatitude = selfInfo.value?.latitude,
                selfLongitude = selfInfo.value?.longitude,
                hops = plot.hops,
                destKeyHex = keyHex,
                destLabel = dest?.name?.ifBlank { null } ?: keyHex.take(12),
                destLatitude = dest?.latitude,
                destLongitude = dest?.longitude,
            ),
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
            if (ok) {
                // Remember that this route was CHOSEN, which the radio's
                // contact record cannot tell us later — a pinned path and
                // a learned one look identical to it.
                prefs.setRoutePinned(
                    keyHex,
                    mode == io.github.thatsfguy.meshcore.protocol.RoutingMode.Manual,
                )
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

    /**
     * A scanned settings code awaiting confirmation, never applied.
     *
     * The most consequential thing the scanner can produce: these values
     * decide whether the radio is on a mesh at all, and which frequency
     * it transmits on. The dialog that reads this is the only thing
     * between an anonymous QR and a retuned radio.
     */
    val pendingRadioConfig = MutableStateFlow<ShareUri.Decoded.RadioConfig?>(null)

    /**
     * Apply a confirmed settings code to the attached radio.
     *
     * TX power is deliberately untouched — it is not in the code, and it
     * is the one parameter that is a local legal question rather than a
     * property of the mesh.
     */
    fun confirmRadioConfig(config: ShareUri.Decoded.RadioConfig) {
        pendingRadioConfig.value = null
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching {
                svc.engine.setRadioParams(
                    config.frequencyKhz,
                    config.bandwidthHz,
                    config.spreadingFactor,
                    config.codingRate,
                )
                svc.engine.setPathHashMode(config.pathHashMode)
            }
            transientMessage.value = "Applied ${config.name.ifBlank { "scanned settings" }}"
        }
    }

    /** Handle a scanned/pasted meshcore:// code, in either form. */
    /**
     * Handle any scanned MeshCore code, whichever screen scanned it.
     *
     * The Chats scanner used to hand everything to the community JSON
     * parser and the Nodes scanner to the URI parser, so scanning a
     * repeater's contact QR from Chats produced "Invalid community
     * code" — an error about the app's own screen layout, dressed up as
     * a problem with the code. Which decoder to use is something the
     * app can work out ([ScannedCode]).
     */
    /**
     * Import a code from pasted text — the clipboard, a chat message,
     * anywhere that isn't a camera.
     *
     * The app could only ever import by scanning, which assumes the code
     * exists as an image and that a camera can resolve it. Neither holds
     * for the commonest case: other clients share a contact by copying a
     * `meshcore://` link, and a code photographed off a screen is dense
     * enough that focus and moiré decide whether it reads at all.
     */
    fun importPastedText(raw: String?) {
        val code = ScannedCode.extract(raw.orEmpty())
        if (code == null) {
            transientMessage.value =
                if (raw.isNullOrBlank()) {
                    "Nothing on the clipboard"
                } else {
                    "No MeshCore code on the clipboard"
                }
            return
        }
        importScannedCode(code)
    }

    fun importScannedCode(text: String) {
        when (ScannedCode.classify(text)) {
            ScannedCode.Community -> joinCommunity(text)
            ScannedCode.MeshCoreUri -> importContactUri(text)
            // Let the URI decoder produce the specific complaint; it
            // distinguishes malformed from oversized from not-ours.
            ScannedCode.Unknown -> importContactUri(text)
        }
    }

    fun importContactUri(text: String) {
        if (_service.value == null) return
        // Scanned QR data is entirely attacker-controlled: decode is
        // total and returns a typed result rather than throwing.
        when (val decoded = ShareUri.decode(text)) {
            is ShareUri.Decoded.Advert -> importAdvertBlob(decoded.blob)
            is ShareUri.Decoded.Contact -> pendingContactCard.value = decoded
            is ShareUri.Decoded.ChannelShare -> pendingChannelShare.value = decoded
            // NEVER applied here. A settings code retunes the radio, so
            // it goes to a confirmation that shows every value first —
            // see pendingRadioConfig.
            is ShareUri.Decoded.RadioConfig -> pendingRadioConfig.value = decoded
            is ShareUri.Decoded.UnsupportedVersion ->
                transientMessage.value = "This settings code needs a newer version of the app " +
                    "(format v${decoded.version})"
            ShareUri.Decoded.NotAContactCode ->
                transientMessage.value = "Not a MeshCore code — expected a contact, channel, " +
                    "settings or community QR"
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
        addChannel(
            share.name.ifBlank { "Shared channel" },
            share.pskHex,
            // The RAW scope, not the canonical one. Handing over the
            // decoder's answer meant an unusable region arrived here as
            // "" — indistinguishable from a code that carried no scope
            // at all — and the join reported "Channel added" for a
            // channel that will now flood globally.
            regionScope = share.rawRegionScope,
        )
    }

    fun dismissChannelShare() {
        pendingChannelShare.value = null
    }

    // ------------------------------------------------------------------
    // Channels
    // ------------------------------------------------------------------

    /**
     * Add a channel with an explicit PSK (hex) or a derived hashtag key.
     *
     * [regionScope] is the flood scope a shared code asked for. It is
     * not cosmetic: it decides how far this channel's messages travel,
     * so a code that carries one and an app that ignores it produce a
     * channel that floods globally while its owner believes it is
     * contained.
     */
    fun addChannel(name: String, pskHexOrEmpty: String, regionScope: String = "") {
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
            // Joining is idempotent, and keyed on the SECRET rather than
            // the name — the key is the channel; the name is a local
            // label. Without this, scanning a code you already hold
            // spends another of the radio's eight slots on a duplicate,
            // and inbound traffic then matches two slots at once.
            val existing = ChannelList.findByPsk(svc.engine.channels.value, psk.toHex())
            if (existing != null) {
                val label = if (existing.name.equals(name, ignoreCase = true)) {
                    "\"${existing.name}\""
                } else {
                    // Say the local name. Someone who called it something
                    // else needs to know WHICH channel they already have,
                    // or the message reads as a mistake.
                    "this channel — you call it \"${existing.name}\""
                }
                // Refusing the extra slot is right; refusing the SCOPE
                // with it was not. Re-sharing the same key with a region
                // added is exactly how a community rolls one out, and
                // every existing member used to get "Already in this
                // channel" and carry on flooding globally.
                transientMessage.value = "Already in $label" + applyChannelScope(existing.index, regionScope)
                return@launch
            }
            val idx = svc.engine.nextFreeChannelIndex()
            if (idx == null) {
                transientMessage.value = if (!svc.engine.channelsKnown) {
                    // Not the same fact as "full", and the difference
                    // decides whether trying again helps.
                    "Still reading the radio's channels — try again in a moment"
                } else {
                    "No free channel slots on the radio"
                }
                return@launch
            }
            val ok = runCatching { svc.engine.setChannel(idx, name, psk) }.getOrDefault(false)
            if (!ok) {
                transientMessage.value = "Channel write failed"
                return@launch
            }
            // The scope belongs to the slot, so it can only be recorded
            // once the slot is known — and only if the write worked, or
            // a failed join would leave a scope pointing at a channel
            // that isn't there.
            transientMessage.value = "Channel added" + applyChannelScope(idx, regionScope)
        }
    }

    /**
     * Record the flood scope a shared code asked for against channel
     * slot [index], and return the clause to append to the join
     * message. Empty when the code carried no scope.
     *
     * [rawScope] is the code's own spelling, NOT a canonical name: this
     * is the only place that can tell "no scope" from "a scope this
     * build can't use", and the second has to be said out loud —
     * joining unscoped floods wider than the person sharing intended.
     *
     * An existing, *different* local scope is reported rather than
     * overwritten. A narrower scope the user chose by hand is a
     * deliberate routing decision, and a QR is not authority to undo it.
     */
    private fun applyChannelScope(index: Int, rawScope: String): String {
        val outcome = ChannelScopeJoin.decide(
            rawScope = rawScope,
            currentRegion = prefs.channelRegion(index),
            knownRegions = prefs.regions,
        )
        if (outcome is ChannelScopeJoin.Outcome.Applied) {
            // A scope the radio does not know aborts the send rather
            // than widening it, so adding the region locally is what
            // makes the channel usable. Doing it silently would be
            // wrong the other way: this is a routing change the user
            // did not ask for by name — hence the message.
            prefs.setChannelRegion(index, outcome.region)
            if (outcome.newRegion) prefs.addRegion(outcome.region)
            // Both writes go through prefs directly, so nothing else
            // bumps the revision the region UI is built on — without
            // this the channel editor reports "Unscoped: messages flood
            // the whole mesh" for a channel whose sends are
            // demonstrably scoped.
            regionRevision.value++
        }
        return ChannelScopeJoin.describe(outcome)
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
            if (ok) {
                db.channels().delete(selfKey.value, index)
                // Everything keyed by SLOT goes with the slot — the
                // radio hands it straight back to the next join. See
                // Preferences.forgetChannelSlot for why that is one
                // function rather than a line per preference.
                prefs.forgetChannelSlot(index)
                regionRevision.value++
            }
            transientMessage.value = if (ok) "Channel removed" else "Channel clear failed"
        }
    }

    /** PSK hex for the channel editor (unsealed on demand, never cached). */
    suspend fun channelPskHex(channel: ChannelEntity): String? {
        val svc = _service.value ?: return null
        // Prefer live engine state; fall back to unsealing the DB row.
        svc.engine.channels.value.firstOrNull { it.index == channel.idx }?.let { return it.pskHex }
        if (channel.pskSealed.isEmpty()) return null // never sealed — see persistChannel
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
                // Stop here if the secret cannot be kept. K derives every
                // channel key in the community; without it this join
                // writes one channel slot and throws away the only thing
                // that could ever produce the others — and the QR is
                // usually gone by the time anyone notices.
                if (!svc.secrets.storeCommunitySecret(communityId, secret)) {
                    transientMessage.value = "This device's keystore refused to store the " +
                        "community secret, so the community was not joined"
                    return@launch
                }

                val psk = ChannelCrypto.communityPublicPsk(crypto, secret)
                val idx = svc.engine.nextFreeChannelIndex()
                if (idx == null) {
                    transientMessage.value = if (!svc.engine.channelsKnown) {
                        "Still reading the radio's channels — try again in a moment"
                    } else {
                        "No free channel slots on the radio"
                    }
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

    /**
     * What the radio said when it put a request to this node on the air,
     * per node key — cleared when the answer arrives or the wait ends.
     *
     * The only genuine progress a LoRa request has. A spinner alone
     * reads the same whether the radio never transmitted, the request
     * is in flight, or the node's own estimate has already passed.
     */
    private val _requestInFlight =
        MutableStateFlow<Map<String, BinaryRequestBudget.InFlight>>(emptyMap())
    val requestInFlight: StateFlow<Map<String, BinaryRequestBudget.InFlight>> = _requestInFlight

    private fun markInFlight(keyHex: String, inFlight: BinaryRequestBudget.InFlight?) {
        _requestInFlight.value = if (inFlight == null) {
            _requestInFlight.value - keyHex
        } else {
            _requestInFlight.value + (keyHex to inFlight)
        }
    }

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
        viewModelScope.launch {
            val result = performLogin(keyHex, password, savePassword)
            // A guard that stopped this before it reached the radio has
            // already said why, in the dialog, next to the button that
            // did nothing. A transient "No answer from the node" on top
            // of it would name the wrong culprit.
            if (!result.attempted) return@launch
            val sealNote = if (result.sealFailed) {
                " — but this device's keystore refused to store the password, " +
                    "so you'll need to type it again next time"
            } else {
                ""
            }
            transientMessage.value = when {
                result.session == AdminSession.Admin -> "Logged in as admin$sealNote"
                // Say what was GRANTED, not what was asked for. A guest
                // grant is a successful login, not a failure.
                result.session == AdminSession.Guest -> "Logged in as guest — read-only$sealNote"
                result.answered -> "Password rejected"
                else -> "No answer from the node"
            }
        }
    }

    /** What one login round trip settled. */
    private data class LoginResult(
        val session: AdminSession,
        /** The node replied at all — a refusal is still an answer. */
        val answered: Boolean,
        val sealFailed: Boolean,
        /** False when a guard stopped this before the radio was asked. */
        val attempted: Boolean = true,
    )

    /**
     * One login round trip: present the password, record what the node
     * granted, and leave the error state fit to read.
     *
     * Separate from [repeaterLogin] so a caller that needs a session in
     * order to do something else — [collectNeighbours] — can wait for
     * one without going through the dialog, and without a second copy
     * of this logic drifting away from it. Everything user-facing that
     * is about the login ITSELF (the transient message) stays with the
     * dialog; a fetch that happens to sign in reports on the fetch.
     */
    private suspend fun performLogin(
        keyHex: String,
        password: String,
        savePassword: Boolean,
    ): LoginResult {
        // Say why nothing happened. These two returns used to be silent,
        // which was survivable when the password row sat on the admin
        // screen and the radio state was visible beside it; from a modal
        // sign-in dialog a dead button is all the user sees.
        val svc = _service.value
        if (svc == null) {
            _loginError.value = _loginError.value + (keyHex to "Not connected to a radio.")
            return LoginResult(AdminSession.None, false, sealFailed = false, attempted = false)
        }
        val key = hexToBytesOrNull(keyHex)
        if (key == null) {
            _loginError.value = _loginError.value +
                (keyHex to "That contact's public key is unreadable.")
            return LoginResult(AdminSession.None, false, sealFailed = false, attempted = false)
        }
        _loginInFlight.value = _loginInFlight.value + keyHex
        _loginError.value = _loginError.value - keyHex
        val outcome = try {
            runCatching {
                svc.engine.sendLoginWithRetry(
                    key,
                    password,
                    floodFallbackEnabled = prefs.floodFallbackOnLastRetry,
                )
            }.getOrDefault(io.github.thatsfguy.meshcore.engine.LoginOutcome.NoAnswer)
        } finally {
            _loginInFlight.value = _loginInFlight.value - keyHex
        }
        // Only seal a credential the node actually accepted.
        //
        // The result is not optional. On a device whose Keystore
        // rejects every key spec — which KeystoreSecretVault
        // documents as real, on shipped Samsung firmware — this
        // returns false and stores nothing, and dropping that
        // meant the dialog offered "Save password", said nothing,
        // and asked again next session.
        var sealFailed = false
        if (outcome.accepted && savePassword) {
            sealFailed = !svc.secrets.storeLoginPassword(keyHex, password)
        }
        // What the NODE granted, straight from its reply byte.
        val granted = when {
            !outcome.accepted -> AdminSession.None
            outcome.isAdmin -> AdminSession.Admin
            else -> AdminSession.Guest
        }
        _adminSessions.value = _adminSessions.value + (keyHex to granted)
        // A node that answered at all is not in update mode.
        //
        // The Nordic bootloader is a BLE-only image — it has no LoRa
        // stack, and MeshCore's firmware is not running underneath
        // it to receive anything. So any reply to a login, including
        // "wrong password", is proof the application is up, and it
        // is the cheapest evidence this app ever gets. Not using it
        // is what left a node that had been reflashed over USB and
        // signed straight back into still described as advertising
        // for an update.
        if (outcome.answered) setUpdateMode(keyHex, false)
        if (granted == AdminSession.None) {
            // A refusal and a silence are different problems with
            // different fixes — check the password, or get closer.
            _loginError.value = _loginError.value + (
                keyHex to if (outcome.answered) {
                    "The node rejected that password."
                } else {
                    "No answer from the node after ${SendRetry.DEFAULT_MAX_ATTEMPTS} tries."
                }
                )
        }
        return LoginResult(granted, answered = outcome.answered, sealFailed = sealFailed)
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

    /**
     * Run an admin request, repairing the route if the node goes quiet.
     *
     * This is what "sign out and sign back in" was actually doing. The
     * repeater's ACL entry never expires — there is no session to lose —
     * so the only thing a re-login fixed was the cached return path,
     * and only when it happened to go out as a flood. [PathRecovery]
     * does that deliberately, and does the free probe before it spends
     * the password.
     *
     * Recovery is gated on **being signed in**. For a node we were never
     * signed into, a blank-password probe cannot be answered and the
     * escalation would only spend the user's time confirming that the
     * node is not talking to us.
     */
    private suspend fun <T> withPathRecovery(
        keyHex: String,
        request: suspend () -> T?,
    ): T? {
        val svc = _service.value ?: return null
        val key = hexToBytesOrNull(keyHex) ?: return null
        val attempt: suspend () -> T? = { runCatching { request() }.getOrNull() }
        if ((_adminSessions.value[keyHex] ?: AdminSession.None) == AdminSession.None) {
            return attempt()
        }
        // A route the user pinned cannot be repaired from here: the
        // repeater only drops its stale return path for a FLOODED login,
        // and a contact with a pinned path never floods. Recovery would
        // overwrite the pin, fail anyway, and bill the user ninety
        // seconds and a cleartext password for the privilege — measured,
        // on hardware, not assumed.
        if (!PathRecovery.shouldAttempt(pinned = prefs.isRoutePinned(keyHex))) {
            return attempt().also {
                if (it == null) transientMessage.value = PathRecovery.PINNED_MESSAGE
            }
        }
        var repairedAnything = false
        val result = runCatching {
            svc.engine.withPathRecovery(
                repeaterPubKey = key,
                password = savedLoginPassword(keyHex),
                onStage = { stage ->
                    repairedAnything = true
                    PathRecovery.progressLabel(stage)?.let { transientMessage.value = it }
                },
                request = attempt,
            )
        }.getOrNull()
        // Only claim the route was the problem if we actually tried to
        // fix it — otherwise this is an ordinary unanswered request and
        // the caller says so in its own words.
        if (result == null && repairedAnything) {
            transientMessage.value = PathRecovery.EXHAUSTED_MESSAGE
        }
        return result
    }

    suspend fun repeaterStatus(keyHex: String): io.github.thatsfguy.meshcore.protocol.RepeaterStatus? {
        val svc = _service.value ?: return null
        val key = hexToBytesOrNull(keyHex) ?: return null
        return try {
            withPathRecovery(keyHex) {
                svc.engine.repeaterStatus(key) { markInFlight(keyHex, it) }
            }
        } finally {
            markInFlight(keyHex, null)
        }
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
        offset: Int = 0,
    ): io.github.thatsfguy.meshcore.protocol.Neighbours.Table? {
        val svc = _service.value ?: return null
        val key = hexToBytesOrNull(keyHex) ?: return null
        val request = io.github.thatsfguy.meshcore.protocol.Neighbours.Request(offset = offset)
        val table = try {
            withPathRecovery(keyHex) {
                svc.engine.requestNeighbours(key, request) { markInFlight(keyHex, it) }
            }
        } finally {
            markInFlight(keyHex, null)
        }
        if (table != null) recordNeighbours(keyHex, table)
        return table
    }

    /**
     * Keep a page of neighbours, stamped with the clock that read it.
     *
     * Recording happens HERE, on the one call every neighbour fetch
     * goes through, rather than at the screen that asked. Two screens
     * ask already (the repeater's Status panel and the map) and a
     * reading is worth the same whichever did — a table costs a login
     * and a round trip over the air, so throwing one away because the
     * user happened to be on the other screen is the kind of waste that
     * makes a feature feel broken.
     *
     * What a page does to what is already stored is [neighbourWrite]'s
     * decision, not this function's.
     */
    private suspend fun recordNeighbours(
        keyHex: String,
        table: io.github.thatsfguy.meshcore.protocol.Neighbours.Table,
    ) {
        val self = selfKey.value
        if (self.isEmpty()) return
        val plan = neighbourWrite(
            offset = table.offset,
            entryCount = table.entries.size,
            rejected = table.isEmptyButNotEmpty,
        )
        if (plan.clearFirst) db.neighbours().clear(self, keyHex)
        if (!plan.store) return
        // One stamp for the whole page: they were all read in the same
        // reply, and dating them individually would invent a precision
        // the wire does not carry.
        val collectedAt = System.currentTimeMillis()
        db.neighbours().upsertAll(
            table.entries.map {
                NeighbourEntity(
                    selfKey = self,
                    repeaterKey = keyHex,
                    keyPrefixHex = it.keyPrefixHex,
                    snr = it.snr,
                    heardSecondsAgo = it.heardSecondsAgo,
                    collectedAt = collectedAt,
                )
            },
        )
    }

    /**
     * Read a repeater's whole neighbour table, signing in first if that
     * is what it takes — the map's one-tap path.
     *
     * The credential is chosen, not asked for: the open session if there
     * is one, then the password saved for this node, and otherwise a
     * BLANK one. Blank is the ordinary way in, not a trick — the
     * firmware falls through to the guest slot, which ships empty
     * (`MyMesh.cpp:90-107`), and `REQ_TYPE_GET_NEIGHBOURS` carries no
     * `isAdmin()` gate the way `GET_ACCESS_LIST` does, so a guest
     * session can read the table. A blank password is never sealed:
     * there is nothing there to keep.
     *
     * Paging is capped at [NEIGHBOUR_PAGE_LIMIT]. A `total` off the mesh
     * is attacker-controlled and 50 rows is the firmware's own ceiling,
     * so this cannot be talked into an unbounded round-trip loop — and
     * when the cap does bite, the outcome says "x of y", never a bare
     * count that would read as the whole table.
     */
    suspend fun collectNeighbours(keyHex: String): NeighbourFetch {
        _service.value ?: return NeighbourFetch.NotConnected
        val session = _adminSessions.value[keyHex] ?: AdminSession.None
        if (!session.signedIn) {
            val saved = savedLoginPassword(keyHex)
            val login = performLogin(keyHex, saved ?: "", savePassword = false)
            if (!login.session.signedIn) {
                // `answered` is carried through because a repeater that
                // turns a password down replies with nothing at all, so
                // "refused" and "out of reach" are the same silence and
                // only the node's having spoken can tell them apart.
                return NeighbourFetch.SignInRefused(
                    blank = saved.isNullOrEmpty(),
                    answered = login.answered,
                )
            }
        }
        var stored = 0
        var total = 0
        var offset = 0
        repeat(NEIGHBOUR_PAGE_LIMIT) {
            val table = repeaterNeighbours(keyHex, offset)
                ?: return if (offset == 0) {
                    NeighbourFetch.NoAnswer
                } else {
                    NeighbourFetch.Collected(stored, total)
                }
            if (table.isEmptyButNotEmpty) return NeighbourFetch.Rejected(table.total)
            stored += table.entries.size
            total = table.total
            offset = table.nextOffset
            if (!table.isPartial || table.entries.isEmpty()) {
                return NeighbourFetch.Collected(stored, total)
            }
        }
        return NeighbourFetch.Collected(stored, total)
    }

    /** True when this node has a password sealed in the keystore. */
    suspend fun hasSavedLoginPassword(keyHex: String): Boolean =
        !savedLoginPassword(keyHex).isNullOrEmpty()

    /** Forget a repeater's stored neighbour table. */
    fun forgetNeighbours(keyHex: String) {
        viewModelScope.launch { db.neighbours().clear(selfKey.value, keyHex) }
    }

    /**
     * Ask a repeater to go looking for neighbours, rather than waiting
     * to be advertised at.
     *
     * A neighbour table is populated ONLY by zero-hop adverts that
     * happen to arrive (`MyMesh.cpp:641`); nothing polls, and nothing
     * expires. So it says "who advertised directly since this node
     * booted", not "who is in range" — a repeater sitting at a
     * perfectly good 3.5 dB can be missing for hours. `discover.neighbors`
     * broadcasts a request that makes them answer, and the replies land
     * in the table by the same path an advert would.
     *
     * Admin only: it goes through the node's admin CLI. Returns false
     * when the node did not take the command.
     */
    suspend fun probeNeighbours(keyHex: String): Boolean {
        val svc = _service.value ?: return false
        val key = hexToBytesOrNull(keyHex) ?: return false
        return runCatching {
            svc.engine.sendCliCommand(key, "discover.neighbors") != null
        }.getOrDefault(false)
    }

    /**
     * A repeater's access list, over the binary request.
     *
     * NOT `get acl`: that command only answers the serial console, so
     * asking for it over the air returned "??: acl" and looked like old
     * firmware. See AccessList.
     */
    suspend fun repeaterAccessList(
        keyHex: String,
    ): List<io.github.thatsfguy.meshcore.protocol.AccessList.BinEntry>? {
        val svc = _service.value ?: return null
        val key = hexToBytesOrNull(keyHex) ?: return null
        return try {
            withPathRecovery(keyHex) {
                svc.engine.requestAccessList(key) { markInFlight(keyHex, it) }
            }
        } finally {
            markInFlight(keyHex, null)
        }
    }

    /** Names a neighbour prefix could belong to — plural stays plural. */
    fun neighbourNames(
        neighbour: io.github.thatsfguy.meshcore.protocol.Neighbours.Neighbour,
    ): List<String> = io.github.thatsfguy.meshcore.protocol.Neighbours
        .resolve(neighbour, dbContacts.value) { it.keyHex }
        .map { it.name.ifBlank { it.keyHex.take(12) } }

    // --- Repeater identity key (PARITY §6) ---

    /**
     * The bytes of a public key that name a node on air, for the node at
     * [repeaterKeyHex].
     *
     * Asked of that node rather than assumed, because the width is a
     * property of the MESH and this phone's radio is only one member of
     * it — a repeater configured differently is exactly the case where a
     * generated key would come out useless. Its own radio's
     * `DEVICE_INFO` is the fallback when the node does not answer, and 1
     * byte the fallback for that (the firmware default,
     * `path_hash_mode` = 0).
     */
    suspend fun repeaterPathHashWidth(repeaterKeyHex: String): Int {
        val reply = cliQuery(repeaterKeyHex, "get ${CliIds.PATH_HASH_MODE}")
        val mode = reply
            ?.let { io.github.thatsfguy.meshcore.protocol.CliReplies.extractGetValue(it) }
            ?.trim()?.toIntOrNull()
            ?.takeIf { io.github.thatsfguy.meshcore.protocol.PathHashMode.isValid(it) }
        if (mode != null) {
            return io.github.thatsfguy.meshcore.protocol.PathHashMode.bytesFor(mode)
        }
        return deviceInfo.value?.pathHashByteWidth?.takeIf { it in 1..4 } ?: 1
    }

    /**
     * Every node this phone knows, ranked by how bad it would be to
     * share leading bytes with — its own radio included.
     *
     * The node being rekeyed is in here too, via its contact record, and
     * that is deliberate: a new key that lands on the node's OWN old
     * prefix is the worst outcome of the lot, because every stale route
     * on the mesh would keep matching it and every packet arriving that
     * way would then fail to decrypt.
     *
     * Two things rank a node, and both come out of the contact database
     * rather than out of a guess: what it is (only repeaters and room
     * servers appear in a packet's path at all) and how far away it is —
     * great-circle metres from this radio where both have advertised a
     * position, hops from the stored route otherwise. A node with
     * neither is ranked as if it were next door, because a node we
     * cannot place is not one we may call distant.
     */
    fun knownNodes(): List<IdentityKeygen.KnownNode> {
        val self = selfInfo.value
        val selfLat = self?.latitude
        val selfLon = self?.longitude
        val selfPlaced = isPlausiblePosition(selfLat, selfLon)

        return buildList {
            // Our own radio, ranked worst on purpose: nothing on the
            // mesh is closer than the thing in your hand, and a repeater
            // that answers to our own destination hash is the one clash
            // guaranteed to be in every path we care about.
            self?.let {
                add(
                    IdentityKeygen.KnownNode(
                        publicKeyHex = it.publicKeyHex,
                        label = (it.name.ifBlank { "this radio" }) + " (this radio)",
                        remoteness = IdentityKeygen.Remoteness.UNKNOWN,
                    ),
                )
            }
            for (contact in dbContacts.value) {
                val distance = if (selfPlaced &&
                    isPlausiblePosition(contact.latitude, contact.longitude)
                ) {
                    haversineMetres(
                        selfLat!!, selfLon!!, contact.latitude!!, contact.longitude!!,
                    )
                } else {
                    null
                }
                val hops = PathCodec.decodePathLen(contact.pathLen)
                    .takeIf { !it.isFlood }?.hops
                val isInfrastructure = contact.type == Codes.ADV_TYPE_REPEATER ||
                    contact.type == Codes.ADV_TYPE_ROOM
                add(
                    IdentityKeygen.KnownNode(
                        publicKeyHex = contact.keyHex,
                        label = (contact.name.ifBlank { contact.keyHex.take(12) }) + ", " +
                            IdentityKeygen.Remoteness.describe(distance, hops),
                        remoteness = IdentityKeygen.Remoteness.of(
                            distance, hops, isInfrastructure,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * A replacement identity whose leading [widthBytes] bytes are nobody
     * else's — see [IdentityKeygen] for why that is the thing that
     * matters and not the key as a whole. [widthBytes] comes from
     * [repeaterPathHashWidth], which asks the node itself.
     *
     * The search runs on [Dispatchers.Default]: it is thousands of
     * SHA-512s and scalar multiplications in the worst case, and the one
     * thing this screen must not do is stall while it decides.
     */
    suspend fun generateIdentityKey(widthBytes: Int): IdentityKeygen.Outcome? {
        val known = knownNodes()
        return withContext(Dispatchers.Default) {
            IdentityKeygen.generate(crypto, widthBytes, known)
        }
    }

    /**
     * Metres from this radio to a contact, or null when either end has
     * no usable position.
     *
     * Used to order the candidates of an ambiguous hop: two nodes that
     * share a truncated hash are told apart by where they are, and the
     * one you can see from here is the likelier carrier of a packet that
     * reached here. It never picks one — [TracePath.Hop] still names
     * every candidate; this only decides which is read first.
     *
     * Null for a node that has never advertised a position, and null for
     * everything when this radio has none: a missing position is
     * unknown, not "here", and the ordering keeps it last rather than
     * ranking it as the nearest thing on the mesh.
     */
    fun metresFromThisRadio(): (String) -> Double? {
        val self = _service.value?.engine?.selfInfo?.value
        if (self == null || !isPlausiblePosition(self.latitude, self.longitude)) {
            return { null }
        }
        val positions = dbContacts.value
            .filter { isPlausiblePosition(it.latitude, it.longitude) }
            .associate { it.keyHex.lowercase() to (it.latitude!! to it.longitude!!) }
        return { keyHex ->
            positions[keyHex.lowercase()]?.let { (lat, lon) ->
                haversineMetres(self.latitude, self.longitude, lat, lon)
            }
        }
    }

    fun publicKeyFor(seedHex: String): String? =
        io.github.thatsfguy.meshcore.protocol.IdentityKey.publicKeyHex(crypto, seedHex)

    /**
     * Nodes already answering to the leading [widthBytes] bytes of
     * [publicKeyHex] — so a hand-typed key can be checked against the
     * same rule the generator applies.
     */
    fun nodesSharingPrefix(publicKeyHex: String, widthBytes: Int): List<String> {
        val width = IdentityKeygen.clampWidth(widthBytes)
        val prefix = publicKeyHex.trim().lowercase().take(width * 2)
        if (prefix.length < width * 2) return emptyList()
        return dbContacts.value
            .filter { it.keyHex.lowercase().startsWith(prefix) }
            .map { it.name.ifBlank { it.keyHex.take(12) } }
    }

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

    /**
     * Replace a repeater's identity key, optionally restarting it, and
     * write the identity it reports into this radio's contact list.
     *
     * Not undoable; see [IdentityKey]. The steps are ordered so that
     * each one only runs on evidence from the last:
     *
     *  1. `set prv.key`. The node stores it and replies with the PUBLIC
     *     half ([IdentityKey.parseRekeyReply]). This is the only copy of
     *     that key the app ever gets without waiting for an advert, and
     *     the reason for the whole sequence — see
     *     [IdentityKey.NEW_IDENTITY_IS_NOT_ANNOUNCED].
     *  2. Cross-check it, when this phone can. A key we generated (or a
     *     seed that was typed) has a public half we can compute. If the
     *     node reports a different one, something is wrong that no
     *     amount of retrying fixes, and NOTHING further is done.
     *  3. Adopt the new identity locally, carrying the things that
     *     describe the hardware rather than the key: name, type,
     *     favourite mark, BLE address, nickname, saved password.
     *  4. Reboot, if asked. Never confirmable — see
     *     [IdentityKey.REBOOT_HAS_NO_ANSWER].
     *  5. Prove it, if we can: sign in to the NEW identity with the same
     *     password. A granted session is the only positive evidence the
     *     node restarted and is running the key.
     */
    suspend fun replaceIdentityKey(
        repeaterKeyHex: String,
        newKeyHex: String,
        reboot: Boolean,
        progress: (String) -> Unit = {},
    ): RekeyFlow.Report {
        progress("Sending the new key…")
        val command = runCatching {
            io.github.thatsfguy.meshcore.protocol.IdentityKey.setCommand(crypto, newKeyHex)
        }.getOrElse { return RekeyFlow.Report(refusal = "Refused: ${it.message}") }

        val reply = cliQuery(repeaterKeyHex, command)
        val parsed = io.github.thatsfguy.meshcore.protocol.IdentityKey.parseRekeyReply(reply)
        val newKey = when (parsed) {
            is IdentityKey.RekeyReply.Accepted -> parsed.newPublicKeyHex
            is IdentityKey.RekeyReply.Refused ->
                return RekeyFlow.Report(refusal = "The node refused the key: ${parsed.text}")
            is IdentityKey.RekeyReply.Unrecognised ->
                return RekeyFlow.Report(
                    refusal = "The node answered something this app does not recognise, so " +
                        "nothing has been assumed: ${parsed.text}",
                )
            IdentityKey.RekeyReply.NoAnswer -> return RekeyFlow.Report()
        }

        // The check is only possible for a key whose public half this
        // phone can derive — a seed, or one the generator produced. A
        // bare 64-byte key cannot be checked here (IdentityKey docs), so
        // its absence is not evidence of anything.
        val expected = io.github.thatsfguy.meshcore.protocol.IdentityKey
            .canonicalSeedHex(newKeyHex)?.let { publicKeyFor(it) }
        if (expected != null && !expected.equals(newKey, ignoreCase = true)) {
            return RekeyFlow.Report(newPublicKeyHex = newKey, mismatchedWith = expected)
        }

        progress("Adding the new identity to this radio…")
        val adopted = adoptNewIdentity(repeaterKeyHex, newKey)
        if (!reboot) {
            return RekeyFlow.Report(newPublicKeyHex = newKey, adopted = adopted)
        }

        progress("Restarting the node…")
        val sent = rebootRepeater(repeaterKeyHex)
        if (!sent) {
            return RekeyFlow.Report(
                newPublicKeyHex = newKey,
                adopted = adopted,
                rebootRequested = true,
            )
        }
        return RekeyFlow.Report(
            newPublicKeyHex = newKey,
            adopted = adopted,
            rebootRequested = true,
            rebootSent = true,
            probe = confirmRebooted(repeaterKeyHex, newKey, progress),
        )
    }

    /**
     * Ask a node to restart, and report only that this radio sent it.
     *
     * Deliberately does NOT wait for a reply: the firmware reboots
     * without writing one and sends no ACK either
     * ([IdentityKey.REBOOT_HAS_NO_ANSWER]), so waiting would spend 15
     * seconds to learn nothing and would present the expected silence as
     * a timeout.
     */
    suspend fun rebootRepeater(repeaterKeyHex: String): Boolean {
        val svc = _service.value ?: return false
        val key = hexToBytesOrNull(repeaterKeyHex) ?: return false
        return runCatching {
            svc.engine.sendCliCommand(
                key,
                io.github.thatsfguy.meshcore.protocol.IdentityKey.REBOOT_COMMAND,
            ) != null
        }.getOrDefault(false)
    }

    /**
     * Put the node's new identity into the radio's contact list now,
     * carrying across everything that belongs to the hardware rather
     * than to the key.
     *
     * What is carried is chosen by one question: would this still be
     * true of the node after it takes a new name? Its name, its kind,
     * whether it is a favourite, its BLE address and the password that
     * opens its admin session all survive a rekey — the password is a
     * repeater *setting*, untouched by `saveIdentity()`. Stored routes
     * and message history do not: a route is to a destination hash that
     * has just changed.
     *
     * The new contact is added flood-routed, which is the truth — no
     * route to this identity has ever existed.
     */
    private suspend fun adoptNewIdentity(oldKeyHex: String, newKeyHex: String): Boolean {
        val svc = _service.value ?: return false
        val newKey = hexToBytesOrNull(newKeyHex) ?: return false
        val old = dbContacts.value.firstOrNull { it.keyHex.equals(oldKeyHex, true) }
        val name = old?.name?.ifBlank { null } ?: "Repeater ${newKeyHex.take(6)}"
        val type = old?.type ?: io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_REPEATER

        val added = runCatching { svc.engine.addContactFromCard(newKey, name, type) }
            .getOrDefault(false)
        if (!added) return false

        // Everything below is a nicety that must not be able to fail the
        // adoption: the contact exists, which is the part that stops the
        // user waiting 47 hours for a flood advert.
        if (old != null) {
            if (old.flags and io.github.thatsfguy.meshcore.protocol.Codes.CONTACT_FLAG_FAVORITE != 0) {
                runCatching { svc.engine.setFavourite(newKey, true) }
            }
            if (!old.otaAddress.isNullOrBlank()) {
                runCatching {
                    db.contacts().rememberOtaAddress(
                        selfKey.value,
                        newKeyHex,
                        old.otaAddress,
                        System.currentTimeMillis() / 1000,
                    )
                }
            }
        }
        prefs.nicknameFor(oldKeyHex)?.let { prefs.setNickname(newKeyHex, it) }
        svc.secrets.loginPassword(oldKeyHex)?.let {
            svc.secrets.storeLoginPassword(newKeyHex, it)
        }
        return true
    }

    /**
     * Try to sign in to the new identity, which is the only positive
     * evidence a reboot happened.
     *
     * Waits [REBOOT_SETTLE_MS] first: the firmware's own boot advert is
     * scheduled 16 seconds after start
     * (`examples/simple_repeater/main.cpp:119`), so anything sooner is
     * asking a node that is not listening yet. A silence here is
     * reported as a silence and never as a failure.
     */
    private suspend fun confirmRebooted(
        oldKeyHex: String,
        newKeyHex: String,
        progress: (String) -> Unit = {},
    ): RekeyFlow.Probe {
        val svc = _service.value ?: return RekeyFlow.Probe.NOT_ATTEMPTED
        val key = hexToBytesOrNull(newKeyHex) ?: return RekeyFlow.Probe.NOT_ATTEMPTED
        val password = svc.secrets.loginPassword(oldKeyHex)
            ?: return RekeyFlow.Probe.NO_PASSWORD

        // Said out loud because it is half a minute of nothing on a
        // screen that has just done something irreversible, and a
        // spinner that never changes is what makes people press again.
        progress("Waiting for the node to come back…")
        kotlinx.coroutines.delay(REBOOT_SETTLE_MS)
        progress("Signing in to the new identity…")
        val outcome = runCatching {
            svc.engine.sendLoginWithRetry(
                key,
                password,
                floodFallbackEnabled = prefs.floodFallbackOnLastRetry,
            )
        }.getOrDefault(io.github.thatsfguy.meshcore.engine.LoginOutcome.NoAnswer)

        if (outcome.accepted) {
            _adminSessions.value = _adminSessions.value + (
                newKeyHex to if (outcome.isAdmin) AdminSession.Admin else AdminSession.Guest
                )
            return RekeyFlow.Probe.CONFIRMED
        }
        return if (outcome.answered) RekeyFlow.Probe.REJECTED else RekeyFlow.Probe.SILENT
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
        val result = runCatching {
            backupRepo.apply(parsed, options, passphrase, currentSelfKeyHex = selfKey.value)
        }.getOrElse { return "Restore failed: ${it.message}" }

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
        // Contacts, same serialised queue as the channels above.
        //
        // These used to be counted and then abandoned, with the result
        // string admitting "Contacts were not written" at the end of a
        // long sentence. Restoring a backup onto a new radio therefore
        // produced an app with no contacts and no obvious reason why.
        var contactsWritten = 0
        if (svc != null && options.contacts) {
            for (contact in parsed.plain.contacts) {
                val key = hexToBytesOrNull(contact.keyHex) ?: continue
                val ok = runCatching {
                    svc.engine.addContactFromCard(key, contact.name, contact.type)
                }.getOrDefault(false)
                if (ok) contactsWritten++
            }
            // The list the UI shows mirrors the radio, so re-read it
            // rather than waiting for whatever refresh comes next.
            if (contactsWritten > 0) runCatching { svc.engine.syncContacts() }
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
            if (options.contacts) {
                append(", $contactsWritten of ${parsed.plain.contacts.size} contact(s)")
                if (svc == null) {
                    append(" (connect a radio to restore contacts)")
                }
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

    /**
     * Clear the console only. A room server's CLI and its chat share one
     * DM thread, so [clearThread] here would take the room's messages
     * with it — which is not what "Clear console" offers to do.
     */
    fun clearCliThread(peerKey: String) {
        viewModelScope.launch {
            val key = selfKey.value
            if (key.isNotEmpty()) {
                db.messages().clearCliThread(key, MessageRepository.KIND_DM, peerKey)
            }
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

    /**
     * Change the radio's BLE pairing PIN.
     *
     * The success message says what to do next rather than just
     * "done": the phone's existing bond is now stale, and a user who
     * does not know to re-pair will read the next failed connection as
     * the app being broken.
     */
    fun setDevicePin(text: String) {
        val pin = io.github.thatsfguy.meshcore.protocol.DevicePin.parse(text) ?: run {
            transientMessage.value = "A PIN is exactly 6 digits"
            return
        }
        val svc = _service.value ?: run {
            transientMessage.value = "Not connected to a radio"
            return
        }
        viewModelScope.launch {
            val ok = runCatching { svc.engine.setDevicePin(pin) }.getOrDefault(false)
            transientMessage.value = if (ok) {
                "PIN changed. Forget this radio in Bluetooth settings, then pair again."
            } else {
                "The radio did not accept the new PIN"
            }
        }
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

        /**
         * How long to leave a rebooting node alone before asking it
         * anything.
         *
         * The firmware schedules its own boot advert 16 seconds after
         * start (`examples/simple_repeater/main.cpp:119`), which is the
         * closest thing to a published "I am ready" the app has. 25
         * seconds is that plus the restart itself, and erring long
         * costs nothing here: the alternative to waiting is a probe that
         * fails for the one reason the report is careful to say is not
         * a failure.
         */
        const val REBOOT_SETTLE_MS = 25_000L

        /**
         * Pages one neighbour collection will ask for.
         *
         * A page holds 11 rows at our prefix width and the firmware's
         * `MAX_NEIGHBOURS` is 50, so five pages covers any real table
         * with room to spare. The cap is not about real tables: `total`
         * arrives off the mesh, and without a ceiling a node claiming
         * 65535 neighbours would drive round trips until the user gave
         * up. When it bites, the outcome reports "x of y" rather than a
         * count that would read as the whole table.
         */
        const val NEIGHBOUR_PAGE_LIMIT = 5
    }
}
