package io.github.thatsfguy.meshcore.firmware

/**
 * One row of a node's admin console, oldest first.
 *
 * [at] is local arrival time in epoch millis — `MessageEntity.receivedAt`
 * — deliberately, not the sender-claimed timestamp. A node with a wrong
 * clock could otherwise write a row that is permanently "newer" than
 * anything this app does.
 */
data class ConsoleRow(val outgoing: Boolean, val text: String, val at: Long)

/** An address a node reported, and when it said so. */
data class AdvertisedAddress(val address: String, val at: Long)

/**
 * Reading a console thread for proof, rather than for recollection.
 *
 * Every version of the update-mode bug has been the same mistake: taking
 * something durable — a stored MAC, then a persisted console row — as a
 * statement about what a node is doing *now*. A console thread is
 * history, and the only way to get a present-tense fact out of it is to
 * insist that the fact is at the end of it and newer than the question.
 *
 * Correlation is positional, which is what the firmware's own CLI
 * affords: there is no request id, and the reply to a command is the
 * next thing the node says (`CommonCLI.cpp`). So [answerTo] requires the
 * command to be the **second-to-last** row and its answer the last.
 * Anything looser reads an old pair as a fresh one.
 */
object OtaEvidence {

    /**
     * The node's answer to [command], or null if it has not answered it
     * since [sentAfter].
     *
     * Returns the reply verbatim, including a refusal — telling a node
     * that said "??" apart from one that said nothing is the difference
     * between "this firmware is too old" and "go closer to it", and the
     * caller needs both.
     */
    fun answerTo(command: String, rows: List<ConsoleRow>, sentAfter: Long): String? {
        if (rows.size < 2) return null
        val asked = rows[rows.size - 2]
        val answer = rows[rows.size - 1]
        if (!asked.outgoing || answer.outgoing) return null
        if (!asked.text.trim().equals(command, ignoreCase = true)) return null
        // The command we sent, not one from an earlier session that
        // happens to be sitting at the end of a persisted thread.
        if (asked.at < sentAfter) return null
        return answer.text
    }

    /**
     * The newest address a node has reported advertising on since
     * [handledAt], anywhere in [rows].
     *
     * This is the passive path: `start ota` typed into the console tab
     * by hand is a real entry into update mode and has to be noticed.
     * It is lenient about position because there was no question to
     * correlate against — the watermark is what keeps it an event
     * rather than a permanent claim.
     */
    fun freshAdvertisingAddress(rows: List<ConsoleRow>, handledAt: Long): AdvertisedAddress? {
        for (row in rows.asReversed()) {
            if (row.outgoing || row.at <= handledAt) continue
            val address = OtaReply.advertisingAddress(row.text) ?: continue
            return AdvertisedAddress(address, row.at)
        }
        return null
    }
}

/**
 * Putting a node into update mode, one confirmed step at a time.
 *
 * `start ota` is not a question — the node switches its Bluetooth on and
 * says so, and after that the only way to change its mind is to walk to
 * it. Sending it to a node that is not listening therefore does not
 * fail; it just leaves the app believing something it has no evidence
 * for, which is precisely the state this whole class exists to prevent.
 *
 * So the node is made to prove it is there first, with a command that
 * costs nothing if it goes unanswered:
 *
 * 1. **`ver`** — a node in its bootloader cannot answer it (the Nordic
 *    bootloader is a BLE-only image; MeshCore's firmware is not running
 *    at all), so an answer is proof the application is up. The version
 *    is worth recording for its own sake: once the node has jumped,
 *    nothing can ask it again, and that is exactly when the firmware
 *    picker needs it.
 * 2. **`start ota`** — sent only on that answer.
 * 3. **`OK - mac: …`** — the node reporting the address it is now
 *    advertising on. That, and only that, is what sets update mode.
 *
 * [advance] is pure: give it the thread and the time, and it returns the
 * next state. A transition *into* a state is the caller's cue to send
 * the command that state is named for.
 */
sealed class OtaEntry {

    /** Nothing in progress. */
    object Idle : OtaEntry()

    /**
     * Asked for, but the console is mid-exchange.
     *
     * Correlating an answer positionally means only one command can be
     * outstanding at a time — with two, the thread ends in two replies
     * and neither is anybody's second-to-last row. The panel asks a node
     * for its `board` the moment it opens, so this is not hypothetical:
     * it is what happens when someone taps straight through.
     */
    data class Queued(val since: Long) : OtaEntry()

    /** `ver` has gone out; waiting to see whether the node is there. */
    data class ProvingTheNodeAnswers(val sentAt: Long) : OtaEntry()

