package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Retention policy (PARITY §3).
 *
 * The rule that matters most here is the failure direction: a policy
 * that can't be parsed must fall back to KEEPING data. Getting that
 * backwards would delete a user's history on the strength of a corrupted
 * preference — silently, and at launch.
 */
class RetentionTest {

    private val now = 1_700_000_000L

    @Test
    fun foreverIsUnboundedAndPrunesNothing() {
        val p = Retention.Policy()
        assertFalse(p.isBounded)
        assertNull(p.cutoffSeconds(now))
        assertNull(p.keepPerThread())
        assertEquals("Keep everything", p.label())
    }

    @Test
    fun dayPolicyComputesTheCutoff() {
        val p = Retention.Policy(Retention.Mode.Days, 30)
        assertTrue(p.isBounded)
        assertEquals(now - 30 * 86_400L, p.cutoffSeconds(now))
        assertNull(p.keepPerThread())
    }

    @Test
    fun countPolicyBoundsPerThread() {
        val p = Retention.Policy(Retention.Mode.Count, 100)
        assertTrue(p.isBounded)
        assertEquals(100, p.keepPerThread())
        assertNull(p.cutoffSeconds(now))
    }

    @Test
    fun aZeroBoundIsTreatedAsUnsetRatherThanDeleteEverything() {
        // "0 days" as "delete all history, continuously" is a legitimate
        // wish, but nobody expresses it by leaving a box at zero.
        for (p in listOf(
            Retention.Policy(Retention.Mode.Days, 0),
            Retention.Policy(Retention.Mode.Count, 0),
            Retention.Policy(Retention.Mode.Days, -5),
            Retention.Policy(Retention.Mode.Count, -1),
        )) {
            assertFalse(p.isBounded, "$p should not be bounded")
            assertNull(p.cutoffSeconds(now))
            assertNull(p.keepPerThread())
        }
    }

    @Test
    fun absurdValuesAreClampedNotHonoured() {
        assertEquals(
            Retention.MAX_DAYS,
            Retention.Policy(Retention.Mode.Days, Int.MAX_VALUE).effectiveValue,
        )
        assertEquals(
            Retention.MAX_COUNT,
            Retention.Policy(Retention.Mode.Count, Int.MAX_VALUE).effectiveValue,
        )
        // And the clamp can't overflow the cutoff arithmetic.
        val cutoff = Retention.Policy(Retention.Mode.Days, Int.MAX_VALUE).cutoffSeconds(now)!!
        assertTrue(cutoff < now, "cutoff overflowed into the future")
    }

    @Test
    fun encodeDecodeRoundTrips() {
        for (p in Retention.PRESETS) {
            assertEquals(p, Retention.decode(p.encode()), "round trip failed for $p")
        }
    }

    @Test
    fun anUnreadablePolicyKeepsEverything() {
        // The failure direction is the whole test: unparseable must mean
        // "keep", never "delete".
        for (bad in listOf(
            null, "", "   ", "garbage", "days", "days:", "days:abc",
            "count:", "count:xyz", "weeks:3", "days:3:4", "::",
        )) {
            val p = Retention.decode(bad)
            assertFalse(p.isBounded, "'$bad' decoded to a pruning policy: $p")
        }
    }

    @Test
    fun decodeToleratesCaseAndWhitespace() {
        assertEquals(Retention.Policy(Retention.Mode.Days, 30), Retention.decode(" DAYS:30 "))
        assertEquals(Retention.Policy(), Retention.decode("  FOREVER "))
    }

    @Test
    fun labelsReadAsSentences() {
        assertEquals("Keep 1 day", Retention.Policy(Retention.Mode.Days, 1).label())
        assertEquals("Keep 30 days", Retention.Policy(Retention.Mode.Days, 30).label())
        assertEquals(
            "Keep the newest message",
            Retention.Policy(Retention.Mode.Count, 1).label(),
        )
        assertEquals(
            "Keep the newest 500 messages",
            Retention.Policy(Retention.Mode.Count, 500).label(),
        )
    }

    @Test
    fun presetsAreDistinctAndStartWithForever() {
        assertEquals(Retention.Policy(), Retention.PRESETS.first())
        assertEquals(Retention.PRESETS.size, Retention.PRESETS.distinct().size)
        assertTrue(Retention.PRESETS.drop(1).all { it.isBounded })
    }
}
