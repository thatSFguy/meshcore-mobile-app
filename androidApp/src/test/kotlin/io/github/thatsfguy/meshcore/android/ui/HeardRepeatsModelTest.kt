package io.github.thatsfguy.meshcore.android.ui

import io.github.thatsfguy.meshcore.presentation.repeatRows
import io.github.thatsfguy.meshcore.android.ui.screens.HEARD_REPEATS_ROUTE
import io.github.thatsfguy.meshcore.protocol.HeardRepeats
import io.github.thatsfguy.meshcore.util.RelativeTime
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "who repeats me" rows: naming, measurement and wording.
 *
 * The rule under test is the same one the protocol object protects, seen
 * from the UI side — a hop is a truncated hash, so a row may only carry
 * a name when exactly one contact matches it, and the SNR may only
 * appear against the repeater whose transmission this radio actually
 * demodulated.
 */
class HeardRepeatsModelTest {

    private fun echo(path: String, snr: Double? = 9.0, at: Long = 10_000L) =
        HeardRepeats.Echo(path, hashWidth = 2, snr = snr, rssi = -95, atMillis = at)

    private val blue = "b389aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val ridge = "c985bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    // --- naming --------------------------------------------------------

    @Test
    fun `a hop matching exactly one contact is named`() {
        val rows = repeatRows(
            listOf(echo("b389")),
            mapOf(blue to "Blue Ridge"),
            nowMillis = 10_000L,
        )
        assertEquals("b389 Blue Ridge", rows.single().label)
        assertFalse(rows.single().isAmbiguous)
    }

    @Test
    fun `a hop matching two contacts is never named`() {
        // 16 bits collide. Picking one would put a specific repeater's
        // name against traffic it may never have touched.
        val colliding = mapOf(
            blue to "Blue Ridge",
            "b389cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc" to "Other",
        )
        val row = repeatRows(listOf(echo("b389")), colliding, nowMillis = 10_000L).single()
        assertEquals("b389 (2 matches)", row.label)
        assertTrue(row.isAmbiguous)
    }

    @Test
    fun `an unknown hop stays a bare hash`() {
        val row = repeatRows(listOf(echo("dead")), emptyMap(), nowMillis = 10_000L).single()
        assertEquals("dead", row.label)
    }

    // --- what the numbers mean -------------------------------------------

    @Test
    fun `SNR appears only against the repeater we actually heard`() {
        // Path b389 -> c985: c985 transmitted the copy we demodulated.
        val rows = repeatRows(
            listOf(echo("b389c985", snr = 7.5)),
            mapOf(blue to "Blue Ridge", ridge to "Ridge Top"),
            nowMillis = 10_000L,
        )
        val first = rows.single { it.hashHex == "b389" }
        val last = rows.single { it.hashHex == "c985" }
        assertNull(first.snrText, "SNR shown for a link nobody measured")
        assertEquals("SNR 7.5 dB", last.snrText)
    }

    @Test
    fun `the direction sentence distinguishes the two ends of a path`() {
        val rows = repeatRows(listOf(echo("b389c985")), emptyMap(), nowMillis = 10_000L)
        val first = rows.single { it.hashHex == "b389" }
        val last = rows.single { it.hashHex == "c985" }
        assertTrue(first.direction.startsWith("Heard you directly"), first.direction)
        assertTrue(last.direction.startsWith("You heard it directly"), last.direction)
    }

    @Test
    fun `a single-hop repeater is flagged two-way`() {
        val row = repeatRows(listOf(echo("b389")), emptyMap(), nowMillis = 10_000L).single()
        assertTrue(row.isTwoWay)
    }

    @Test
    fun `age is measured against the engine clock the echo was stamped with`() {
        val row = repeatRows(
            listOf(echo("b389", at = 10_000L)),
            emptyMap(),
            nowMillis = 10_000L + 3 * 60_000L,
        ).single()
        assertEquals("3 min ago", row.ageText)
    }

