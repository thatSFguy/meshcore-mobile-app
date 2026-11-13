package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.RadioStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignalReadoutTest {

    private fun radio(noise: Int = -112, rssi: Int = -87, snr: Double = 6.5) =
        RadioStats.Radio(noise, rssi, snr, 0, 0)

    private fun packets(received: Long, errors: Long = 0) =
        RadioStats.Packets(received, 5, 0, 0, 0, 0, errors)

    @Test
    fun `a measured noise floor reads as itself`() {
        assertEquals("-112 dBm", SignalReadout.noiseFloor(-112))
    }

    @Test
    fun `zero is the firmware not having measured yet rather than 0 dBm`() {
        // RadioLibWrappers.cpp starts _noise_floor at 0 and resets it there.
        assertEquals("Measuring…", SignalReadout.noiseFloor(0))
    }

    @Test
    fun `the firmware's clamp is not reported as a measurement`() {
        assertEquals("-120 dBm or lower", SignalReadout.noiseFloor(-120))
        assertEquals("-120 dBm or lower", SignalReadout.noiseFloor(-125))
    }

    @Test
    fun `the last packet reads as rssi and snr once something has arrived`() {
        assertEquals("-87 dBm · 6.5 dB", SignalReadout.lastPacket(radio(), packets(received = 12)))
    }

    @Test
    fun `nothing received is said rather than shown as zeros`() {
        // With counters: zero received is decisive even if the radio's
        // fields hold leftovers.
        assertEquals(
            "Nothing received since the radio started",
            SignalReadout.lastPacket(radio(), packets(received = 0)),
        )
        // Without counters: the untouched all-zero state.
        assertEquals(
            "Nothing received since the radio started",
            SignalReadout.lastPacket(radio(rssi = 0, snr = 0.0), null),
        )
    }

    @Test
    fun `counters say errors in the singular and plural`() {
        assertEquals("12 received · 5 sent · 1 receive error", SignalReadout.counters(packets(12, 1)))
        assertEquals("12 received · 5 sent · 3 receive errors", SignalReadout.counters(packets(12, 3)))
        assertNull(SignalReadout.counters(null))
    }

    @Test
    fun `the noise watch ignores unmeasured samples and needs two readings`() {
        val w = SignalReadout.NoiseWatch()
        w.add(0)
        w.add(-112)
        assertNull(w.range())
        w.add(0)
        w.add(-104)
        assertEquals("low -112 · high -104 dBm over 2 readings", w.range())
    }

    @Test
    fun `the noise watch keeps only the recent window`() {
        val w = SignalReadout.NoiseWatch()
        w.add(-90) // old spike, will fall out
        repeat(SignalReadout.NoiseWatch.MAX_SAMPLES) { w.add(-115) }
        assertEquals("low -115 · high -115 dBm over 60 readings", w.range())
    }
}
