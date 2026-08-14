package io.github.thatsfguy.meshcore.firmware

/**
 * What a remote node says when asked what it is.
 *
 * A companion reports its board and firmware in `DEVICE_INFO`, but a
 * repeater is not the connected radio — the only way to learn the same
 * facts is to ask it over its admin session. Two commands do it
 * (`CommonCLI.cpp`):
 *
 * ```
 * } else if (memcmp(command, "ver", 3) == 0) {
 *   sprintf(reply, "%s (Build: %s)", getFirmwareVer(), getBuildDate());
 * } else if (memcmp(command, "board", 5) == 0) {
 *   sprintf(reply, "%s", _board->getManufacturerName());
 * }
 * ```
 *
 * `board` returns the *same string* a companion reports, so one lookup
 * table serves both. Without it the firmware picker cannot narrow forty
 * boards to one, and a user is left choosing a build for a node they
 * cannot see by matching names by eye — which is exactly the mistake
 * that ends with the wrong image on a repeater.
 *
 * Correlation is positional: a console thread is a sequence, the node's
 * CLI is serialized, and replies come back in the order the commands
 * went out. So outstanding commands are a QUEUE — including ones we did
 * not send, which still consume their own reply. The LAST resolved pair
 * wins, so a fresh answer supersedes one from an earlier session.
 */
data class NodeIdentityReplies(
    /** `getManufacturerName()`, e.g. `ProMicro DIY`. */
    val board: String?,
    /** Firmware version as reported, e.g. `v1.16.0-07a3ca9`. */
    val version: String?,
    /** Build date out of the `ver` reply, e.g. `06-Jun-2026`. */
    val buildDate: String?,
) {
    /** One line for a header, or null when the node has said nothing yet. */
    fun describe(): String? =
        listOfNotNull(board, version).joinToString(" · ").ifBlank { null }

    companion object {
        /**
         * True when [text] is the node answering rather than declining.
         *
         * Public because the same distinction decides whether an
         * update-mode sequence may continue: a node that says `??` is
         * reachable, which is what [OtaEntry] is asking, but it is not
         * going to answer the question either.
         */
        fun isRealAnswer(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return false
            // "??: board" is the firmware's unknown-command reply, and
            // an older node that does not implement `board` gives it.
            if (t.startsWith("??")) return false
            if (t.equals("Error", ignoreCase = true)) return false
            if (t.startsWith("(ERR", ignoreCase = true)) return false
            return true
        }

        /**
         * [messages] is the console thread in order, as
         * `(outgoing, text)` pairs.
         */
        fun from(messages: List<Pair<Boolean, String>>): NodeIdentityReplies {
            var board: String? = null
            var version: String? = null
            var buildDate: String? = null
            // A queue, not a slot. The firmware answers in the order it
            // was asked (one serialized CLI), and the panel really does
            // send `board` and `ver` back to back — with a single slot
            // the board name was handed to `ver` and the version was
            // lost, which looks on screen like a node that never
            // reported its firmware at all.
            val awaiting = ArrayDeque<String?>()

            for ((outgoing, text) in messages) {
                if (outgoing) {
                    awaiting.addLast(
                        when (text.trim().lowercase()) {
                            "board" -> "board"
                            "ver" -> "ver"
                            // Not ours, but still owed a reply — so it
                            // takes its turn rather than dropping out of
                            // the queue and letting its answer land on
                            // whatever was asked before it.
                            else -> null
                        },
                    )
                    continue
                }
                if (awaiting.isEmpty()) continue
                val pending = awaiting.removeFirst() ?: continue
                if (!isRealAnswer(text)) continue
                val answer = text.trim()
                when (pending) {
                    // Shape-checked, both ways. Correlation is
                    // positional, so anything that reorders the thread
                    // files each answer under the other command — and
                    // the wrong one gets persisted against the contact
                    // as its board, which is what the firmware picker
                    // and the bootloader scan both work from. These two
                    // answers do not resemble each other, so the swap is
                    // recognisable on sight and worth refusing.
                    "board" -> if (!isNotABoardName(answer)) board = answer
                    // The mirror of the guard above, and it was missing.
                    // A board name was refused as a version; an
                    // `OK - mac: …` was not, so a node asked `ver` and
                    // then `start ota` in the same breath — which is
                    // exactly what the update sequence does — reported
                    // its firmware as "OK - mac: FF:5C:EF:28:2A:92".
                    // Seen on hardware 2026-08-14. A version has a shape;
                    // anything without it is somebody else's answer.
                    "ver" -> if (BoardAssets.isKnown(answer) || !looksLikeVersion(answer)) {
                        Unit
                    } else {
                        val build = BUILD.find(answer)
                        buildDate = build?.groupValues?.get(1)?.trim()
                        version = (build?.let { answer.removeRange(it.range) } ?: answer)
                            .trim()
                            .trim('(', ')', ',')
                            .trim()
                            .ifBlank { null }
                    }
                }
            }
            return NodeIdentityReplies(board, version, buildDate)
        }

        private val BUILD = Regex("""\(\s*Build:\s*([^)]*)\)""", RegexOption.IGNORE_CASE)

        /**
         * `getFirmwareVer()` is a semver-ish string and `ver` appends a
         * build date to it; no `getManufacturerName()` looks remotely
         * like either.
         */
        private val VERSION_SHAPE = Regex("""^v?\d+\.\d+""", RegexOption.IGNORE_CASE)

        private fun looksLikeVersion(text: String) =
            VERSION_SHAPE.containsMatchIn(text.trim()) || BUILD.containsMatchIn(text)

        /**
         * True when [text] is plainly not a `getManufacturerName()`.
         *
         * A board name is a short human string chosen per variant. It is
         * never a version, and it is never the node reporting the
         * address it is advertising on — but `OK - mac: …` was filed as
         * one and then persisted, because an unanswered `board` leaves
         * the queue holding an expectation that the next unrelated reply
         * satisfies. On a live repeater the Firmware screen read
         * "OK - mac: FF:5C:EF:28:2A:92 · v1.15.0-dee3e26" and the build
         * picker, which narrows on this string, offered all thirty-one
         * boards instead of the one.
         */
        private fun isNotABoardName(text: String) =
            looksLikeVersion(text) || OtaReply.advertisingAddress(text) != null ||
                text.trim().startsWith("OK", ignoreCase = true)
    }
}
