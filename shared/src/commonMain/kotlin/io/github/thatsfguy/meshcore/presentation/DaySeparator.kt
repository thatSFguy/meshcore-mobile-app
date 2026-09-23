package io.github.thatsfguy.meshcore.presentation

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/**
 * Which date heading a message sits under.
 *
 * The decision lives here and the wording lives on the platform, so the
 * rule is tested once and iOS inherits it, while the actual words stay
 * locale-aware — "Wednesday" and "17 September 2026" are the phone's
 * business, not this object's.
 */
enum class DayLabel {
    Today,
    Yesterday,

    /** Inside the last week: name the day, no numbers. */
    Weekday,

    /** Older than that: spell the date out. */
    FullDate,
}

/**
 * Date separators in a conversation, of the kind every chat app has.
 *
 * A bubble shows a time and nothing else, so a thread read cold gives
 * no way to tell a message from this morning from one three weeks back
 * — reported by a user, 2026-09-22.
 *
 * **Dated by the SENDER's timestamp**, because that is what the bubble
 * under the heading prints and what the thread is sorted by. Using
 * arrival time instead would produce a heading that disagrees with
 * every time beneath it, and would put a message under "Today" while
 * the bubble read 11:58 PM. The cost is that a peer with a wrong clock
 * files its message under a wrong date — but it is already *ordered* by
 * that same wrong clock, so the heading tells the truth about where the
 * message has been placed, which is the more useful honesty.
 */
object DaySeparator {

    /**
     * How recent still gets a weekday name instead of a date.
     *
     * Seven would make "Monday" ambiguous on a Monday — last Monday and
     * today share a name — so the window stops one short of a full week.
     */
    const val WEEKDAY_WINDOW_DAYS = 6

    /**
     * Epoch-second bounds that [Instant] can turn into a date at all.
     * A timestamp outside them is not a date this app should try to
     * render; it is a broken field, and it gets no heading rather than
     * a crash or a year 292277026596.
     */
    private const val MIN_EPOCH_SECONDS = -62_135_596_800L // year 1
    private const val MAX_EPOCH_SECONDS = 253_402_300_799L // year 9999

    /** The local calendar day a timestamp falls on, or null if unusable. */
    fun dayOf(epochSeconds: Long, zone: TimeZone): LocalDate? {
        if (epochSeconds < MIN_EPOCH_SECONDS || epochSeconds > MAX_EPOCH_SECONDS) return null
        return try {
            Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(zone).date
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Whether a heading belongs above the message at [index] of
     * [newestFirst].
     *
     * The list is newest-first and drawn in a reversed layout, so
     * `index + 1` is the message ABOVE this one on screen — the older
     * one. A heading appears when the day changes between them.
     *
     * The oldest loaded message always gets one: the top of the
     * scrollback is exactly where a reader has least idea what they are
     * looking at, and "Load older" above it does not say when.
     */
    fun startsNewDay(newestFirst: List<Long>, index: Int, zone: TimeZone): Boolean {
        if (index !in newestFirst.indices) return false
        val mine = dayOf(newestFirst[index], zone) ?: return false
        val older = newestFirst.getOrNull(index + 1) ?: return true
        val theirs = dayOf(older, zone) ?: return true
        return mine != theirs
    }

    /**
     * Which wording the heading should use. Null when the timestamp
     * cannot be read as a date.
     *
     * [nowEpochSeconds] is passed rather than read from a clock so the
     * rule is testable and so a thread renders consistently within one
     * frame — "Today" must not become "Yesterday" halfway down a list
     * because midnight passed mid-draw.
     */
    fun labelFor(epochSeconds: Long, nowEpochSeconds: Long, zone: TimeZone): DayLabel? {
        val day = dayOf(epochSeconds, zone) ?: return null
        val today = dayOf(nowEpochSeconds, zone) ?: return null
        val age = day.daysUntil(today)
        return when {
            age == 0 -> DayLabel.Today
            age == 1 -> DayLabel.Yesterday
            // A message stamped in the future — a sender's clock ahead
            // of ours — is not "today" and has no weekday worth naming.
            // Spell its date out so the oddity is visible rather than
            // disguised as something familiar.
            age < 0 -> DayLabel.FullDate
            age <= WEEKDAY_WINDOW_DAYS -> DayLabel.Weekday
            else -> DayLabel.FullDate
        }
    }
}
