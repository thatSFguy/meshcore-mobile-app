package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.presentation.ScannedSettingsPlan.RegionPlan
import io.github.thatsfguy.meshcore.protocol.CliReplies
import io.github.thatsfguy.meshcore.protocol.Regions
import io.github.thatsfguy.meshcore.protocol.ShareUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Applying a scanned code to a repeater a second time must send nothing,
 * and must never re-open flood on a region its operator denied.
 *
 * Node state is given as the firmware prints it — `RegionMap::exportTo`
 * for `region`, ` default scope is …` for `region default` — and parsed
 * by the app's own parsers, so the plan is tested against the real shape.
 */
class ScannedSettingsPlanTest {

    private val tree = listOf("midwest", "mi", "mi-west", "grr")

    private fun node(reply: String) = Regions.parseRegionTree(reply)!!
    private fun default(reply: String) = Regions.parseDefaultScope(reply)

    /** What SpartaMI holds after the code has been applied once. */
    private val applied = "* F\n midwest F\n  mi F\n   mi-west F\n    grr F"

    @Test
    fun aNodeThatAlreadyHasTheTreeGetsNothing() {
        val plan = ScannedSettingsPlan.regions(tree, "mi", node(applied), default(" default scope is mi"))
        assertEquals(RegionPlan.AlreadyApplied, plan)
    }

    @Test
    fun aDenyTheOperatorSetSurvivesASecondScan() {
        // Tree matches, default matches, #midwest set to deny since:
        // nothing is sent, so nothing re-opens it.
        val denied = "* F\n midwest\n  mi F\n   mi-west F\n    grr F"
        assertEquals(
            RegionPlan.AlreadyApplied,
            ScannedSettingsPlan.regions(tree, "mi", node(denied), default(" default scope is mi")),
        )
    }

    @Test
    fun aDenyIsPutBackWhenTheTreeHasToBeWritten() {
        // #grr is new, so `region def` must go — and it re-opens every
        // name it lists, #midwest included. The deny is restored before save.
        val partial = "* F\n midwest\n  mi F\n   mi-west F"
        val plan = ScannedSettingsPlan.regions(tree, "mi", node(partial), default(" default scope is mi"))
            as RegionPlan.Changes
        assertEquals(
            listOf("region def midwest mi mi-west grr", "region denyf midwest", "region save"),
            plan.commands,
        )
        assertEquals(listOf("grr"), plan.added)
        assertEquals(listOf("midwest"), plan.keptDenied)
    }

    @Test
    fun aDeniedDefaultIsPutBackAfterRegionDefaultReopensIt() {
        val node = "* F\n midwest F\n  mi\n   mi-west F\n    grr F"
        val plan = ScannedSettingsPlan.regions(tree, "mi", node(node), default(" default scope is <null>"))
            as RegionPlan.Changes
        assertEquals(listOf("region default mi", "region denyf mi", "region save"), plan.commands)
        assertEquals("mi", plan.newDefault)
        assertNull(plan.previousDefault, "none is not a region")
    }

    @Test
    fun aFreshNodeGetsTheWholeTreeAndDefault() {
        val plan = ScannedSettingsPlan.regions(tree, "mi", node("* F"), default(" default scope is <null>"))
            as RegionPlan.Changes
        assertEquals(
            listOf("region def midwest mi mi-west grr", "region default mi", "region save"),
            plan.commands,
        )
        assertEquals(tree, plan.added)
        assertEquals(emptyList(), plan.keptDenied)
    }

    @Test
    fun aRegionUnderTheWrongParentIsMoved() {
        // grr sits at the top level on the node; the code puts it under mi-west.
        val node = "* F\n midwest F\n  mi F\n   mi-west F\n grr F"
        val plan = ScannedSettingsPlan.regions(tree, "mi", node(node), default(" default scope is mi"))
            as RegionPlan.Changes
        assertEquals(listOf("grr" to "mi-west"), plan.moved)
        assertEquals(listOf("region def midwest mi mi-west grr", "region save"), plan.commands)
    }

    @Test
    fun aChangedDefaultAloneIsOneCommand() {
        val plan = ScannedSettingsPlan.regions(tree, "mi", node(applied), default(" default scope is grr"))
            as RegionPlan.Changes
        assertEquals(listOf("region default mi", "region save"), plan.commands)
        assertEquals("grr", plan.previousDefault)
    }

    @Test
    fun regionsTheCodeDoesntNameAreLeftAlone() {
        // An extra region, denied, outside this tree: never touched.
        val node = "* F\n ohio\n midwest F\n  mi F\n   mi-west F\n    grr F"
        assertEquals(
            RegionPlan.AlreadyApplied,
            ScannedSettingsPlan.regions(tree, "mi", node(node), default(" default scope is mi")),
        )
    }

