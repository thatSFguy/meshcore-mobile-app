package io.github.thatsfguy.meshcore.protocol

/**
 * Turning a mesh path into something drawable (PARITY.md §9,
 * `TracePathMapScreen` / `ViewPathScreen`).
 *
 * A MeshCore path is a list of truncated hop hashes — 1 to 4 bytes each.
 * Drawing it on a map means answering "where is this hop?", and that
 * question has three honest answers, all of which the UI has to be able
 * to show:
 *
 *  - **Here.** Exactly one known contact matches the hash AND has a
 *    position. Plot it.
 *  - **Somewhere.** A contact matches but has never advertised a
 *    position, or several contacts match the hash (a 1-byte hop is 256
 *    buckets — collisions are ordinary, not exotic). Do NOT plot: a
 *    line drawn through a guessed hop is a map that lies.
 *  - **Unknown.** No contact matches at all.
 *
 * A route drawn with gaps is useful. A route drawn by picking one of
 * several candidates is worse than no route, because it looks the same
 * as a correct one.
 */
object PathGeometry {

    /** A contact that could anchor a hop. */
    data class PositionedContact(
        val keyHex: String,
        val name: String,
        val latitude: Double?,
        val longitude: Double?,
    ) {
        val hasPosition: Boolean
            get() {
                val lat = latitude ?: return false
                val lon = longitude ?: return false
                // The all-zero position is MeshCore's "unset", not a
                // point in the Gulf of Guinea.
                return (lat != 0.0 || lon != 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0
            }
    }

    /** Why a hop could not be drawn. */
    enum class Gap { NoMatch, Ambiguous, NoPosition }

    /** One hop, plotted or explained. */
    data class HopPoint(
        val hashHex: String,
        val name: String?,
        val latitude: Double?,
        val longitude: Double?,
        val gap: Gap?,
    ) {
        val isPlotted: Boolean get() = gap == null && latitude != null && longitude != null
    }

    data class Plot(val hops: List<HopPoint>) {
        val plotted: List<HopPoint> get() = hops.filter { it.isPlotted }
        val gaps: Int get() = hops.count { !it.isPlotted }

        /** True when a line through [plotted] would be misleadingly incomplete. */
        val hasGaps: Boolean get() = gaps > 0

        /** One sentence for the UI; the honest version, not the tidy one. */
        fun summary(): String = when {
            hops.isEmpty() -> "No path to draw — this contact is reached by flooding."
            !hasGaps -> "All ${hops.size} hop(s) located."
            plotted.isEmpty() ->
                "None of the ${hops.size} hop(s) could be located, so there is nothing to draw."
            else -> "${plotted.size} of ${hops.size} hop(s) located; the rest are drawn as gaps."
        }
    }

    /** Resolve [path] into plottable points, hop by hop. */
    fun plot(
        path: ByteArray,
        hashWidth: Int,
        contacts: List<PositionedContact>,
    ): Plot {
        val width = hashWidth.coerceIn(1, 4)
        if (path.isEmpty()) return Plot(emptyList())
        val hops = PathCodec.resolveHops(path, width, contacts.associate { it.keyHex to it.name })

        return Plot(
            hops.map { hop ->
                val matches = contacts.filter {
                    it.keyHex.startsWith(hop.hashHex, ignoreCase = true)
                }
                when {
                    matches.isEmpty() ->
                        HopPoint(hop.hashHex, null, null, null, Gap.NoMatch)

                    matches.size > 1 -> {
                        // Two contacts share this hash. Naming one would
                        // be a guess; drawing one would be a guess on a
                        // map, which is worse (PARITY §12).
                        HopPoint(hop.hashHex, null, null, null, Gap.Ambiguous)
                    }

                    !matches[0].hasPosition ->
                        HopPoint(hop.hashHex, matches[0].name, null, null, Gap.NoPosition)

                    else -> HopPoint(
                        hop.hashHex,
                        matches[0].name,
                        matches[0].latitude,
                        matches[0].longitude,
                        null,
                    )
                }
            },
        )
    }

    /** Human wording for a gap, for a legend or a tooltip. */
    fun gapReason(gap: Gap): String = when (gap) {
        Gap.NoMatch -> "no contact matches this hop"
        Gap.Ambiguous -> "several contacts match this hop hash"
        Gap.NoPosition -> "this node has never advertised a position"
    }
}
