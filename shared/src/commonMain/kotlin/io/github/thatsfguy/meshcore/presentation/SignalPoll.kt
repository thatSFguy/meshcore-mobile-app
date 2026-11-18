package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine

/**
 * What the Settings → Radio "Signal" section shows, and whether it should
 * keep asking the radio — fed one poll result at a time (issue #6).
 *
 * Lifted out of the composable so the decision to stop polling is tested
 * rather than only ever exercised against a radio that supports stats.
 *
 * The rules:
 * - **Unsupported before any reading** stops for good: firmware without
 *   CMD_GET_STATS refuses it every time, and asking every 2 s forever
 *   would only fill the command queue.
 * - **Unsupported after a reading** is NOT believed: the radio has already
 *   proved it supports the command, so an error now is transient (or an
 *   error frame meant for another command) and polling carries on.
 * - **Silence** keeps the last reading on screen and keeps asking; it only
 *   says "did not answer" when nothing has ever come back.
 */
class SignalPoll {

    sealed class View {
        data object Waiting : View()
        data object NoAnswer : View()
        data object Unsupported : View()
        data class Showing(val reading: MeshCoreEngine.Stats.Reading, val range: String?) : View()
    }

    var view: View = View.Waiting
        private set

    private val watch = SignalReadout.NoiseWatch()

    /**
     * The sentence to show instead of readings, or null while showing
     * them. With [rows] and [rangeHint], what a screen needs without
     * reaching into the reading's nested types (iOS can't name them
     * cheaply).
     */
    val statusLine: String?
        get() = when (view) {
            View.Waiting -> "Reading…"
            View.NoAnswer -> "The radio did not answer."
            View.Unsupported -> "This radio's firmware does not report signal statistics."
            is View.Showing -> null
        }

    val rows: List<RadioFacts.Fact>
        get() {
            val s = view as? View.Showing ?: return emptyList()
            val radio = s.reading.radio
            val packets = s.reading.packets
            return buildList {
                add(RadioFacts.Fact("Noise floor", SignalReadout.noiseFloor(radio.noiseFloorDbm)))
                add(RadioFacts.Fact("Last packet heard", SignalReadout.lastPacket(radio, packets)))
                SignalReadout.counters(packets)?.let { add(RadioFacts.Fact("Since boot", it)) }
            }
        }

    val rangeHint: String? get() = (view as? View.Showing)?.range

    /** Take one poll result. Returns true while the screen should keep polling. */
    fun onResult(result: MeshCoreEngine.Stats?): Boolean {
        val current = view
        when (result) {
            is MeshCoreEngine.Stats.Reading -> {
                watch.add(result.radio.noiseFloorDbm)
                view = View.Showing(result, watch.range())
            }
            MeshCoreEngine.Stats.Unsupported -> {
                if (current is View.Showing) return true
                view = View.Unsupported
                return false
            }
            null -> if (current !is View.Showing) view = View.NoAnswer
        }
        return true
    }
}
