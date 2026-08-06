package io.github.thatsfguy.meshcore.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.thatsfguy.meshcore.protocol.MessageNotice
import io.github.thatsfguy.meshcore.android.MainActivity
import io.github.thatsfguy.meshcore.android.R
import io.github.thatsfguy.meshcore.android.storage.DiagnosticsLog
import io.github.thatsfguy.meshcore.android.storage.KeystoreSecretVault
import io.github.thatsfguy.meshcore.android.storage.MeshCoreDatabase
import io.github.thatsfguy.meshcore.android.storage.MessageRepository
import io.github.thatsfguy.meshcore.android.storage.Preferences
import io.github.thatsfguy.meshcore.android.storage.SecretsRepository
import io.github.thatsfguy.meshcore.engine.EngineState
import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.platform.BleTransport
import io.github.thatsfguy.meshcore.platform.UsbSerialTransport
import io.github.thatsfguy.meshcore.platform.androidCryptoProvider
import io.github.thatsfguy.meshcore.transport.ConnectionMemory
import io.github.thatsfguy.meshcore.transport.TcpInterface
import io.github.thatsfguy.meshcore.transport.Transport
import io.github.thatsfguy.meshcore.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the radio link: one engine, one active
 * transport, and an event-driven reconnect supervisor with exponential
 * backoff. Activities bind to reach the engine and repositories.
 *
 * Structure carried over from reticulum-mobile-app's ReticulumService,
 * trimmed to the MeshCore v1 scope (BLE + USB by default; TCP only when
 * the stern-warning toggle enabled it).
 */
class MeshCoreService : Service() {

    inner class LocalBinder : Binder() {
        val service: MeshCoreService get() = this@MeshCoreService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    lateinit var scope: CoroutineScope
        private set
    lateinit var prefs: Preferences
        private set
    lateinit var diagnostics: DiagnosticsLog
        private set
    lateinit var secrets: SecretsRepository
        private set
    lateinit var engine: MeshCoreEngine
        private set
    lateinit var repository: MessageRepository
        private set

    private val _activeTransport = MutableStateFlow<Transport?>(null)
    val activeTransport: StateFlow<Transport?> = _activeTransport

    private val _connectionLabel = MutableStateFlow<String?>(null)
    val connectionLabel: StateFlow<String?> = _connectionLabel

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private var supervisorJob: Job? = null
    private var currentMemory: ConnectionMemory? = null
    private var usbDeviceName: String? = null

    /** Detach on USB unplug — a bulk read can't tell idle from unplug. */
    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (device.deviceName == usbDeviceName) {
                diagnostics.log("USB", "Device detached — disconnecting")
                disconnect()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        prefs = Preferences(this)
        diagnostics = DiagnosticsLog(prefs)
        secrets = SecretsRepository(prefs, KeystoreSecretVault())
        engine = MeshCoreEngine(
            scope = scope,
            crypto = androidCryptoProvider(),
            nowSeconds = { System.currentTimeMillis() / 1000 },
            log = { diagnostics.log("Engine", it) },
        )
        // The DB is opened encrypted with a Keystore-sealed passphrase.
        // Blocking here is deliberate and brief (one Keystore unseal):
        // nothing may touch the database before the key is resolved.
        val vault = KeystoreSecretVault()
        val dbKey = kotlinx.coroutines.runBlocking {
            io.github.thatsfguy.meshcore.android.storage.DatabaseKey.passphrase(prefs, vault)
        }
        repository = MessageRepository(MeshCoreDatabase.get(this, dbKey), secrets, scope)
        repository.start(engine)
        // The block/filter sets are read on every inbound message, so
        // they live on the repository rather than behind a prefs lookup
        // in the hot path. Refreshed whenever the app could have changed
        // them (see refreshBlockLists).
        refreshBlockLists()

        scope.launch {
            engine.selfInfo.collect { info ->
                if (info == null) return@collect
                val firstSight = repository.selfKey != info.publicKeyHex
                repository.selfKey = info.publicKeyHex
                // Retention runs when a radio's history first becomes
                // addressable, not on a timer: the sweep is cheap when
                // the policy is unbounded (it returns immediately) and
                // there is no point pruning history nobody can see yet.
                if (firstSight) {
                    runCatching {
                        repository.applyRetention(
                            prefs.retentionPolicy,
                            prefs.channelRetentions(),
                        )
                    }
                }
            }
        }
        scope.launch {
            engine.state.collect { updateNotification(it) }
        }

        repository.onNewMessage = { kind, peerKey, senderName, text ->
            postMessageNotification(kind, peerKey, senderName, text)
        }

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            usbDetachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startAsForeground()

        // Cold-start auto-reconnect from the persisted last connection.
        prefs.resolveReconnect()?.let { memory ->
            diagnostics.log("Service", "Auto-reconnecting to remembered ${memory.kind}")
            connect(memory)
        }
    }

    /**
     * Re-read the block/filter sets into the repository. Called at start
     * and whenever the UI changes them — they sit on the hot path for
     * every inbound message, so they are cached rather than looked up.
     */
    fun refreshBlockLists() {
        repository.blockedKeys = prefs.blockedKeys
        repository.filteredChannelNames = prefs.filteredChannelNames
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbDetachReceiver) }
        supervisorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Connection control (called from the ViewModel)
    // ------------------------------------------------------------------

