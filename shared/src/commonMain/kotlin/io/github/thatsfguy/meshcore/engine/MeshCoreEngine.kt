package io.github.thatsfguy.meshcore.engine

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.model.BatteryAndStorage
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.model.ChannelList
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.model.DeviceInfo
import io.github.thatsfguy.meshcore.model.SelfInfo
import io.github.thatsfguy.meshcore.protocol.Advert
import io.github.thatsfguy.meshcore.protocol.AdvertInfo
import io.github.thatsfguy.meshcore.protocol.BufferReader
import io.github.thatsfguy.meshcore.protocol.ChannelCrypto
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.DeviceEvent
import io.github.thatsfguy.meshcore.protocol.Frames
import io.github.thatsfguy.meshcore.protocol.SendRetry
import io.github.thatsfguy.meshcore.protocol.HeardRepeats
import io.github.thatsfguy.meshcore.protocol.HeardVia
import io.github.thatsfguy.meshcore.protocol.AccessList
import io.github.thatsfguy.meshcore.protocol.BinaryRequestBudget
import io.github.thatsfguy.meshcore.protocol.Neighbours
import io.github.thatsfguy.meshcore.protocol.NodeDiscovery
import io.github.thatsfguy.meshcore.protocol.PathCodec
import io.github.thatsfguy.meshcore.protocol.PathRecovery
import io.github.thatsfguy.meshcore.protocol.Regions
import io.github.thatsfguy.meshcore.protocol.RoutingMode
import io.github.thatsfguy.meshcore.protocol.TracePath
import io.github.thatsfguy.meshcore.protocol.TraceResult
import io.github.thatsfguy.meshcore.protocol.CayenneLpp
import io.github.thatsfguy.meshcore.protocol.RawPacket
import io.github.thatsfguy.meshcore.protocol.RepeaterStatus
import io.github.thatsfguy.meshcore.protocol.StatusCodec
import io.github.thatsfguy.meshcore.protocol.TelemetryReading
import io.github.thatsfguy.meshcore.protocol.ResponseParser
import io.github.thatsfguy.meshcore.transport.Transport
import io.github.thatsfguy.meshcore.transport.TransportState
import io.github.thatsfguy.meshcore.util.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel as CoroutineChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Engine lifecycle, layered above the raw transport state. */
enum class EngineState { Detached, Connecting, Handshaking, Ready }

/**
 * What a repeater/room granted in response to a login.
 *
 * The NODE decides this, not the user. `PUSH_CODE_LOGIN_SUCCESS` carries
 * a permission byte (`[1]`, 1 = admin), so whether a session is admin or
 * guest is a fact reported back over the air — never something to ask
 * for up front.
 *
 * This app used to ask. A "Guest (read-only)" checkbox sat next to the
 * password field, and the session's rights were set from the CHECKBOX
 * while the byte the node actually sent was parsed and discarded. That
 * made the checkbox both confusing (it looks like it selects an access
 * level; it cannot) and wrong (tick it after an admin password and the
 * UI locks controls the node would have allowed; leave it clear with a
 * guest password and the UI offers controls the node will refuse).
 */
data class LoginOutcome(
    val accepted: Boolean,
    val isAdmin: Boolean,
    /**
     * The node answered at all.
     *
     * A rejected password and a login that vanished are different
     * events with opposite responses: one must not be retried (the
     * answer will not change and the password is on the air each time),
     * the other is exactly what retrying is for. Collapsing them into a
     * single `accepted = false` meant the app could not tell, and so
     * did neither.
     */
    val answered: Boolean,
) {
    companion object {
        /** The node said no. Do not retry. */
        val Rejected = LoginOutcome(accepted = false, isAdmin = false, answered = true)

        /** Nothing came back. Worth another attempt. */
        val NoAnswer = LoginOutcome(accepted = false, isAdmin = false, answered = false)
    }
}

/** Domain events the app layer persists / notifies on. */
sealed class MeshEvent {
    /** Inbound direct message (or CLI reply when [txtType] == cli). */
    data class DirectMessageReceived(
        val senderPrefixHex: String,
        val senderKeyHex: String?,     // resolved against contacts when possible
        val text: String,
        val timestamp: Long,
        val txtType: Int,
        val snr: Double?,
        /** "Name [AABBCCDD]" for a room post; null for a plain DM. */
        val roomAuthorLabel: String? = null,
        /** Hops travelled; [FLOOD_HOPS] when flooded, null if unknown. */
        val hops: Int? = null,
        /**
         * The route this arrived on, hex, in TRAVEL order — recovered by
         * correlating the RX-log packet with this message (see
         * [HeardVia]). Null when no single packet could be credited; the
         * UI must show that as "not known", never as "direct".
         */
        val arrivalPathHex: String? = null,
        val arrivalHashWidth: Int? = null,
    ) : MeshEvent()

    /**
     * Inbound channel message. [senderName] is UNAUTHENTICATED display
     * text (see MESHCORE_PROTOCOL §12). [contentKey] is a stable dedup
     * key — the same message can arrive via both the companion sync
     * path and the RX log.
     */
    data class ChannelMessageReceived(
        val channelIndex: Int,
        val senderName: String,
        val text: String,
        val timestamp: Long,
        val contentKey: String,
        /** Hops travelled; [FLOOD_HOPS] when flooded, null if unknown. */
        val hops: Int? = null,
        val snr: Double? = null,
        /**
         * The route this arrived on, hex, in TRAVEL order. EXACT for a
         * channel message: the engine decrypts the raw packet itself, so
         * the packet and the message are the same object — no
         * correlation is involved. Null on the companion-sync copy,
         * which carries no path.
         */
        val arrivalPathHex: String? = null,
        val arrivalHashWidth: Int? = null,
    ) : MeshEvent()

    /**
     * A re-broadcast of one of OUR direct messages, heard off the air.
     *
     * The payload is encrypted to its recipient, so this carries only
     * what the packet exposes: the recipient's one-byte [destHash] and
     * the route the copy travelled. Deciding WHICH sent message it
     * belongs to is correlation and belongs to the store, which is the
     * side that knows the outbox — see `MessageRepeats`.
     */
    data class OwnDirectRepeatHeard(
        val destHash: Int,
        val pathHex: String,
        val hashWidth: Int,
    ) : MeshEvent()

    /** The radio accepted an outbound message (RESP_CODE_SENT). */
    data class MessageSentToRadio(
        val ackHash: Long,
        val timeoutMs: Long,
        val isFlood: Boolean,
    ) : MeshEvent()

    /** End-to-end ACK (PUSH_CODE_SEND_CONFIRMED). */
    data class MessageDelivered(val ackHash: Long, val tripMs: Long) : MeshEvent()

    /**
     * A signature-verified advert heard over the air. [payload] is the
     * raw advert payload, kept so the node can be imported as a contact
     * later (CMD_IMPORT_CONTACT) without waiting to hear it again.
     */
    data class VerifiedAdvertHeard(
        val advert: AdvertInfo,
        val snr: Double,
        val rssi: Int,
        val payload: ByteArray,
    ) : MeshEvent()

    /** Repeater/room login outcome. */
    data class LoginResult(
        val success: Boolean,
        val pubKeyPrefixHex: String?,
        val permissions: Int?,
    ) : MeshEvent()

    /** Contact list fully (re)synced. */
    object ContactsSynced : MeshEvent()

    /** Channel slots fully (re)synced. */
    object ChannelsSynced : MeshEvent()
}

/**
 * The MeshCore companion session: owns exactly one [Transport] at a
 * time, runs the app-start handshake, keeps contact/channel state in
 * sync, pulls queued messages, decrypts RX-log channel traffic, and
 * verifies adverts.
 *
 * Commands are serialized through a single mutex (the companion
 * protocol has no request ids — responses correlate by order, per
 * MESHCORE_PROTOCOL §3). All state flows are safe to collect from UI.
 */
