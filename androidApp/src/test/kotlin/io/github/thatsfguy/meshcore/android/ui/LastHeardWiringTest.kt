package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No screen may age a node by the node's own claim.
 *
 * A lint, and it earns its place by arithmetic: this defect appeared in
 * FIVE places at once — the node row, the contact sheet, the map popup,
 * the "last heard" sort and the "heard in last 24 h" filter — because
 * `lastSeen` is the obvious-looking field and reads correctly on every
 * node whose clock happens to be right. It is only wrong on the nodes
 * that most need looking at, so it survives casual use indefinitely.
 * On a live mesh it rendered a repeater heard that morning as "20688
 * days ago · Jan 1, 1970".
 *
 * `lastSeen` is not banned outright: the contact sheet shows it
 * deliberately, as the node's own clock, when it disagrees with what we
 * observed. What is banned is passing it to something that formats an
 * age.
 */
class LastHeardWiringTest {

    private val screens = File("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens")

    @Test
    fun `no screen formats an age from the advert's own timestamp`() {
        val offenders = mutableListOf<String>()
        var scanned = 0
        for (file in screens.listFiles { f: File -> f.name.endsWith(".kt") } ?: emptyArray()) {
            val source = file.readText()
            scanned++
            for ((i, line) in source.lines().withIndex()) {
                val code = line.substringBefore("//")
                if (!code.contains("lastSeen")) continue
                // The two ways an age reaches the screen.
                if (Regex("""relativeAge\([^)]*lastSeen""").containsMatchIn(code) ||
                    Regex("""RelativeTime\.\w+\([^)]*lastSeen""").containsMatchIn(code)
                ) {
                    offenders += "${file.name}:${i + 1}: $code"
                }
            }
        }
        assertTrue("no screen sources scanned", scanned > 20)
        assertTrue(
            "these age a node by its own claim instead of LastHeard:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the node row and the map popup ask LastHeard`() {
        // The positive control for the lint above: it would also pass if
        // the screens stopped showing an age at all.
        val nodes = File(screens, "NodesScreen.kt").readText()
        val map = File(screens, "MapNodeSheet.kt").readText()
        assertTrue(
            "the node row no longer shows when it was heard",
            nodes.contains("relativeAge(LastHeard.seconds(c))"),
        )
        assertTrue(
            "the contact sheet no longer shows when it was heard",
            nodes.contains("relativeAge(LastHeard.seconds(contact))"),
        )
        assertTrue(
            "the map popup no longer shows when it was heard",
            map.contains("relativeAge(LastHeard.seconds(contact))"),
        )
    }

    @Test
    fun `a node with a wrong clock is told about, not silently corrected`() {
        // Its owner would otherwise be chasing this as a bug in every
        // client they try.
        val nodes = File(screens, "NodesScreen.kt").readText()
        assertTrue(
            "the contact sheet hides a disagreeing clock",
            nodes.contains("LastHeard.claimDisagrees(contact)") &&
                nodes.contains("this node's clock is wrong"),
        )
    }
}
