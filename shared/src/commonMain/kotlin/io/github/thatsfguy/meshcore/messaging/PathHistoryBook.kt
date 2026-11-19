package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.protocol.PathCodec
import io.github.thatsfguy.meshcore.protocol.PathHistoryHygiene
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One route to one contact, and how it has done. `pathHex == ""` is the flood route. */
@Serializable
data class PathRow(
    val contactKey: String,
    val pathHex: String,
    val hops: Int,
    /** Bytes per hop; [PathHistoryHygiene.WIDTH_UNKNOWN] for a row from before widths were kept. */
    val hashWidth: Int,
    val successes: Int = 0,
    val failures: Int = 0,
    /** Our clock, ms; 0 = never confirmed. */
    val lastWorkedAt: Long = 0,
    val lastUsedAt: Long = 0,
) {
    val isFlood: Boolean get() = pathHex.isEmpty()
}

@Serializable
data class PathSnapshot(val version: Int = 1, val rows: List<PathRow> = emptyList())

/**
 * The routes this radio has used to each contact, and their delivery
 * record — Android's `path_history` table as a pure class, for iOS
 * (Phase 4, 2026-11-18). One per radio identity, persisted as JSON.
 *
 * The rules are Android's: a path is remembered when the radio holds it
 * or when the person pins it; an attempt scores the route it actually
 * used; at most [PathHistoryHygiene.MAX_PER_CONTACT] rows per contact,
 * the flood row never pruned; a row that is not whole hops at the
 * mesh's width is dropped (the hop-hash width is this codebase's
 * recurring defect — CLAUDE.md).
 */
class PathHistoryBook(private val nowMs: () -> Long, initial: List<PathRow> = emptyList()) {

    private val _rows = MutableStateFlow(initial)
    val rows: StateFlow<List<PathRow>> = _rows.asStateFlow()

    /** Remember [pathHex] for [contactKey], creating its row or touching its last use. */
    fun remember(contactKey: String, pathHex: String, hashWidth: Int) {
        val key = contactKey.lowercase()
        val hex = pathHex.lowercase()
        // "" is the flood route, which has no hops to check.
        if (hex.isNotEmpty() && hashWidth in 1..4 && !PathHistoryHygiene.isUsable(hex, hashWidth)) return
        val now = nowMs()
        val rows = _rows.value.toMutableList()
        val i = rows.indexOfFirst { it.contactKey == key && it.pathHex == hex }
        if (i >= 0) {
            rows[i] = rows[i].copy(lastUsedAt = now)
        } else {
            rows += PathRow(
                contactKey = key,
                pathHex = hex,
                hops = if (hashWidth in 1..4) PathHistoryHygiene.hopCount(hex.length / 2, hashWidth) else hex.length / 2,
                hashWidth = hashWidth,
                lastUsedAt = now,
            )
        }
        _rows.value = prune(rows, key)
    }

