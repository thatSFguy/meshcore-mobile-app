package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.presentation.RegionAdmin.Action
import io.github.thatsfguy.meshcore.presentation.RegionAdmin.Untagged
import io.github.thatsfguy.meshcore.protocol.Regions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegionAdminTest {

    /** MobileTruckBase, 2026-09-26, with grr made home for the test. */
    private val tree = assertNotNull(
        Regions.parseRegionTree("* F\n midwest F\n  mi F\n   mi-west F\n    grr^\n"),
    )
    private fun entry(name: String) = tree.regions.single { it.name == name }

    // ------------------------------------------------------------------
    // Region actions
    // ------------------------------------------------------------------

    @Test
    fun aRegionWithChildrenCannotBeRemoved() {
        // The firmware answers "Err - not empty"; don't offer it.
        assertFalse(Action.Remove in RegionAdmin.actionsFor(entry("mi"), tree, "mi"))
        assertTrue(Action.Remove in RegionAdmin.actionsFor(entry("grr"), tree, "mi"))
    }

    @Test
    fun theDefaultAndHomeAreNotOfferedAgain() {
        val mi = RegionAdmin.actionsFor(entry("mi"), tree, "mi")
        assertFalse(Action.MakeDefault in mi)
        assertTrue(Action.SetHome in mi)
        val grr = RegionAdmin.actionsFor(entry("grr"), tree, "mi")
        assertTrue(Action.MakeDefault in grr)
        assertFalse(Action.SetHome in grr)
    }

    @Test
    fun floodOffersTheOppositeOfWhatItIs() {
        assertTrue(Action.DenyFlood in RegionAdmin.actionsFor(entry("mi"), tree, null))
        assertTrue(Action.AllowFlood in RegionAdmin.actionsFor(entry("grr"), tree, null))
    }

    @Test
    fun everyRegionCanTakeAChild() {
        for (e in tree.regions) assertTrue(Action.AddChild in RegionAdmin.actionsFor(e, tree, null))
    }

    @Test
    fun aNameWeWouldRewriteOffersNothing() {
        val odd = assertNotNull(Regions.parseRegionTree("* F\n MI F"))
        assertEquals(emptyList(), RegionAdmin.actionsFor(odd.regions.single(), odd, null))
        // ...and can't be chosen as a parent either.
        assertEquals(listOf("*"), RegionAdmin.parentChoices(odd).map { it.name })
    }

    @Test
    fun parentsAreTheTopLevelThenTheTreeInOrder() {
        assertEquals(
            listOf("*" to 0, "midwest" to 1, "mi" to 2, "mi-west" to 3, "grr" to 4),
            RegionAdmin.parentChoices(tree).map { it.name to it.depth },
        )
        assertEquals(listOf("*"), RegionAdmin.parentChoices(null).map { it.name })
    }

    // ------------------------------------------------------------------
    // What needs `region save`
    // ------------------------------------------------------------------

    @Test
    fun regionEditsWaitForSaveButDefaultAndSettingsDoNot() {
        assertTrue(RegionAdmin.needsRegionSave(Regions.put("mi", "midwest")))
        assertTrue(RegionAdmin.needsRegionSave(Regions.denyFlood("*")))
        assertTrue(RegionAdmin.needsRegionSave(Regions.setHome("mi")))
        assertTrue(RegionAdmin.needsRegionSave(Regions.remove("grr")))
        // CommonCLI: `region default <x>` calls saveRegions() itself.
        assertFalse(RegionAdmin.needsRegionSave(Regions.setDefault("mi")))
        assertFalse(RegionAdmin.needsRegionSave(Regions.setDefault(null)))
        // `set flood.max.unscoped` calls savePrefs().
        assertFalse(RegionAdmin.needsRegionSave(RegionAdmin.setHopLimit(3)))
        assertFalse(RegionAdmin.needsRegionSave(Regions.save()))
    }

    @Test
    fun settingTheDefaultSavesWhateverWasPending() {
        assertTrue(RegionAdmin.savesRegions(Regions.setDefault("mi")))
        assertTrue(RegionAdmin.savesRegions(Regions.save()))
        assertFalse(RegionAdmin.savesRegions(Regions.default()))
        assertFalse(RegionAdmin.savesRegions(RegionAdmin.setHopLimit(3)))
    }

    // ------------------------------------------------------------------
    // Untagged traffic
    // ------------------------------------------------------------------

    @Test
    fun theHopLimitArrivedInV1_16() {
        // Shaped like real `ver` replies ("v1.16.0-07a3ca9 (Build: …)").
        assertEquals(true, RegionAdmin.supportsHopLimit("v1.16.0-07a3ca9 (Build: 18-May-2026)"))
        assertEquals(true, RegionAdmin.supportsHopLimit("v1.17.1-abc1234 (Build: 1-Sep-2026)"))
        assertEquals(true, RegionAdmin.supportsHopLimit("v2.0.0"))
        // v1.15.0's CommonCLI has no flood.max.unscoped.
        assertEquals(false, RegionAdmin.supportsHopLimit("v1.15.0-dee3e26 (Build: 1-Apr-2026)"))
        assertEquals(false, RegionAdmin.supportsHopLimit("v1.9.1"))
        assertNull(RegionAdmin.supportsHopLimit("??: ver"))
        assertNull(RegionAdmin.supportsHopLimit(null))
    }

    @Test
    fun theHopLimitIsReadFromTheReplyShape() {
        assertEquals(3, RegionAdmin.parseHopLimit("> 3"))
        assertEquals(64, RegionAdmin.parseHopLimit("> 64"))
        assertNull(RegionAdmin.parseHopLimit("unknown config: flood.max.unscoped"))
        assertNull(RegionAdmin.parseHopLimit("3"))
        assertNull(RegionAdmin.parseHopLimit("> lots"))
        assertNull(RegionAdmin.parseHopLimit(null))
    }

    @Test
    fun theModeComesFromBothSettings() {
        assertEquals(Untagged.RelayAll, RegionAdmin.untaggedMode(true, 64))
        assertEquals(Untagged.RelayAll, RegionAdmin.untaggedMode(true, null))
        assertEquals(Untagged.Nearby, RegionAdmin.untaggedMode(true, 3))
        assertEquals(Untagged.Refuse, RegionAdmin.untaggedMode(false, 64))
        // A closed wildcard wins over any limit.
        assertEquals(Untagged.Refuse, RegionAdmin.untaggedMode(false, 3))
        // hops >= 0 is always true: a limit of 0 refuses everything.
        assertEquals(Untagged.Refuse, RegionAdmin.untaggedMode(true, 0))
    }

    @Test
    fun anUnansweredHopLimitIsUnknownNotRelayAll() {
        // Seen on the phone: a node set to relay only nearby traffic
        // showed "Relay all" whenever the `get` went unanswered.
        assertNull(RegionAdmin.currentUntagged(true, hopLimit = null, limitSupported = true))
        // `ver` unanswered too: the limit was never asked for.
        assertNull(RegionAdmin.currentUntagged(true, hopLimit = null, limitSupported = null))
    }

    @Test
    fun theCurrentModeIsStatedWhenItCanBeKnown() {
        // Positive controls: every case that must still answer.
        assertEquals(Untagged.Nearby, RegionAdmin.currentUntagged(true, 3, limitSupported = true))
        assertEquals(Untagged.RelayAll, RegionAdmin.currentUntagged(true, 64, limitSupported = true))
        assertEquals(Untagged.Refuse, RegionAdmin.currentUntagged(true, 0, limitSupported = true))
        // Pre-1.16 has no limit to read, so an open wildcard relays all.
        assertEquals(Untagged.RelayAll, RegionAdmin.currentUntagged(true, null, limitSupported = false))
        // A closed wildcard refuses whatever the limit is, read or not.
        assertEquals(Untagged.Refuse, RegionAdmin.currentUntagged(false, null, limitSupported = null))
    }

    @Test
    fun refusingClosesTheWildcardAndLeavesTheLimit() {
        assertEquals(
            listOf("region denyf *"),
            RegionAdmin.commandsFor(Untagged.Refuse, 3, wildcardFloodAllowed = true, hopLimit = 3, limitSupported = true),
        )
    }

    @Test
    fun nearbyOpensTheWildcardAndSetsTheLimit() {
        assertEquals(
            listOf("region allowf *", "set flood.max.unscoped 3"),
            RegionAdmin.commandsFor(Untagged.Nearby, 3, wildcardFloodAllowed = false, hopLimit = 64, limitSupported = true),
        )
    }

    @Test
    fun relayAllLiftsTheLimitAndOpensTheWildcard() {
        assertEquals(
            listOf("region allowf *", "set flood.max.unscoped 64"),
            RegionAdmin.commandsFor(Untagged.RelayAll, 3, wildcardFloodAllowed = false, hopLimit = 3, limitSupported = true),
        )
        // A limit of 0 is a refusal too; relay-all must lift it.
        assertEquals(
            listOf("set flood.max.unscoped 64"),
            RegionAdmin.commandsFor(Untagged.RelayAll, 3, wildcardFloodAllowed = true, hopLimit = 0, limitSupported = true),
        )
    }

    @Test
    fun oldFirmwareIsNeverSentTheLimit() {
        assertEquals(
            listOf("region allowf *"),
            RegionAdmin.commandsFor(Untagged.RelayAll, 3, wildcardFloodAllowed = false, hopLimit = null, limitSupported = false),
        )
        assertFailsWith<IllegalArgumentException> {
            RegionAdmin.commandsFor(Untagged.Nearby, 3, wildcardFloodAllowed = true, hopLimit = null, limitSupported = false)
        }
    }

    @Test
    fun applyingWhatIsAlreadyTrueSendsNothing() {
        assertEquals(emptyList(), RegionAdmin.commandsFor(Untagged.RelayAll, 3, true, 64, true))
        assertEquals(emptyList(), RegionAdmin.commandsFor(Untagged.Nearby, 3, true, 3, true))
        assertEquals(emptyList(), RegionAdmin.commandsFor(Untagged.Refuse, 3, false, 3, true))
    }

    @Test
    fun nearbyTakesOneToSixtyThree() {
        // 0 would refuse and 64 relay everything — both are other modes.
        for (bad in listOf(0, 64, -1, 999)) {
            assertFailsWith<IllegalArgumentException> {
                RegionAdmin.commandsFor(Untagged.Nearby, bad, true, 64, true)
            }
        }
        assertFailsWith<IllegalArgumentException> { RegionAdmin.setHopLimit(65) }
    }

    @Test
    fun theLimitIsDescribedAsTheFirmwareAppliesIt() {
        // Dropped when hops already taken >= limit: 3 relays what came
        // through at most 2 repeaters.
        assertEquals("Relayed only when it has come through 2 repeaters or fewer.", RegionAdmin.describeNearby(3))
        assertEquals("Relayed only when it has come through 1 repeater or fewer.", RegionAdmin.describeNearby(2))
        assertEquals("Relayed only when heard straight from the sender.", RegionAdmin.describeNearby(1))
    }
}
