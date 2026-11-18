package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.presentation.NodeListItem
import io.github.thatsfguy.meshcore.presentation.StaleNodes
import io.github.thatsfguy.meshcore.util.RelativeTime
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull

/**
 * Carrying out a stale-node sweep: remove each chosen node from the
 * RADIO, one at a time, oldest first, counting refusals — the loop
 * Android ran in its ViewModel, shared for iOS (2026-11-18). Which nodes
 * are chosen is [StaleNodes.sweep]: favourites, never-heard nodes and
 * anything heard or messaged inside the window are never in it.
 */
class StaleSweep(private val radio: Radio) {

    interface Radio {
        suspend fun remove(keyHex: String): Boolean
    }

    /** What happened: the keys actually removed, and the sentence to show. */
    data class Result(val removedKeys: List<String>, val message: String)

    suspend fun run(sweep: StaleNodes.Sweep): Result {
        val removed = ArrayList<String>()
        var failed = 0
        for (node in sweep.remove) {
            val gone = runCatching { radio.remove(node.keyHex) }.getOrDefault(false)
            if (gone) removed += node.keyHex else failed++
        }
        return Result(removed, StaleNodes.outcome(removed.size, failed))
    }

    companion object {
        /** Rows previewed in the confirmation before "…and N more" (Android's 6). */
        const val PREVIEW_ROWS = 6

        const val EXPLAINER =
            "Removes nodes from the radio's contact list when nothing has been heard from them — no advert and no message — for longer than this."
        const val PROTECTED =
            "Favourites are never removed, whatever the slider says. Nor is a node that has never been heard from at all, which is what a contact just added from a QR code looks like."
        const val KEPT_CONVERSATIONS =
            "Conversations are kept. A thread with a removed node shows its key instead of its name, and the node comes back on its own if it advertises again."

        /** "Name · 3 weeks ago" for the first [PREVIEW_ROWS] nodes to go. */
        fun previewLines(sweep: StaleNodes.Sweep, nowMillis: Long): List<String> =
            sweep.remove.take(PREVIEW_ROWS).map { node ->
                node.name.ifBlank { node.keyHex.take(12) } + " · " +
                    RelativeTime.agoMillis(nowMillis - StaleNodes.lastEvidenceMillis(node))
            }

        fun moreLine(sweep: StaleNodes.Sweep): String? =
            (sweep.count - PREVIEW_ROWS).takeIf { it > 0 }?.let { "…and $it more" }

        fun sweep(nodes: List<NodeListItem>, olderThanDays: Int, nowMillis: Long): StaleNodes.Sweep =
            StaleNodes.sweep(nodes, olderThanDays, nowMillis)

        fun over(engine: MeshCoreEngine): StaleSweep = StaleSweep(object : Radio {
            override suspend fun remove(keyHex: String): Boolean {
                val key = hexToBytesOrNull(keyHex) ?: return false
                return engine.removeContact(key)
            }
        })
    }
}
