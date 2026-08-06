package io.github.thatsfguy.meshcore.protocol

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Laying a received message's route out on a map, including the parts
 * whose position nobody knows.
 *
 * [PathGeometry] answers "where is this hop?" and refuses to guess.
 * That is right, and it produces a map with holes in it: a route
 * through a repeater that has never advertised a position simply stops
 * and restarts, and the sender — very often a companion node with no
 * GPS — cannot be drawn at all, so the route has no beginning.
 *
 * This adds a drawing-only layer on top. Some nodes get an **inferred**
 * position so the shape of the route is visible, and the distinction
 * between what is known and what was placed for legibility is carried
 * in the data, not left to the renderer:
 *
 *  - [Certainty.Known] — the node advertised this position. Solid.
 *  - [Certainty.Inferred] — we know WHICH node this is, but not where.
 *    Drawn near where it must roughly have been, dotted and hollow,
 *    because the point is a drawing aid and not a claim.
 *  - Not placed at all — we do not know WHO the hop was (no contact
 *    matches, or several do). Never given a position, at any confidence.
 *    The span across it is **dashed**, which is what dashed already
 *    means on this app's map: "no route is being claimed here."
 *
 * That last line is why inferred positions are dotted rather than
 * dashed. The map's existing legend already spends "dashed" on *we
 * refuse to guess*; spending it again on *this is a guess* would leave
 * one visual carrying two opposite meanings.
 */
object PathSketch {

    data class Point(val latitude: Double, val longitude: Double)

    /** How much to trust a drawn position. */
    enum class Certainty { Known, Inferred }

    /** How to draw the run between two placed nodes. */
    enum class Style {
        /** Both ends known, and adjacent. */
        Solid,

        /** An end was placed for legibility rather than reported. */
        Dotted,

        /** Something in between could not be identified at all. */
        Dashed,
    }

    /** One entry in the chain, in order from sender to receiver. */
    data class Node(
        val label: String,
        val point: Point?,
        val certainty: Certainty?,
        /** Why there is no position, for the strip under the map. */
        val reason: String?,
        val isEndpoint: Boolean = false,
    ) {
        val isPlaced: Boolean get() = point != null
    }

    data class Segment(val from: Point, val to: Point, val style: Style)

    data class Sketch(val nodes: List<Node>, val segments: List<Segment>) {
        val known: Int get() = nodes.count { it.certainty == Certainty.Known }
        val inferred: Int get() = nodes.count { it.certainty == Certainty.Inferred }
        val unplaced: Int get() = nodes.count { !it.isPlaced }

        /** Nothing to show a map for. */
        val isEmpty: Boolean get() = nodes.none { it.isPlaced }

        /**
         * One line, and it has to be honest about the mix — a map with
         * three inferred pins on it looks exactly like a map with three
         * surveyed ones.
         */
        fun summary(): String = when {
            nodes.isEmpty() -> "No route was recorded for this message."
            isEmpty -> "None of the ${nodes.size} node(s) on this route can be placed."
            inferred == 0 && unplaced == 0 -> "All ${nodes.size} node(s) located."
            else -> buildString {
                append("$known of ${nodes.size} located")
                if (inferred > 0) append("; $inferred placed approximately")
                if (unplaced > 0) append("; $unplaced not placed")
                append(".")
            }
        }
    }

    /** A node the caller can describe: an endpoint, or a resolved hop. */
    data class Waypoint(
        val label: String,
        val latitude: Double?,
        val longitude: Double?,
        /** Set when the identity itself is unknown — never inferred. */
        val unidentifiedReason: String? = null,
        val isEndpoint: Boolean = false,
    )

