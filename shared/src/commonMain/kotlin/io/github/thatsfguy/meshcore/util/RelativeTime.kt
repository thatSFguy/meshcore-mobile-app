package io.github.thatsfguy.meshcore.util

/**
 * "9 hours ago" — the at-a-glance half of a timestamp.
 *
 * Takes an ELAPSED duration rather than a timestamp on purpose. The two
 * callers measure from different clocks: the node list ages an advert
 * against the wall clock, while the heard-repeats list ages against the
 * engine's monotonic mark, because a wall clock that steps (and this
 * radio's clock is corrected by the app) would render something heard a
 * moment ago as hours old. Subtracting at the call site keeps the
 * wording in one place without forcing one clock on both.
 */
object RelativeTime {

    /** [elapsedSeconds] in the past, in words. Negative reads as now. */
    fun ago(elapsedSeconds: Long): String {
        val s = elapsedSeconds.coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            // "min" is the abbreviation and does not take an s; the
            // spelt-out units do. It read "1 hours ago" for the whole
            // hour after every advert, which is the most-looked-at hour.
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> plural(s / 3600, "hour")
            else -> plural(s / 86_400, "day")
        }
    }

    private fun plural(n: Long, unit: String): String =
        "$n $unit${if (n == 1L) "" else "s"} ago"

    /** [ago] for a duration measured in milliseconds. */
    fun agoMillis(elapsedMillis: Long): String = ago(elapsedMillis / 1000)
}
