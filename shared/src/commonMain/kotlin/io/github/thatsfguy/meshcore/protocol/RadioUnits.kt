package io.github.thatsfguy.meshcore.protocol

import kotlin.math.abs
import kotlin.math.floor

/**
 * Radio parameters in the units a person reads them in — **MHz** for
 * frequency, **kHz** for bandwidth — regardless of what the transport
 * carries.
 *
 * ## Why this is needed
 *
 * The two ways to configure a node disagree, and both are right:
 *
 *  - the **companion binary API** takes frequency in whole **kHz** and
 *    bandwidth in whole **Hz** (`CMD_SET_RADIO_PARAMS`);
 *  - the **text CLI** takes both as floats in **MHz** and **kHz**
 *    (`set radio <MHz>,<kHz>,<sf>,<cr>`).
 *
 * The app used to show each transport's own units, so the same radio
 * read `910525` / `62500` locally and `910.5250244` / `62.5` remotely.
 * That is an implementation detail leaking into the UI, and the two
 * screens sit one tap apart.
 *
 * **The wire is unchanged.** MESHCORE_PROTOCOL.md is explicit that the
 * asymmetry is real — "Bandwidth really is Hz … Do not 'correct' one to
 * match the other" — and it cost this project every regional preset
 * once. Conversion happens at the edge, for display and entry only.
 *
 * ## Why conversion is integer arithmetic
 *
 * `910.525 * 1000` in binary floating point is not 910525. Every
 * conversion here goes through whole units so a value cannot drift by
 * one kHz on a round trip through a text field.
 */
object RadioUnits {

    /** kHz per MHz, and Hz per kHz — the same factor, named twice. */
    private const val THOUSAND = 1000L

    /**
     * Whole kHz rendered as MHz, with no trailing zeros: 910525 →
     * "910.525", 915000 → "915".
     */
    fun khzToMhzText(khz: Long): String = thousandthsText(khz)

    /** Whole Hz rendered as kHz: 62500 → "62.5", 250000 → "250". */
    fun hzToKhzText(hz: Long): String = thousandthsText(hz)

    /**
     * MHz as typed → whole kHz, or null when it is not a number.
     *
     * Rounds to the nearest kHz because that is the finest the binary
     * API can express; typing more precision than the radio can hold
     * would silently do nothing.
     */
    fun mhzTextToKhz(text: String): Long? = thousandthsOf(text)

    /** kHz as typed → whole Hz, or null when it is not a number. */
    fun khzTextToHz(text: String): Long? = thousandthsOf(text)

    /**
     * Tidy a decimal the firmware printed, WITHOUT changing the value it
     * denotes.
     *
     * The CLI stores frequency as a 32-bit float and prints it at full
     * precision, so `910.525` comes back as `910.5250244` — the nearest
     * float32, faithfully rendered. Showing the shorter form is honest
     * only if it is the same number to the radio, so this rounds to kHz
     * and keeps the result **only when it maps back to the same
     * float32**. Anything genuinely finer than a kHz is left exactly as
     * the node reported it.
     */
    fun tidyDecimal(text: String): String {
        val raw = text.trim()
        val value = raw.toFloatOrNull() ?: return text
        val thousandths = thousandthsOf(raw) ?: return text
        val tidied = thousandthsText(thousandths)
        return if (tidied.toFloatOrNull() == value) tidied else text
    }

    /** "910.525" → 910525; half-up, so .0005 does not round to even. */
    private fun thousandthsOf(text: String): Long? {
        val value = text.trim().toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        val scaled = floor(abs(value) * THOUSAND + 0.5).toLong()
        return if (value < 0) -scaled else scaled
    }

    /** 910525 → "910.525"; 915000 → "915"; 62500 → "62.5". */
    private fun thousandthsText(thousandths: Long): String {
        val sign = if (thousandths < 0) "-" else ""
        val magnitude = abs(thousandths)
        val whole = magnitude / THOUSAND
        val frac = magnitude % THOUSAND
        if (frac == 0L) return "$sign$whole"
        // Three digits, then drop the zeros the value does not need.
        val digits = frac.toString().padStart(3, '0').trimEnd('0')
        return "$sign$whole.$digits"
    }
}
