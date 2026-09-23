package io.github.thatsfguy.meshcore.presentation

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Date headings in a conversation.
 *
 * A fixed zone is used throughout rather than the system default, so
 * these assert the same thing on a CI runner in UTC and on a phone in
 * Michigan. The boundary cases — midnight, a day with one message, the
 * oldest message loaded — are where this either works or quietly
 * doesn't.
 */
class DaySeparatorTest {

    // UTC-5, no DST games unless a test asks for them.
    private val ny = TimeZone.of("America/New_York")
    private val utc = TimeZone.UTC

    /** 2026-09-22 12:00:00 UTC, a Tuesday. */
    private val noon = 1_790_078_400L
    private val day = 86_400L

    // ---- which day a stamp falls on --------------------------------------

    @Test
    fun theDayIsTheLocalOneNotUtc() {
        // 01:30 UTC on the 22nd is still the 21st in New York. A heading
        // computed in UTC would file it under the wrong day and disagree
        // with the time printed on the bubble.
        val lateNight = 1_790_040_600L // 2026-09-22 01:30 UTC
        assertEquals(22, DaySeparator.dayOf(lateNight, utc)!!.dayOfMonth)
        assertEquals(21, DaySeparator.dayOf(lateNight, ny)!!.dayOfMonth)
    }

    @Test
    fun anUnusableTimestampHasNoDay() {
        // A sender's clock can report anything. Neither a crash nor a
        // heading reading year 292277026596.
        assertNull(DaySeparator.dayOf(Long.MAX_VALUE, utc))
        assertNull(DaySeparator.dayOf(Long.MIN_VALUE, utc))
    }

    @Test
    fun epochZeroIsAReadableDateNotAnError() {
        // An unset radio clock reports 1970, and that IS the claim the
        // message carries. Showing it is how the reader sees something
        // is wrong with that node.
        assertEquals(1970, DaySeparator.dayOf(0, utc)!!.year)
    }

    // ---- where a heading goes --------------------------------------------

    @Test
    fun aHeadingAppearsWhereTheDayChanges() {
        // Newest-first, as the list is ordered: today, today, yesterday.
        val stamps = listOf(noon + 3600, noon, noon - day)
        assertFalse(DaySeparator.startsNewDay(stamps, 0, utc), "same day as the one below it")
        assertTrue(DaySeparator.startsNewDay(stamps, 1, utc), "oldest of its day")
        assertTrue(DaySeparator.startsNewDay(stamps, 2, utc), "oldest loaded")
    }

    @Test
    fun theOldestLoadedMessageAlwaysGetsOne() {
        // THE POSITIVE CONTROL for the top of the scrollback: without
        // this the first screen of a freshly opened thread can carry no
        // date at all, which is the exact complaint being fixed.
        val stamps = listOf(noon, noon - 60, noon - 120)
        assertTrue(DaySeparator.startsNewDay(stamps, 2, utc))
    }

    @Test
    fun aSingleMessageGetsAHeading() {
        assertTrue(DaySeparator.startsNewDay(listOf(noon), 0, utc))
    }

    @Test
    fun aMidnightBoundaryIsADayChangeEvenSecondsApart() {
        // 23:59:59 and 00:00:01 are two seconds and two days apart.
        val justBefore = 1_790_121_599L // 2026-09-22 23:59:59 UTC
        val justAfter = justBefore + 2
        val stamps = listOf(justAfter, justBefore)
        assertTrue(DaySeparator.startsNewDay(stamps, 0, utc))
    }

    @Test
    fun anHourApartWithinADayIsNotADayChange() {
        val stamps = listOf(noon, noon - 3600)
        assertFalse(DaySeparator.startsNewDay(stamps, 0, utc))
    }

    @Test
    fun anIndexOutsideTheListIsNotAHeading() {
        assertFalse(DaySeparator.startsNewDay(listOf(noon), 5, utc))
        assertFalse(DaySeparator.startsNewDay(emptyList(), 0, utc))
    }

    @Test
    fun anUnreadableStampGetsNoHeadingButDoesNotHideTheNextOne() {
        val stamps = listOf(noon, Long.MAX_VALUE, noon - day * 3)
        assertFalse(DaySeparator.startsNewDay(stamps, 1, utc), "no day, no heading")
        // The one below a broken stamp still gets its own heading rather
        // than inheriting the confusion.
        assertTrue(DaySeparator.startsNewDay(stamps, 0, utc))
    }

    // ---- what the heading says -------------------------------------------

    @Test
    fun todayAndYesterdayAreNamed() {
        assertEquals(DayLabel.Today, DaySeparator.labelFor(noon, noon, utc))
        assertEquals(DayLabel.Yesterday, DaySeparator.labelFor(noon - day, noon, utc))
    }

    @Test
    fun todayIsAboutTheCalendarDayNotTwentyFourHours() {
        // 00:20 and 12:00 on the same date are both "Today". An
        // elapsed-time rule would start calling the first one
        // "Yesterday" once 24 hours had passed from it.
        val earlyToday = 1_790_036_400L
        assertEquals(DayLabel.Today, DaySeparator.labelFor(earlyToday, noon, utc))
    }

    @Test
    fun thisWeekIsNamedByItsWeekday() {
        for (d in 2..DaySeparator.WEEKDAY_WINDOW_DAYS) {
            assertEquals(
                DayLabel.Weekday,
                DaySeparator.labelFor(noon - day * d, noon, utc),
                "$d days ago",
            )
        }
    }

    @Test
    fun theWeekdayWindowStopsShortOfAFullWeek() {
        // Seven days ago shares its weekday name with today, so
        // "Monday" would be ambiguous on a Monday. It gets a date.
        assertEquals(DayLabel.FullDate, DaySeparator.labelFor(noon - day * 7, noon, utc))
    }

    @Test
    fun olderMessagesGetASpeltOutDate() {
        assertEquals(DayLabel.FullDate, DaySeparator.labelFor(noon - day * 30, noon, utc))
        assertEquals(DayLabel.FullDate, DaySeparator.labelFor(noon - day * 400, noon, utc))
    }

    @Test
    fun aFutureStampIsNotDisguisedAsSomethingFamiliar() {
        // A sender whose clock runs ahead. "Tomorrow" would imply we
        // expect it; a bare weekday would read as the past. The date is
        // the only wording that shows the oddity.
        assertEquals(DayLabel.FullDate, DaySeparator.labelFor(noon + day, noon, utc))
        assertEquals(DayLabel.FullDate, DaySeparator.labelFor(noon + day * 400, noon, utc))
    }

    @Test
    fun anUnreadableStampHasNoLabel() {
        assertNull(DaySeparator.labelFor(Long.MAX_VALUE, noon, utc))
    }

    @Test
    fun theZoneDecidesWhetherSomethingIsToday() {
        // 02:20 UTC on the 23rd is still 22:20 on the 22nd in New
        // York. Reading the phone's zone rather than UTC is the whole
        // point of passing one in.
        val lateUtc = 1_790_130_000L // 2026-09-23 02:20 UTC
        assertEquals(DayLabel.Today, DaySeparator.labelFor(lateUtc, lateUtc, utc))
        assertEquals(DayLabel.Today, DaySeparator.labelFor(lateUtc, lateUtc, ny))
        // ...and a stamp 12 hours earlier straddles the boundary
        // differently in each.
        assertEquals(DayLabel.Yesterday, DaySeparator.labelFor(lateUtc - day, lateUtc, utc))
    }
}
