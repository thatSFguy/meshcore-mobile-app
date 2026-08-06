package io.github.thatsfguy.meshcore.util

import kotlin.math.abs
import kotlin.math.floor

/**
 * Fixed-point decimal formatting that works on every target.
 *
 * `String.format` and `"%.1f".format(x)` are JVM-only. They compile for
 * Android, they are invisible in review, and they fail the day someone
 * builds for Native — which is the whole reason presentation logic kept
 * having to live in `androidApp` instead of here.
 *
 * This exists so that moving a screen's logic into `shared` does not
 * quietly take a JVM dependency with it.
 */
fun fixed(value: Double, places: Int): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
    val p = places.coerceIn(0, 9)
    var factor = 1L
    repeat(p) { factor *= 10 }
    // floor(x + 0.5), i.e. HALF-UP — deliberately NOT kotlin.math.round,
    // which rounds ties to even. `String.format("%.1f", 1.25)` yields
    // "1.3" and round() yields "1.2", so using round() here would have
    // silently changed rendered values as the code moved platforms. The
    // whole promise of this helper is that it does not.
    val scaled = floor(abs(value) * factor + 0.5).toLong()
    val whole = scaled / factor
    val frac = scaled % factor
    // -0.04 at one place is "0.0", not "-0.0": a sign on a value that
    // rounded away to zero says the reading was negative when it wasn't
    // measurably anything.
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return if (p == 0) "$sign$whole" else "$sign$whole." + frac.toString().padStart(p, '0')
}

/** Lower-case hex, zero-padded to [width] digits — a Native-safe `%0Nx`. */
fun hexPadded(value: Int, width: Int): String =
    value.toString(16).padStart(width.coerceIn(1, 16), '0')
