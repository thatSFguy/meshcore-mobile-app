package io.github.thatsfguy.meshcore.presentation

/**
 * Threads whose notifications are muted, stored as one line of thread
 * keys (`dm|<key>` / `ch|<index>`). Android keeps two sets (channels and
 * contacts) in its prefs; iOS keeps this (2026-11-18). Muting silences
 * notifications only — unread counts still move.
 */
object MutedThreads {
    private const val SEP = ","

    fun decode(raw: String?): Set<String> =
        raw.orEmpty().split(SEP).map { it.trim() }.filter { it.startsWith("dm|") || it.startsWith("ch|") }.toSet()

    fun encode(keys: Set<String>): String = keys.sorted().joinToString(SEP)

    fun toggled(raw: String?, threadKey: String): String {
        val now = decode(raw)
        return encode(if (threadKey in now) now - threadKey else now + threadKey)
    }

    fun isMuted(raw: String?, threadKey: String): Boolean = threadKey in decode(raw)
}
