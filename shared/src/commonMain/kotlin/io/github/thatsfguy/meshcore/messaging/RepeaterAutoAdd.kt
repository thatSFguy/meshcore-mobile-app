package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.presentation.RepeaterSignals
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull

/**
 * The app's own repeater auto-add rules, as a pass over this phone's
 * history and discovery inbox (2026-11-18 — Android's
 * MessageRepository.autoAddUsefulRepeaters, lifted so iOS runs the same).
 *
 * Every decision is [RepeaterSignals.toAutoAdd]'s (tested there): only
 * while the radio is NOT adding repeaters itself, only repeaters that
 * relayed messages you received or were heard direct, never one already
 * tried this session, and the pass stops at a full contact list.
 */
class RepeaterAutoAdd(private val radio: Radio, private val nowMs: () -> Long) {

    interface Radio {
        val contactKeys: Set<String>
        val autoAddFlags: Int?
        val contactsFull: Boolean
        suspend fun addFromAdvert(advert: ByteArray): MeshCoreEngine.AddOutcome
    }

    private val tried = mutableSetOf<String>()
    private var lastPass = 0L

    /**
     * Run a pass; the log lines it produced. [throttle] skips a pass within
     * a minute of the last, for triggers that fire on every message.
     */
    suspend fun run(book: MessageBook, heard: DiscoveredBook, rules: RepeaterSignals.Rules, throttle: Boolean = false): List<String> {
        if (!rules.any) return emptyList()
        val now = nowMs()
        if (throttle && now - lastPass < THROTTLE_MS) return emptyList()
        lastPass = now
        val nodes = heard.nodes.value
        val counts = RepeaterSignals.relayCounts(
            book.messages.value
                .filter { !it.outgoing && it.receivedAtMs >= now - RepeaterSignals.RELAY_WINDOW_MS }
                .mapNotNull { m ->
                    val path = m.arrivalPathHex?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val width = m.arrivalHashWidth ?: return@mapNotNull null
                    RepeaterSignals.RouteSample(path, width)
                },
        )
        val known = radio.contactKeys + nodes.map { it.keyHex }
        val chosen = RepeaterSignals.toAutoAdd(
            candidates = nodes.map { RepeaterSignals.Candidate(it.keyHex, it.type, it.minHops) },
            rules = rules,
            radioAutoAddFlags = radio.autoAddFlags,
            contactsFull = radio.contactsFull,
            counts = counts,
            knownKeys = known,
            tried = tried.toSet(),
        )
        val out = ArrayList<String>()
        for (key in chosen) {
            val node = nodes.firstOrNull { it.keyHex == key } ?: continue
            val advert = hexToBytesOrNull(node.advertHex)
            val outcome = if (advert == null) {
                MeshCoreEngine.AddOutcome.Unverified
            } else {
                runCatching { radio.addFromAdvert(advert) }.getOrDefault(MeshCoreEngine.AddOutcome.Failed)
            }
            val why = RepeaterSignals.describe(RepeaterSignals.signalsFor(key, node.minHops, counts, known))
            out += "auto-add repeater ${key.take(8)} ($why): $outcome"
            when (outcome) {
                MeshCoreEngine.AddOutcome.Added -> heard.dismiss(key)
                MeshCoreEngine.AddOutcome.TableFull -> return out
                else -> tried += key
            }
        }
        return out
    }

    companion object {
        const val THROTTLE_MS = 60_000L

        fun over(engine: MeshCoreEngine, nowMs: () -> Long): RepeaterAutoAdd = RepeaterAutoAdd(
            object : Radio {
                override val contactKeys: Set<String> get() = engine.contacts.value.keys
                override val autoAddFlags: Int? get() = engine.autoAddFlags.value
                override val contactsFull: Boolean get() = engine.contactsFull.value
                override suspend fun addFromAdvert(advert: ByteArray) = engine.addContactFromAdvert(advert)
            },
            nowMs,
        )
    }
}
