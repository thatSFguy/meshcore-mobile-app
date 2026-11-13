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
