package io.github.thatsfguy.meshcore.presentation

/**
 * Watching a repeater's noise floor: poll its status every few seconds
 * and keep a short window. Shared since iOS Phase 5 (2026-11-18); the
 * constants and readout lived in Android's RepeaterStatusPanel.
 */
object NoiseWatch {
    const val INTERVAL_MS: Long = 5_000
    const val SAMPLES: Int = 24

    const val IDLE_HINT = "Polls the node every 5s while this is on."

    /** [samples] with [reading] added, keeping the newest [SAMPLES]. */
    fun add(samples: List<Int>, reading: Int): List<Int> = (samples + reading).takeLast(SAMPLES)

    fun readout(samples: List<Int>): String? {
        if (samples.isEmpty()) return null
        return "Now ${samples.last()} dBm · min ${samples.min()} · max ${samples.max()} (${samples.size} samples)"
    }
}
