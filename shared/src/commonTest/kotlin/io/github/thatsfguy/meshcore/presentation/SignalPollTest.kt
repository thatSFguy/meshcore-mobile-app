package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.presentation.SignalPoll.View
import io.github.thatsfguy.meshcore.protocol.RadioStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalPollTest {

    private fun reading(noise: Int) =
        MeshCoreEngine.Stats.Reading(RadioStats.Radio(noise, -87, 6.5, 0, 0), null)

    @Test
    fun `a supporting radio is shown and polled`() {
        // The positive control: the stop rules below would all pass if the
        // poll never showed anything.
        val poll = SignalPoll()
        assertEquals(View.Waiting, poll.view)
        assertTrue(poll.onResult(reading(-112)))
        val shown = assertIs<View.Showing>(poll.view)
        assertEquals(-112, shown.reading.radio.noiseFloorDbm)
        assertNull(shown.range) // one reading is not a range
    }

    @Test
    fun `firmware without the command stops the polling`() {
        val poll = SignalPoll()
        assertFalse(poll.onResult(MeshCoreEngine.Stats.Unsupported))
        assertEquals(View.Unsupported, poll.view)
    }

    @Test
    fun `an error after a reading is not taken as unsupported`() {
        val poll = SignalPoll()
        poll.onResult(reading(-112))
        assertTrue(poll.onResult(MeshCoreEngine.Stats.Unsupported))
        assertIs<View.Showing>(poll.view)
    }

    @Test
    fun `silence before any reading says so and keeps asking`() {
        val poll = SignalPoll()
        assertTrue(poll.onResult(null))
        assertEquals(View.NoAnswer, poll.view)
        // And a later answer replaces it.
        poll.onResult(reading(-110))
        assertIs<View.Showing>(poll.view)
    }

    @Test
    fun `silence after a reading keeps the reading on screen`() {
        val poll = SignalPoll()
        poll.onResult(reading(-112))
        assertTrue(poll.onResult(null))
        assertEquals(-112, assertIs<View.Showing>(poll.view).reading.radio.noiseFloorDbm)
    }

    @Test
    fun `the noise range builds from successive readings`() {
        val poll = SignalPoll()
        poll.onResult(reading(-118))
        poll.onResult(null)
        poll.onResult(reading(0)) // not measured yet: left out of the range
        poll.onResult(reading(-104))
        assertEquals(
            "low -118 · high -104 dBm over 2 readings",
            assertIs<View.Showing>(poll.view).range,
        )
    }
}
