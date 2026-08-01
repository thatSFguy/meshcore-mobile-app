package io.github.thatsfguy.meshcore.android.storage

import org.json.JSONObject

/**
 * emoji -> count, stored on a message row as a small JSON object.
 *
 * The input is derived from mesh traffic, so decoding is total: a
 * malformed or hostile blob yields an empty map rather than an
 * exception on the render path.
 */
object ReactionCounts {

    fun decode(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            buildMap {
                for (key in obj.keys()) {
                    val count = obj.optInt(key, 0)
                    if (count > 0) put(key, count)
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Null when empty, so "no reactions" stays a NULL column. */
    fun encode(counts: Map<String, Int>): String? {
        val kept = counts.filterValues { it > 0 }
        if (kept.isEmpty()) return null
        return JSONObject(kept.mapValues { it.value as Any }).toString()
    }
}