    /**
     * Build the sketch for an ordered [chain] running sender → receiver.
     *
     * Inference rules, all deterministic — the same route must draw the
     * same way every time it is opened:
     *
     *  - A run of position-less nodes **between** two known ones is
     *    spread along the line between them. With one unknown that is
     *    the midpoint; with two, thirds; and so on.
     *  - A run at either **end** — the companion that sent it, or this
     *    phone receiving it — is stepped outward from the nearest known
     *    node, in the direction *away* from the rest of the route, so it
     *    does not land on top of the hop it came from.
     *  - Nodes whose identity is unknown are never placed. A run
     *    containing one is crossed by a dashed segment instead.
     */
    fun build(chain: List<Waypoint>): Sketch {
        if (chain.isEmpty()) return Sketch(emptyList(), emptyList())

        val nodes = chain.map { w ->
            val point = knownPoint(w)
            Node(
                label = w.label,
                point = point,
                certainty = if (point != null) Certainty.Known else null,
                reason = w.unidentifiedReason
                    ?: if (point == null) "position never advertised" else null,
                isEndpoint = w.isEndpoint,
            )
        }.toMutableList()

        val anchors = nodes.indices.filter { nodes[it].certainty == Certainty.Known }
        if (anchors.isEmpty()) return Sketch(nodes, emptyList())

        // Nodes we may place approximately: identity known, position not.
        fun inferable(i: Int) =
            nodes[i].point == null && chain[i].unidentifiedReason == null

        // --- interior runs, spread along the line between two anchors ---
        for (a in 0 until anchors.size - 1) {
            val from = anchors[a]
            val to = anchors[a + 1]
            val between = (from + 1 until to).toList()
            if (between.isEmpty()) continue
            val placeable = between.filter { inferable(it) }
            if (placeable.isEmpty()) continue
            val p0 = nodes[from].point!!
            val p1 = nodes[to].point!!
            // Spread over the whole span rather than bunching: with n
            // unknowns the fractions are 1/(n+1) … n/(n+1).
            placeable.forEachIndexed { k, index ->
                val t = (k + 1).toDouble() / (placeable.size + 1)
                nodes[index] = nodes[index].copy(
                    point = lerp(p0, p1, t),
                    certainty = Certainty.Inferred,
                    reason = "position never advertised",
                )
            }
        }

        // --- leading run (the sender), stepped away from the route -----
        placeEndRun(
            nodes = nodes,
            indices = (anchors.first() - 1 downTo 0).toList(),
            anchorIndex = anchors.first(),
            awayFrom = anchors.getOrNull(1)?.let { nodes[it].point },
            inferable = ::inferable,
        )

        // --- trailing run (this phone), same rule mirrored -------------
        placeEndRun(
            nodes = nodes,
            indices = (anchors.last() + 1 until nodes.size).toList(),
            anchorIndex = anchors.last(),
            awayFrom = anchors.getOrNull(anchors.size - 2)?.let { nodes[it].point },
            inferable = ::inferable,
        )

        return Sketch(nodes, segmentsFor(nodes))
    }

    // ------------------------------------------------------------------

    private fun knownPoint(w: Waypoint): Point? {
        if (w.unidentifiedReason != null) return null
        val lat = w.latitude ?: return null
        val lon = w.longitude ?: return null
        // All-zero is MeshCore's "unset", not a point in the Gulf of
        // Guinea — the same rule PathGeometry applies.
        if (lat == 0.0 && lon == 0.0) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Point(lat, lon)
    }

    /**
     * Place a run of unknown nodes hanging off one end of the route.
     *
     * [indices] runs outward from the anchor. Each step moves one
     * offset further, so two unknown tail nodes do not stack.
     */
    private fun placeEndRun(
        nodes: MutableList<Node>,
        indices: List<Int>,
        anchorIndex: Int,
        awayFrom: Point?,
        inferable: (Int) -> Boolean,
    ) {
        if (indices.isEmpty()) return
        val anchor = nodes[anchorIndex].point ?: return
        val direction = outwardDirection(anchor, awayFrom)
        val step = offsetFor(anchor, awayFrom)
        var placedSoFar = 0
        for (i in indices) {
            if (!inferable(i)) continue
            placedSoFar++
            val d = step * placedSoFar
            nodes[i] = nodes[i].copy(
                point = Point(
                    latitude = (anchor.latitude + direction.second * d).coerceIn(-90.0, 90.0),
                    longitude = normaliseLon(
                        anchor.longitude +
                            direction.first * d / lonScale(anchor.latitude),
                    ),
                ),
                certainty = Certainty.Inferred,
                reason = "position never advertised",
            )
        }
    }

