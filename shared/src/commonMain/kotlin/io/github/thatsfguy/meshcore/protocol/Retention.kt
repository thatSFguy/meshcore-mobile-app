package io.github.thatsfguy.meshcore.protocol

/**
 * Message-retention policy (PARITY.md §3).
 *
 * This is a privacy feature before it is a storage feature: history
 * that isn't kept can't be read off a seized phone, can't be handed
 * over, and can't leak in a backup. The database is encrypted at rest,
 * but the strongest form of "encrypted" is "absent".
 *
 * The policy is pure so it can be tested without a database, and so the
 * same rules describe what the UI promises and what the pruner does —
 * two implementations of "older than 30 days" is exactly how a retention
 * setting quietly stops working.
 */
object Retention {

    /** How a thread's history is bounded. */
    enum class Mode {
        /** Keep everything until the user deletes it. */
        Forever,

        /** Keep messages newer than [Policy.value] days. */
        Days,

        /** Keep the newest [Policy.value] messages per thread. */
        Count,
    }

    /**
     * [value] is days for [Mode.Days] and messages for [Mode.Count];
     * ignored for [Mode.Forever].
     */
    data class Policy(val mode: Mode = Mode.Forever, val value: Int = 0) {

        val isBounded: Boolean get() = mode != Mode.Forever && effectiveValue > 0

        /**
         * [value] clamped to something sane. A zero or negative bound
         * would mean "delete everything, continuously" — which is a
         * legitimate wish but not one anybody expresses by typing 0 into
         * a retention box, so it is treated as unset instead.
         */
        val effectiveValue: Int
            get() = when (mode) {
                Mode.Forever -> 0
                Mode.Days -> value.coerceIn(0, MAX_DAYS)
                Mode.Count -> value.coerceIn(0, MAX_COUNT)
            }

        /**
         * Messages with a timestamp strictly older than this are due for
         * deletion. Null when the policy isn't time-based.
         */
        fun cutoffSeconds(nowSeconds: Long): Long? {
            if (mode != Mode.Days || effectiveValue <= 0) return null
            return nowSeconds - effectiveValue.toLong() * SECONDS_PER_DAY
        }

        /** Newest-N to keep per thread, or null when not count-based. */
        fun keepPerThread(): Int? =
            if (mode == Mode.Count && effectiveValue > 0) effectiveValue else null

        /** What the settings screen says this policy does. */
        fun label(): String = when {
            !isBounded -> "Keep everything"
            mode == Mode.Days && effectiveValue == 1 -> "Keep 1 day"
            mode == Mode.Days -> "Keep $effectiveValue days"
            effectiveValue == 1 -> "Keep the newest message"
            else -> "Keep the newest $effectiveValue messages"
        }

        /** Round-trip form for preferences: "days:30", "count:200", "forever". */
        fun encode(): String = when (mode) {
            Mode.Forever -> "forever"
            Mode.Days -> "days:$effectiveValue"
            Mode.Count -> "count:$effectiveValue"
        }
    }

    /** Parse [encode]; anything unrecognised means "keep everything". */
    fun decode(raw: String?): Policy {
        val text = raw?.trim()?.lowercase() ?: return Policy()
        if (text.isEmpty() || text == "forever") return Policy()
        val parts = text.split(':', limit = 2)
        val amount = parts.getOrNull(1)?.toIntOrNull() ?: return Policy()
        // An unparseable stored policy must fail SAFE — and "safe" here
        // is keeping the data, because the alternative is deleting the
        // user's history on the strength of a corrupted preference.
        return when (parts[0]) {
            "days" -> Policy(Mode.Days, amount)
            "count" -> Policy(Mode.Count, amount)
            else -> Policy()
        }
    }

    /** The choices the settings UI offers, in order. */
    val PRESETS: List<Policy> = listOf(
        Policy(Mode.Forever),
        Policy(Mode.Days, 1),
        Policy(Mode.Days, 7),
        Policy(Mode.Days, 30),
        Policy(Mode.Days, 90),
        Policy(Mode.Days, 365),
        Policy(Mode.Count, 100),
        Policy(Mode.Count, 500),
    )

    const val SECONDS_PER_DAY = 86_400L

    /** ~27 years; past this the bound is meaningless, not stricter. */
    const val MAX_DAYS = 10_000

    const val MAX_COUNT = 100_000
}
