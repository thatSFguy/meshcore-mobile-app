package io.github.thatsfguy.meshcore.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance in metres.
 *
 * In `shared` rather than beside the one screen that first needed it,
 * for the reason [isHexString] gives: the second copy is where the
 * versions start to differ. It is also the only form that compiles for
 * Native — the original used `Math.toRadians`, which is `java.lang`
 * wearing ordinary-Kotlin clothes, exactly the mistake
 * `SharedIsPlatformNeutralTest` was written to catch.
 */
fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = (lat2 - lat1).toRadians()
    val dLon = (lon2 - lon1).toRadians()
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

/**
 * True for a coordinate pair worth doing arithmetic with.
 *
 * A node that has never had a position advertises 0, 0 — a real place
 * in the Gulf of Guinea, about 6 000 km from anywhere this app is used.
 * Treating it as a location turns "no position" into "very far away",
 * which is the wrong answer everywhere it matters.
 */
fun isPlausiblePosition(lat: Double?, lon: Double?): Boolean {
    if (lat == null || lon == null) return false
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
    return abs(lat) > 1e-6 || abs(lon) > 1e-6
}

/**
 * The middle of the nodes we can actually place, or null if there are none.
 *
 * A node advertising 0, 0 is not in the Gulf of Guinea — it is a node
 * with no fix, sitting wherever the repeaters that heard it are, which
 * for a LoRa mesh is tens of kilometres across and not six thousand.
 * [isPlausiblePosition] is the half of that which refuses the bad
 * answer; this is the half that supplies a usable one when something
 * has to start *somewhere* — a map with no saved camera, a position
 * picker opened on a radio that has never had GPS.
 *
 * It is deliberately NOT a position for any individual node. Nothing
 * here is written to a contact, sent in an advert, or measured against:
 * it is where to point a camera, and the caller says so at the call
 * site. Guessing a node's own coordinates is [PathSketch]'s job, where
 * the guess is carried as `Inferred` and drawn as one.
 *
 * Longitude is averaged as a direction rather than as a number, so a
 * mesh straddling the antimeridian — or one hostile advert claiming to
 * — cannot fold two edges of the world into a centre near Africa.
 */
fun meshCentre(positions: List<Pair<Double, Double>>): Pair<Double, Double>? {
    val placed = positions.filter { isPlausiblePosition(it.first, it.second) }
    if (placed.isEmpty()) return null
    var x = 0.0
    var y = 0.0
    var lat = 0.0
    for ((la, lo) in placed) {
        lat += la
        x += cos(lo.toRadians())
        y += sin(lo.toRadians())
    }
    // Antipodal points cancel to the origin, where the direction is
    // undefined. Fall back to the first placed node rather than to an
    // atan2(0, 0) that silently reads as 0deg.
    val lon = if (abs(x) < 1e-12 && abs(y) < 1e-12) {
        placed[0].second
    } else {
        atan2(y, x) * 180.0 / PI
    }
    return (lat / placed.size) to lon
}

private fun Double.toRadians(): Double = this * PI / 180.0
