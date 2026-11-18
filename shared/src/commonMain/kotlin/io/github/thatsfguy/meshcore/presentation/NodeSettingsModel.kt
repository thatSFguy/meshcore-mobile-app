package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.model.SelfInfo
import io.github.thatsfguy.meshcore.protocol.RadioPresets
import io.github.thatsfguy.meshcore.protocol.RadioUnits
import kotlin.math.abs

/**
 * The decisions behind the "This node" settings screens — what a typed
 * value means, whether it may be sent, and the sentence shown about it.
 *
 * Lifted out of Android's SettingsSections (2026-11-18) for iOS Phase 3:
 * the same rules, tested once, instead of a Swift copy of each.
 */
object NodeSettings {

    // --- Radio -----------------------------------------------------------

    data class RadioParams(val freqKhz: Long, val bwHz: Long, val sf: Int, val cr: Int)

    const val RADIO_PARAMS_HELP =
        "Freq is MHz (300–2500, e.g. 910.525), BW is kHz (7–500, e.g. 62.5), SF 5–12, CR 5–8"

    /**
     * The four typed fields as the frame wants them, or null when any is
     * out of range. Frequency is typed in MHz and sent in kHz; bandwidth
     * typed in kHz and sent in Hz (RadioUnits says why the wire is
     * asymmetric).
     */
    fun parseRadioParams(freqMhz: String, bwKhz: String, sf: String, cr: String): RadioParams? {
        val f = RadioUnits.mhzTextToKhz(freqMhz) ?: return null
        val b = RadioUnits.khzTextToHz(bwKhz) ?: return null
        val s = sf.trim().toIntOrNull() ?: return null
        val c = cr.trim().toIntOrNull() ?: return null
        if (f !in 300_000L..2_500_000L || b !in 7_000L..500_000L || s !in 5..12 || c !in 5..8) return null
        return RadioParams(f, b, s, c)
    }

    /** A typed TX power the radio says it can do, or null. */
    fun parseTxPower(text: String, maxDbm: Int): Int? =
        text.trim().toIntOrNull()?.takeIf { it in 1..maxDbm }

    /** Which presets the radio's current four values match, in words. */
    fun presetMatchLine(freqKhz: Long, bwHz: Long, sf: Int, cr: Int): String {
        val matches = RadioPresets.matching(freqKhz, bwHz, sf, cr)
        return when (matches.size) {
            0 -> "Current settings match no known preset."
            1 -> "Currently: ${matches[0].name}."
            else -> "Currently matches ${matches.size} presets: ${matches.joinToString(", ") { it.name }}"
        }
    }

    /** The one preset name to put in a share code, or null when ambiguous. */
    fun presetNameFor(freqKhz: Long, bwHz: Long, sf: Int, cr: Int): String? =
        RadioPresets.matching(freqKhz, bwHz, sf, cr).singleOrNull()?.name

    fun shareCaption(region: String?): String = if (region.isNullOrBlank()) {
        "Frequency, bandwidth, SF, CR and hop-hash width; no region. Not TX power — that is the scanner's legal limit."
    } else {
        "Frequency, bandwidth, SF, CR, hop-hash width and region #$region. Not TX power — that is the scanner's legal limit."
    }

    /**
     * [radioDefaultScopeLine] under a parameter name Swift can call: the
     * original's `default` is a C keyword, which Kotlin/Native renames.
     */
    fun defaultScopeLine(scope: io.github.thatsfguy.meshcore.protocol.RadioDefaultScope): String? =
        radioDefaultScopeLine(scope)

    // --- Identity --------------------------------------------------------

    data class Position(val latitude: Double, val longitude: Double)

    /** Two typed coordinates, or null unless both parse and are on Earth. */
    fun parsePosition(lat: String, lon: String): Position? {
        val la = lat.trim().toDoubleOrNull() ?: return null
        val lo = lon.trim().toDoubleOrNull() ?: return null
        if (la.isNaN() || lo.isNaN() || la !in -90.0..90.0 || lo !in -180.0..180.0) return null
        return Position(la, lo)
    }

    // --- Clock -----------------------------------------------------------

    /**
     * Drift past this is worth correcting. The engine auto-corrects on
     * connect at the same threshold ([io.github.thatsfguy.meshcore.engine.MeshCoreEngine]).
     */
    const val CLOCK_DRIFT_WARN_SECONDS: Long = 30

    fun isDriftLarge(seconds: Long): Boolean = abs(seconds) > CLOCK_DRIFT_WARN_SECONDS

    /** [seconds] = radio clock minus phone clock; positive means the radio is ahead. */
    fun formatDrift(seconds: Long): String {
        val magnitude = abs(seconds)
        val amount = when {
            magnitude == 0L -> return "none — clocks agree"
            magnitude < 60 -> "$magnitude s"
            magnitude < 3600 -> "${magnitude / 60} min ${magnitude % 60} s"
            magnitude < 86_400 -> "${magnitude / 3600} h ${(magnitude % 3600) / 60} min"
            else -> "${magnitude / 86_400} days"
        }
        return if (seconds > 0) "$amount (radio ahead)" else "$amount (radio behind)"
    }

    // --- Custom variables ------------------------------------------------

    /**
     * The `key:value` string CMD_SET_CUSTOM_VAR carries, or null when it
     * cannot be said unambiguously: the firmware splits on the FIRST ':',
     * so a key containing one would set a different variable than the
     * one typed.
     */
    fun customVarAssignment(key: String, value: String): String? {
        val k = key.trim()
        if (k.isEmpty() || ':' in k || '\u0000' in k || '\u0000' in value) return null
        return "$k:${value.trim()}"
    }

    // --- Auto-add --------------------------------------------------------

    fun toggleFlag(flags: Int, bit: Int, on: Boolean): Int = if (on) flags or bit else flags and bit.inv()
}

/**
 * The three telemetry permissions and two advert policies that
 * CMD_SET_OTHER_PARAMS writes TOGETHER — changing one control sends all
 * five, so each change starts from what the radio last reported.
 * Telemetry modes: 0 deny, 1 per-contact flags, 2 everyone; two bits each.
 */
data class OtherParams(
    val base: Int,
    val location: Int,
    val environment: Int,
    val advertLocationPolicy: Int,
    val multiAcks: Int,
) {
    val telemetryFlags: Int get() = ((environment and 3) shl 4) or ((location and 3) shl 2) or (base and 3)

    val includesLocation: Boolean get() = advertLocationPolicy == 1
    val multiAcksOn: Boolean get() = multiAcks != 0

    fun withBase(mode: Int) = copy(base = mode.coerceIn(0, 2))
    fun withLocation(mode: Int) = copy(location = mode.coerceIn(0, 2))
    fun withEnvironment(mode: Int) = copy(environment = mode.coerceIn(0, 2))
    fun withIncludesLocation(on: Boolean) = copy(advertLocationPolicy = if (on) 1 else 0)
    fun withMultiAcks(on: Boolean) = copy(multiAcks = if (on) 1 else 0)

    companion object {
        val TELEMETRY_LABELS = listOf("Deny", "Flags", "All")

        fun of(info: SelfInfo) = OtherParams(
            base = info.telemetryModes and 3,
            location = (info.telemetryModes shr 2) and 3,
            environment = (info.telemetryModes shr 4) and 3,
            advertLocationPolicy = info.advertLocPolicy,
            multiAcks = info.multiAcks,
        )
    }
}
