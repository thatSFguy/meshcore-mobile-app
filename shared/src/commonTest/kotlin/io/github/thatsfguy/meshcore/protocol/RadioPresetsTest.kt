package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regional radio presets (PARITY §1).
 *
 * These are transcribed values, so the tests that earn their keep are
 * the transcription checks: a fat-fingered frequency here puts a user
 * on a band they may not transmit on, and nothing in the app would
 * catch it. Spot values are pinned against the reference client's table.
 */
class RadioPresetsTest {

    @Test
    fun spotValuesMatchTheReferenceTable() {
        val usa = RadioPresets.byName("USA/Canada")!!
        assertEquals(910.525, usa.frequencyMhz)
        assertEquals(62.5, usa.bandwidthKhz)
        assertEquals(7, usa.spreadingFactor)
        assertEquals(5, usa.codingRate)
        assertEquals(20, usa.txPowerDbm)

        val eu = RadioPresets.byName("EU/UK (Long Range)")!!
        assertEquals(869.525, eu.frequencyMhz)
        assertEquals(250.0, eu.bandwidthKhz)
        assertEquals(11, eu.spreadingFactor)
        // The EU ERP limit is the reason this differs from the US entries.
        assertEquals(14, eu.txPowerDbm)

        val au = RadioPresets.byName("Australia")!!
        assertEquals(915.8, au.frequencyMhz)
        assertEquals(250.0, au.bandwidthKhz)
        assertEquals(10, au.spreadingFactor)
    }

    @Test
    fun theWholeTableIsPresent() {
        assertEquals(47, RadioPresets.ALL.size)
        assertEquals(
            RadioPresets.ALL.size,
            RadioPresets.ALL.map { it.name }.distinct().size,
            "duplicate preset names",
        )
    }

    @Test
    fun everyPresetIsPhysicallyPlausible() {
        // A transcription slip usually lands outside these, which is the
        // point: SF and CR are protocol-bounded, and the frequencies are
        // all in the ISM bands MeshCore hardware supports.
        for (p in RadioPresets.ALL) {
            assertTrue(p.spreadingFactor in 5..12, "${p.name}: SF${p.spreadingFactor}")
            assertTrue(p.codingRate in 5..8, "${p.name}: CR4/${p.codingRate}")
            assertTrue(p.txPowerDbm in 1..30, "${p.name}: ${p.txPowerDbm} dBm")
            assertTrue(
                p.frequencyMhz in 430.0..435.0 || p.frequencyMhz in 863.0..930.0,
                "${p.name}: ${p.frequencyMhz} MHz is outside the supported ISM bands",
            )
            assertTrue(
                p.bandwidthKhz in setOf(7.8, 10.4, 15.6, 20.8, 31.25, 62.5, 125.0, 250.0, 500.0),
                "${p.name}: ${p.bandwidthKhz} kHz is not a LoRa bandwidth",
            )
        }
    }

    @Test
    fun unitConversionsAreExact() {
        val usa = RadioPresets.byName("USA/Canada")!!
        // kHz for frequency, Hz for bandwidth. This test used to assert
        // 910_525_000 — a number this codebase invented and then agreed
        // with itself about, in the builder, the parser, AND the fake
        // radio in the engine tests. The suite was green and the radio
        // rejected every preset. See MESHCORE_PROTOCOL §11.
        assertEquals(910_525L, usa.frequencyKhz)
        assertEquals(62_500L, usa.bandwidthHz)
        // 910.525 MHz, spelled out, so a future "fix" to either unit has
        // to argue with an actual frequency.
        assertEquals(910.525, usa.frequencyKhz / 1000.0)
        assertEquals(62.5, usa.bandwidthHz / 1000.0)

        // The awkward one: 62.5 kHz must not truncate to 62 kHz.
        for (p in RadioPresets.ALL) {
            assertTrue(p.frequencyKhz > 0, "${p.name} lost its frequency")
            // Every preset must land in the band the firmware accepts.
            // The old Hz values were all ~1000x over this ceiling, which
            // is the whole bug in one assertion.
            assertTrue(
                p.frequencyKhz in 300_000..2_500_000,
                "${p.name}: ${p.frequencyKhz} kHz is outside the firmware's 300-2500 MHz range",
            )
            assertTrue(p.bandwidthHz > 0, "${p.name} lost its bandwidth")
            assertEquals(
                (p.bandwidthKhz * 1000).toLong(),
                p.bandwidthHz,
                "${p.name} bandwidth rounded",
            )
        }
    }

    @Test
    fun radioCsvRoundTripsThroughTheCliParser() {
        // The CSV is what `set radio` takes, and CliReplies parses the
        // same shape back — so the two must agree exactly.
        for (p in RadioPresets.ALL) {
            val parsed = CliReplies.parseRadioCsv(p.toRadioCsv())
            assertTrue(parsed != null, "${p.name}: '${p.toRadioCsv()}' didn't parse")
            assertEquals(p.frequencyMhz, parsed!!.freqMhz, "${p.name} frequency")
            assertEquals(p.bandwidthKhz, parsed.bwKhz, "${p.name} bandwidth")
            assertEquals(p.spreadingFactor, parsed.sf, "${p.name} SF")
            assertEquals(p.codingRate, parsed.cr, "${p.name} CR")
        }
    }

    @Test
    fun lookupByNameIsForgivingAboutCaseAndSpace() {
        assertEquals("USA/Canada", RadioPresets.byName("usa/canada")?.name)
        assertEquals("USA/Canada", RadioPresets.byName("  USA/Canada  ")?.name)
        assertNull(RadioPresets.byName("Atlantis"))
        assertNull(RadioPresets.byName(null))
        assertNull(RadioPresets.byName(""))
    }

    @Test
    fun matchingFindsEveryPresetForALiveConfigAndNeverPicksOne() {
        val usa = RadioPresets.byName("USA/Canada")!!
        val hits = RadioPresets.matching(
            usa.frequencyKhz, usa.bandwidthHz, usa.spreadingFactor, usa.codingRate,
        )
        assertTrue(usa in hits)

        // The Russian city presets overlap: several share 868.731 MHz /
        // 62.5 kHz. Whatever matches, all of it comes back.
        val overlapping = RadioPresets.matching(868_731L, 62_500L, 8, 6)
        assertTrue(overlapping.size > 1, "expected overlapping presets, got ${overlapping.size}")

        assertEquals(emptyList(), RadioPresets.matching(1L, 1L, 7, 5))
    }

    @Test
    fun everyPresetLandsInAGroup() {
        for (p in RadioPresets.ALL) {
            val region = RadioPresets.region(p)
            assertTrue(region.isNotBlank(), "${p.name} has no region")
        }
        // "Other" is a fallback, not the common case.
        val other = RadioPresets.ALL.count { RadioPresets.region(it) == "Other" }
        assertTrue(other < RadioPresets.ALL.size / 2, "$other presets fell through to Other")
    }

    @Test
    fun theRegulatoryCaveatSaysWhatItMustSay() {
        val caveat = RadioPresets.REGULATORY_CAVEAT.lowercase()
        assertTrue("not legal advice" in caveat)
        assertTrue("regulated" in caveat)
    }
}
