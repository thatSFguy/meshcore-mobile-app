package io.github.thatsfguy.meshcore.android.storage

import android.content.Context
import android.content.SharedPreferences
import io.github.thatsfguy.meshcore.transport.ConnectionMemory
import io.github.thatsfguy.meshcore.transport.SavedNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Non-secret app preferences over SharedPreferences. Anything sensitive
 * (passwords, PSKs, seeds) goes through [SecretsRepository]/[SecretVault]
 * instead — never here.
 */
class Preferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("meshcore_prefs", Context.MODE_PRIVATE)

    // --- Transport enable toggles (SCOPE.md: per-transport, disabled =
    // never started, never scans, never parses) ---

    var bleEnabled: Boolean
        get() = prefs.getBoolean("transport_ble_enabled", true)
        set(v) { prefs.edit().putBoolean("transport_ble_enabled", v).apply() }

    var usbEnabled: Boolean
        get() = prefs.getBoolean("transport_usb_enabled", true)
        set(v) { prefs.edit().putBoolean("transport_usb_enabled", v).apply() }

    /** TCP is OFF by default, behind a stern-warning toggle. */
    var tcpEnabled: Boolean
        get() = prefs.getBoolean("transport_tcp_enabled", false)
        set(v) { prefs.edit().putBoolean("transport_tcp_enabled", v).apply() }

    /** One-time acknowledgement of the TCP plaintext warning. */
    var tcpWarningAccepted: Boolean
        get() = prefs.getBoolean("tcp_warning_accepted", false)
        set(v) { prefs.edit().putBoolean("tcp_warning_accepted", v).apply() }

    // --- Auto-reconnect + last connection ---

    var autoReconnect: Boolean
        get() = prefs.getBoolean("auto_reconnect", true)
        set(v) { prefs.edit().putBoolean("auto_reconnect", v).apply() }

    var lastKind: String?
        get() = prefs.getString("last_kind", null)
        set(v) { prefs.edit().putString("last_kind", v).apply() }

    var lastBleAddress: String?
        get() = prefs.getString("last_ble_address", null)
        set(v) { prefs.edit().putString("last_ble_address", v).apply() }

    var lastBleName: String?
        get() = prefs.getString("last_ble_name", null)
        set(v) { prefs.edit().putString("last_ble_name", v).apply() }

    var lastTcpHost: String?
        get() = prefs.getString("last_tcp_host", null)
        set(v) { prefs.edit().putString("last_tcp_host", v).apply() }

    var lastTcpPort: Int
        get() = prefs.getInt("last_tcp_port", 0)
        set(v) { prefs.edit().putInt("last_tcp_port", v).apply() }

    fun rememberConnection(memory: ConnectionMemory) {
        when (memory) {
            is ConnectionMemory.Ble -> {
                lastKind = ConnectionMemory.KIND_BLE
                lastBleAddress = memory.address
                lastBleName = memory.name
            }
            is ConnectionMemory.Tcp -> {
                lastKind = ConnectionMemory.KIND_TCP
                lastTcpHost = memory.host
                lastTcpPort = memory.port
            }
        }
    }

    fun resolveReconnect(): ConnectionMemory? = ConnectionMemory.resolve(
        autoReconnect = autoReconnect,
        kind = lastKind,
        bleAddress = lastBleAddress,
        bleName = lastBleName,
        tcpHost = lastTcpHost,
        tcpPort = lastTcpPort.takeIf { it != 0 },
        tcpEnabled = tcpEnabled,
    )

    // --- Saved nodes ---

    fun savedNodes(): List<SavedNode> =
        prefs.getString("saved_nodes", "")!!
            .split('\n')
            .mapNotNull { line -> line.takeIf { it.isNotBlank() }?.let(SavedNode::decode) }

    fun saveNode(node: SavedNode) {
        val nodes = savedNodes().filter { it.key != node.key } + node
        prefs.edit().putString("saved_nodes", nodes.joinToString("\n") { it.encode() }).apply()
    }

    fun forgetNode(key: String) {
        val nodes = savedNodes().filter { it.key != key }
        prefs.edit().putString("saved_nodes", nodes.joinToString("\n") { it.encode() }).apply()
    }

    // --- App settings ---

    /** "system" | "light" | "dark". [themeFlow] mirrors it observably so
     *  the root composable can re-theme immediately on change. */
    val themeFlow: MutableStateFlow<String> by lazy { MutableStateFlow(theme) }

    var theme: String
        get() = prefs.getString("theme", "system")!!
        set(v) {
            prefs.edit().putString("theme", v).apply()
            themeFlow.value = v
        }

    /** Redaction-aware diagnostics log, off by default (SCOPE.md). */
    var diagnosticsEnabled: Boolean
        get() = prefs.getBoolean("diagnostics_enabled", false)
        set(v) { prefs.edit().putBoolean("diagnostics_enabled", v).apply() }

    // --- UI state that should survive tab switches and restarts ---

    /** Selected Nodes-tab index (0 contacts / 1 repeaters / 2 rooms). */
    var nodesTab: Int
        get() = prefs.getInt("nodes_tab", 0)
        set(v) { prefs.edit().putInt("nodes_tab", v).apply() }

    /** Last map camera (lat, lon, zoom); null when never set. Doubles
     *  are stored as raw bits so map precision isn't truncated. */
    var mapCamera: Triple<Double, Double, Double>?
        get() {
            if (!prefs.contains("map_lat")) return null
            return Triple(
                Double.fromBits(prefs.getLong("map_lat", 0)),
                Double.fromBits(prefs.getLong("map_lon", 0)),
                Double.fromBits(prefs.getLong("map_zoom", 0)),
            )
        }
        set(v) {
            if (v == null) return
            prefs.edit()
                .putLong("map_lat", v.first.toRawBits())
                .putLong("map_lon", v.second.toRawBits())
                .putLong("map_zoom", v.third.toRawBits())
                .apply()
        }

    /** Channel slots whose notifications are muted. */
    var mutedChannels: Set<Int>
        get() = prefs.getStringSet("muted_channels", emptySet())!!
            .mapNotNull { it.toIntOrNull() }.toSet()
        set(v) {
            prefs.edit().putStringSet("muted_channels", v.map { it.toString() }.toSet()).apply()
        }

    fun isChannelMuted(idx: Int): Boolean = idx in mutedChannels

    fun setChannelMuted(idx: Int, muted: Boolean) {
        mutedChannels = if (muted) mutedChannels + idx else mutedChannels - idx
    }

    /**
     * Conversations pinned to the top of the Chats list, as
     * "kind|peerKey" keys. Local-only — nothing is sent to the radio.
     */
    var pinnedThreads: Set<String>
        get() = prefs.getStringSet("pinned_threads", emptySet())!!
        set(v) { prefs.edit().putStringSet("pinned_threads", v).apply() }

    fun isThreadPinned(key: String): Boolean = key in pinnedThreads

    fun setThreadPinned(key: String, pinned: Boolean) {
        pinnedThreads = if (pinned) pinnedThreads + key else pinnedThreads - key
    }

    /**
     * Private per-contact nickname, keyed by pubkey hex.
     *
     * Distinct from Rename, which rewrites the contact record on the
     * radio and is therefore visible to the rest of the mesh. A nickname
     * never leaves this phone.
     */
    fun nicknameFor(keyHex: String): String? =
        prefs.getString("nick_$keyHex", null)?.takeIf { it.isNotBlank() }

    fun setNickname(keyHex: String, nickname: String?) {
        prefs.edit().apply {
            if (nickname.isNullOrBlank()) remove("nick_$keyHex") else putString("nick_$keyHex", nickname)
        }.apply()
    }

    /** Map: show only nodes of these types (empty = all). */
    var mapTypeFilter: Set<Int>
        get() = prefs.getStringSet("map_types", emptySet())!!
            .mapNotNull { it.toIntOrNull() }.toSet()
        set(v) { prefs.edit().putStringSet("map_types", v.map { it.toString() }.toSet()).apply() }

    /** Fetch OSM tiles on the Map tab — the app's only outbound HTTP. */
    var mapTilesEnabled: Boolean
        get() = prefs.getBoolean("map_tiles_enabled", true)
        set(v) { prefs.edit().putBoolean("map_tiles_enabled", v).apply() }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(v) { prefs.edit().putBoolean("notifications_enabled", v).apply() }

    // --- Secret storage backing (sealed blobs, base64; see SecretsRepository) ---

    fun putSealed(key: String, sealedB64: String?) {
        prefs.edit().apply {
            if (sealedB64 == null) remove("sealed_$key") else putString("sealed_$key", sealedB64)
        }.apply()
    }

    fun getSealed(key: String): String? = prefs.getString("sealed_$key", null)

    fun sealedKeys(prefix: String): List<String> =
        prefs.all.keys.filter { it.startsWith("sealed_$prefix") }
            .map { it.removePrefix("sealed_") }
}