class MeshCoreEngine(
    private val scope: CoroutineScope,
    private val crypto: CryptoProvider,
    private val nowSeconds: () -> Long,
    private val appName: String = "MeshCoreHardened",
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(EngineState.Detached)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _selfInfo = MutableStateFlow<SelfInfo?>(null)
    val selfInfo: StateFlow<SelfInfo?> = _selfInfo.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _contacts = MutableStateFlow<Map<String, Contact>>(emptyMap())
    val contacts: StateFlow<Map<String, Contact>> = _contacts.asStateFlow()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _battery = MutableStateFlow<BatteryAndStorage?>(null)
    val battery: StateFlow<BatteryAndStorage?> = _battery.asStateFlow()

    private val _customVars = MutableStateFlow<Map<String, String>>(emptyMap())
    val customVars: StateFlow<Map<String, String>> = _customVars.asStateFlow()

    /** CMD_GET_AUTO_ADD_CONFIG flags (Codes.AUTO_ADD_*), null until read. */
    private val _autoAddFlags = MutableStateFlow<Int?>(null)
    val autoAddFlags: StateFlow<Int?> = _autoAddFlags.asStateFlow()

    /** Last flood-scope region set through this app ("" = cleared; the
     *  radio can't be queried for it, so this is app-side memory only). */
    private val _floodScopeRegion = MutableStateFlow<String?>(null)
    val floodScopeRegion: StateFlow<String?> = _floodScopeRegion.asStateFlow()

    /**
     * Set to the region the radio may still be stuck on when restoring
     * the scope after a region-scoped channel send failed. While this is
     * non-null every flood packet the radio sends may carry that scope,
     * so the UI must say so rather than let it pass silently.
     */
    private val _floodScopeStuck = MutableStateFlow<String?>(null)
    val floodScopeStuck: StateFlow<String?> = _floodScopeStuck.asStateFlow()

    /** Whether the active link is plaintext (TCP) — surface in the UI. */
    private val _plaintextLink = MutableStateFlow(false)
    val plaintextLink: StateFlow<Boolean> = _plaintextLink.asStateFlow()

    /**
     * Copies of our OWN signed adverts that came back off the mesh — the
     * evidence behind "which repeaters carry my traffic" ([HeardRepeats]).
     *
     * Held in memory only. These are a live measurement of right here,
     * right now: the useful gesture is to send a flood advert and watch
     * what returns, and a list persisted from a different location would
     * answer a question nobody asked.
     */
    private val _heardRepeats = MutableStateFlow<List<HeardRepeats.Echo>>(emptyList())
    val heardRepeats: StateFlow<List<HeardRepeats.Echo>> = _heardRepeats.asStateFlow()

    /** Start a fresh measurement — e.g. after moving the radio. */
    fun clearHeardRepeats() {
        _heardRepeats.value = emptyList()
    }

    /** Every parsed frame — diagnostics feed. */
    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    /** Domain events for persistence/notifications. */
    private val _meshEvents = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 128)
    val meshEvents: SharedFlow<MeshEvent> = _meshEvents.asSharedFlow()

    private var transport: Transport? = null
    private var rxJob: Job? = null
    private var stateJob: Job? = null
    private val commandMutex = Mutex()

    /**
     * Held across a whole region-scoped channel send (set scope → send →
     * restore). The flood scope is GLOBAL radio state, so two sends that
     * interleave would put one channel's traffic into another channel's
     * region. [commandMutex] cannot do this job: it is released between
     * commands by design, and it is not reentrant.
     */
    private val scopedSendMutex = Mutex()

    /** Rolling correlation tag for control/anonymous requests. */
    private var controlTagCounter = 0

    // Contact sync accumulator (CONTACTS_START … CONTACT* … END_OF_CONTACTS).
    private var syncingContacts: MutableMap<String, Contact>? = null

    // Serialized message-queue drain (MSG_WAITING → SYNC_NEXT_MESSAGE
    // loop). Written from the RX collector and from the drain coroutine
    // on a multi-threaded dispatcher, so it must be volatile.
    @kotlin.concurrent.Volatile
    private var drainingQueue = false

    /**
     * Last time (ms) we refreshed each contact after an advert/path
     * push. A hostile peer can replay adverts at line rate; without a
     * debounce every one would spawn a coroutine and a radio command.
     */
    private val lastContactRefresh = HashMap<String, Long>()
    private val refreshMutex = Mutex()

    val isReady: Boolean get() = _state.value == EngineState.Ready

    // ------------------------------------------------------------------
    // Attach / detach
    // ------------------------------------------------------------------

    /**
     * Attach to a connected-or-connecting transport and run the session.
     * The caller owns transport connect/reconnect policy; the engine
     * (re)handshakes whenever the transport reports Connected.
     */
    fun attach(t: Transport) {
        detach()
        transport = t
        _plaintextLink.value = t.isPlaintextLink
        _state.value = EngineState.Connecting

        rxJob = scope.launch {
            t.incoming.collect { incoming ->
                // A hostile frame must never kill the RX path: anything
                // thrown below (including Errors from a not-yet-bridged
                // platform crypto stub) is logged and dropped, not
                // propagated out of the collector.
                try {
                    logFrame("RX", incoming.frame)
                    val event = ResponseParser.parse(incoming.frame) ?: return@collect
                    if (!_events.tryEmit(event)) log("Diagnostics event dropped (buffer full)")
                    handleEvent(event)
                } catch (t: Throwable) {
                    log("RX frame handling failed: ${t::class.simpleName}: ${t.message}")
                }
            }
        }
        stateJob = scope.launch {
            t.state.collect { ts ->
                when (ts) {
                    TransportState.Connected -> if (_state.value != EngineState.Ready) {
                        launchHandshake()
                    }
                    TransportState.Disconnected, TransportState.Error -> {
                        if (_state.value != EngineState.Detached) {
                            _state.value = EngineState.Connecting
                        }
                    }
                    TransportState.Connecting -> _state.value = EngineState.Connecting
                }
            }
        }
    }

    fun detach() {
        rxJob?.cancel(); rxJob = null
        stateJob?.cancel(); stateJob = null
        transport = null
        _state.value = EngineState.Detached
        _plaintextLink.value = false
        syncingContacts = null
        drainingQueue = false
        // The next radio's slots are unknown until we read them, even
        // if the last one's are still sitting in _channels.
        channelsEverSynced = false
    }

    private fun launchHandshake() = scope.launch {
        _state.value = EngineState.Handshaking
        try {
            // APP_START → SELF_INFO is the session handshake; retry a few
            // times (radio may still be booting its BLE stack).
            var self: SelfInfo? = null
            for (attempt in 1..3) {
                val ev = sendAndAwait(Frames.appStart(appName)) { it is DeviceEvent.SelfInfoReceived }
                if (ev is DeviceEvent.SelfInfoReceived) { self = ev.info; break }
                log("APP_START attempt $attempt got no SELF_INFO, retrying")
                delay(1500)
            }
            if (self == null) {
                log("Handshake failed: no SELF_INFO")
                _state.value = EngineState.Connecting
                return@launch
            }
            _selfInfo.value = self

            val q = sendAndAwait(Frames.deviceQuery()) { it is DeviceEvent.DeviceInfoReceived }
            if (q is DeviceEvent.DeviceInfoReceived) _deviceInfo.value = q.info

            // Keep the radio clock sane (radios lose RTC without GPS).
            val timeEv = sendAndAwait(Frames.getDeviceTime()) { it is DeviceEvent.CurrentTime }
            if (timeEv is DeviceEvent.CurrentTime) {
                val skew = nowSeconds() - timeEv.timestamp
                if (skew > 30 || skew < -30) {
                    sendAndAwait(Frames.setDeviceTime(nowSeconds())) { it is DeviceEvent.Ok }
                    log("Radio clock skew ${skew}s — corrected")
                }
            }

            _state.value = EngineState.Ready

            // Initial syncs (serialized by the command mutex anyway).
            syncChannels()
            syncContacts()
            refreshBattery()
            requestCustomVars()
            requestAutoAddConfig()
            // A reconnect (or a radio reboot) starts from an unknown
            // flood scope. Re-assert whatever this app last set, so a
            // scope left behind by a failed restore can't outlive the
            // link that created it.
            _floodScopeRegion.value?.let {
                scopedSendMutex.withLock { applyFloodScope(it.takeIf(String::isNotBlank)) }
            }
            drainMessageQueue()
        } catch (t: Throwable) {
            log("Handshake error: ${t.message}")
            _state.value = EngineState.Connecting
        }
    }

    // ------------------------------------------------------------------
    // Command plumbing
    // ------------------------------------------------------------------

    /**
     * Send one frame and await the first event matching [predicate]
     * (or an Err). Serialized so response correlation-by-order holds.
     * Returns null on timeout.
     */
    private suspend fun sendAndAwait(
        frame: ByteArray,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        predicate: (DeviceEvent) -> Boolean,
    ): DeviceEvent? = commandMutex.withLock {
        val t = transport ?: return null
        coroutineScope {
            // Subscribe BEFORE sending (UNDISPATCHED) — otherwise a fast
            // response can land between send() and first() and be lost
            // (SharedFlow has no replay).
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(timeoutMs) {
                    _events.first { predicate(it) || it is DeviceEvent.Err }
                }
            }
            logFrame("TX", frame)
            t.send(frame)
            waiter.await()
        }
    }

    /** Fire-and-forget send (no response expected / caller listens itself). */
    private suspend fun sendOnly(frame: ByteArray) {
        commandMutex.withLock {
            logFrame("TX", frame)
            transport?.send(frame)
        }
    }

    /**
     * Frame-level diagnostics.
     *
     * Every frame logs its code and length — enough to see what the
     * radio answered, or that it didn't. Full bytes are logged ONLY for
     * [FRAME_LOG_FULL_CODES], a whitelist of frames that structurally
     * cannot carry a secret. A blanket hex dump would put channel PSKs
     * (CMD_SET_CHANNEL) and `set prv.key` text into the log, and the
     * redaction pass can't reliably catch either once they're bytes.
     *
     * Bytes are space-separated deliberately: DiagnosticsLog redacts
     * runs of 32+ hex characters, which would otherwise blank exactly
     * the frames this exists to show.
     */
    private fun logFrame(direction: String, frame: ByteArray) {
        if (frame.isEmpty()) return
        val code = frame[0].toInt() and 0xFF
        val hex = if (code in FRAME_LOG_FULL_CODES) {
            " " + frame.joinToString(" ") { b ->
                val v = b.toInt() and 0xFF
                if (v < 16) "0${v.toString(16)}" else v.toString(16)
            }
        } else {
            ""
        }
        log("$direction code=0x${code.toString(16)} len=${frame.size}$hex")
    }

    // ------------------------------------------------------------------
    // Event dispatch
    // ------------------------------------------------------------------

    private suspend fun handleEvent(event: DeviceEvent) {
        when (event) {
            is DeviceEvent.SelfInfoReceived -> _selfInfo.value = event.info
            is DeviceEvent.DeviceInfoReceived -> _deviceInfo.value = event.info
            is DeviceEvent.BatteryAndStorageReceived -> _battery.value = event.info
            is DeviceEvent.CustomVars -> _customVars.value = event.vars
            is DeviceEvent.AutoAddConfig -> _autoAddFlags.value = event.flags

            is DeviceEvent.ContactsStart -> {
                syncingContacts = LinkedHashMap()
            }
            is DeviceEvent.ContactReceived -> {
                val c = event.contact
                val syncing = syncingContacts
                val cap = (_deviceInfo.value?.maxContacts?.takeIf { it > 0 } ?: MAX_TRACKED_CONTACTS)
                    .coerceAtMost(MAX_TRACKED_CONTACTS)
                if (syncing != null && !event.fromPush) {
                    // A hostile link can stream contact records forever;
                    // stop accumulating past what the radio can hold.
                    if (syncing.size >= cap) {
                        log("Contact sync exceeded $cap records — abandoning sync")
                        syncingContacts = null
                        return
                    }
                    syncing[c.publicKeyHex] = c
                } else if (_contacts.value.size >= cap && !_contacts.value.containsKey(c.publicKeyHex)) {
                    log("Contact map at capacity ($cap) — dropping ${c.publicKeyHex.take(12)}")
                } else {
                    // Single fetch, or a NEW_ADVERT push (the radio has
                    // already accepted it as a contact record).
                    _contacts.value = _contacts.value + (c.publicKeyHex to c)
                }
            }
            is DeviceEvent.EndOfContacts -> {
                syncingContacts?.let { _contacts.value = it.toMap() }
                syncingContacts = null
                _meshEvents.tryEmit(MeshEvent.ContactsSynced)
            }

            is DeviceEvent.ChannelInfoReceived ->
                // ONE rule for what a channel is, shared with the sweep
                // in syncChannels — see ChannelList. This path used to
                // keep unconfigured slots while the sweep dropped them,
                // so reading a slot put blank rows back into the list.
                _channels.value = ChannelList.applySlot(_channels.value, event.channel)

            is DeviceEvent.Sent -> _meshEvents.tryEmit(
                MeshEvent.MessageSentToRadio(event.ackHash, event.timeoutMs, event.isFlood),
            )
            is DeviceEvent.SendConfirmed -> _meshEvents.tryEmit(
                MeshEvent.MessageDelivered(event.ackHash, event.tripMs),
            )

            is DeviceEvent.MessageWaiting -> drainMessageQueue()

            is DeviceEvent.ContactMessage -> {
                val prefixHex = event.senderPrefix.toHex()
                // A 6-byte prefix can collide; if it matches more than
                // one contact, leave the sender unresolved rather than
                // attributing the message to an arbitrary identity.
                val matches = _contacts.value.values.filter {
                    it.publicKeyHex.startsWith(prefixHex)
                }
                val resolved = matches.singleOrNull()
                val hops = PathCodec.decodePathLen(event.pathLen).hops
                // Which raw packet carried this? Only answered when
                // exactly one fits — see HeardVia. The packet is then
                // consumed so a second message can't claim it too.
                // A FLOODED message reports path_len 0xFF — decoded to
                // -1, meaning "no route recorded", NOT "zero hops". Pass
                // it as unknown rather than as a hop count no packet can
                // ever equal: a flood is exactly the case where the route
                // is most worth recovering, and matching on -1 made it
                // the one case we could never recover.
                val arrival =
                    HeardVia.match(pendingArrivals, prefixHex, hops.takeIf { it >= 0 }, nowMillis())
                if (arrival != null) pendingArrivals = pendingArrivals - arrival
                emitMeshEvent(
                    MeshEvent.DirectMessageReceived(
                        senderPrefixHex = prefixHex,
                        senderKeyHex = resolved?.publicKeyHex,
                        text = event.text,
                        timestamp = event.timestamp,
                        txtType = event.txtType,
                        snr = event.snr,
                        roomAuthorLabel = event.roomAuthorPrefix?.let { roomAuthorLabel(it) },
                        hops = hops,
                        arrivalPathHex = arrival?.pathHex,
                        arrivalHashWidth = arrival?.hashWidth,
                    ),
                )
            }

            is DeviceEvent.ChannelMessage -> {
                emitChannelMessage(
                    event.channelIndex, event.senderName, event.text, event.timestamp,
                    // The V3 channel parser already decodes the packed
                    // byte to a hop count; older frames carry it raw.
                    hops = if (event.pathHashWidth != null) {
                        event.pathLen
                    } else {
                        PathCodec.decodePathLen(event.pathLen).hops
                    },
                )
            }

            is DeviceEvent.LoginSuccess -> _meshEvents.tryEmit(
                MeshEvent.LoginResult(true, event.pubKeyPrefix.toHex(), event.permissions),
            )
            DeviceEvent.LoginFail -> _meshEvents.tryEmit(
                MeshEvent.LoginResult(false, null, null),
            )

            is DeviceEvent.AdvertReheard -> refreshContactDebounced(event.publicKey)
            is DeviceEvent.PathUpdated -> refreshContactDebounced(event.publicKey)

            is DeviceEvent.LogRxData -> handleRxLog(event)

            else -> Unit
        }
    }

    /**
     * Dedup key for a channel message — mirrors the reference client's
     * content hash. The same message can arrive via companion sync AND
     * the RX log, and your OWN messages echo back through both; an
     * outgoing row stored under the same key suppresses the echo.
     */
    fun channelContentKey(
        channelIndex: Int,
        timestamp: Long,
        senderName: String,
        text: String,
    ): String {
        val keyInput = ByteArray(5).also {
            it[0] = channelIndex.toByte()
            it[1] = (timestamp and 0xFF).toByte()
            it[2] = ((timestamp shr 8) and 0xFF).toByte()
            it[3] = ((timestamp shr 16) and 0xFF).toByte()
            it[4] = ((timestamp shr 24) and 0xFF).toByte()
        } + "$senderName: $text".encodeToByteArray()
        return crypto.sha256(keyInput).copyOfRange(0, 8).toHex()
    }

    /**
     * Refresh a contact record at most once per [REFRESH_DEBOUNCE_MS].
     * Launched (not awaited) because handleEvent runs on the RX
     * collector and a suspending send would deadlock against a held
     * command mutex whose waiter needs this collector to keep running.
     */
    private fun refreshContactDebounced(pubKey: ByteArray) {
        val hex = pubKey.toHex()
        scope.launch {
            val now = nowSeconds() * 1000
            val proceed = refreshMutex.withLock {
                val last = lastContactRefresh[hex] ?: 0L
                if (now - last < REFRESH_DEBOUNCE_MS) {
                    false
                } else {
                    lastContactRefresh[hex] = now
                    // Bound the debounce map itself.
                    if (lastContactRefresh.size > MAX_TRACKED_CONTACTS) {
                        lastContactRefresh.clear()
                    }
                    true
                }
            }
            if (proceed) sendOnly(Frames.getContactByKey(pubKey))
        }
    }

    private fun emitChannelMessage(
        channelIndex: Int,
        senderName: String,
        text: String,
        timestamp: Long,
        hops: Int? = null,
        snr: Double? = null,
        arrivalPathHex: String? = null,
        arrivalHashWidth: Int? = null,
    ) {
        emitMeshEvent(
            MeshEvent.ChannelMessageReceived(
                channelIndex, senderName, text, timestamp,
                channelContentKey(channelIndex, timestamp, senderName, text),
                hops = hops,
                snr = snr,
                arrivalPathHex = arrivalPathHex,
                arrivalHashWidth = arrivalHashWidth,
            ),
        )
    }

    /**
     * Emit a domain event, surfacing (rather than silently swallowing) a
     * dropped emission — a dropped message event means the message is
     * never persisted.
     */
    private fun emitMeshEvent(event: MeshEvent) {
        if (!_meshEvents.tryEmit(event)) {
            log("DROPPED mesh event ${event::class.simpleName} — consumer too slow")
        }
    }

    /**
     * Raw packets heard but not yet matched to the message they carried.
     *
     * Only direct messages need this. A DM payload is encrypted to the
     * radio's identity key, which this app never holds, so the packet
     * (with its full path) and the decrypted message arrive by different
     * routes and have to be correlated — see [HeardVia].
     */
    private var pendingArrivals = emptyList<HeardVia.Arrival>()

    /**
     * Millis since the engine started, from the MONOTONIC clock.
     *
     * Correlation only cares about the gap between two events, and a
     * wall clock can jump — the radio's own `syncDeviceClock` exists
     * precisely because these clocks disagree. A backwards jump would
     * make a just-heard packet look stale, or a stale one look fresh.
     */
    private val startMark = kotlin.time.TimeSource.Monotonic.markNow()

    /**
     * Milliseconds since the engine started, monotonic.
     *
     * Public because the UI needs the same clock the engine stamped
     * [heardRepeats] with — comparing a monotonic stamp against a wall
     * clock would render "3 hours ago" for something heard a moment ago.
     */
    fun nowMillis(): Long = startMark.elapsedNow().inWholeMilliseconds

    private fun rememberArrival(packet: RawPacket) {
        // dest_hash, src_hash, then the MAC (reference client:
        // "prefixed with dest/src hashes, MAC").
        if (packet.payload.size < 2) return
        if (packet.hopCount <= 0 || packet.pathBytes.isEmpty()) return
        pendingArrivals = HeardVia.remember(
            HeardVia.expire(pendingArrivals, nowMillis()),
            HeardVia.Arrival(
                pathHex = packet.pathBytes.toHex(),
                hashWidth = packet.pathHashWidth,
                hops = packet.hopCount,
                srcHash = packet.payload[1].toInt() and 0xFF,
                atMillis = nowMillis(),
            ),
        )
    }

    /**
     * Record a copy of our own advert that a repeater sent back.
     *
     * [DeviceEvent.LogRxData.snr]/`rssi` describe the transmission this
     * radio demodulated, which is the LAST hop's — [HeardRepeats] is
     * careful never to credit them to any other hop on the path.
     */
    private fun recordOwnEcho(packet: RawPacket, event: DeviceEvent.LogRxData) {
        _heardRepeats.value = HeardRepeats.record(
            _heardRepeats.value,
            HeardRepeats.Echo(
                pathHex = packet.pathBytes.toHex(),
                hashWidth = packet.pathHashWidth,
                snr = event.snr,
                rssi = event.rssi,
                atMillis = nowMillis(),
            ),
        )
    }

    private fun handleRxLog(event: DeviceEvent.LogRxData) {
        val packet = RawPacket.parse(event.packet) ?: return
        when (packet.payloadType) {
            // We cannot decrypt this — but we CAN keep its route until
            // the radio hands us the message it carried.
            Codes.PAYLOAD_TYPE_TXT_MSG -> {
                // A DM packet whose src_hash is OURS is one of our own
                // messages coming back off a repeater, not an inbound
                // one. One byte narrows rather than identifies, so this
                // only reports the sighting; the store decides whether
                // exactly one sent message fits it.
                val selfFirst = _selfInfo.value?.publicKey?.firstOrNull()?.toInt()?.and(0xFF)
                val src = packet.payload.getOrNull(1)?.toInt()?.and(0xFF)
                val dest = packet.payload.getOrNull(0)?.toInt()?.and(0xFF)
                val ours = selfFirst != null && src == selfFirst
                log(
                    "RX TXT_MSG route=${packet.routeType} hops=${packet.hopCount} " +
                        "path=${packet.pathBytes.toHex()} dest=$dest src=$src self=$selfFirst" +
                        if (ours) " — OURS, repeat sighting" else "",
                )
                if (ours && dest != null && packet.pathBytes.isNotEmpty()) {
                    _meshEvents.tryEmit(
                        MeshEvent.OwnDirectRepeatHeard(
                            destHash = dest,
                            pathHex = packet.pathBytes.toHex(),
                            hashWidth = packet.pathHashWidth,
                        ),
                    )
                }
                rememberArrival(packet)
            }
            Codes.PAYLOAD_TYPE_GRP_TXT -> {
                if (packet.payload.isEmpty()) return
                val channelHash = packet.payload[0].toInt() and 0xFF
                val encrypted = packet.payload.copyOfRange(1, packet.payload.size)
                // The 1-byte hash collides — try EVERY matching channel,
                // not just the first (MESHCORE_PROTOCOL §10).
                for (channel in _channels.value) {
                    if (channel.isEmpty) continue
                    if (ChannelCrypto.channelHash(crypto, channel.psk) != channelHash) continue
                    val plain = ChannelCrypto.decrypt(crypto, channel.psk, encrypted) ?: continue
                    val msg = ChannelCrypto.parsePlaintext(plain) ?: continue
                    emitChannelMessage(
                        channel.index, msg.senderName, msg.text, msg.timestamp,
                        // An RX-log packet states its hop count directly,
                        // no width arithmetic needed.
                        hops = packet.hopCount,
                        snr = event.snr,
                        // EXACT, not correlated: we decrypted this very
                        // packet, so its path is this message's path.
                        arrivalPathHex = packet.pathBytes.toHex()
                            .takeIf { packet.pathBytes.isNotEmpty() },
                        arrivalHashWidth = packet.pathHashWidth,
                    )
                    break
                }
            }
            Codes.PAYLOAD_TYPE_ADVERT -> {
                // SECURITY: only signature-verified adverts surface to the
                // app (map pins, contact suggestions). Forged adverts are
                // dropped silently.
                val info = Advert.parseVerified(crypto, packet.payload) ?: return
                val selfKey = _selfInfo.value?.publicKey
                if (selfKey != null && info.publicKey.contentEquals(selfKey)) {
                    // Our own advert, relayed back to us. Useless as a
                    // contact — and it used to be dropped here — but it
                    // is the one packet that says, with a signature only
                    // we could produce, which repeaters carry our
                    // traffic. See HeardRepeats.
                    recordOwnEcho(packet, event)
                    return
                }
                _meshEvents.tryEmit(
                    MeshEvent.VerifiedAdvertHeard(info, event.snr, event.rssi, packet.payload),
                )
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------
    // Sync operations
    // ------------------------------------------------------------------

    suspend fun syncContacts() {
        // The full sweep ends with END_OF_CONTACTS; accumulation happens
        // in handleEvent. Await the end marker so callers can sequence.
        sendAndAwait(Frames.getContacts(), timeoutMs = 30_000) { it is DeviceEvent.EndOfContacts }
    }

    /**
     * Read every channel slot the radio has.
     *
     * **A sweep that did not finish is not published.** `Err` and a
     * timeout used to share one `break`, and the truncated list was
     * written to [channels] regardless — so one dropped answer erased
     * every slot after it. That is not a display problem: it is what
     * [nextFreeChannelIndex] reads, so the next join would be handed a
     * slot that is actually in use and [setChannel] would overwrite a
     * live channel, whose PSK the radio cannot give back. The duplicate
     * guard in the join path reads the same list, so it went quiet at
     * the same moment.
     *
     * `Err` ends the sweep normally ONLY where the firmware could have
     * meant it. A timeout never does — it says the radio stopped
     * answering, which tells us nothing about the slots we had not
     * reached yet.
     *
     * **What `Err` can and cannot mean here** (`BaseChatMesh.cpp`,
     * `getChannel`): the firmware answers `RESP_CODE_CHANNEL_INFO` for
     * *every* index in `0 .. MAX_GROUP_CHANNELS-1`, configured or not —
     * an unused slot comes back with a blank name and an all-zero
     * secret, which is what [ChannelList] filters. `false`, and so
     * `ERR_CODE_NOT_FOUND`, comes back for one reason: an index past
     * the end.
     *
     * So an `Err` at slot 0 is not an answer to this command, and
     * neither is one below the `maxChannels` the radio itself reported
     * in `DEVICE_INFO` (`MyMesh.cpp` sends `MAX_GROUP_CHANNELS`
     * verbatim). [sendAndAwait] resolves on ANY `Err` — it cannot tell
     * whose it is — so a refusal belonging to something else, arriving
     * while a sweep is in flight, used to be read as "this radio has no
     * channels" and published as truth.
     *
     * That is what emptied a live channel list after a flood advert:
     * every channel vanished from Chats (threads fell back to
     * "Channel 0", "Channel 1"), and — because the sweep also set
     * [channelsEverSynced] — [nextFreeChannelIndex] then answered 0, the
     * slot holding Public, whose key the radio cannot give back.
     */
    suspend fun syncChannels() {
        val reportedMax = _deviceInfo.value?.maxChannels?.takeIf { it > 0 }
        val max = reportedMax ?: DEFAULT_MAX_CHANNELS
        val found = ArrayList<Channel>()
        for (idx in 0 until max) {
            val ev = sendAndAwait(Frames.getChannel(idx)) {
                it is DeviceEvent.ChannelInfoReceived && it.channel.index == idx
            }
            when (ev) {
                // Collect every slot the radio reports; ChannelList
                // applies the same empty-slot rule the event handler
                // does, so the sweep and the handler cannot disagree.
                is DeviceEvent.ChannelInfoReceived -> found.add(ev.channel)

                is DeviceEvent.Err -> {
                    // Past the last slot the firmware supports — but
                    // only where that is a thing the firmware could be
                    // saying. Where it is not, this is somebody else's
                    // refusal and the sweep has learned nothing.
                    if (idx > 0 && reportedMax == null) break
                    log(
                        "Channel sweep refused at slot $idx of $max " +
                            "(${if (reportedMax != null) "radio-reported" else "assumed"} " +
                            "maximum) — not an answer this command can give, so the " +
                            "previous list is kept",
                    )
                    return
                }
                // No answer. Keep whatever we already knew rather than
                // publishing a list that claims slots are free.
                null -> {
                    log("Channel sweep timed out at slot $idx — keeping the previous list")
                    return
                }
                else -> Unit
            }
        }
        _channels.value = ChannelList.fromSlots(found)
        channelsEverSynced = true
        _meshEvents.tryEmit(MeshEvent.ChannelsSynced)
    }

    /**
     * Whether a channel sweep has ever completed on this session.
     *
     * An empty [channels] list means one of two things — "the radio has
     * no channels" and "we have not looked yet" — and only one of them
     * makes slot 0 free.
     */
    @kotlin.concurrent.Volatile
    private var channelsEverSynced = false

    private fun drainMessageQueue() {
        if (drainingQueue) return
        drainingQueue = true
        scope.launch {
            try {
                var guard = 0
                while (isReady && guard++ < 200) {
                    val ev = sendAndAwait(Frames.syncNextMessage()) {
                        it is DeviceEvent.ContactMessage ||
                            it is DeviceEvent.ChannelMessage ||
                            it is DeviceEvent.NoMoreMessages
                    }
                    if (ev !is DeviceEvent.ContactMessage && ev !is DeviceEvent.ChannelMessage) break
                }
            } finally {
                drainingQueue = false
            }
        }
    }

    suspend fun refreshBattery() {
        sendAndAwait(Frames.getBattAndStorage()) { it is DeviceEvent.BatteryAndStorageReceived }
    }

    suspend fun requestCustomVars() {
        sendAndAwait(Frames.getCustomVars()) { it is DeviceEvent.CustomVars }
    }

    // ------------------------------------------------------------------
    // Messaging
    // ------------------------------------------------------------------

    /**
     * Send a direct message. Returns the radio's Sent receipt (ack hash
     * + suggested timeout) or null when the radio didn't accept it.
     * Delivery is only real on a later [MeshEvent.MessageDelivered] with
     * the same ack hash — never infer it from anything else (§12).
     */
    suspend fun sendDirectMessage(
        recipientPubKey: ByteArray,
        text: String,
        attempt: Int = 0,
        timestampSeconds: Long = nowSeconds(),
    ): DeviceEvent.Sent? {
        val ev = sendAndAwait(
            Frames.sendTextMessage(recipientPubKey, text, timestampSeconds, attempt),
            timeoutMs = 10_000,
        ) { it is DeviceEvent.Sent }
        return ev as? DeviceEvent.Sent
    }

    /**
     * Wait for the end-to-end ACK of [ackHash]. Returns true when
     * PUSH_CODE_SEND_CONFIRMED arrives within [timeoutMs] — the ONLY
     * evidence of delivery the protocol offers (§12: never infer it
     * from anything else).
     */
    suspend fun awaitDelivery(ackHash: Long, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            meshEvents.first { it is MeshEvent.MessageDelivered && it.ackHash == ackHash }
        } != null

    /**
     * Send a channel message ("name: text" prefixing happens on the
     * radio). Pass [timestampSeconds] when the caller pre-computed a
     * content key — the frame timestamp must match it for echo dedup.
     *
     * Hardware note (Galaxy A42 + real radio, 2026-07-31): firmware
     * answers a group send with a generic RESP_CODE_OK, not
     * RESP_CODE_SENT — the reference client accepts either
     * (`expectsGenericAck: true`), so we do too. Flood group messages
     * have no end-to-end ACK; "accepted by radio" is the terminal state.
     *
     * When the channel carries a [region] (PARITY §8), the send is
     * wrapped in set-scope → send → restore-scope. The flood scope is a
     * single global radio setting, so the whole sequence holds
     * [scopedSendMutex] — including unscoped sends, which would
     * otherwise be able to slip inside another channel's scope window
     * and flood into a region they were never meant for.
     */
    suspend fun sendChannelMessage(
        channelIndex: Int,
        text: String,
        timestampSeconds: Long = nowSeconds(),
        region: String? = null,
    ): Boolean = scopedSendMutex.withLock {
        val scoped = Regions.canonical(region)
        if (scoped == null) return@withLock rawChannelSend(channelIndex, text, timestampSeconds)

        if (!applyFloodScope(scoped)) {
            // Sending anyway would put the message on whatever scope the
            // radio happens to hold — the wrong region, silently.
            log("Flood scope #$scoped refused; channel $channelIndex message not sent")
            return@withLock false
        }
        try {
            rawChannelSend(channelIndex, text, timestampSeconds)
        } finally {
            // Restore the *user's* global scope, not blank: the Settings
            // screen owns that value and a per-channel send must not
            // quietly clear it.
            if (!applyFloodScope(_floodScopeRegion.value?.takeIf { it.isNotBlank() })) {
                _floodScopeStuck.value = scoped
                log("Flood scope restore failed — radio may still be scoped to #$scoped")
            }
        }
    }

    private suspend fun rawChannelSend(
        channelIndex: Int,
        text: String,
        timestampSeconds: Long,
    ): Boolean {
        val ev = sendAndAwait(
            Frames.sendChannelTextMessage(channelIndex, text, timestampSeconds),
            timeoutMs = 10_000,
        ) { it is DeviceEvent.Sent || it is DeviceEvent.Ok }
        return ev is DeviceEvent.Sent || ev is DeviceEvent.Ok
    }

    // ------------------------------------------------------------------
    // Repeater / room administration
    // ------------------------------------------------------------------

    /**
     * Log in to a repeater/room. Outcome arrives as
     * [MeshEvent.LoginResult]. SECURITY: [password] is cleartext on the
     * radio link (and the network, on TCP) — never log it; store only in
     * the platform keystore.
     */
    /**
     * One login attempt, waiting as long as the RADIO says to.
     *
     * `CMD_SEND_LOGIN` is answered with `RESP_CODE_SENT` carrying
     * `is_flood` and an airtime-derived `est_timeout`, exactly like a
     * text message — verified in the firmware
     * (`companion_radio/MyMesh.cpp`, the `CMD_SEND_LOGIN` branch, which
     * writes a 10-byte frame ending in `est_timeout`). The previous
     * hardcoded 20 s ignored that: far too long to fail a 0-hop login,
     * and not necessarily long enough for a deep flood.
     */
    suspend fun sendLogin(
        repeaterPubKey: ByteArray,
        password: String,
    ): LoginOutcome = coroutineScope {
        // Subscribe to the OUTCOME before the frame is sent, not after
        // the RESP_CODE_SENT comes back. On a 0-hop link the node's
        // answer can arrive in the same breath as the radio's
        // acknowledgement, and _events has no replay — subscribing
        // afterwards drops it and reports a timeout on a login that
        // actually succeeded. Same reasoning as sendAndAwait's own
        // UNDISPATCHED waiter.
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
            _events.first { it is DeviceEvent.LoginSuccess || it is DeviceEvent.LoginFail }
        }

        val sent = sendAndAwait(
            Frames.sendLogin(repeaterPubKey, password),
            timeoutMs = 10_000,
        ) { it is DeviceEvent.Sent } as? DeviceEvent.Sent

        if (sent == null) {
            outcome.cancel()
            return@coroutineScope LoginOutcome.NoAnswer
        }

        val budget = sent.timeoutMs.coerceIn(MIN_LOGIN_WAIT_MS, MAX_LOGIN_WAIT_MS)
        val ev = withTimeoutOrNull(budget) { outcome.await() }
        outcome.cancel()
        when (ev) {
            is DeviceEvent.LoginSuccess ->
                LoginOutcome(
                    accepted = true,
                    isAdmin = ev.permissions == PERMISSION_ADMIN,
                    answered = true,
                )
            is DeviceEvent.LoginFail -> LoginOutcome.Rejected
            else -> LoginOutcome.NoAnswer
        }
    }

    /**
     * Log in, retrying only the failure that retrying can fix.
     *
     * The firmware sends a login over the contact's stored path and
     * falls back to flood only when there is no path at all — the same
     * branch a text message takes (`BaseChatMesh::sendLogin`: 
     * `out_path_len == OUT_PATH_UNKNOWN ? sendFloodScoped : sendDirect`).
     * So a login inherits the stale-path failure mode exactly, and gets
     * the same remedy as [SendRetry] applies to messages: the last
     * attempt clears the dead path and floods.
     *
     * The firmware itself never retries — `sendLogin()` transmits once
     * and returns — so this is the client's job. Each attempt is a fresh
     * packet: the payload begins with `getCurrentTimeUnique()`, which
     * the firmware comments as "mostly an extra blob to help make
     * packet_hash unique", so retries are not dropped as duplicates.
     *
     * A **rejection stops the loop immediately**. The answer will not
     * change, and `CMD_SEND_LOGIN` carries the password in cleartext —
     * putting it on the air two more times to be told "no" again is a
     * cost with no upside.
     */
    suspend fun sendLoginWithRetry(
        repeaterPubKey: ByteArray,
        password: String,
        maxAttempts: Int = SendRetry.DEFAULT_MAX_ATTEMPTS,
        floodFallbackEnabled: Boolean = true,
    ): LoginOutcome {
        val keyHex = repeaterPubKey.toHex()
        var last = LoginOutcome.NoAnswer
        for (attempt in 0 until maxAttempts) {
            val hasStoredPath = contacts.value[keyHex]?.storedPath?.isNotEmpty() == true
            val route = SendRetry.routeFor(
                attempt = attempt,
                maxAttempts = maxAttempts,
                hasStoredPath = hasStoredPath,
                floodFallbackEnabled = floodFallbackEnabled,
            )
            if (route == SendRetry.Route.ResetAndFlood) {
                runCatching { resetPath(repeaterPubKey) }
            }
            last = sendLogin(repeaterPubKey, password)
            if (last.accepted || last.answered) return last
        }
        return last
    }

    /**
     * Clear the route to [repeaterPubKey] in both directions.
     *
     * `CMD_RESET_PATH` clears it on our side, which forces the login
     * that follows to go out as a flood; the flood is what makes the
     * repeater clear ITS cached `out_path` for us
     * (`simple_repeater/MyMesh.cpp`, `if (is_flood) client->out_path_len
     * = OUT_PATH_UNKNOWN`). Doing only one half leaves the reply going
     * down the same dead route.
     *
     * The login carries a **blank password on purpose**. For a sender
     * already in the ACL the firmware never reaches the password
     * comparison, so this re-establishes the route without putting the
     * credential on the air. It answering also tells us something worth
     * knowing: we are still in the node's ACL.
     *
     * Returns true when the node answered.
     */
    suspend fun resetPathAndProbe(repeaterPubKey: ByteArray): Boolean {
        runCatching { resetPath(repeaterPubKey) }
        return runCatching { sendLogin(repeaterPubKey, "").answered }.getOrDefault(false)
    }

    /**
     * Run [request], and if it goes unanswered, repair the route and try
     * again — the thing the user was doing by hand with Sign out /
     * Sign in.
     *
     * The escalation order lives in [PathRecovery] and is tested there.
     * What this adds is the reason the loop is shaped the way it is: a
     * repair that itself goes unanswered escalates **without** re-sending
     * the request. Re-sending after a failed repair would burn another
     * full timeout to learn nothing — the route demonstrably was not
     * fixed.
     *
     * [password] null or blank means the credential is not available
     * (never saved, or the keystore is locked); the free probe still
     * runs, and the loop simply ends one stage earlier.
     *
     * Null out means the same thing it meant before: no answer. A
     * request that fails for any other reason — the node replying "not
     * admin" by staying silent, for instance — is indistinguishable
     * from a timeout at this layer, and gets the same treatment. That
     * costs a wasted repair in a case that was never going to work; the
     * alternative is not attempting recovery for the case that was.
     */
    suspend fun <T> withPathRecovery(
        repeaterPubKey: ByteArray,
        password: String?,
        onStage: (PathRecovery.Stage) -> Unit = {},
        request: suspend () -> T?,
    ): T? {
        val hasPassword = !password.isNullOrBlank()
        var stage = PathRecovery.Stage.Initial
        while (true) {
            request()?.let { return it }

            // Escalate until a repair reports success. PathRecovery only
            // ever moves forwards and Exhausted is absorbing, so this
            // terminates whatever the radio does.
            var repaired = false
            while (!repaired) {
                stage = PathRecovery.escalate(stage, hasPassword)
                if (stage == PathRecovery.Stage.Exhausted) return null
                onStage(stage)
                repaired = when (stage) {
                    PathRecovery.Stage.PathReset ->
                        resetPathAndProbe(repeaterPubKey)
                    PathRecovery.Stage.Reauthenticated ->
                        runCatching {
                            sendLoginWithRetry(repeaterPubKey, password!!).accepted
                        }.getOrDefault(false)
                    else -> false
                }
                if (repaired) log("Path recovery: $stage repaired the route")
            }
        }
    }

    /** Send a raw CLI command to a repeater. Replies arrive as
     *  [MeshEvent.DirectMessageReceived] with the repeater's prefix. */
    suspend fun sendCliCommand(repeaterPubKey: ByteArray, command: String): DeviceEvent.Sent? {
        val ev = sendAndAwait(
            Frames.sendCliCommand(repeaterPubKey, command, nowSeconds()),
            timeoutMs = 10_000,
        ) { it is DeviceEvent.Sent }
        return ev as? DeviceEvent.Sent
    }

    suspend fun requestStatus(repeaterPubKey: ByteArray) {
        sendOnly(Frames.sendStatusRequest(repeaterPubKey))
    }

    /**
     * Ask a repeater/room for its status and decode the reply. The
     * response is correlated by the 6-byte sender prefix the firmware
     * echoes, so a status push from another node can't be mistaken for
     * this one's.
     */
    suspend fun repeaterStatus(
        repeaterPubKey: ByteArray,
        timeoutMs: Long = BinaryRequestBudget.MAX_BUDGET_MS,
        onSent: ((BinaryRequestBudget.InFlight) -> Unit)? = null,
    ): RepeaterStatus? {
        val prefix = repeaterPubKey.copyOfRange(0, 6).toHex()
        // Same budget as the other fetches, and for the same reason:
        // this is the first thing the hub asks a node, so it is the
        // wait a user actually sits through.
        val status = sendThenAwait(
            timeoutMs = timeoutMs,
            onSent = onSent,
            buildFrame = { Frames.sendStatusRequest(repeaterPubKey) },
        ) { event, _ ->
            (event as? DeviceEvent.StatusResponse)
                ?.let { StatusCodec.parse(statusFrame(it)) }
                ?.takeIf { it.senderPrefixHex == prefix }
        }
        return status
    }

    private fun statusFrame(ev: DeviceEvent.StatusResponse): ByteArray =
        byteArrayOf(Codes.PUSH_CODE_STATUS_RESPONSE.toByte()) + ev.payload

    /**
     * Ask a repeater for its access list (PARITY §6).
     *
     * Binary request, NOT the `get acl` CLI command — that one is
     * guarded by `sender_timestamp == 0` in the firmware, so it only
     * ever answers the serial console. See [AccessList].
     *
     * Null means no answer (or not admin — the firmware gates this on
     * `sender->isAdmin()` and simply does not reply otherwise).
     */
    suspend fun requestAccessList(
        repeaterPubKey: ByteArray,
        timeoutMs: Long = BinaryRequestBudget.MAX_BUDGET_MS,
        onSent: ((BinaryRequestBudget.InFlight) -> Unit)? = null,
    ): List<AccessList.BinEntry>? {
        val body = binaryRequest(
            Frames.sendBinaryRequest(repeaterPubKey, AccessList.requestPayload()),
            timeoutMs = timeoutMs,
            onSent = onSent,
        ) ?: return null
        return AccessList.parseBinary(body)
    }

    /**
     * Ask a repeater for one page of its one-hop neighbour table
     * (PARITY §6).
     *
     * Null means no answer; an empty table means "answered, knows
     * nobody" — which are different facts about a repeater's coverage.
     *
     * The [request] is used twice on purpose — once to build the bytes
     * and once to read the reply — because the entry width is a
     * parameter we chose, not a constant, and the two must not be able
     * to drift apart.
     */
    suspend fun requestNeighbours(
        repeaterPubKey: ByteArray,
        request: Neighbours.Request = Neighbours.Request(),
        timeoutMs: Long = BinaryRequestBudget.MAX_BUDGET_MS,
        onSent: ((BinaryRequestBudget.InFlight) -> Unit)? = null,
    ): Neighbours.Table? {
        // A fresh nonce per CALL, which is per path-recovery attempt:
        // the four random bytes exist for packet-hash uniqueness, so a
        // repeat that reused them would be the mesh's to drop as a
        // duplicate.
        val body = binaryRequest(
            Frames.sendBinaryRequest(
                repeaterPubKey,
                request.payload(crypto.randomBytes(Neighbours.NONCE_BYTES)),
            ),
            timeoutMs = timeoutMs,
            onSent = onSent,
        ) ?: return null
        return Neighbours.parse(body, request)
    }

    /** Request Cayenne-LPP telemetry from a node; empty on timeout. */
    suspend fun requestTelemetry(
        pubKey: ByteArray,
        timeoutMs: Long = 30_000,
    ): List<TelemetryReading> {
        val ev = sendAndAwait(
            Frames.sendBinaryRequest(pubKey, byteArrayOf(Codes.REQ_TYPE_GET_TELEMETRY.toByte(), 0, 0, 0, 0)),
            timeoutMs = timeoutMs,
        ) { it is DeviceEvent.TelemetryResponse || it is DeviceEvent.BinaryResponse }
        val payload = when (ev) {
            is DeviceEvent.TelemetryResponse -> ev.payload
            is DeviceEvent.BinaryResponse -> ev.payload
            else -> return emptyList()
        }
        // Skip the 6-byte sender prefix the firmware prepends, when present.
        val body = if (payload.size > 7) payload.copyOfRange(6, payload.size) else payload
        return CayenneLpp.parse(body)
    }

    /**
     * Send a CLI command and await the repeater's TEXT reply (which
     * arrives as a direct message from that node). Correlation is
     * next-reply-from-target — callers must serialize their queries
     * (the form-based settings UI fetches one value at a time, like
     * the reference client). Returns null on timeout / not accepted.
     */
    suspend fun sendCliAndAwaitReply(
        repeaterPubKey: ByteArray,
        command: String,
        timeoutMs: Long = 15_000,
    ): String? {
        val targetHex = repeaterPubKey.toHex()
        return coroutineScope {
            // Subscribe to the reply BEFORE sending so a fast (0-hop)
            // response can't slip between send and collect.
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(timeoutMs) {
                    meshEvents.first { ev ->
                        // Must be a CLI-typed reply from THIS node — a
                        // queued chat message from the same node is not
                        // an answer to our query.
                        ev is MeshEvent.DirectMessageReceived &&
                            ev.txtType == Codes.TXT_TYPE_CLI_DATA &&
                            (ev.senderKeyHex == targetHex ||
                                targetHex.startsWith(ev.senderPrefixHex))
                    }
                }
            }
            val sent = sendCliCommand(repeaterPubKey, command)
            if (sent == null) {
                waiter.cancel()
                return@coroutineScope null
            }
            (waiter.await() as? MeshEvent.DirectMessageReceived)?.text
        }
    }

    // ------------------------------------------------------------------
    // Contacts
    // ------------------------------------------------------------------

    suspend fun removeContact(pubKey: ByteArray): Boolean {
        val ev = sendAndAwait(Frames.removeContact(pubKey)) { it is DeviceEvent.Ok }
        if (ev is DeviceEvent.Ok) {
            _contacts.value = _contacts.value - pubKey.toHex()
            return true
        }
        return false
    }

    suspend fun resetPath(pubKey: ByteArray): Boolean =
        sendAndAwait(Frames.resetPath(pubKey)) { it is DeviceEvent.Ok } is DeviceEvent.Ok

    /**
     * Toggle the favourite bit in a contact's flags (the radio owns the
     * flag, so this is a contact-record rewrite like routing changes).
     */
    suspend fun setFavourite(pubKey: ByteArray, favourite: Boolean): Boolean =
        setContactFlag(pubKey, Codes.CONTACT_FLAG_FAVORITE, favourite)

    /**
     * Set or clear one CONTACT_FLAG_* bit on a contact record.
     *
     * The telemetry bits (TELE_BASE / TELE_LOC / TELE_ENV) are what
     * PARITY §2 calls per-contact permissions: they say which of this
     * node's telemetry THIS contact is allowed to read. They only take
     * effect when the corresponding global policy is set to "Flags"
     * (SELF_INFO's telemetryModes) — a per-contact grant cannot widen a
     * global Deny, and the UI has to say so or the switch looks broken.
     */
    suspend fun setContactFlag(pubKey: ByteArray, flag: Int, enabled: Boolean): Boolean {
        val c = _contacts.value[pubKey.toHex()] ?: return false
        val flags = if (enabled) c.flags or flag else c.flags and flag.inv()
        val ok = sendRaw(
            Frames.addUpdateContact(
                pubKey = pubKey,
                type = c.type,
                flags = flags,
                pathLen = c.pathLen,
                path = c.path,
                name = c.name,
                // The contact's OWN advert timestamp, not ours — see
                // addUpdateContact's KDoc.
                timestampSeconds = c.timestamp,
                lat = c.latitude,
                lon = c.longitude,
            ),
        )
        if (ok) sendOnly(Frames.getContactByKey(pubKey))
        return ok
    }

    // ------------------------------------------------------------------
    // Routing / paths
    // ------------------------------------------------------------------

    /**
     * Set how packets to [pubKey] are routed. MeshCore keeps the route
     * in the contact record, so each mode is a contact rewrite:
     *  - [RoutingMode.Auto]   reset the stored path; the radio relearns.
     *  - [RoutingMode.Flood]  clear the path (path_len = 0xFF) so
     *                         packets flood the mesh.
     *  - [RoutingMode.Manual] pin [manualPath] (hop hashes, wire order).
     */
    suspend fun setRouting(
        pubKey: ByteArray,
        mode: RoutingMode,
        manualPath: ByteArray = ByteArray(0),
    ): Boolean {
        if (mode == RoutingMode.Auto) return resetPath(pubKey)

        val contact = _contacts.value[pubKey.toHex()]
        // path_len is PACKED (hops in the low 6 bits, hash-width mode in
        // the top 2) — sending a byte count writes a route the firmware
        // reads as a different number of hops at a different width.
        val width = _deviceInfo.value?.pathHashByteWidth?.takeIf { it in 1..4 } ?: 1
        val pathLen = when (mode) {
            RoutingMode.Flood -> PathCodec.PATH_LEN_FLOOD
            else -> PathCodec.encodePathLen(manualPath.size / width, width)
        }
        if (mode == RoutingMode.Manual && manualPath.isEmpty()) return false

        val ok = sendRaw(
            Frames.addUpdateContact(
                pubKey = pubKey,
                type = contact?.type ?: Codes.ADV_TYPE_CHAT,
                flags = contact?.flags ?: 0,
                pathLen = pathLen,
                path = if (mode == RoutingMode.Manual) manualPath else ByteArray(0),
                name = contact?.name ?: "",
                // Preserve the node's advert timestamp: a route change
                // is not an advert, and claiming it is stops the real
                // ones landing. See addUpdateContact's KDoc.
                timestampSeconds = contact?.timestamp ?: 0L,
                lat = contact?.latitude,
                lon = contact?.longitude,
            ),
        )
        if (ok) sendOnly(Frames.getContactByKey(pubKey))
        return ok
    }

    /**
     * Current routing mode implied by the radio's contact record.
     *
     * Decoded, never compared raw. `path_len` packs the hash-width mode
     * into its top two bits, so on a width-2 mesh — the common case
     * here — a contact with a zero-hop (direct) path encodes as 0x40,
     * and `pathLen > 0` called that "Manual". The same contact on a
     * width-1 mesh read "Auto". This is the hop-hash-width defect the
     * project notes warn about: a width-dependent value read as though
     * the width were 1.
     */
    fun routingMode(pubKeyHex: String): RoutingMode {
        val c = _contacts.value[pubKeyHex] ?: return RoutingMode.Auto
        val info = PathCodec.decodePathLen(c.pathLen)
        return when {
            info.isFlood -> RoutingMode.Flood
            info.hops > 0 -> RoutingMode.Manual
            else -> RoutingMode.Auto
        }
    }

    /**
     * Trace the path to a node: sends CMD_SEND_TRACE_PATH and waits for
     * the matching PUSH_CODE_TRACE_DATA (correlated by [tag]). Returns
     * the per-hop result, or null on timeout.
     */
    suspend fun tracePath(
        tag: Long,
        path: ByteArray = ByteArray(0),
        hashWidth: Int = _deviceInfo.value?.pathHashByteWidth ?: 1,
        auth: Long = 0L,
        timeoutMs: Long = 30_000,
    ): TraceResult? {
        // The flags byte DECLARES the hop-hash width, and our own parser
        // reads it back as `1 shl (flags and 0x03)`. Sending a hardcoded
        // 0 told a 2-byte mesh we were using 1-byte hashes, so the trace
        // went out malformed and nothing came back.
        val flags = TracePath.flagsForHashWidth(hashWidth)
        // Try the stored path as-is, then reversed. Which direction a
        // trace wants is genuinely ambiguous — the reference client
        // exposes "reverse path"/"flip path" as user toggles rather than
        // committing to one — so rather than making the user guess, try
        // both and report the one that answers.
        val candidates = if (path.isEmpty()) {
            // Confirmed on hardware: a trace with an empty path is
            // answered with RESP_CODE_ERR. There is nothing to trace on
            // a direct contact — no intermediate hop exists to report —
            // so refuse locally rather than spend airtime being told so.
            log("Trace needs a multi-hop route; this contact is direct or flooded")
            return null
        } else {
            val reversed = Frames.reversePathByHop(path, hashWidth)
            if (reversed.contentEquals(path)) listOf(path) else listOf(path, reversed)
        }

        for ((attempt, payload) in candidates.withIndex()) {
            val result = traceAttempt(tag + attempt, auth, flags, payload, timeoutMs, hashWidth)
            if (result != null) return result
        }
        return null
    }

    private suspend fun traceAttempt(
        tag: Long,
        auth: Long,
        flags: Int,
        payload: ByteArray,
        timeoutMs: Long,
        hashWidth: Int,
    ): TraceResult? = coroutineScope {
        val replies = CoroutineChannel<DeviceEvent>(CoroutineChannel.UNLIMITED)
        val pump = launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect {
                if (it is DeviceEvent.TraceData || it is DeviceEvent.Err) replies.trySend(it)
            }
        }
        // The radio answers with RESP_CODE_SENT carrying its OWN airtime
        // estimate for the round trip. Waiting a flat 30s ignored that
        // and made a dead trace indistinguishable from a slow one.
        val sent = sendAndAwait(
            Frames.sendTracePath(tag, auth, flags, payload),
            timeoutMs = 10_000,
        ) { it is DeviceEvent.Sent }

        if (sent is DeviceEvent.Err) {
            log("Trace refused by the radio (RESP_CODE_ERR) — flags=$flags width=$hashWidth")
            pump.cancel(); replies.close()
            return@coroutineScope null
        }
        val estimate = (sent as? DeviceEvent.Sent)?.timeoutMs ?: 0L
        val wait = (estimate + TRACE_REPLY_GRACE_MS).coerceIn(5_000, timeoutMs)
        log("Trace sent (estimate ${estimate}ms); waiting ${wait}ms")

        val result = withTimeoutOrNull(wait) {
            var found: TraceResult? = null
            for (ev in replies) {
                if (ev is DeviceEvent.Err) continue
                val parsed = (ev as? DeviceEvent.TraceData)?.let { TracePath.parse(traceFrame(it)) }
                if (parsed?.tag == tag) { found = parsed; break }
            }
            found
        }
        pump.cancel()
        replies.close()
        if (result == null) log("Trace got no reply within ${wait}ms")
        result
    }

    /** The parser expects the whole frame; TraceData carries the tail. */
    /**
     * Display label for the author of a room post.
     *
     * The room server asserts a 4-byte pubkey prefix; four bytes is not
     * an identity, so the label always carries the prefix itself and the
     * name is only a convenience when exactly one contact matches. An
     * ambiguous or unknown author shows as the bare prefix rather than
     * being attributed to someone — and never to the room itself.
     */
    private fun roomAuthorLabel(prefix: ByteArray): String {
        val hex = prefix.toHex()
        val matches = _contacts.value.values.filter { it.publicKeyHex.startsWith(hex) }
        val name = matches.singleOrNull()?.name?.takeIf { it.isNotBlank() }
        return if (name != null) "$name [${hex.uppercase()}]" else "[${hex.uppercase()}]"
    }

    private fun traceFrame(ev: DeviceEvent.TraceData): ByteArray =
        byteArrayOf(Codes.PUSH_CODE_TRACE_DATA.toByte()) + ev.payload

    /** Export self (or a contact) as a shareable advert blob (for QR). */
    suspend fun exportContact(pubKey: ByteArray = ByteArray(0)): ByteArray? {
        val ev = sendAndAwait(Frames.exportContact(pubKey)) { it is DeviceEvent.ExportedContact }
        return (ev as? DeviceEvent.ExportedContact)?.advertBlob
    }

    /**
     * Import a contact from an advert blob (QR-scanned).
     * SECURITY: verifies the embedded advert signature FIRST — an
     * unsigned/forged blob is rejected before it reaches the radio.
     */
    suspend fun importContact(advertBlob: ByteArray): Boolean {
        val payload = extractAdvertPayload(advertBlob) ?: return false
        if (!Advert.verifySignature(crypto, payload)) {
            log("importContact: advert signature verification FAILED — rejected")
            return false
        }
        val ok = sendAndAwait(Frames.importContact(advertBlob)) { it is DeviceEvent.Ok }
        if (ok is DeviceEvent.Ok) {
            syncContacts()
            return true
        }
        return false
    }

    /**
     * Add a contact from an unsigned contact card (the QR form the
     * mainstream app shares).
     *
     * SECURITY: unlike [importContact] there is nothing to verify — the
     * card carries no signature, so the only assurance is that the user
     * scanned it off someone's screen in person. The caller MUST have
     * confirmed the key with the user first; this function does not, and
     * cannot, authenticate anything. The name is stored as display text
     * only, never as identity.
     *
     * No path is known for an out-of-band contact, so it starts in flood
     * mode until the mesh teaches us a route.
     *
     * The advert timestamp is **0**, because no advert has been heard.
     * Stamping it with the phone's clock — which this did — tells the
     * firmware we saw an advert at this instant, and its replay guard
     * then drops every real advert from that node until its own clock
     * passes ours. See [Frames.addUpdateContact].
     */
    suspend fun addContactFromCard(pubKey: ByteArray, name: String, type: Int): Boolean {
        if (pubKey.size != Codes.PUB_KEY_SIZE) return false
        val ok = sendRaw(
            Frames.addUpdateContact(
                pubKey = pubKey,
                type = type,
                flags = 0,
                pathLen = PathCodec.PATH_LEN_FLOOD,
                path = ByteArray(0),
                name = name,
                timestampSeconds = 0L,
            ),
        )
        if (ok) syncContacts()
        return ok
    }

    // ------------------------------------------------------------------
    // Channel management
    // ------------------------------------------------------------------

    suspend fun setChannel(index: Int, name: String, psk: ByteArray): Boolean {
        val ev = sendAndAwait(Frames.setChannel(index, name, psk)) { it is DeviceEvent.Ok }
        if (ev is DeviceEvent.Ok) {
            syncChannels()
            return true
        }
        return false
    }

    suspend fun clearChannel(index: Int): Boolean = setChannel(index, "", ByteArray(16))

    /**
     * First free channel slot — or null when the radio is full, **or
     * when we have not successfully read its slots yet**.
     *
     * The second case matters as much as the first. Before any sweep
     * completes the list is empty, which is indistinguishable from a
     * radio with no channels; answering 0 there hands the caller a slot
     * that probably holds Public, and writing it destroys a key the
     * radio will not give back.
     */
    fun nextFreeChannelIndex(): Int? {
        if (!channelsEverSynced) return null
        val max = _deviceInfo.value?.maxChannels?.takeIf { it > 0 } ?: DEFAULT_MAX_CHANNELS
        val used = _channels.value.map { it.index }.toSet()
        return (0 until max).firstOrNull { it !in used }
    }

    /** True once a channel sweep has completed — see [nextFreeChannelIndex]. */
    val channelsKnown: Boolean get() = channelsEverSynced

    // ------------------------------------------------------------------
    // Device settings
    // ------------------------------------------------------------------

    suspend fun setAdvertName(name: String): Boolean =
        okAndRefreshSelf(Frames.setAdvertName(name))

    suspend fun setAdvertLatLon(lat: Double, lon: Double): Boolean =
        okAndRefreshSelf(Frames.setAdvertLatLon(lat, lon))

    suspend fun setRadioParams(freqKhz: Long, bwHz: Long, sf: Int, cr: Int): Boolean =
        okAndRefreshSelf(Frames.setRadioParams(freqKhz, bwHz, sf, cr))

    suspend fun setTxPower(dbm: Int): Boolean =
        okAndRefreshSelf(Frames.setRadioTxPower(dbm))

    suspend fun sendSelfAdvert(flood: Boolean): Boolean =
        sendAndAwait(Frames.sendSelfAdvert(flood)) { it is DeviceEvent.Ok } is DeviceEvent.Ok

    suspend fun reboot() {
        sendOnly(Frames.reboot())
    }

    /** Re-query SELF_INFO from the radio (name/GPS/radio params/policy
     *  bytes). Used by settings sections that must show fresh values
     *  when opened. */
    suspend fun refreshSelfInfo(): Boolean =
        sendAndAwait(Frames.appStart(appName)) { it is DeviceEvent.SelfInfoReceived } is DeviceEvent.SelfInfoReceived

    /** Read the radio's RTC (epoch seconds), or null on timeout. */
    suspend fun deviceTime(): Long? {
        val ev = sendAndAwait(Frames.getDeviceTime()) { it is DeviceEvent.CurrentTime }
        return (ev as? DeviceEvent.CurrentTime)?.timestamp
    }

    /** Set the radio's RTC from the phone clock. */
    suspend fun syncDeviceClock(): Boolean =
        sendAndAwait(Frames.setDeviceTime(nowSeconds())) { it is DeviceEvent.Ok } is DeviceEvent.Ok

    /** CMD_SET_OTHER_PARAMS: telemetry-permission flags, advert-location
     *  policy (0 none / 1 share), multi-acks (0/1). */
    /**
     * Set the BLE pairing PIN.
     *
     * Write-only: the firmware offers no way to read it back, so the
     * app can never show the current one and must not pretend to.
     *
     * Not [okAndRefreshSelf] — the PIN is not part of SELF_INFO, so
     * re-reading it would prove nothing about whether this worked.
     */
    suspend fun setDevicePin(pin: Int): Boolean =
        sendAndAwait(Frames.setDevicePin(pin)) { it is DeviceEvent.Ok } is DeviceEvent.Ok

    suspend fun setOtherParams(
        allowTelemetryFlags: Int,
        advertLocationPolicy: Int,
        multiAcks: Int,
    ): Boolean = okAndRefreshSelf(
        Frames.setOtherParams(allowTelemetryFlags, advertLocationPolicy, multiAcks),
    )

    /** CMD_SET_AUTO_ADD_CONFIG (Codes.AUTO_ADD_* flags), then re-read. */
    suspend fun setAutoAddConfig(flags: Int): Boolean {
        val ok = sendAndAwait(Frames.setAutoAddConfig(flags)) { it is DeviceEvent.Ok } is DeviceEvent.Ok
        if (ok) requestAutoAddConfig()
        return ok
    }

    suspend fun requestAutoAddConfig() {
        sendAndAwait(Frames.getAutoAddConfig()) { it is DeviceEvent.AutoAddConfig }
    }

    /**
     * Set (or clear, with null/blank) the global flood scope region tag,
     * and remember it as the app's global scope. A name that isn't a
     * canonical region ([Regions.canonical]) is refused rather than sent
     * — the radio would hash whatever it was given and scope traffic to
     * a region nobody else is using.
     */
    suspend fun setFloodScope(region: String?): Boolean = scopedSendMutex.withLock {
        val wanted = region?.takeIf { it.isNotBlank() }
        val canonical = if (wanted == null) {
            ""
        } else {
            Regions.canonical(wanted) ?: return@withLock false
        }
        val ok = applyFloodScope(canonical.ifEmpty { null })
        if (ok) _floodScopeRegion.value = canonical
        ok
    }

    /**
     * Push a scope to the radio without touching the remembered global
     * value — the primitive behind both [setFloodScope] and the
     * per-channel scope window in [sendChannelMessage].
     */
    private suspend fun applyFloodScope(region: String?): Boolean {
        val scope = region?.takeIf { it.isNotBlank() }
            ?.let { ChannelCrypto.floodScopeHash(crypto, it) }
        val ok = sendAndAwait(Frames.setFloodScope(scope)) { it is DeviceEvent.Ok } is DeviceEvent.Ok
        if (ok) _floodScopeStuck.value = null
        return ok
    }

    // ------------------------------------------------------------------
    // Regions (PARITY §8)
    // ------------------------------------------------------------------

    /**
     * Broadcast a discovery request and collect the public-key prefixes
     * that answer within [timeoutMs].
     *
     * A prefix is NOT an identity (PARITY §12): callers match it against
     * contacts they already hold, and an ambiguous match stays
     * ambiguous. Nothing about a reply is authenticated — anyone in
     * range can answer with a prefix they don't own.
     */
    suspend fun discoverNodePrefixes(
        nodeType: Int = Codes.ADV_TYPE_REPEATER,
        timeoutMs: Long = 10_000,
    ): Set<String> = coroutineScope {
        val tag = nextControlTag()
        val found = CoroutineChannel<String>(CoroutineChannel.UNLIMITED)
        // Subscribe before sending: a 0-hop neighbour can answer before
        // send() has even returned.
        val pump = launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { ev ->
                if (ev is DeviceEvent.ControlData) {
                    NodeDiscovery.parseDiscoveryResponse(ev.payload, tag, nodeType)
                        ?.let { found.trySend(it) }
                }
            }
        }
        sendOnly(
            Frames.sendControlData(
                Frames.discoveryRequestPayload(tag, typeMask = 1 shl nodeType),
            ),
        )
        val prefixes = LinkedHashSet<String>()
        withTimeoutOrNull(timeoutMs) {
            for (prefix in found) {
                prefixes += prefix
                // A hostile node can answer at line rate; stop listening
                // rather than let one flood the picker.
                if (prefixes.size >= MAX_DISCOVERY_REPLIES) break
            }
        }
        pump.cancel()
        found.close()
        prefixes
    }

    /**
     * Ask a repeater for its region list (CMD_SEND_ANON_REQ type 0x01).
     *
     * Returns the canonical names it answered with; an empty list means
     * "answered, no regions" and null means "never answered" — the two
     * must not be shown the same way.
     *
     * [replyPath]/[replyHopCount] describe the route the *answer* takes;
     * pass the contact's stored path so the reply comes back the way the
     * request went. Unlike the reference client we never rewrite the
     * contact's stored path to force a direct reply: clobbering a pinned
     * route (and restoring it afterwards, if the app is still alive) is
     * a worse failure than a request that goes unanswered.
     */
    suspend fun requestRegions(
        repeaterPubKey: ByteArray,
        replyPath: ByteArray = ByteArray(0),
        replyHopCount: Int = 0,
        timeoutMs: Long = 30_000,
    ): List<String>? = coroutineScope {
        val width = _deviceInfo.value?.pathHashByteWidth ?: 1
        val replies = CoroutineChannel<ByteArray>(CoroutineChannel.UNLIMITED)
        // Buffer binary responses from before the send: the correlation
        // tag only becomes known when the radio's Sent receipt arrives,
        // and a fast reply must not be lost in that gap.
        val pump = launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { if (it is DeviceEvent.BinaryResponse) replies.trySend(it.payload) }
        }
        val sent = sendAndAwait(
            Frames.sendAnonRequest(
                repeaterPubKey,
                Codes.ANON_REQ_TYPE_REGIONS,
                replyPath,
                replyHopCount,
                width,
            ),
            timeoutMs = 10_000,
        ) { it is DeviceEvent.Sent } as? DeviceEvent.Sent

        val body = if (sent == null) {
            null
        } else {
            // The radio's own airtime estimate is the best wait; bound it.
            val wait = (sent.timeoutMs + REGION_REPLY_GRACE_MS).coerceIn(5_000, timeoutMs)
            withTimeoutOrNull(wait) {
                var match: ByteArray? = null
                for (payload in replies) {
                    if (binaryResponseTag(payload) == sent.ackHash) {
                        match = binaryResponseBody(payload)
                        break
                    }
                }
                match
            }
        }
        pump.cancel()
        replies.close()
        when {
            sent == null -> null
            body == null -> null
            else -> {
                if (Regions.isGlobalScopeOnly(body)) {
                    log("Node answered with the global scope only (no named regions)")
                }
                Regions.parseDiscoveryResponse(body)
            }
        }
    }

    /**
     * Send a binary request and return the body of the reply that
     * **carries our tag**, or null.
     *
     * `requestRegions` has done this since it was written; the ACL,
     * neighbour and telemetry requests took the first
     * `DeviceEvent.BinaryResponse` to arrive instead. Three of the four
     * agreed and one did not, which is this project's recurring shape.
     *
     * In practice the radio covers for us — `companion_radio` keeps a
     * single `pending_req` slot and drops any reply whose tag does not
     * match it (`MyMesh.cpp:736`), so a late answer to a timed-out
     * request is discarded before it reaches the wire. That makes this
     * defence in depth rather than a bug fix. It is still worth having:
     * the correlation costs one comparison, and "the radio happens to
     * filter it for us" is a property of the firmware we would not
     * notice losing.
     *
     * The reply pump subscribes BEFORE the send because the tag only
     * becomes known when the radio's `Sent` receipt arrives, and on a
     * 0-hop link the answer can beat us to it.
     */
    /**
     * Send, wait for the radio's own receipt, then wait for the node —
     * the two-phase shape every over-the-air request needs.
     *
     * Phase one is local and fast: `RESP_CODE_SENT` says the radio put
     * the frame on the air, and carries the estimate this budget is
     * built from. Phase two is the mesh's, and is the part that used to
     * be a flat 30 s on every caller regardless of how far away the
     * node was.
     *
     * [match] is handed each event and the receipt, and returns non-null
     * for the one it wanted — correlation stays the caller's business,
     * because a status push is matched by sender prefix and a binary
     * response by ack hash.
     */
    private suspend fun <T> sendThenAwait(
        timeoutMs: Long,
        attempt: Int = 1,
        ofAttempts: Int = 1,
        onSent: ((BinaryRequestBudget.InFlight) -> Unit)? = null,
        buildFrame: () -> ByteArray,
        match: (DeviceEvent, DeviceEvent.Sent) -> T?,
    ): T? = coroutineScope {
        val seen = CoroutineChannel<DeviceEvent>(CoroutineChannel.UNLIMITED)
        // Buffer from BEFORE the send: the correlation tag is only known
        // once the receipt arrives, and a 0-hop node can answer inside
        // that gap.
        val pump = launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { seen.trySend(it) }
        }
        val sent = sendAndAwait(buildFrame(), timeoutMs = 10_000) {
            it is DeviceEvent.Sent
        } as? DeviceEvent.Sent

        val result = if (sent == null) {
            null
        } else {
            val budget = BinaryRequestBudget.budget(sent.timeoutMs).coerceAtMost(timeoutMs)
            onSent?.invoke(
                BinaryRequestBudget.InFlight(
                    isFlood = sent.isFlood,
                    estimateMs = sent.timeoutMs,
                    budgetMs = budget,
                    attempt = attempt,
                    ofAttempts = ofAttempts,
                ),
            )
            withTimeoutOrNull(budget) {
                var found: T? = null
                for (event in seen) {
                    val hit = match(event, sent)
                    if (hit != null) {
                        found = hit
                        break
                    }
                }
                found
            }
        }
        pump.cancel()
        seen.close()
        result
    }

    /**
     * One binary request, waited for as long as the RADIO says to.
     *
     * The wait is [BinaryRequestBudget.budget] of the `timeout_ms` the
     * radio reports in `RESP_CODE_SENT`, not a fixed number: that value
     * is derived from the airtime and hop count of the path actually
     * used, and this call site used to receive it and throw it away
     * while the login on the same node was already using it.
     */
    private suspend fun binaryRequest(
        frame: ByteArray,
        timeoutMs: Long,
        attempt: Int = 1,
        ofAttempts: Int = 1,
        onSent: ((BinaryRequestBudget.InFlight) -> Unit)? = null,
    ): ByteArray? = sendThenAwait(
        timeoutMs = timeoutMs,
        attempt = attempt,
        ofAttempts = ofAttempts,
        onSent = onSent,
        buildFrame = { frame },
    ) { event, sent ->
        (event as? DeviceEvent.BinaryResponse)
            ?.payload
            ?.takeIf { binaryResponseTag(it) == sent.ackHash }
            ?.let { binaryResponseBody(it) }
    }


    /** u32 tag echoed in a PUSH_CODE_BINARY_RESPONSE, or null if short. */
    private fun binaryResponseTag(payload: ByteArray): Long? {
        if (payload.size < Codes.BINARY_RESPONSE_BODY_OFFSET) return null
        val r = BufferReader(payload)
        r.readBytes(Codes.BINARY_RESPONSE_TAG_OFFSET)
        return r.readUInt32LE()
    }

    private fun binaryResponseBody(payload: ByteArray): ByteArray =
        if (payload.size <= Codes.BINARY_RESPONSE_BODY_OFFSET) {
            ByteArray(0)
        } else {
            payload.copyOfRange(Codes.BINARY_RESPONSE_BODY_OFFSET, payload.size)
        }

    /**
     * Non-zero, non-repeating-within-a-session correlation tag. It ties
     * our own request to its replies; it is not a secret and not
     * authentication — anyone who hears the request can echo the tag.
     */
    private fun nextControlTag(): Long {
        controlTagCounter = if (controlTagCounter >= 0xFFFF) 1 else controlTagCounter + 1
        return ((nowSeconds() shl 16) or controlTagCounter.toLong()) and 0xFFFFFFFFL
    }

    /** Re-query DEVICE_INFO (fw version, slot counts, path-hash width). */
    suspend fun refreshDeviceInfo(): Boolean =
        sendAndAwait(Frames.deviceQuery()) { it is DeviceEvent.DeviceInfoReceived } is DeviceEvent.DeviceInfoReceived

    /** On-air path-hash width: mode 0..3 → (mode+1) bytes per hop. The
     *  radio reports the active width in DEVICE_INFO, so re-read it after
     *  a change to keep [deviceInfo] truthful. */
    suspend fun setPathHashMode(mode: Int): Boolean {
        val ok = sendAndAwait(Frames.setPathHashMode(mode)) { it is DeviceEvent.Ok } is DeviceEvent.Ok
        if (ok) refreshDeviceInfo()
        return ok
    }

    /** Write a custom var ("key:value", e.g. "gps:1"), then re-read all. */
    suspend fun setCustomVar(keyValue: String): Boolean {
        val ok = sendAndAwait(Frames.setCustomVar(keyValue)) { it is DeviceEvent.Ok } is DeviceEvent.Ok
        if (ok) requestCustomVars()
        return ok
    }

    /** Escape hatch for commands without a 1:1 wrapper (e.g. contact
     *  rename via CMD_ADD_UPDATE_CONTACT). Sends and awaits OK. */
    suspend fun sendRaw(frame: ByteArray): Boolean =
        sendAndAwait(frame) { it is DeviceEvent.Ok } is DeviceEvent.Ok

    private suspend fun okAndRefreshSelf(frame: ByteArray): Boolean {
        val ok = sendAndAwait(frame) { it is DeviceEvent.Ok } is DeviceEvent.Ok
        if (ok) {
            // Re-pull SELF_INFO so the settings screen reflects reality.
            sendAndAwait(Frames.appStart(appName)) { it is DeviceEvent.SelfInfoReceived }
        }
        return ok
    }

    companion object {
        /** Sentinel hop count for a flooded (pathless) message. */
        const val FLOOD_HOPS = -1

        /**
         * `PUSH_CODE_LOGIN_SUCCESS[1]` value meaning an ADMIN session.
         * Anything else the node sends is a guest/read-only grant — and
         * "anything else" is deliberate: an unrecognised permission
         * value must fall to the LESSER right, never the greater.
         */
        const val PERMISSION_ADMIN = 1

        private const val DEFAULT_TIMEOUT_MS = 6_000L

        // Floor and ceiling on the radio's own login estimate. The floor
        // covers a suspiciously small estimate on a 0-hop link; the
        // ceiling stops one bad number hanging the sign-in dialog.
        private const val MIN_LOGIN_WAIT_MS = 5_000L
        private const val MAX_LOGIN_WAIT_MS = 45_000L

        /** Minimum gap between contact-record refreshes per node. */
        private const val REFRESH_DEBOUNCE_MS = 30_000L

        /** Hard ceiling on tracked contacts, independent of firmware. */
        private const val MAX_TRACKED_CONTACTS = 1024

        /** Cap on responders collected from one discovery broadcast. */
        private const val MAX_DISCOVERY_REPLIES = 64

        /** Slack added to the radio's airtime estimate for a region reply. */
        private const val REGION_REPLY_GRACE_MS = 2_000L

        /** Same, for a trace reply. */
        private const val TRACE_REPLY_GRACE_MS = 4_000L

        /**
         * Frame codes whose FULL bytes are safe to write to the
         * diagnostics log. Everything here is routing/diagnostic data
         * with no key or password in it. Deliberately excludes
         * CMD_SET_CHANNEL (carries a PSK), CMD_SEND_TXT_MSG and the
         * CLI paths (carry `set prv.key` and passwords as text).
         */
        private val FRAME_LOG_FULL_CODES = setOf(
            Codes.CMD_SEND_TRACE_PATH,
            Codes.PUSH_CODE_TRACE_DATA,
            Codes.CMD_SEND_ANON_REQ,
            Codes.PUSH_CODE_BINARY_RESPONSE,
            Codes.CMD_SEND_CONTROL_DATA,
            Codes.PUSH_CODE_CONTROL_DATA,
            Codes.RESP_CODE_ERR,
            Codes.RESP_CODE_OK,
            Codes.RESP_CODE_SENT,
        )
        private const val DEFAULT_MAX_CHANNELS = 8

        /** pubkey(32) + timestamp(4) + signature(64), before app_data. */
        const val ADVERT_MIN_PAYLOAD_BYTES = 100

        /**
         * The advert payload inside an exported-contact blob.
         *
         * ## The blob is a PACKET, not a payload
         *
         * This used to return the blob unchanged, on a comment claiming
         * an exported contact "is the advert payload itself". It is not,
         * and never was. `CMD_EXPORT_CONTACT` writes a whole packet:
         * self-export calls `pkt->writeTo()` and contact-export returns
         * `getBlobByKey()`, commented in the firmware as "retrieve last
         * raw advert packet". Both are
         * `[header][transport?][path_len][path][payload]`. The firmware
         * agrees on the way back in — its own `importContact()` does
         * `pkt->readFrom(src_buf, len)` and requires
         * `PAYLOAD_TYPE_ADVERT`, which only parses if a packet is what
         * you sent it.
         *
         * Handing that to [Advert.verifySignature], which reads the
         * public key from offset 0, means verifying with the header byte
         * as the first byte of the key. It cannot succeed. Every
         * `meshcore://<hex>` advert QR was rejected as "Import failed
         * (bad signature?)" — which reads as *their* code being forged
         * rather than ours being misparsed.
         *
         * It went unnoticed because nothing we emit takes this form: our
         * own sharing uses the `contact/add` card, so the only codes
         * that reach here come from other clients. `ShareUri.encodeAdvert`
         * exists and has no callers. A decoder with no encoder pointed at
         * it is a decoder nothing tests end to end.
         *
         * The packet is still what gets SENT to the radio — only the
         * verification needed the payload. See [importContact].
         */
        fun extractAdvertPayload(blob: ByteArray): ByteArray? {
            val packet = RawPacket.parse(blob) ?: return null
            if (packet.payloadType != Codes.PAYLOAD_TYPE_ADVERT) return null
            return packet.payload.takeIf { it.size >= ADVERT_MIN_PAYLOAD_BYTES }
        }
    }
}
