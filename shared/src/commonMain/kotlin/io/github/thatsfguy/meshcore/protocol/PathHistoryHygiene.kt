package io.github.thatsfguy.meshcore.protocol

/**
 * Keeping the remembered-paths table honest and bounded (PARITY.md §13).
 *
 * Three separate problems, all of them visible on a real phone:
 *
 *  1. **Hop counts were byte counts.** Every write site stored
 *     `path.size` or `pathHex.length / 2`. On this mesh a hop is two
 *     bytes, so a 2-hop route was remembered — and displayed — as "4
 *     hop(s)". Same family as the `path_len` bug that turned 4 hops into
 *     34: a width-dependent number computed as if the width were 1.
 *
 *  2. **Junk rows.** Before the fix, a path was recorded as
 *     `copyOfRange(0, pathLen)` — with `pathLen` read as a byte length
 *     when it is a packed hop count. That wrote 64-byte, zero-padded
 *     rows into the table, which then offered themselves as routes.
 *
 *  3. **Nothing pruned.** The table only ever grew.
 *
 * The width can't be recovered from the stored hex alone, so rows
 * written before the fix are repaired against the *mesh's* width once
 * DEVICE_INFO is known, and anything that still doesn't make sense is
 * deleted rather than displayed with a guess.
 */
object PathHistoryHygiene {

    /**
     * Rows kept per contact. Paths accumulate as the mesh re-routes, and
     * an unbounded list is both a slow query and an unusable picker. The
     * cap keeps the ones with a delivery record — the only ones worth
     * re-trying.
     */
    const val MAX_PER_CONTACT = 20

    /** Sentinel stored in the `hashWidth` column for pre-repair rows. */
    const val WIDTH_UNKNOWN = 0

    /** Hops in a path of [byteLength] bytes at [hashWidth] bytes per hop. */
    fun hopCount(byteLength: Int, hashWidth: Int): Int {
        val width = hashWidth.coerceIn(1, 4)
        return byteLength / width
    }

    /**
     * Reasons a remembered path is not usable. Reported rather than
     * silently swallowed so the repair pass can be explained.
     */
    enum class Defect {
        /** Not valid hex, or an odd number of characters. */
        NotHex,

        /** Longer than the contact record can hold. */
        TooLong,

        /** Doesn't divide into whole hops at this mesh's width. */
        NotWholeHops,

        /**
         * Contains an all-zero hop — the signature of the zero-padded
         * rows the old `copyOfRange(0, pathLen)` wrote. A real node whose
         * key hash is all zeros is not a case worth preserving one of
         * these for.
         */
        ZeroHop,
    }

    /**
     * What's wrong with [pathHex] at [hashWidth], or null if nothing is.
     *
     * The empty string is the FLOOD route and is always valid — deleting
     * it as "empty" would drop the one entry that always works.
     */
    fun defect(pathHex: String, hashWidth: Int): Defect? {
        if (pathHex.isEmpty()) return null
        if (pathHex.length % 2 != 0 || pathHex.any { it.digitToIntOrNull(16) == null }) {
            return Defect.NotHex
        }
        val bytes = pathHex.length / 2
        if (bytes > Codes.MAX_PATH_SIZE) return Defect.TooLong

        val width = hashWidth.coerceIn(1, 4)
        if (bytes % width != 0) return Defect.NotWholeHops
        val hops = bytes / width
        if (hops > PathCodec.maxHopsFor(width)) return Defect.TooLong

        val chars = width * 2
        for (i in 0 until hops) {
            val hop = pathHex.substring(i * chars, (i + 1) * chars)
            if (hop.all { it == '0' }) return Defect.ZeroHop
        }
        return null
    }

    fun isUsable(pathHex: String, hashWidth: Int): Boolean = defect(pathHex, hashWidth) == null

    /** Wording for the diagnostics log; the repair pass is otherwise silent. */
    fun explain(defect: Defect): String = when (defect) {
        Defect.NotHex -> "not a hex path"
        Defect.TooLong -> "longer than a contact record can hold"
        Defect.NotWholeHops -> "does not divide into whole hops at this mesh's hop width"
        Defect.ZeroHop -> "contains an all-zero hop (zero-padding from an older build)"
    }
}