/**
 * Redaction-aware in-memory diagnostics log — the single log SCOPE.md
 * allows, off by default. Secrets never enter: [redact] strips
 * `set prv.key …`, `login`/password CLI text and hex PSKs before a
 * line is stored.
 */
class DiagnosticsLog(private val prefs: Preferences) {
    private val buffer = ArrayDeque<String>()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Synchronized
    fun log(tag: String, message: String) {
        if (!prefs.diagnosticsEnabled) return
        val line = "${System.currentTimeMillis() / 1000} [$tag] ${redact(message)}"
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        _lines.value = buffer.toList()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _lines.value = emptyList()
    }

    companion object {
        private const val MAX_LINES = 500

        private val PRV_KEY = Regex("""(set\s+prv\.key\s+)\S+""", RegexOption.IGNORE_CASE)
        private val PASSWORD_CLI = Regex("""(password\s+)\S+""", RegexOption.IGNORE_CASE)
        private val LONG_HEX = Regex("""\b[0-9a-fA-F]{32,}\b""")

        /** Strip anything secret-shaped before it can reach the log. */
        fun redact(message: String): String = message
            .replace(PRV_KEY) { "${it.groupValues[1]}[REDACTED]" }
            .replace(PASSWORD_CLI) { "${it.groupValues[1]}[REDACTED]" }
            .replace(LONG_HEX, "[HEX-REDACTED]")
    }
}
