package io.github.thatsfguy.meshcore.util

import kotlin.math.PI
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
    return kotlin.math.abs(lat) > 1e-6 || kotlin.math.abs(lon) > 1e-6
}

private fun Double.toRadians(): Double = this * PI / 180.0
