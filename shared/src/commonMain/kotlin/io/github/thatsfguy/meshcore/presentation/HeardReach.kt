package io.github.thatsfguy.meshcore.presentation

/**
 * How far away a heard-but-not-added node is, for its row in Nodes.
 *
 * The hop count is the fewest any of its adverts arrived with, so it is
 * the nearest the node has been. A signal is shown only beside "direct":
 * a relayed copy's SNR is the last repeater's signal at this radio, which
 * says nothing about the node, and printed next to it would read as if it
 * did. For the same reason the direct figure is the one measured on a
 * direct copy, never the latest copy of any kind.
 */
object HeardReach {
    /**
     * "direct · 9.5 dB", "direct", "1 hop", "3 hops", or null when no
     * hop count was ever recorded for the node.
     */
    fun of(minHops: Int?, directSnr: Double?): String? = when {
        minHops == null || minHops < 0 -> null
        minHops == 0 -> directSnr?.let { "direct · ${formatSnr(it)}" } ?: "direct"
        minHops == 1 -> "1 hop"
        else -> "$minHops hops"
    }
}
