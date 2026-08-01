package io.github.thatsfguy.meshcore.protocol

/**
 * Coercion between what the firmware CLI *replies* and what it
 * *accepts* — they are not always the same, and getting it wrong sends
 * a malformed `set` to someone's repeater.
 *
 * Every helper here is total: malformed input yields null (or a safe
 * default) rather than throwing, because the input is a text reply from
 * a node on the mesh.
 */
object CliValues {

    /** Canonical on/off wording the firmware accepts for boolean vars. */
    fun onOff(enabled: Boolean): String = if (enabled) "on" else "off"

    /** `multi.acks` is 1/0 rather than on/off. */
    fun oneZero(enabled: Boolean): String = if (enabled) "1" else "0"

    /**
     * Duty cycle: the firmware REPLIES `"50.0%"` but only accepts a
     * whole percent (`set dutycycle 50`). Returns null when the value
     * isn't a usable percent — including out-of-range values, which the
     * node would reject anyway.
     */
    fun parsePercent(reply: String, range: IntRange = 1..100): Int? {
        val cleaned = reply.trim().removeSuffix("%").trim()
        if (cleaned.isEmpty()) return null
        val whole = cleaned.substringBefore('.')
        val value = whole.toIntOrNull() ?: return null
        return value.takeIf { it in range }
    }

    /** Loop-detection modes the firmware understands. */
    val LOOP_DETECT_MODES = listOf("off", "minimal", "moderate", "strict")

    /** Index of [reply] in [LOOP_DETECT_MODES], or null if unrecognised. */
    fun parseLoopDetect(reply: String): Int? =
        LOOP_DETECT_MODES.indexOf(reply.trim().lowercase()).takeIf { it >= 0 }

    /**
     * `owner.info` carries user newlines as `|` on the wire. Decode for
     * display, encode before sending — a raw newline would truncate the
     * command at the frame's NUL/линe boundary.
     */
    fun decodeOwnerInfo(wire: String): String = wire.replace('|', '\n')

    fun encodeOwnerInfo(text: String): String =
        text.replace('\r', ' ').replace('\n', '|')

    /**
     * A plain integer setting (`flood.max`, delays, thresholds). Null
     * when the text isn't an integer in [range] — the caller then simply
     * doesn't emit that `set`.
     */
    fun parseInt(text: String, range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE): Int? =
        text.trim().toIntOrNull()?.takeIf { it in range }
}