    @Test
    fun whatCantBeReadIsNotWritten() {
        assertEquals(RegionPlan.Unreadable, ScannedSettingsPlan.regions(tree, "mi", null, " default scope is mi".let(::default)))
        // The tree read but the default didn't: can't tell if it changes.
        assertEquals(RegionPlan.Unreadable, ScannedSettingsPlan.regions(tree, "mi", node(applied), null))
        // Without a default in the code, an unread default doesn't matter.
        assertEquals(RegionPlan.AlreadyApplied, ScannedSettingsPlan.regions(tree, null, node(applied), null))
    }

    @Test
    fun aListingCutOffBeforeTheTreeCantBeChecked() {
        // A reply at the 160-byte limit: regions past the cut are unseen.
        val filler = (1..40).joinToString("\n") { " r$it F" }
        val cut = ("* F\n$filler\n midwest F").take(159)
        assertTrue(Regions.parseRegionTree(cut)!!.truncated)
        assertEquals(RegionPlan.TooManyToRead, ScannedSettingsPlan.regions(tree, "mi", node(cut), default(" default scope is mi")))
    }

    @Test
    fun noUsableTreeIsNoPlan() {
        assertNull(ScannedSettingsPlan.regions(emptyList(), null, node("* F"), null))
        assertNull(ScannedSettingsPlan.regions(listOf("mi", "mi"), null, node("* F"), null))
        // Six 29-character names: "region def " + 6*29 + 5 spaces = 190.
        val long = (1..6).map { "r".repeat(28) + it }
        assertEquals(RegionPlan.TooLong, ScannedSettingsPlan.regions(long, null, node("* F"), null))
        // Positive control: the longest line a node reads (159) is sent.
        val fits = listOf("a".repeat(29), "b".repeat(29), "c".repeat(29), "d".repeat(29), "e".repeat(28))
        val plan = ScannedSettingsPlan.regions(fits, null, node("* F"), null) as RegionPlan.Changes
        assertEquals(RegionAdmin.MAX_COMMAND_LENGTH, plan.commands.first().length)
    }

    @Test
    fun theChangesAreSaidInWords() {
        val partial = "* F\n midwest\n  mi F\n grr F"
        val plan = ScannedSettingsPlan.regions(tree, "mi", node(partial), default(" default scope is grr"))
            as RegionPlan.Changes
        assertEquals(
            listOf(
                "Adds #mi-west",
                "Moves #grr under #mi-west",
                "Makes #mi its default region (now #grr)",
                "Keeps #midwest refusing flood traffic, as now",
            ),
            ScannedSettingsPlan.describe(plan),
        )
        val fresh = ScannedSettingsPlan.regions(tree, "mi", node("* F"), default(" default scope is <null>"))
            as RegionPlan.Changes
        assertEquals(
            listOf("Adds #midwest, #mi, #mi-west, #grr", "Makes #mi its default region (none set now)"),
            ScannedSettingsPlan.describe(fresh),
        )
    }

    // --- radio ------------------------------------------------------------

    private val code = ShareUri.decode(
        "meshcore://radio/set?v=1&name=USA%2FCanada&freq=910.525&bw=62.5&sf=7&cr=5&hash=1&region=mi",
    ) as ShareUri.Decoded.RadioConfig

    @Test
    fun theRadioMatchesDespiteTheNodesFloatPrinting() {
        // `get radio` on a live node: the float32 nearest 910.525.
        val r = CliReplies.parseRadioCsv(CliReplies.extractGetValue("> 910.5250244,62.5,7,5")!!)
        assertTrue(ScannedSettingsPlan.radioMatches(code, r, nodePathHashMode = 1))
    }

    @Test
    fun anyDifferenceOrUnknownMeansWrite() {
        val r = CliReplies.RadioCsv(910.525, 62.5, 7, 5)
        assertFalse(ScannedSettingsPlan.radioMatches(code, r, nodePathHashMode = 0))
        assertFalse(ScannedSettingsPlan.radioMatches(code, r, nodePathHashMode = null))
        assertFalse(ScannedSettingsPlan.radioMatches(code, null, nodePathHashMode = 1))
        assertFalse(ScannedSettingsPlan.radioMatches(code, r.copy(freqMhz = 906.875), 1))
        assertFalse(ScannedSettingsPlan.radioMatches(code, r.copy(bwKhz = 250.0), 1))
        assertFalse(ScannedSettingsPlan.radioMatches(code, r.copy(sf = 8), 1))
        assertFalse(ScannedSettingsPlan.radioMatches(code, r.copy(cr = 6), 1))
    }
}
