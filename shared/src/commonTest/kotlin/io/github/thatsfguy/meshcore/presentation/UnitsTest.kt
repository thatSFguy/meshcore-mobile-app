package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Metric and US customary rendering.
 *
 * The values here are checked against the definitions rather than
 * against this code's own arithmetic: a mile is exactly 1609.344 m and
 * a foot exactly 0.3048 m by international agreement (1959), and
 * 100 °C is the steam point. Testing a converter against numbers it
 * produced itself is the same assumption twice.
 */
class UnitsTest {

    // ---- distance --------------------------------------------------------

    @Test
    fun metricUsesMetresBelowAKilometreAndKilometresAbove() {
        assertEquals("0 m", Units.distance(0.0, UnitSystem.Metric))
        assertEquals("850 m", Units.distance(850.0, UnitSystem.Metric))
        assertEquals("999 m", Units.distance(999.0, UnitSystem.Metric))
        assertEquals("1.0 km", Units.distance(1_000.0, UnitSystem.Metric))
        assertEquals("12.3 km", Units.distance(12_345.0, UnitSystem.Metric))
    }

    @Test
    fun imperialUsesFeetBelowAMileAndMilesAbove() {
        // 1609.344 m is one mile exactly, so a hair under it is still feet.
        assertEquals("5280 ft", Units.distance(1609.343, UnitSystem.Imperial))
        assertEquals("1.0 mi", Units.distance(1609.344, UnitSystem.Imperial))
        // 1 m = 3.280839895 ft
        assertEquals("328 ft", Units.distance(100.0, UnitSystem.Imperial))
    }

    @Test
    fun aHundredMilesOrKilometresDropsTheFraction() {
        // A tenth of a km is noise on a link this long and costs a digit
        // the row has not got.
        assertEquals("150 km", Units.distance(150_000.0, UnitSystem.Metric))
        assertEquals("99.9 km", Units.distance(99_900.0, UnitSystem.Metric))
        assertEquals("150 mi", Units.distance(150 * Units.METRES_PER_MILE, UnitSystem.Imperial))
        assertEquals("99.0 mi", Units.distance(99 * Units.METRES_PER_MILE, UnitSystem.Imperial))
    }

    @Test
    fun theTwoSystemsSwitchUnitAtTheSamePlaceInTheirOwnTerms() {
        // Mirrored on purpose: fine unit right up to 1 km / 1 mile.
        assertTrue(Units.distance(999.0, UnitSystem.Metric).endsWith(" m"))
        assertTrue(Units.distance(1_001.0, UnitSystem.Metric).endsWith(" km"))
        assertTrue(Units.distance(1_609.0, UnitSystem.Imperial).endsWith(" ft"))
        assertTrue(Units.distance(1_610.0, UnitSystem.Imperial).endsWith(" mi"))
    }

    @Test
    fun aRealMeshDistanceReadsTheWayAnOperatorWouldSayIt() {
        // 13 Mile Rd to downtown Grand Rapids, roughly: 21 km.
        assertEquals("21.0 km", Units.distance(21_000.0, UnitSystem.Metric))
        assertEquals("13.0 mi", Units.distance(21_001.5, UnitSystem.Imperial))
    }

    // ---- temperature -----------------------------------------------------

    @Test
    fun celsiusConvertsAtTheFixedPoints() {
        assertEquals(32.0, Units.temperatureValue(0.0, UnitSystem.Imperial))
        assertEquals(212.0, Units.temperatureValue(100.0, UnitSystem.Imperial))
        // -40 is the one place the two scales agree.
        assertEquals(-40.0, Units.temperatureValue(-40.0, UnitSystem.Imperial))
    }

    @Test
    fun metricLeavesATemperatureExactlyAsItArrived() {
        assertEquals(21.5, Units.temperatureValue(21.5, UnitSystem.Metric))
    }

    // ---- altitude --------------------------------------------------------