    @Test
    fun `no echoes is an empty list, not a placeholder row`() {
        assertTrue(repeatRows(emptyList(), emptyMap(), nowMillis = 0L).isEmpty())
    }

    // --- the navigation graph ---------------------------------------------

    /**
     * The menu item is a tap that does nothing if the NavHost never
     * declares the route, and nothing else would catch it — the same
     * failure the settings tiles are guarded against. Reads the real
     * source, not a copy of the constant (REBUILD-PLAYBOOK §7.1).
     */
    @Test
    fun `the heard-repeats route has a destination in the NavHost`() {
        val nav = File("src/main/kotlin/io/github/thatsfguy/meshcore/android/MainActivity.kt")
        assertTrue(nav.exists(), "MainActivity.kt not found at ${nav.absolutePath}")
        assertTrue(
            nav.readText().contains("composable(HEARD_REPEATS_ROUTE)"),
            "$HEARD_REPEATS_ROUTE has no composable() in the NavHost",
        )
    }

    @Test
    fun `the Nodes menu is what reaches it`() {
        val nodes = File(
            "src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/NodesScreen.kt",
        )
        assertTrue(nodes.exists(), "NodesScreen.kt not found")
        assertTrue(
            nodes.readText().contains("nav.navigate(HEARD_REPEATS_ROUTE)"),
            "nothing navigates to HEARD_REPEATS_ROUTE",
        )
    }

    // --- the shared wording ------------------------------------------------

    @Test
    fun `relative ages read the way the node list already reads`() {
        assertEquals("just now", RelativeTime.ago(0))
        assertEquals("just now", RelativeTime.ago(59))
        assertEquals("1 min ago", RelativeTime.ago(60))
        assertEquals("2 hours ago", RelativeTime.ago(7_200))
        assertEquals("3 days ago", RelativeTime.ago(3 * 86_400))
    }

    @Test
    fun `one of something is singular`() {
        // It read "1 hours ago" for the whole hour after every advert,
        // and "1 days ago" for a whole day — the two spans a node list
        // spends most of its time showing, now that the row states an
        // age rather than a date.
        assertEquals("1 hour ago", RelativeTime.ago(3_600))
        assertEquals("1 hour ago", RelativeTime.ago(7_199))
        assertEquals("1 day ago", RelativeTime.ago(86_400))
        assertEquals("1 day ago", RelativeTime.ago(2 * 86_400 - 1))
    }

    @Test
    fun `every unit crosses over at the right second`() {
        assertEquals("just now", RelativeTime.ago(59))
        assertEquals("1 min ago", RelativeTime.ago(60))
        assertEquals("59 min ago", RelativeTime.ago(3_599))
        assertEquals("1 hour ago", RelativeTime.ago(3_600))
        assertEquals("23 hours ago", RelativeTime.ago(86_399))
        assertEquals("1 day ago", RelativeTime.ago(86_400))
    }

    @Test
    fun `an age never runs out of words, however old the node`() {
        // A contact imported from a QR and never heard again is aged
        // against a timestamp of 0, which is 56 years of seconds.
        assertEquals("20000 days ago", RelativeTime.ago(20_000L * 86_400))
        assertTrue(RelativeTime.ago(Long.MAX_VALUE / 2).endsWith("days ago"))
    }

    @Test
    fun `a clock that runs backwards reads as now, not as the future`() {
        assertEquals("just now", RelativeTime.ago(-5))
        assertEquals("just now", RelativeTime.agoMillis(-5_000))
    }

    @Test
    fun `the node list uses the shared formatter rather than its own copy`() {
        // It had its own inline when-block; two copies of "9 hours ago"
        // is exactly the drift this codebase keeps finding.
        val nodes = File(
            "src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/NodesScreen.kt",
        ).readText()
        assertTrue(nodes.contains("RelativeTime.ago("), "NodesScreen no longer calls RelativeTime")
        assertFalse(
            nodes.contains("\"${'$'}{seconds / 3600} hours ago\""),
            "NodesScreen still carries its own age wording",
        )
    }
}
