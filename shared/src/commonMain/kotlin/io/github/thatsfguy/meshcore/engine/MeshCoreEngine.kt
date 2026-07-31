package io.github.thatsfguy.meshcore.engine

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.model.BatteryAndStorage
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.model.DeviceInfo
import io.github.thatsfguy.meshcore.model.SelfInfo
import io.github.thatsfguy.meshcore.protocol.Advert
import io.github.thatsfguy.meshcore.protocol.AdvertInfo
import io.github.thatsfguy.meshcore.protocol.ChannelCrypto
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.DeviceEvent
import io.github.thatsfguy.meshcore.protocol.Frames
import io.github.thatsfguy.meshcore.protocol.RawPacket
import io.github.thatsfguy.meshcore.protocol.ResponseParser
import io.github.thatsfguy.meshcore.transport.Transport
import io.github.thatsfguy.meshcore.transport.TransportState
import io.github.thatsfguy.meshcore.util.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    ) : MeshEvent()

    /** The radio accepted an outbound message (RESP_CODE_SENT). */
    data class MessageSentToRadio(
        val ackHash: Long,
        val timeoutMs: Long,
        val isFlood: Boolean,
    ) : MeshEvent()

    /** End-to-end ACK (PUSH_CODE_SEND_CONFIRMED). */
    data class MessageDelivered(val ackHash: Long, val tripMs: Long) : MeshEvent()

    /** A signature-verified advert heard over the air. */
    data class VerifiedAdvertHeard(val advert: AdvertInfo, val snr: Double, val rssi: Int) : MeshEvent()

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
    private val appName: String = "MeshCoreMobile",
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

    /** Whether the active link is plaintext (TCP) — surface in the UI. */
    private val _plaintextLink = MutableStateFlow(false)
    val plaintextLink: StateFlow<Boolean> = _plaintextLink.asStateFlow()

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

    // Contact sync accumulator (CONTACTS_START … CONTACT* … END_OF_CONTACTS).
    private var syncingContacts: MutableMap<String, Contact>? = null

    // Serialized message-queue drain (MSG_WAITING → SYNC_NEXT_MESSAGE loop).
    private var drainingQueue = false

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
                val event = ResponseParser.parse(incoming.frame) ?: return@collect
                _events.tryEmit(event)
                handleEvent(event)
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
            t.send(frame)
            waiter.await()
        }
    }

    /** Fire-and-forget send (no response expected / caller listens itself). */
    private suspend fun sendOnly(frame: ByteArray) {
        commandMutex.withLock { transport?.send(frame) }
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

            is DeviceEvent.ContactsStart -> {
                syncingContacts = LinkedHashMap()
            }
            is DeviceEvent.ContactReceived -> {
                val c = event.contact
                val syncing = syncingContacts
                if (syncing != null && !event.fromPush) {
                    syncing[c.publicKeyHex] = c
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

            is DeviceEvent.ChannelInfoReceived -> {
                val ch = event.channel
                val current = _channels.value.filter { it.index != ch.index }
                _channels.value = (current + ch).sortedBy { it.index }
            }

            is DeviceEvent.Sent -> _meshEvents.tryEmit(
                MeshEvent.MessageSentToRadio(event.ackHash, event.timeoutMs, event.isFlood),
            )
            is DeviceEvent.SendConfirmed -> _meshEvents.tryEmit(
                MeshEvent.MessageDelivered(event.ackHash, event.tripMs),
            )

            is DeviceEvent.MessageWaiting -> drainMessageQueue()

            is DeviceEvent.ContactMessage -> {
                val prefixHex = event.senderPrefix.toHex()
                val resolved = _contacts.value.values.firstOrNull {
                    it.publicKeyHex.startsWith(prefixHex)
                }
                _meshEvents.tryEmit(
                    MeshEvent.DirectMessageReceived(
                        senderPrefixHex = prefixHex,
                        senderKeyHex = resolved?.publicKeyHex,
                        text = event.text,
                        timestamp = event.timestamp,
                        txtType = event.txtType,
                        snr = event.snr,
                    ),
                )
            }

            is DeviceEvent.ChannelMessage -> {
                emitChannelMessage(
                    event.channelIndex, event.senderName, event.text, event.timestamp,
                )
            }

            is DeviceEvent.LoginSuccess -> _meshEvents.tryEmit(
                MeshEvent.LoginResult(true, event.pubKeyPrefix.toHex(), event.permissions),
            )
            DeviceEvent.LoginFail -> _meshEvents.tryEmit(
                MeshEvent.LoginResult(false, null, null),
            )

            is DeviceEvent.AdvertReheard -> {
                // Known contact re-heard: refresh its record. Launched —
                // handleEvent runs on the RX collector, and a suspending
                // send here would deadlock against a held command mutex
                // whose waiter needs this very collector to keep running.
                scope.launch { sendOnly(Frames.getContactByKey(event.publicKey)) }
            }
            is DeviceEvent.PathUpdated -> {
                scope.launch { sendOnly(Frames.getContactByKey(event.publicKey)) }
            }

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

    private fun emitChannelMessage(
        channelIndex: Int,
        senderName: String,
        text: String,
        timestamp: Long,
    ) {
        _meshEvents.tryEmit(
            MeshEvent.ChannelMessageReceived(
                channelIndex, senderName, text, timestamp,
                channelContentKey(channelIndex, timestamp, senderName, text),
            ),
        )
    }

    private fun handleRxLog(event: DeviceEvent.LogRxData) {
        val packet = RawPacket.parse(event.packet) ?: return
        when (packet.payloadType) {
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
                    emitChannelMessage(channel.index, msg.senderName, msg.text, msg.timestamp)
                    break
                }
            }
            Codes.PAYLOAD_TYPE_ADVERT -> {
                // SECURITY: only signature-verified adverts surface to the
                // app (map pins, contact suggestions). Forged adverts are
                // dropped silently.
                val info = Advert.parseVerified(crypto, packet.payload) ?: return
                val selfKey = _selfInfo.value?.publicKey
                if (selfKey != null && info.publicKey.contentEquals(selfKey)) return
                _meshEvents.tryEmit(MeshEvent.VerifiedAdvertHeard(info, event.snr, event.rssi))
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

    suspend fun syncChannels() {
        val max = _deviceInfo.value?.maxChannels?.takeIf { it > 0 } ?: DEFAULT_MAX_CHANNELS
        val found = ArrayList<Channel>()
        for (idx in 0 until max) {
            val ev = sendAndAwait(Frames.getChannel(idx)) {
                it is DeviceEvent.ChannelInfoReceived && it.channel.index == idx
            }
            when (ev) {
                is DeviceEvent.ChannelInfoReceived -> if (!ev.channel.isEmpty) found.add(ev.channel)
                is DeviceEvent.Err, null -> break // past the last slot / firmware balked
                else -> Unit
            }
        }
        _channels.value = found.sortedBy { it.index }
        _meshEvents.tryEmit(MeshEvent.ChannelsSynced)
    }

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
    suspend fun sendDirectMessage(recipientPubKey: ByteArray, text: String): DeviceEvent.Sent? {
        val ev = sendAndAwait(
            Frames.sendTextMessage(recipientPubKey, text, nowSeconds()),
            timeoutMs = 10_000,
        ) { it is DeviceEvent.Sent }
        return ev as? DeviceEvent.Sent
    }

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
     */
    suspend fun sendChannelMessage(
        channelIndex: Int,
        text: String,
        timestampSeconds: Long = nowSeconds(),
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
    suspend fun sendLogin(repeaterPubKey: ByteArray, password: String): Boolean {
        val ev = sendAndAwait(
            Frames.sendLogin(repeaterPubKey, password),
            timeoutMs = 20_000,
        ) { it is DeviceEvent.LoginSuccess || it is DeviceEvent.LoginFail }
        return ev is DeviceEvent.LoginSuccess
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

    suspend fun shareContactZeroHop(pubKey: ByteArray): Boolean =
        sendAndAwait(Frames.shareContact(pubKey)) { it is DeviceEvent.Ok } is DeviceEvent.Ok

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

    /** First free channel slot, or null when the radio is full. */
    fun nextFreeChannelIndex(): Int? {
        val max = _deviceInfo.value?.maxChannels?.takeIf { it > 0 } ?: DEFAULT_MAX_CHANNELS
        val used = _channels.value.map { it.index }.toSet()
        return (0 until max).firstOrNull { it !in used }
    }

    // ------------------------------------------------------------------
    // Device settings
    // ------------------------------------------------------------------

    suspend fun setAdvertName(name: String): Boolean =
        okAndRefreshSelf(Frames.setAdvertName(name))

    suspend fun setAdvertLatLon(lat: Double, lon: Double): Boolean =
        okAndRefreshSelf(Frames.setAdvertLatLon(lat, lon))

    suspend fun setRadioParams(freqHz: Long, bwHz: Long, sf: Int, cr: Int): Boolean =
        okAndRefreshSelf(Frames.setRadioParams(freqHz, bwHz, sf, cr))

    suspend fun setTxPower(dbm: Int): Boolean =
        okAndRefreshSelf(Frames.setRadioTxPower(dbm))

    suspend fun sendSelfAdvert(flood: Boolean): Boolean =
        sendAndAwait(Frames.sendSelfAdvert(flood)) { it is DeviceEvent.Ok } is DeviceEvent.Ok

    suspend fun reboot() {
        sendOnly(Frames.reboot())
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
        private const val DEFAULT_TIMEOUT_MS = 6_000L
        private const val DEFAULT_MAX_CHANNELS = 8

        /**
         * An exported-contact blob is the advert payload itself (≥98
         * bytes: pubkey+timestamp+signature+app_data). Kept as a hook in
         * case future firmware prepends a header.
         */
        fun extractAdvertPayload(blob: ByteArray): ByteArray? =
            if (blob.size >= 98) blob else null
    }
}
