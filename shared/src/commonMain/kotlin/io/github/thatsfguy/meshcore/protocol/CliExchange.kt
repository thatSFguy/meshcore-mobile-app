package io.github.thatsfguy.meshcore.protocol

/**
 * Telling a repeater's answer to one CLI command from its answer to
 * another.
 *
 * The CLI has no request id. The repeater answers with a TXT_MSG whose
 * timestamp is its own clock (`simple_repeater/MyMesh.cpp`,
 * onPeerDataRecv: `timestamp = getCurrentTimeUnique()`), so nothing in
 * the reply points back at the command. Taking "the next reply from that
 * node" as the answer is right only while every answer arrives inside
 * its wait. One that arrives late becomes the answer to the NEXT
 * question, and every answer after it shifts one place. On the Regions
 * screen that turned a late `ver` into the reply to
 * `get flood.max.unscoped`: no number in it, so no hop limit, so
 * "Relay all".
 *
 * What the firmware does give us is distinct shapes. A `get` always
 * answers `> value` (CommonCLI.cpp, handleGetCmd), `ver` always
 * `<version> (Build: <date>)`, `region default` always
 * ` default scope is …`. A reply in a shape that belongs to a DIFFERENT
 * command is known not to be this command's answer, and is skipped
 * rather than taken.
 */
object CliExchange {

    /** The shapes a reply can be recognised by. */
    enum class Shape {
        /** `> value` — every `get`. */
        Value,
        /** `OK`, `OK - reboot to apply`, `ok` — a write that worked. */
        Ok,
        /** `v1.16.0-07a3ca9 (Build: 06-Jun-2026)` — `ver`. */
        Version,
        /** ` default scope is <name>` / `… is now …` — `region default`. */
        DefaultScope,
        /** ` home is <name>` / ` home is now …` — `region home`. */
        Home,
        /** A refusal. Any command can get one, so it is never skipped. */
        Refusal,
        /** Nothing recognisable. Also never skipped. */
        Other,
    }

    fun shapeOf(reply: String): Shape {
        val t = reply.trim()
        return when {
            t.startsWith(">") -> Shape.Value
            isRefusal(t) -> Shape.Refusal
            t.startsWith("OK", ignoreCase = true) -> Shape.Ok
            t.contains("(Build:") -> Shape.Version
            t.startsWith("default scope is") -> Shape.DefaultScope
            t.startsWith("home is") -> Shape.Home
            else -> Shape.Other
        }
    }

    /**
     * The firmware's ways of saying no: `Err - unknown region`,
     * `Error, max 64`, `ERR: bad pubkey`, `(ERR: …)`, `??: region`,
     * `unknown config: x`, `Unknown command`.
     */
    private fun isRefusal(t: String): Boolean =
        t.startsWith("Err", ignoreCase = true) ||
            t.startsWith("(ERR", ignoreCase = true) ||
            t.startsWith("??") ||
            t.startsWith("unknown config", ignoreCase = true) ||
            t.startsWith("Unknown command", ignoreCase = true)

    /**
     * The shape [command]'s answer takes, or null when it has no
     * distinctive one. A bare `region` prints a tree — [Shape.Other] —
     * so it still refuses every distinctive shape; a command this table
     * doesn't know is left to positional matching, as before.
     */
    fun expectedShape(command: String): Shape? {
        val c = command.trim()
        val words = c.split(' ').filter { it.isNotEmpty() }
        return when {
            words.firstOrNull() == "get" -> Shape.Value
            words.firstOrNull() == "set" -> Shape.Ok
            c == "ver" -> Shape.Version
            words.firstOrNull() != "region" -> null
            words.size == 1 -> Shape.Other
            words[1] == "default" || words[1] == "def" -> Shape.DefaultScope
            words[1] == "home" -> Shape.Home
            words[1] in REGION_WRITES -> Shape.Ok
            words[1] == "list" || words[1] == "get" -> Shape.Other
            else -> null
        }
    }