    /** Every contact's stored route, as the radio reports them. */
    fun rememberContacts(contacts: Collection<Contact>) {
        for (c in contacts) {
            if (c.storedPath.isEmpty()) continue
            remember(c.publicKeyHex, c.storedPath.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }, c.pathInfo.hashWidth)
        }
    }

    /** Count one attempt over [pathHex] ("" = flood) as delivered or not. */
    fun score(contactKey: String, pathHex: String, delivered: Boolean, hashWidth: Int) {
        val key = contactKey.lowercase()
        val hex = pathHex.lowercase()
        val now = nowMs()
        if (_rows.value.none { it.contactKey == key && it.pathHex == hex }) remember(key, hex, hashWidth)
        val rows = _rows.value.toMutableList()
        val i = rows.indexOfFirst { it.contactKey == key && it.pathHex == hex }
        if (i < 0) return
        val r = rows[i]
        rows[i] = if (delivered) {
            r.copy(successes = r.successes + 1, lastWorkedAt = now, lastUsedAt = now)
        } else {
            r.copy(failures = r.failures + 1, lastUsedAt = now)
        }
        _rows.value = rows
    }

    /** Best first: most recently proven, then most successes. */
    fun forContact(contactKey: String): List<PathRow> {
        val key = contactKey.lowercase()
        return _rows.value.filter { it.contactKey == key }
            .sortedWith(compareByDescending<PathRow> { it.lastWorkedAt }.thenByDescending { it.successes })
    }

    fun delete(contactKey: String, pathHex: String) {
        val key = contactKey.lowercase()
        _rows.value = _rows.value.filterNot { it.contactKey == key && it.pathHex == pathHex.lowercase() }
    }

    /** Purge: every contact's path history. */
    fun clearAll() { _rows.value = emptyList() }

    fun clear(contactKey: String) {
        val key = contactKey.lowercase()
        _rows.value = _rows.value.filterNot { it.contactKey == key }
    }

    /**
     * Drop rows that are not whole hops at the mesh's [meshWidth] and
     * restate the rest at it. Run when the radio reports its width.
     * Returns how many rows were dropped.
     */
    fun repair(meshWidth: Int): Int {
        if (meshWidth !in 1..4) return 0
        val before = _rows.value.size
        _rows.value = _rows.value.mapNotNull { r ->
            if (r.isFlood) return@mapNotNull r
            if (PathHistoryHygiene.defect(r.pathHex, meshWidth) != null) return@mapNotNull null
            r.copy(hops = PathHistoryHygiene.hopCount(r.pathHex.length / 2, meshWidth), hashWidth = meshWidth)
        }
        return before - _rows.value.size
    }

    private fun prune(rows: List<PathRow>, key: String): List<PathRow> {
        val mine = rows.filter { it.contactKey == key }
        if (mine.size <= PathHistoryHygiene.MAX_PER_CONTACT) return rows
        val keep = mine.sortedWith(
            compareByDescending<PathRow> { it.isFlood }
                .thenByDescending { it.lastWorkedAt }
                .thenByDescending { it.successes }
                .thenByDescending { it.lastUsedAt },
        ).take(PathHistoryHygiene.MAX_PER_CONTACT).toSet()
        return rows.filter { it.contactKey != key || it in keep }
    }

    fun toJson(): String = JSON.encodeToString(PathSnapshot.serializer(), PathSnapshot(rows = _rows.value))

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Never throws: an unreadable file is an empty history. */
        fun fromJson(json: String?, nowMs: () -> Long): PathHistoryBook {
            val rows = json?.let { runCatching { JSON.decodeFromString(PathSnapshot.serializer(), it).rows }.getOrNull() }
            return PathHistoryBook(nowMs, rows.orEmpty())
        }

        /**
         * A row's two lines: the hops (or "flood") and its record —
         * quality, hop count, ✓/✗, and [workedLabel] when it has ever
         * been proven (the platform formats the date).
         */
        fun hopsLine(row: PathRow, meshWidth: Int): String {
            if (row.isFlood) return "flood"
            val width = row.hashWidth.takeIf { it in 1..4 } ?: meshWidth.coerceIn(1, 4)
            val bytes = row.pathHex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
            return PathCodec.formatHops(bytes, width)
        }

        fun recordLine(row: PathRow, meshWidth: Int, workedLabel: String?): String = buildString {
            append(PathCodec.qualityLabel(row.successes, row.failures, row.isFlood))
            if (!row.isFlood) {
                val width = row.hashWidth.takeIf { it in 1..4 } ?: meshWidth.coerceIn(1, 4)
                append(" · ${PathHistoryHygiene.hopCount(row.pathHex.length / 2, width)} hop(s)")
            }
            append(" · ${row.successes}✓/${row.failures}✗")
            if (row.lastWorkedAt > 0 && workedLabel != null) append(" · worked $workedLabel")
        }
    }
}