    /** The node answered; `start ota` has gone out. */
    data class AwaitingUpdateMode(val version: String, val sentAt: Long) : OtaEntry()

    /**
     * The node reported the address it is advertising on. [at] is that
     * reply's own arrival time, to be stamped as the watermark so the
     * same row cannot be consumed a second time.
     */
    data class Confirmed(val version: String, val address: String, val at: Long) : OtaEntry()

    /** Stopped, with something worth showing the operator. */
    data class GaveUp(val reason: String) : OtaEntry()

    companion object {

        /**
         * How long to wait for either answer.
         *
         * A DM round trip is retried three times by default with a
         * flood on the last (MeshCore FAQ), so a node that is reachable
         * at all has answered well inside this. Two minutes is
         * generous on purpose: giving up early on a slow path would
         * abandon a sequence that was about to succeed, and the cost of
         * waiting is a spinner.
         */
        const val ANSWER_TIMEOUT_MS = 120_000L

        private fun timedOut(sentAt: Long, now: Long) = now - sentAt >= ANSWER_TIMEOUT_MS

        private fun refused(command: String, reply: String) =
            "The node answered `$command` with \"${reply.trim()}\". It is reachable, so this " +
                "is the firmware declining rather than a lost message — an older build may " +
                "not have the command at all."

        internal const val NO_VERSION_ANSWER =
            "The node did not answer `ver`, so `start ota` was not sent and nothing on it " +
                "has changed. Get closer, or check it is still on the mesh, and try again."

        internal const val NO_UPDATE_MODE_ANSWER =
            "`start ota` was sent but the node never reported an address. It may have " +
                "entered update mode anyway — check for a red LED, or look for it in the " +
                "flash step directly."

        internal const val NO_ADDRESS_REPORTED =
            "The node accepted `start ota` but did not report a Bluetooth address, so this " +
                "app cannot flash it. ESP32 boards answer that way: they raise a Wi-Fi " +
                "hotspot called `MeshCore OTA` instead, and the firmware is uploaded from a " +
                "browser at http://192.168.4.1/update."

        internal const val ADDRESS_READ_FAILED =
            "The node reported an all-zero Bluetooth address, which is what its firmware " +
                "leaves behind when the radio stack does not answer. It is not advertising " +
                "for an update. Power-cycling it and trying again is the usual fix."

        /**
         * The next state, given the console thread and the current time.
         *
         * Terminal states do not move: a sequence that finished or gave
         * up stays that way until the operator starts another.
         */
        fun advance(state: OtaEntry, rows: List<ConsoleRow>, now: Long): OtaEntry = when (state) {
            is Queued ->
                when {
                    // Something else is still owed a reply. Wait for it
                    // rather than sending into the gap.
                    rows.lastOrNull()?.outgoing == true ->
                        if (timedOut(state.since, now)) GaveUp(NO_VERSION_ANSWER) else state
                    else -> ProvingTheNodeAnswers(now)
                }

            is ProvingTheNodeAnswers -> {
                val reply = OtaEvidence.answerTo("ver", rows, state.sentAt)
                when {
                    reply == null ->
                        if (timedOut(state.sentAt, now)) GaveUp(NO_VERSION_ANSWER) else state
                    // A node that says anything at all is running its
                    // application firmware, so `??: ver` still proves
                    // the point — but it also means the console is not
                    // going to tell us the version, and an operator
                    // watching a sequence stall deserves the reason.
                    NodeIdentityReplies.isRealAnswer(reply) ->
                        AwaitingUpdateMode(reply.trim(), now)
                    else -> GaveUp(refused("ver", reply))
                }
            }

            is AwaitingUpdateMode -> {
                val reply = OtaEvidence.answerTo("start ota", rows, state.sentAt)
                val address = OtaReply.advertisingAddress(reply)
                when {
                    address != null -> Confirmed(state.version, address, rows.last().at)
                    reply == null ->
                        if (timedOut(state.sentAt, now)) GaveUp(NO_UPDATE_MODE_ANSWER) else state
                    !NodeIdentityReplies.isRealAnswer(reply) -> GaveUp(refused("start ota", reply))
                    // Answered, accepted, and no usable address in it.
                    // An all-zero MAC is a failed read on an nRF board;
                    // no MAC at all is an ESP32 raising a hotspot. Both
                    // leave nothing here to connect to, for different
                    // reasons and with different fixes.
                    reply.contains("mac:", ignoreCase = true) -> GaveUp(ADDRESS_READ_FAILED)
                    else -> GaveUp(NO_ADDRESS_REPORTED)
                }
            }

            else -> state
        }
    }
}