    /**
     * A unit vector pointing away from the rest of the route.
     *
     * Falls back to due west when there is nothing to point away from
     * (a single-anchor route) or when the two anchors are co-sited —
     * co-located repeaters are ordinary, and "opposite direction" has
     * no meaning when the direction is zero. Deterministic either way:
     * the same route must not wander between openings.
     */
    private fun outwardDirection(anchor: Point, awayFrom: Point?): Pair<Double, Double> {
        val other = awayFrom ?: return -1.0 to 0.0
        val dx = (other.longitude - anchor.longitude) * lonScale(anchor.latitude)
        val dy = other.latitude - anchor.latitude
        val len = hypot(dx, dy)
        if (len < 1e-9) return -1.0 to 0.0
        return -dx / len to -dy / len
    }

    /** Offset in degrees of latitude: a fraction of the span, clamped. */
    private fun offsetFor(anchor: Point, awayFrom: Point?): Double {
        val other = awayFrom ?: return MIN_OFFSET_DEG * 4
        val dx = (other.longitude - anchor.longitude) * lonScale(anchor.latitude)
        val dy = other.latitude - anchor.latitude
        val span = hypot(dx, dy)
        return min(MAX_OFFSET_DEG, max(MIN_OFFSET_DEG, span * OFFSET_FRACTION))
    }

    private fun lerp(a: Point, b: Point, t: Double) = Point(
        latitude = a.latitude + (b.latitude - a.latitude) * t,
        longitude = a.longitude + (b.longitude - a.longitude) * t,
    )

    /** Longitude degrees shrink with latitude; keep offsets round. */
    private fun lonScale(latitude: Double): Double =
        max(0.05, cos(latitude * kotlin.math.PI / 180.0))

    private fun normaliseLon(lon: Double): Double {
        var l = lon
        while (l > 180.0) l -= 360.0
        while (l < -180.0) l += 360.0
        return l
    }

    /**
     * Segments between placed nodes.
     *
     * Adjacent placed nodes join directly; a run of never-placed nodes
     * between two placed ones is crossed by a single dashed segment,
     * which is this app's existing "no route is being claimed" line.
     */
    private fun segmentsFor(nodes: List<Node>): List<Segment> {
        val placed = nodes.indices.filter { nodes[it].isPlaced }
        val out = ArrayList<Segment>()
        for (k in 0 until placed.size - 1) {
            val i = placed[k]
            val j = placed[k + 1]
            val a = nodes[i]
            val b = nodes[j]
            val skipped = j - i > 1
            val style = when {
                skipped -> Style.Dashed
                a.certainty == Certainty.Inferred || b.certainty == Certainty.Inferred ->
                    Style.Dotted
                else -> Style.Solid
            }
            out += Segment(a.point!!, b.point!!, style)
        }
        return out
    }

    /** One line for the map, explaining both non-solid styles. */
    const val LEGEND: String =
        "Hollow pins on a dotted line are placed approximately — that node's position " +
            "isn't known. A dashed line crosses a hop we couldn't identify at all."

    private const val OFFSET_FRACTION = 0.35
    private const val MIN_OFFSET_DEG = 0.004
    private const val MAX_OFFSET_DEG = 0.25

    /** Distance in degrees, for tests and for fitting the map bounds. */
    fun separation(a: Point, b: Point): Double {
        val dx = (b.longitude - a.longitude) * lonScale(a.latitude)
        val dy = b.latitude - a.latitude
        return sqrt(dx * dx + dy * dy).let { abs(it) }
    }
}