    @Test
    fun altitudeConvertsByTheDefinedFoot() {
        // A foot is exactly 0.3048 m, so 1000 m is 3280.8399 ft.
        assertEquals(3280.839895013123, Units.altitudeValue(1_000.0, UnitSystem.Imperial))
        assertEquals(1_000.0, Units.altitudeValue(1_000.0, UnitSystem.Metric))
    }

    // ---- telemetry readings ---------------------------------------------

    @Test
    fun aCelsiusReadingBecomesFahrenheitWithItsUnit() {
        assertEquals(68.0 to "°F", Units.reading(20.0, "°C", UnitSystem.Imperial))
        assertEquals(20.0 to "°C", Units.reading(20.0, "°C", UnitSystem.Metric))
    }

    @Test
    fun aMetreReadingBecomesFeetWithItsUnit() {
        assertEquals(328.0839895013123 to "ft", Units.reading(100.0, "m", UnitSystem.Imperial))
        assertEquals(100.0 to "m", Units.reading(100.0, "m", UnitSystem.Metric))
    }

    @Test
    fun everythingElsePassesThroughUntouched() {
        // THE GUARD THAT MATTERS. A sensor type this app has never seen
        // must not be converted on a guess, and the radio figures must
        // never move at all — a frequency in "MHz" that quietly became
        // something else would be a genuinely dangerous bug.
        for (unit in listOf("hPa", "lux", "V", "A", "W", "g", "%", "°", "dB", "dBm", "MHz", "")) {
            assertEquals(
                42.0 to unit,
                Units.reading(42.0, unit, UnitSystem.Imperial),
                "$unit must not be converted",
            )
        }
    }

    @Test
    fun theUnitStringIsMatchedExactlyAndNotByPrefix() {
        // "m" is altitude; "mi", "ms" and "mV" are not, and a prefix
        // match would have converted all of them.
        for (unit in listOf("mi", "ms", "mV", "mm", "M")) {
            assertEquals(5.0 to unit, Units.reading(5.0, unit, UnitSystem.Imperial))
        }
    }

    // ---- which system ----------------------------------------------------

    @Test
    fun theThreeImperialCountriesGetImperialAndEveryoneElseMetric() {
        for (c in listOf("US", "LR", "MM", "us", " us ")) {
            assertEquals(UnitSystem.Imperial, UnitSystem.forCountry(c), c)
        }
        for (c in listOf("GB", "DE", "CA", "AU", "JP")) {
            assertEquals(UnitSystem.Metric, UnitSystem.forCountry(c), c)
        }
    }

    @Test
    fun anUnknownOrMissingCountryFallsToMetric() {
        assertEquals(UnitSystem.Metric, UnitSystem.forCountry(null))
        assertEquals(UnitSystem.Metric, UnitSystem.forCountry(""))
        assertEquals(UnitSystem.Metric, UnitSystem.forCountry("ZZ"))
    }

    @Test
    fun anExplicitChoiceBeatsTheDeviceCountry() {
        assertEquals(UnitSystem.Metric, UnitSystem.resolve("metric", "US"))
        assertEquals(UnitSystem.Imperial, UnitSystem.resolve("imperial", "GB"))
    }

    @Test
    fun followSystemDefersToTheDeviceEveryTimeItIsRead() {
        // Stored unresolved on purpose: a user who moves, or changes
        // their phone's region, gets the new answer without touching
        // the setting.
        assertEquals(UnitSystem.Imperial, UnitSystem.resolve("system", "US"))
        assertEquals(UnitSystem.Metric, UnitSystem.resolve("system", "GB"))
    }

    @Test
    fun anUnrecognisedStoredValueFollowsTheDeviceRatherThanPickingASide() {
        // A preference written by a newer build and restored into an
        // older one, or a corrupted string.
        assertEquals(UnitSystem.Imperial, UnitSystem.resolve("nautical", "US"))
        assertEquals(UnitSystem.Metric, UnitSystem.resolve(null, "DE"))
    }

    @Test
    fun theSettingsLineNamesTheUnitsInForce() {
        assertTrue(Units.label(UnitSystem.Imperial).contains("miles"))
        assertTrue(Units.label(UnitSystem.Metric).contains("kilometres"))
    }
}