    fun connect(memory: ConnectionMemory) {
        // Per-transport enable toggles gate every path (SCOPE.md): a
        // disabled transport is never constructed at all.
        val allowed = when (memory) {
            is ConnectionMemory.Ble -> prefs.bleEnabled
            is ConnectionMemory.Tcp -> prefs.tcpEnabled
        }
        if (!allowed) {
            _lastError.value = "${memory.kind.uppercase()} transport is disabled in Settings"
            return
        }
        currentMemory = memory
        usbDeviceName = null
        prefs.rememberConnection(memory)
        startSupervisor { buildTransport(memory) }
    }

    fun connectUsb(device: UsbDevice) {
        if (!prefs.usbEnabled) {
            _lastError.value = "USB transport is disabled in Settings"
            return
        }
        currentMemory = null
        usbDeviceName = device.deviceName
        _connectionLabel.value = "USB ${device.productName ?: device.deviceName}"
        startSupervisor {
            val d = UsbSerialTransport.deviceByName(this, device.deviceName)
                ?: error("USB device no longer attached")
            UsbSerialTransport(this, d, scope)
        }
    }

    fun disconnect() {
        supervisorJob?.cancel()
        supervisorJob = null
        currentMemory = null
        usbDeviceName = null
        val t = _activeTransport.value
        _activeTransport.value = null
        engine.detach()
        scope.launch { runCatching { t?.disconnect() } }
        _connectionLabel.value = null
        updateNotification(EngineState.Detached)
    }

    private fun buildTransport(memory: ConnectionMemory): Transport = when (memory) {
        is ConnectionMemory.Ble -> {
            _connectionLabel.value = memory.name ?: memory.address
            BleTransport(this, BleTransport.deviceByAddress(this, memory.address), scope)
        }
        is ConnectionMemory.Tcp -> {
            // The UI keeps flagging this link as unencrypted while up.
            _connectionLabel.value = "${memory.host}:${memory.port} (unencrypted)"
            TcpInterface(memory.host, memory.port, scope, logger = { diagnostics.log("TCP", it) })
        }
    }

