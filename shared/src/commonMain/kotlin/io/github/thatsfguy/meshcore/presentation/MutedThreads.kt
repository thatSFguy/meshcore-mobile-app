package io.github.thatsfguy.meshcore.presentation

/**
 * A set of thread keys (`dm|<key>` / `ch|<index>`) stored as one line —
 * what muted and pinned threads both are on iOS (2026-11-18).
 */
object ThreadKeys {
    private const val SEP = ","

    fun decode(raw: String?): Set<String> =
        raw.orEmpty().split(SEP).map { it.trim() }.filter { it.startsWith("dm|") || it.startsWith("ch|") }.toSet()

    fun encode(keys: Set<String>): String = keys.sorted().joinToString(SEP)

    fun toggled(raw: String?, threadKey: String): String {
        val now = decode(raw)
        return encode(if (threadKey in now) now - threadKey else now + threadKey)
    }

    fun contains(raw: String?, threadKey: String): Boolean = threadKey in decode(raw)
}

/**
 * Threads whose notifications are muted. Android keeps two sets (channels
 * and contacts) in its prefs; iOS keeps this. Muting silences
 * notifications only — unread counts still move.
 */
object MutedThreads {
    fun decode(raw: String?): Set<String> = ThreadKeys.decode(raw)
    fun encode(keys: Set<String>): String = ThreadKeys.encode(keys)
    fun toggled(raw: String?, threadKey: String): String = ThreadKeys.toggled(raw, threadKey)
    fun isMuted(raw: String?, threadKey: String): Boolean = ThreadKeys.contains(raw, threadKey)
}

/** Chats search (Android's): a thread matches on its title or its last message. */
object ChatSearch {
    fun matches(title: String, preview: String?, query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || title.contains(q, ignoreCase = true) || preview.orEmpty().contains(q, ignoreCase = true)
    }
}
