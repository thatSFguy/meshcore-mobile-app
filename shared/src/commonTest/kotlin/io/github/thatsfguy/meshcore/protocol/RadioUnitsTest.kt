package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Radio units, and the two things that go wrong with them here.
 *
 * **Wrong unit.** The companion API takes kHz and Hz; the CLI takes MHz
 * and kHz. Getting that backwards is a factor of a thousand, and it has
 * cost this project before — a label that said Hz where the value was
 * kHz let a 1000x-wrong frequency pass validation and be rejected by the
 * radio with no explanation (LESSONS §5). The UI now shows MHz/kHz
 * everywhere and converts at the edge, so the conversion itself is the
 * thing that has to be right.
 *
 * **Float drift.** `910.525 * 1000` is not 910525 in binary floating
 * point. A frequency that loses a kHz on a round trip through a text
 * field is a radio that has gone deaf, so every conversion is pinned on
 * real preset values.
 */
class RadioUnitsTest {

    // --- rendering -------------------------------------------------------

    @Test
    fun wholeUnitsRenderWithoutADecimalPoint() {
        assertEquals("915", RadioUnits.khzToMhzText(915_000))
        assertEquals("250", RadioUnits.hzToKhzText(250_000))
    }

    @Test
    fun fractionsKeepOnlyTheDigitsTheyNeed() {
        assertEquals("910.525", RadioUnits.khzToMhzText(910_525))
        assertEquals("62.5", RadioUnits.hzToKhzText(62_500))
        assertEquals("433.65", RadioUnits.khzToMhzText(433_650))
        // A value whose fraction starts with a zero must not lose it —
        // 910.025 is not 910.25.
        assertEquals("910.025", RadioUnits.khzToMhzText(910_025))
        assertEquals("910.005", RadioUnits.khzToMhzText(910_005))
    }

    // --- parsing ---------------------------------------------------------

    @Test
    fun typedMhzBecomesWholeKhz() {
        assertEquals(910_525L, RadioUnits.mhzTextToKhz("910.525"))
        assertEquals(915_000L, RadioUnits.mhzTextToKhz("915"))
        assertEquals(433_650L, RadioUnits.mhzTextToKhz("433.65"))
        assertEquals(910_525L, RadioUnits.mhzTextToKhz("  910.525  "))
    }

    @Test
    fun typedKhzBecomesWholeHz() {
        assertEquals(62_500L, RadioUnits.khzTextToHz("62.5"))
        assertEquals(250_000L, RadioUnits.khzTextToHz("250"))
        assertEquals(7_800L, RadioUnits.khzTextToHz("7.8"))
    }

    @Test
    fun nonsenseIsRefusedRatherThanCoercedToZero() {
        // A frequency that silently became 0 would be sent to the radio.
        for (bad in listOf("", "  ", "abc", "9.9.9", "NaN", "Infinity", "-Infinity")) {
            assertNull(RadioUnits.mhzTextToKhz(bad), "accepted \"$bad\"")
            assertNull(RadioUnits.khzTextToHz(bad), "accepted \"$bad\"")
        }
    }

    // --- the property that actually matters ------------------------------

    @Test
    fun everyShippedPresetSurvivesTheRoundTrip() {
        // Display then re-parse must be the identity. This is the whole
        // point of the helper: a preset that drifts by one kHz through
        // the settings screen is a radio that cannot hear its mesh.
        for (preset in RadioPresets.ALL) {
            val shownMhz = RadioUnits.khzToMhzText(preset.frequencyKhz)
            val backToKhz: Long = RadioUnits.mhzTextToKhz(shownMhz) ?: -1L
            assertEquals(
                preset.frequencyKhz,
                backToKhz,
                "${preset.name}: $shownMhz MHz did not round-trip",
            )
            val shownKhz = RadioUnits.hzToKhzText(preset.bandwidthHz)
            val backToHz: Long = RadioUnits.khzTextToHz(shownKhz) ?: -1L
            assertEquals(
                preset.bandwidthHz,
                backToHz,
                "${preset.name}: $shownKhz kHz did not round-trip",
            )
        }
    }

    @Test
    fun theWholeKhzRangeRoundTrips() {
        // Examples miss what a sweep catches — the .x00 and .0x0 cases
        // are exactly where trailing-zero trimming goes wrong.
        for (khz in 902_000L..902_100L) {
            val back: Long = RadioUnits.mhzTextToKhz(RadioUnits.khzToMhzText(khz)) ?: -1L
            assertEquals(khz, back, "$khz kHz")
        }
    }

    // --- tidying what the firmware printed --------------------------------

    @Test
    fun theFloat32ArtifactIsShortenedToWhatItMeans() {
        // The reported case: the node stores frequency as a 32-bit float,
        // and 910.525 is not exactly representable, so `get radio`
        // answers 910.5250244 — the nearest float32, printed honestly.
        assertEquals("910.525", RadioUnits.tidyDecimal("910.5250244"))
        assertEquals("62.5", RadioUnits.tidyDecimal("62.5"))
        assertEquals("915", RadioUnits.tidyDecimal("915.0"))
    }

    @Test
    fun tidyingNeverChangesWhatTheRadioHolds() {
        // Shortening is honest only when the shorter text is the SAME
        // float32. Anything genuinely finer than a kHz must survive
        // untouched rather than be rounded into a different frequency.
        val finer = "910.5255"
        assertEquals(finer, RadioUnits.tidyDecimal(finer))
        // And whatever comes back, it still denotes the same number.
        for (text in listOf("910.5250244", "62.5", "915.0", "433.6499939", finer)) {
            assertEquals(
                text.toFloat(),
                RadioUnits.tidyDecimal(text).toFloat(),
                "tidyDecimal changed the value of $text",
            )
        }
    }

    @Test
    fun tidyingLeavesWhatItCannotParse() {
        for (text in listOf("", "abc", "1,2", "--")) {
            assertEquals(text, RadioUnits.tidyDecimal(text))
        }
    }
}