    /**
     * Event-driven reconnect: build → connect → attach → wait for the
     * transport to die → backoff → rebuild. Backoff 1s → 2s → 4s … 60s,
     * reset on a connection that held for 30s+.
     */
    private fun startSupervisor(factory: () -> Transport) {
        supervisorJob?.cancel()
        engine.detach()
        supervisorJob = scope.launch {
            var backoffMs = 1_000L
            while (true) {
                val transport = try {
                    factory()
                } catch (t: Throwable) {
                    _lastError.value = t.message
                    diagnostics.log("Service", "Transport build failed: ${t.message}")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000)
                    continue
                }
                _activeTransport.value = transport
                engine.attach(transport)
                val connectedAt = System.currentTimeMillis()
                try {
                    transport.connect()
                    _lastError.value = null
                    // Park until the link drops.
                    transport.state.collect { st ->
                        if (st == TransportState.Disconnected || st == TransportState.Error) {
                            throw LinkDownException()
                        }
                    }
                } catch (t: Throwable) {
                    if (t !is LinkDownException) _lastError.value = t.message
                    diagnostics.log("Service", "Link down: ${t.message ?: "disconnected"}")
                } finally {
                    runCatching { transport.disconnect() }
                }
                val heldMs = System.currentTimeMillis() - connectedAt
                backoffMs = if (heldMs > 30_000) 1_000L else (backoffMs * 2).coerceAtMost(60_000)
                delay(backoffMs)
            }
        }
    }

    private class LinkDownException : Exception()

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private fun startAsForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIF_CHANNEL, "Connection status", NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                MSG_CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Incoming mesh messages" },
        )
        val notification = buildNotification("Not connected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Declare only the FGS types whose runtime permissions are
            // actually granted (connectedDevice needs BLUETOOTH_CONNECT
            // on 14+; TCP-only users may have denied it).
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIF_ID, notification, types)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(state: EngineState) {
        val label = _connectionLabel.value
        val text = when (state) {
            EngineState.Ready -> "Connected to ${label ?: "radio"}"
            EngineState.Handshaking -> "Handshaking with ${label ?: "radio"}…"
            EngineState.Connecting -> if (label != null) "Reconnecting to $label…" else "Connecting…"
            EngineState.Detached -> "Not connected"
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(getString(R.string.app_name_full))
            .setContentText(text)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    /**
     * System notification for a genuinely-new inbound message (invoked
     * by the repository AFTER dedup + not-the-open-thread checks, so a
     * channel echo or a duplicate RX-log delivery never buzzes).
     */
    private fun postMessageNotification(
        kind: String,
        peerKey: String,
        senderName: String?,
        text: String,
    ) {
        if (!prefs.notificationsEnabled) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val isChannel = kind == MessageRepository.KIND_CHANNEL
        // Three levels, narrowest last: the per-kind switch, then the
        // per-thread mute. Muted threads still count unread — silence is
        // about interruption, not about hiding that something arrived.
        if (isChannel && !prefs.notifyChannels) return
        if (!isChannel && !prefs.notifyDirect) return
        if (isChannel && peerKey.toIntOrNull()?.let { prefs.isChannelMuted(it) } == true) return
        if (!isChannel && prefs.isContactMuted(peerKey)) return
        val title = if (isChannel) {
            val idx = peerKey.toIntOrNull()
            val name = engine.channels.value.firstOrNull { it.index == idx }?.name
            "# ${name?.ifBlank { null } ?: "Channel $peerKey"}" +
                (senderName?.let { " · $it" } ?: "")
        } else {
            val contact = engine.contacts.value[peerKey]
                ?: engine.contacts.value.values.firstOrNull { it.publicKeyHex.startsWith(peerKey) }
            contact?.name?.ifBlank { null } ?: peerKey.take(12)
        }

        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // A quote-reply is one string on the wire, so printing it raw
        // gave ">yeah good" — the answer buried behind the question, and
        // the collapsed line showing the wrong half of it.
        val notice = MessageNotice.forMessage(text)
        val notification = NotificationCompat.Builder(this, MSG_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(notice.collapsed)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.expanded))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Message bodies stay off the lock screen; the public
            // version says only that something arrived.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, MSG_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_message)
                    .setContentTitle(getString(R.string.app_name_full))
                    .setContentText("New message")
                    .setContentIntent(intent)
                    .setAutoCancel(true)
                    .build(),
            )
            .build()

        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(messageNotificationId(kind, peerKey), notification)
    }

    /**
     * Drop a thread's notification because the user is now reading it.
     *
     * `setAutoCancel(true)` only fires when the notification is TAPPED.
     * Opening the conversation from inside the app left it sitting in
     * the shade, so the phone kept insisting there was something to read
     * on a screen the user was already looking at — and the next message
     * silently replaced it, making the stale one indistinguishable from
     * a fresh one.
     */
    fun clearMessageNotification(kind: String, peerKey: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(messageNotificationId(kind, peerKey))
    }

    companion object {
        private const val NOTIF_CHANNEL = "meshcore_connection"
        private const val MSG_CHANNEL = "meshcore_messages"
        private const val NOTIF_ID = 1
        private const val MSG_NOTIF_BASE = 1000

        /**
         * Stable id per thread: repeated messages update one entry
         * instead of stacking dozens — and the same id is what lets the
         * app cancel it again when the thread is opened. Post and cancel
         * MUST agree, so neither computes it independently.
         */
        fun messageNotificationId(kind: String, peerKey: String): Int =
            MSG_NOTIF_BASE + "$kind|$peerKey".hashCode().and(0xFFFF)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MeshCoreService::class.java))
        }
    }
}
