package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.protocol.RadioPresets
import io.github.thatsfguy.meshcore.protocol.ShareUri
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioRetuneTest {

    private class FakeRadio(
        var params: Boolean = true,
        var power: Boolean = true,
        var width: Boolean = true,
        var scope: Boolean = true,
    ) : RadioRetune.Radio {
        val calls = mutableListOf<String>()
        override suspend fun setRadioParams(freqKhz: Long, bwHz: Long, sf: Int, cr: Int): Boolean {
            calls += "params $freqKhz $bwHz $sf $cr"; return params
        }
        override suspend fun setTxPower(dbm: Int): Boolean { calls += "power $dbm"; return power }
        override suspend fun setPathHashMode(mode: Int): Boolean { calls += "width $mode"; return width }
        override suspend fun setFloodScope(region: String?): Boolean { calls += "scope $region"; return scope }
    }

    private val usa = RadioPresets.byName("Australia (Narrow)")!!

    private fun code(region: String? = "bayarea", tree: List<String> = emptyList()) = ShareUri.Decoded.RadioConfig(
        name = "GR Mesh", frequencyKhz = 910_525, bandwidthHz = 62_500, spreadingFactor = 7, codingRate = 5,
        pathHashMode = 1, region = region, regionTree = tree,
    )

    @Test
    fun aPresetSetsTheBandBeforeThePower() = runTest {
        val r = FakeRadio()
        val out = RadioRetune(r).applyPreset(usa)
        assertEquals(listOf("params 916575 62500 7 5", "power 20"), r.calls)
        assertEquals(RadioRetune.Outcome(true, "Applied Australia (Narrow)"), out)
    }

    @Test
    fun aRefusedBandNeverTouchesThePower() = runTest {
        val r = FakeRadio(params = false)
        val out = RadioRetune(r).applyPreset(usa)
        assertEquals(1, r.calls.size)
        assertFalse(out.ok)
    }

    @Test
    fun aRefusedPowerIsSaidOutLoud() = runTest {
        val out = RadioRetune(FakeRadio(power = false)).applyPreset(usa)
        assertTrue(out.ok)
        assertTrue("TX power stayed unchanged" in out.message)
    }

    @Test
    fun aThrowingRadioIsARefusalNotACrash() = runTest {
        val r = object : RadioRetune.Radio {
            override suspend fun setRadioParams(freqKhz: Long, bwHz: Long, sf: Int, cr: Int): Boolean = error("link gone")
            override suspend fun setTxPower(dbm: Int) = true
            override suspend fun setPathHashMode(mode: Int) = true
            override suspend fun setFloodScope(region: String?) = true
        }
        assertFalse(RadioRetune(r).applyPreset(usa).ok)
    }

    @Test
    fun aScannedCodeAppliesParamsWidthThenTheTickedRegionAndNeverPower() = runTest {
        val r = FakeRadio()
        val out = RadioRetune(r).applyScanned(code(), pickedRegion = "bayarea")
        assertEquals(listOf("params 910525 62500 7 5", "width 1", "scope bayarea"), r.calls)
        assertEquals("Applied GR Mesh · region #bayarea", out.message)
    }

    @Test
    fun anUntickedRegionIsNotSet() = runTest {
        val r = FakeRadio()
        RadioRetune(r).applyScanned(code(), pickedRegion = null)
        assertTrue(r.calls.none { it.startsWith("scope") })
    }

    @Test
    fun aPickedRegionTheCodeNeverOfferedIsNotSet() = runTest {
        // The pick is applied to a radio, so only the code's own choices count.
        val r = FakeRadio()
        RadioRetune(r).applyScanned(code(), pickedRegion = "elsewhere")
        assertTrue(r.calls.none { it.startsWith("scope") })
    }

    @Test
    fun aRefusedScannedCodeIsNotReportedAsApplied() = runTest {
        // The defect this class fixes: Android ignored this result.
        val r = FakeRadio(params = false)
        val out = RadioRetune(r).applyScanned(code(), pickedRegion = "bayarea")
        assertFalse(out.ok)
        assertFalse(out.message.startsWith("Applied"))
        assertEquals(1, r.calls.size)
    }

    @Test
    fun aRefusedWidthOrRegionIsNamed() = runTest {
        val w = RadioRetune(FakeRadio(width = false)).applyScanned(code(region = null), null)
        assertTrue("hop-hash width" in w.message)
        val s = RadioRetune(FakeRadio(scope = false)).applyScanned(code(), "bayarea")
        assertTrue(s.message.endsWith("but the radio refused region #bayarea"))
    }
}
