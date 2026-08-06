package io.github.thatsfguy.meshcore.android.storage

import android.content.Context
import android.content.SharedPreferences
import io.github.thatsfguy.meshcore.protocol.BlockList
import io.github.thatsfguy.meshcore.protocol.Regions
import io.github.thatsfguy.meshcore.protocol.Retention
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

    /**
     * Flood on the final retry of a direct message, resetting the dead
     * path first. MeshCore's documented default is ON, and its own FAQ
     * says it "can be turned off in settings" — so it is a real setting
     * with a real default, not a preference invented here.
     */
    var floodFallbackOnLastRetry: Boolean
        get() = prefs.getBoolean("flood_fallback_last_retry", true)
        set(v) { prefs.edit().putBoolean("flood_fallback_last_retry", v).apply() }

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

    /**
     * Remove a node from the saved list AND stop reconnecting to it.
     *
     * These are two stores, and clearing only the list left the radio
     * still named in the auto-reconnect memory — so a "forgotten" node
     * came straight back on the next connect, and because that path
     * never re-added it, the app ended up connected to something absent
     * from its own list.
     */
    fun forgetNode(key: String) {
        val nodes = savedNodes().filter { it.key != key }
        prefs.edit().putString("saved_nodes", nodes.joinToString("\n") { it.encode() }).apply()
        if (reconnectKey() == key) clearReconnect()
    }

    /** The saved-node key the reconnect memory points at, if any. */
    fun reconnectKey(): String? = when (lastKind) {
        ConnectionMemory.KIND_BLE ->
            lastBleAddress?.let { SavedNode(ConnectionMemory.KIND_BLE, it, null, null).key }
        ConnectionMemory.KIND_TCP ->
            lastTcpHost?.let {
                SavedNode(ConnectionMemory.KIND_TCP, it, lastTcpPort.takeIf { p -> p != 0 }).key
            }
        else -> null
    }

    fun clearReconnect() {
        lastKind = null
        lastBleAddress = null
        lastBleName = null
        lastTcpHost = null
        lastTcpPort = 0
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

    /**
     * Whether first-run setup has been dismissed. Not "has a radio" —
     * a user who skips it should not be shown it again every launch.
     */
    var setupComplete: Boolean
        get() = prefs.getBoolean("setup_complete", false)
        set(v) { prefs.edit().putBoolean("setup_complete", v).apply() }

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

    /** Contact keys whose notifications are muted (PARITY §3). */
    var mutedContacts: Set<String>
        get() = prefs.getStringSet("muted_contacts", emptySet())!!
        set(v) { prefs.edit().putStringSet("muted_contacts", v).apply() }

    fun isContactMuted(keyHex: String): Boolean = keyHex in mutedContacts

    fun setContactMuted(keyHex: String, muted: Boolean) {
        mutedContacts = if (muted) mutedContacts + keyHex else mutedContacts - keyHex
    }

    /** Notify for direct messages (PARITY §3, finer control). */
    var notifyDirect: Boolean
        get() = prefs.getBoolean("notify_direct", true)
        set(v) { prefs.edit().putBoolean("notify_direct", v).apply() }

    /** Notify for channel messages. Off by default is tempting but
     *  would silently change behaviour for existing users. */
    var notifyChannels: Boolean
        get() = prefs.getBoolean("notify_channels", true)
        set(v) { prefs.edit().putBoolean("notify_channels", v).apply() }

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

    // --- Regions (flood scope; PARITY §8) ---
    //
    // Region names are local routing labels, not secrets, so plain prefs
    // are the right home. They are still validated on the way in:
    // discovered names come off the mesh, and a name is pasted into CLI
    // commands sent to a repeater.

    /** Known region names, canonical and sorted. */
    var regions: List<String>
        get() = prefs.getStringSet("regions", emptySet())!!
            .mapNotNull { Regions.canonical(it) }
            .distinct()
            .sorted()
        set(v) {
            val clean = v.mapNotNull { Regions.canonical(it) }.distinct().sorted()
            prefs.edit().putStringSet("regions", clean.toSet()).apply()
        }

    /** Add [name] if it canonicalises; returns the stored form, or null. */
    fun addRegion(name: String): String? {
        val canonical = Regions.canonical(name) ?: return null
        regions = regions + canonical
        return canonical
    }

    /**
     * Forget a region, and clear it from every channel that used it — a
     * channel left pointing at a region the user deleted would keep
     * scoping its traffic to it.
     */
    fun removeRegion(name: String) {
        val canonical = Regions.canonical(name) ?: return
        regions = regions - canonical
        for ((idx, region) in channelRegions()) {
            if (region == canonical) setChannelRegion(idx, null)
        }
    }

    /** Region assigned to channel slot [idx], or null when unscoped. */
    fun channelRegion(idx: Int): String? =
        Regions.canonical(prefs.getString("channel_region_$idx", null))

    fun setChannelRegion(idx: Int, region: String?) {
        val canonical = region?.let { Regions.canonical(it) }
        prefs.edit().apply {
            if (canonical == null) remove("channel_region_$idx") else {
                putString("channel_region_$idx", canonical)
            }
        }.apply()
    }

    /** Every channel slot that carries a region, as slot → region. */
    fun channelRegions(): Map<Int, String> =
        prefs.all.keys.filter { it.startsWith("channel_region_") }
            .mapNotNull { key ->
                val idx = key.removePrefix("channel_region_").toIntOrNull() ?: return@mapNotNull null
                val region = channelRegion(idx) ?: return@mapNotNull null
                idx to region
            }
            .toMap()

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

    // --- Blocking and filtering (PARITY §3) ---
    //
    // Two separate stores because they are two different promises: a
    // blocked KEY is a real block, a filtered NAME is a noise filter.
    // See BlockList for why channels can only have the latter.

    /** Public keys whose direct messages are dropped. */
    var blockedKeys: Set<String>
        get() = prefs.getStringSet("blocked_keys", emptySet())!!
            .mapNotNull { BlockList.canonicalKey(it) }.toSet()
        set(v) {
            val clean = v.mapNotNull { BlockList.canonicalKey(it) }
                .take(BlockList.MAX_ENTRIES).toSet()
            prefs.edit().putStringSet("blocked_keys", clean).apply()
        }

    fun blockKey(keyHex: String): Boolean {
        val canonical = BlockList.canonicalKey(keyHex) ?: return false
        blockedKeys = blockedKeys + canonical
        return true
    }

    fun unblockKey(keyHex: String) {
        BlockList.canonicalKey(keyHex)?.let { blockedKeys = blockedKeys - it }
    }

    fun isKeyBlocked(keyHex: String?): Boolean =
        BlockList.isBlockedSender(keyHex, blockedKeys)

    /** Channel sender names hidden from view — NOT a block. */
    var filteredChannelNames: Set<String>
        get() = prefs.getStringSet("filtered_channel_names", emptySet())!!
            .mapNotNull { BlockList.canonicalName(it) }.toSet()
        set(v) {
            val clean = v.mapNotNull { BlockList.canonicalName(it) }
                .take(BlockList.MAX_ENTRIES).toSet()
            prefs.edit().putStringSet("filtered_channel_names", clean).apply()
        }

    fun filterChannelName(name: String): Boolean {
        val canonical = BlockList.canonicalName(name) ?: return false
        filteredChannelNames = filteredChannelNames + canonical
        return true
    }

    fun unfilterChannelName(name: String) {
        BlockList.canonicalName(name)?.let { filteredChannelNames = filteredChannelNames - it }
    }

    // --- Message retention (PARITY §3) ---

    /**
     * Default retention for every thread. Stored in [Retention.Policy]'s
     * own encoding so the settings screen and the pruner can never drift
     * apart on what "30 days" means.
     */
    var retentionPolicy: Retention.Policy
        get() = Retention.decode(prefs.getString("retention", null))
        set(v) { prefs.edit().putString("retention", v.encode()).apply() }

    /** Per-channel override; absent means "use the default". */
    fun channelRetention(idx: Int): Retention.Policy? =
        prefs.getString("retention_ch_$idx", null)?.let { Retention.decode(it) }

    // UNWIRED (audited 2026-08-06): the retention sweep reads per-channel
    // overrides, but nothing ever writes one — so the feature exists in
    // the data model with no way to reach it from the UI.
    fun setChannelRetention(idx: Int, policy: Retention.Policy?) {
        prefs.edit().apply {
            if (policy == null) remove("retention_ch_$idx") else {
                putString("retention_ch_$idx", policy.encode())
            }
        }.apply()
    }

    /** Every channel slot carrying an override. */
    fun channelRetentions(): Map<Int, Retention.Policy> =
        prefs.all.keys.filter { it.startsWith("retention_ch_") }
            .mapNotNull { key ->
                val idx = key.removePrefix("retention_ch_").toIntOrNull() ?: return@mapNotNull null
                val policy = channelRetention(idx) ?: return@mapNotNull null
                idx to policy
            }
            .toMap()

    // --- Config backup (PARITY §1) ---

    /**
     * Preference keys a backup may carry, as an explicit ALLOW-list.
     *
     * A deny-list would be the wrong shape here: every future preference
     * would be exported by default, and the day someone adds one holding
     * a token or a path to something private, it ships in every backup
     * silently. This list is the review point — adding a key to it is a
     * deliberate act.
     *
     * Deliberately absent: everything under `sealed_` (that is the
     * Keystore's business and goes in the encrypted section), and
     * `last_*` connection details, which are device-specific.
     */
    private val EXPORTABLE_KEYS = listOf(
        "transport_ble_enabled", "transport_usb_enabled", "transport_tcp_enabled",
        "auto_reconnect", "theme", "diagnostics_enabled",
        "map_tiles_enabled", "notifications_enabled",
        "nodes_tab",
    )

    /** Boolean-valued keys, so import can put them back with the right type. */
    private val BOOLEAN_KEYS = setOf(
        "transport_ble_enabled", "transport_usb_enabled", "transport_tcp_enabled",
        "auto_reconnect", "diagnostics_enabled", "map_tiles_enabled",
        "notifications_enabled",
    )

    private val INT_KEYS = setOf("nodes_tab")

    /** The allow-listed preferences, as strings, for a backup file. */
    fun exportableSettings(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (key in EXPORTABLE_KEYS) {
            if (!prefs.contains(key)) continue
            val value = when (key) {
                in BOOLEAN_KEYS -> prefs.getBoolean(key, false).toString()
                in INT_KEYS -> prefs.getInt(key, 0).toString()
                else -> prefs.getString(key, null) ?: continue
            }
            out[key] = value
        }
        return out
    }

    /**
     * Restore allow-listed preferences. Returns how many were applied;
     * anything unrecognised or ill-typed is ignored, since a backup file
     * is as much "handed to me" as "written by me".
     *
     * TCP stays off unless its warning was already accepted on THIS
     * device — a backup must not be able to turn on a plaintext
     * transport behind the one-time warning.
     */
    fun importSettings(settings: Map<String, String>): Int {
        var applied = 0
        val editor = prefs.edit()
        for ((key, value) in settings) {
            if (key !in EXPORTABLE_KEYS) continue
            when (key) {
                in BOOLEAN_KEYS -> {
                    val bool = value.toBooleanStrictOrNull() ?: continue
                    if (key == "transport_tcp_enabled" && bool && !tcpWarningAccepted) continue
                    editor.putBoolean(key, bool)
                }
                in INT_KEYS -> editor.putInt(key, value.toIntOrNull() ?: continue)
                else -> editor.putString(key, value)
            }
            applied++
        }
        editor.apply()
        themeFlow.value = theme
        return applied
    }

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

    /**
     * Drop every sealed blob (purge, PARITY §1). The Keystore key itself
     * is left alone: it also wraps the message database, and destroying
     * it would take rows this purge deliberately isn't touching.
     */
    fun clearAllSealed() {
        val editor = prefs.edit()
        for (key in prefs.all.keys.filter { it.startsWith("sealed_") }) editor.remove(key)
        editor.apply()
    }
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
        val redacted = redact(message)
        val line = "${System.currentTimeMillis() / 1000} [$tag] $redacted"
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        _lines.value = buffer.toList()
        // Debug builds also mirror to logcat. The in-app viewer is the
        // product surface; this exists because driving that viewer over
        // adb to read one frame is far harder than reading logcat, and
        // field debugging is what this log is for. Release builds never
        // mirror — the same redacted text, but logcat is a wider
        // audience than the app's own screen.
        if (io.github.thatsfguy.meshcore.android.BuildConfig.DEBUG) {
            android.util.Log.d("MeshCoreDiag", "[$tag] $redacted")
        }
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
