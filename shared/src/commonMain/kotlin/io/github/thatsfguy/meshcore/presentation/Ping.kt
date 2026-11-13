package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.TraceResult
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
import io.github.thatsfguy.meshcore.util.toHex

/**
 * Ping a repeater: a one-hop path trace whose only hop is that repeater.
 *
 * Why this works, from the firmware (`src/Mesh.cpp:42-68`): a node
 * forwards a TRACE only when the next hash in its payload matches its own
 * identity, appending the SNR it heard the packet at. Once every hop is
 * consumed, whoever hears it treats it as complete and the companion radio
 * pushes PUSH_CODE_TRACE_DATA (`companion_radio/MyMesh.cpp:855-876`) with
 * the hop hashes, one SNR per hop, and a final SNR for the link back to us.
 * So a trace of `[X]` reaches X only if X can hear us, comes back only if
 * we can hear X, and reports both directions — a ping, with no new wire
 * format and no login.
 *
 * Asked for in issue #5, to help choose hops for a manual path: the
 * question is "can I reach this repeater directly, and how well".
 */
object Ping {

    /** What a ping found. */
    sealed class Outcome {
        /**
         * It answered. [heardUsDb] is the SNR the repeater heard us at,
         * [weHeardDb] the SNR we heard it at, [roundTripMs] wall-clock
         * from send to reply (including the radio's own queueing).
         */
        data class Reached(
            val heardUsDb: Double,
            val weHeardDb: Double,
            val roundTripMs: Long,
        ) : Outcome()

        /** Nothing came back in time. Not proof it is down — see [describe]. */
        data object NoReply : Outcome()

        /**
         * The ping could not be sent at all: no radio, a key that is not
         * hex, or a hash width the trace flags cannot express.
         */
        data class CannotSend(val reason: String) : Outcome()
    }

    /**
     * The trace path for pinging the node with [publicKeyHex] on a mesh
     * whose hop hashes are [hashWidth] bytes: the key's first [hashWidth]
     * bytes, which is the hash a repeater matches itself against
     * (`isHashMatch(hash, 1 << path_sz)`).
     *
     * Derived from the FULL key at the width in force now, never stored —
     * the hop-hash width is a property of the mesh, and a hash computed at
     * the wrong width is the defect this codebase keeps relearning
     * (CLAUDE.md). Null when the width is one the trace flags cannot
     * declare: the flags carry log2 of the width in two bits, so only 1, 2
     * and 4 bytes exist on the wire for a trace, while a mesh's path hash
     * mode can be 3 bytes.
     */
    fun path(publicKeyHex: String, hashWidth: Int): ByteArray? {
        if (hashWidth !in TRACE_WIDTHS) return null
        val key = hexToBytesOrNull(publicKeyHex) ?: return null
        if (key.size < hashWidth) return null
        return key.copyOfRange(0, hashWidth)
    }

    /** Hash widths a TRACE's flags byte can declare. */
    val TRACE_WIDTHS = setOf(1, 2, 4)

    /**
     * Read a trace reply as a ping of [expectedHashHex].
     *
     * Insists the reply is a one-hop trace through that exact hash with
     * both SNRs present. The tag already correlates the reply with the
     * request, so a mismatch here means a malformed or truncated frame —
     * reported as no reply rather than as a reading nobody gave.
     */
    fun outcome(result: TraceResult?, expectedHashHex: String, roundTripMs: Long): Outcome {
        if (result == null) return Outcome.NoReply
        if (result.hops != listOf(expectedHashHex.lowercase())) return Outcome.NoReply
        if (result.snrs.size != 2) return Outcome.NoReply
        return Outcome.Reached(
            heardUsDb = result.snrs[0],
            weHeardDb = result.snrs[1],
            roundTripMs = roundTripMs.coerceAtLeast(0),
        )
    }

    /**
     * How many OTHER known nodes share [hashHex] as a key prefix.
     *
     * At a 1-byte width there are 256 possible hashes, and a busy mesh
     * has repeaters that collide: every one of them forwards a trace
     * addressed to that hash, so a reply proves that *a* node with this
     * hash answered, not necessarily this one. Nothing on the wire can
     * tell them apart, so the honest thing is to say so.
     */
    fun sharedPrefixCount(hashHex: String, targetKeyHex: String, knownKeys: Collection<String>): Int {
        val h = hashHex.lowercase()
        val target = targetKeyHex.lowercase()
        return knownKeys.asSequence()
            .map { it.lowercase() }
            .filter { it != target && it.startsWith(h) }
            .distinct()
            .count()
    }

    /** One line for the screen. */
    fun describe(outcome: Outcome, sharedWith: Int = 0): String = when (outcome) {
        is Outcome.Reached -> buildString {
            append("Reached directly in ${outcome.roundTripMs} ms. ")
            append("It heard you at ${snr(outcome.heardUsDb)}; ")
            append("you heard it at ${snr(outcome.weHeardDb)}.")
            if (sharedWith > 0) {
                append(" ⚠ $sharedWith other known ")
                append(if (sharedWith == 1) "node shares" else "nodes share")
                append(" this hop hash, so the reply may have come from ")
                append(if (sharedWith == 1) "it" else "one of them")
                append(".")
            }
        }
        Outcome.NoReply ->
            "No reply. It did not hear you, or you did not hear it, this time — " +
                "a ping only reaches repeaters in direct range, and one miss is not " +
                "proof a node is down."
        is Outcome.CannotSend -> outcome.reason
    }

    private fun snr(db: Double): String {
        val rounded = kotlin.math.round(db * 4) / 4
        val text = if (rounded == kotlin.math.floor(rounded)) "${rounded.toLong()}" else "$rounded"
        return "$text dB SNR"
    }

    /** Hex of [path], for comparing with [TraceResult.hops]. */
    fun hashHex(path: ByteArray): String = path.toHex()
}
