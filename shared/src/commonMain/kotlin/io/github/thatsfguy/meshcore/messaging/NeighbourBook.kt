package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.presentation.NeighbourEndpoint
import io.github.thatsfguy.meshcore.presentation.NeighbourFetch
import io.github.thatsfguy.meshcore.presentation.NeighbourLink
import io.github.thatsfguy.meshcore.presentation.NeighbourRecord
import io.github.thatsfguy.meshcore.presentation.neighbourLinks
import io.github.thatsfguy.meshcore.presentation.neighbourWrite
import io.github.thatsfguy.meshcore.protocol.Neighbours
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repeaters' neighbour tables as read — Android's `neighbours` table as
 * a pure class, for iOS (Phase 5, 2026-11-18). In memory for the app's
 * life on iOS (IOS_DECISIONS §52): a reading is only as fresh as the
 * adverts behind it, and nothing keeps it current.
 *
 * Page writes follow the shared [neighbourWrite] rule: the first page
 * replaces what was held for that repeater, later pages add to it, and
 * a rejected request never wipes a good earlier reading.
 */
class NeighbourBook {

    private val _records = MutableStateFlow<List<NeighbourRecord>>(emptyList())
    val records: StateFlow<List<NeighbourRecord>> = _records.asStateFlow()

    fun record(repeaterKeyHex: String, offset: Int, table: Neighbours.Table, nowMs: Long) {
        val key = repeaterKeyHex.lowercase()
        val plan = neighbourWrite(offset, table.entries.size, table.isEmptyButNotEmpty)
        var rows = _records.value
        if (plan.clearFirst) rows = rows.filterNot { it.repeaterKeyHex == key }
        if (plan.store) {
            val fresh = table.entries.map { NeighbourRecord(key, it.keyPrefixHex.lowercase(), it.snr, it.heardSecondsAgo, nowMs) }
            val replaced = fresh.map { it.keyPrefixHex }.toSet()
            rows = rows.filterNot { it.repeaterKeyHex == key && it.keyPrefixHex in replaced } + fresh
        }
        _records.value = rows
    }

    fun forRepeater(repeaterKeyHex: String): List<NeighbourRecord> =
        _records.value.filter { it.repeaterKeyHex == repeaterKeyHex.lowercase() }

    fun clear(repeaterKeyHex: String) {
        _records.value = _records.value.filterNot { it.repeaterKeyHex == repeaterKeyHex.lowercase() }
    }

    /** The repeater's links, resolved against every contact (not only the ones on screen). */
    fun links(repeaterKeyHex: String, contacts: Collection<Contact>, nowMs: Long): List<NeighbourLink> =
        neighbourLinks(
            forRepeater(repeaterKeyHex),
            contacts.map { NeighbourEndpoint(it.publicKeyHex, it.name.ifBlank { it.publicKeyHex.take(12) }, it.latitude, it.longitude) },
            nowMs,
        )

    /**
     * Read up to [PAGE_LIMIT] pages through [page], recording each, and
     * say what happened. Silence on the first page is no answer; on a
     * later page, what was collected so far stands.
     */
    suspend fun collect(repeaterKeyHex: String, nowMs: Long, page: suspend (offset: Int) -> Neighbours.Table?): NeighbourFetch {
        var offset = 0
        var stored = 0
        var total = 0
        repeat(PAGE_LIMIT) {
            val table = page(offset) ?: return if (offset == 0) NeighbourFetch.NoAnswer else NeighbourFetch.Collected(stored, total)
            if (table.isEmptyButNotEmpty) return NeighbourFetch.Rejected(table.total)
            record(repeaterKeyHex, offset, table, nowMs)
            stored += table.entries.size
            total = table.total
            if (!table.isPartial || table.entries.isEmpty()) return NeighbourFetch.Collected(stored, total)
            offset = table.nextOffset
        }
        return NeighbourFetch.Collected(stored, total)
    }

    companion object {
        /** Pages read per fetch (Android's NEIGHBOUR_PAGE_LIMIT). */
        const val PAGE_LIMIT = 5

        /** How long a probe's answers take to come back over the air. */
        const val PROBE_SETTLE_MS = 40_000L
    }
}
