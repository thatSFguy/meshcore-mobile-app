package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Frames
import io.github.thatsfguy.meshcore.util.truncateUtf8

/**
 * How many bytes the composer may hold (2026-11-18, shared so iOS stops
 * accepting messages the radio can't carry — the frame builder does NOT
 * trim message text).
 *
 * A channel post also carries "<name>: ", so its budget depends on this
 * radio's name. MeshCore has no reply field: a quote is literally
 * prefixed to the text and comes out of the same budget, so it is
 * reserved up front (Android's bodyBudget).
 */
object ComposerBudget {
    fun budget(isChannel: Boolean, selfName: String?, quotePrefix: String): Int {
        val max = if (isChannel) Frames.maxChannelMessageBytes(selfName) else Frames.maxContactMessageBytes()
        return (max - quotePrefix.encodeToByteArray().size).coerceAtLeast(0)
    }

    fun fits(text: String, budget: Int): Boolean = text.encodeToByteArray().size <= budget

    /** [text] cut to [budget] bytes on a character boundary. */
    fun clamp(text: String, budget: Int): String =
        if (fits(text, budget)) text else truncateUtf8(text, budget).decodeToString()

    fun remaining(text: String, budget: Int): Int = budget - text.encodeToByteArray().size

    /** Shown only near the end, so it isn't noise on every message. */
    fun counter(text: String, budget: Int): String? =
        remaining(text, budget).takeIf { it < 20 }?.let { "$it bytes left" }
}