    private val REGION_WRITES = setOf("save", "allowf", "denyf", "put", "remove", "load")

    /**
     * Whether [reply] can be the answer to [command]. Refusals and
     * unrecognised text always can; a distinctive shape only when it is
     * the one [command] answers with.
     */
    fun accepts(command: String, reply: String): Boolean {
        val expected = expectedShape(command) ?: return true
        val shape = shapeOf(reply)
        return shape == Shape.Refusal || shape == Shape.Other || shape == expected
    }

    /**
     * Whether [command] only reads, and so may be sent again when the
     * node is silent. A write is sent once: most are harmless twice, but
     * not all — a second `start ota` answers "Error" — and the caller of
     * a write is the one who knows.
     */
    fun isRead(command: String): Boolean {
        val words = command.trim().split(' ').filter { it.isNotEmpty() }
        return when (words.firstOrNull()) {
            "get" -> words.size >= 2
            "ver", "board" -> words.size == 1
            "region" -> words.size == 1 ||
                (words.size == 2 && words[1] in setOf("default", "home")) ||
                (words.size >= 2 && words[1] in setOf("list", "get"))
            else -> false
        }
    }
}

/**
 * When to ask a repeater again.
 *
 * A CLI command has no delivery receipt: the companion sends it with no
 * ACK expected (`sendCommandData`, `expected_ack = 0`), so the only sign
 * it arrived is the answer, and an answer lost on the air looks exactly
 * like a command that never got there. The operator's own fix — ask
 * again — worked on the test RAK when a `ver` went unanswered.
 *
 * Asking again is safe for the node: the companion stamps each send
 * with `getCurrentTimeUnique()` (companion_radio/MyMesh.cpp,
 * CMD_SEND_TXT_MSG, firmware v1.12+), so a resend is a new command to
 * the repeater, not the same-timestamp retry it answers with nothing.
 */
object CliResend {
    /**
     * Silence before asking again. A direct reply on this mesh came back
     * in 1.3-1.8 s — the repeater holds each CLI answer for 600 ms
     * (`CLI_REPLY_DELAY_MILLIS`) — so ten seconds is several round trips
     * even over a flood path; asking early costs only airtime.
     */
    const val RESEND_AFTER_MS = 10_000L

    /** Sends in all, for a command that changes nothing on the node. */
    const val MAX_SENDS = 3

    /** True when a command last sent at [lastSentAt] should go again. */
    fun due(lastSentAt: Long, now: Long, sends: Int, maxSends: Int): Boolean =
        sends < maxSends && now - lastSentAt >= RESEND_AFTER_MS
}

/**
 * Answers still owed to commands that were sent more than once.
 *
 * A resend fixes a lost reply, but when the first reply was only SLOW
 * the node answers both sends, and the second answer lands while the app
 * is waiting on its next question. Shape can't catch that when the next
 * question is the same kind (`get` after `get`). Content can: the extra
 * answer repeats the one already taken, so a reply identical to an owed
 * one is skipped.
 *
 * Skipping by text is safe even when the next question's real answer
 * happens to be the same text: whichever of the two is taken, the value
 * is the same, and the wait resends if the one skipped was the only one.
 */
class CliStragglers(private val ttlMs: Long = TTL_MS) {
    private class Owed(val text: String, var count: Int, val until: Long)

    private val owed = ArrayList<Owed>()

    /** [text] answered a command sent [sends] times; the rest may follow. */
    fun owe(text: String, sends: Int, now: Long) {
        if (sends <= 1) return
        owed += Owed(text.trim(), sends - 1, now + ttlMs)
    }

    /** True, and one fewer owed, when [text] is an expected duplicate. */
    fun consume(text: String, now: Long): Boolean {
        owed.removeAll { it.until <= now || it.count <= 0 }
        val hit = owed.firstOrNull { it.text == text.trim() } ?: return false
        hit.count--
        return true
    }

    companion object {
        /** Longer than any one command's whole wait: three sends and the last timeout. */
        const val TTL_MS = 45_000L
    }
}
