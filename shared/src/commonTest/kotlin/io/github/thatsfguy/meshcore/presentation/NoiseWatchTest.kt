package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoiseWatchTest {
    @Test
    fun theWindowKeepsTheNewestAndReadsOut() {
        var s = emptyList<Int>()
        assertNull(NoiseWatch.readout(s))
        repeat(NoiseWatch.SAMPLES + 3) { s = NoiseWatch.add(s, -120 + it) }
        assertEquals(NoiseWatch.SAMPLES, s.size)
        assertEquals(-117, s.first())
        assertEquals("Now -94 dBm · min -117 · max -94 (24 samples)", NoiseWatch.readout(s))
    }
}
