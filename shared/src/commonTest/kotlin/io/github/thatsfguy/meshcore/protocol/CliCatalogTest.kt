package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coverage tests for the MeshCore CLI command catalog — the inventory
 * is asserted against the command list extracted from the MeshCore
 * Open reference client's CLI screen, and role filtering is what the
 * admin/settings UI relies on.
 */
class CliCatalogTest {

    /** Every command the reference client's CLI screen knows. */
    private val referenceInventory = setOf(
        // actions
        "ver", "board", "clock", "clock sync", "time", "advert", "reboot",
        "password", "neighbors", "erase", "start ota",
        "log start", "log stop", "log erase", "clear stats",
        // get/set variables
        "name", "owner.info", "lat", "lon",
        "radio", "freq", "tx", "af", "radio.rxgain",
        "agc.reset.interval", "int.thresh", "dutycycle",
        "repeat", "flood.max", "multi.acks", "path.hash.mode", "loop.detect",
        "rxdelay", "txdelay", "direct.txdelay",
        "advert.interval", "flood.advert.interval",
        "guest.password", "allow.read.only", "prv.key",
        "adc.multiplier",
        // get-only
        "acl", "role", "public.key", "bootloader.ver",
        "pwrmgt.support", "pwrmgt.source", "pwrmgt.bootmv", "pwrmgt.bootreason",
        // bridge family (repeater serial bridge)
        "bridge.enabled", "bridge.type", "bridge.source", "bridge.channel",
        "bridge.baud", "bridge.delay", "bridge.secret",
        // region family (PARITY §8). Deliberately absent: bare `region`
        // (serial-only on the firmware) and `region load` (a multi-line
        // mode — a one-shot CLI message would strand the node in it).
        "region get", "region put", "region remove", "region allowf", "region denyf",
        "region home", "region default", "region list allowed", "region list denied",
        "region save",
    )

    @Test
    fun catalogCoversTheFullReferenceInventory() {
        val ids = CliCatalog.all.map { it.id }.toSet()
        val missing = referenceInventory - ids
        val extra = ids - referenceInventory
        assertTrue(missing.isEmpty(), "Catalog missing commands: $missing")
        assertTrue(extra.isEmpty(), "Catalog has unknown commands: $extra")
    }

    @Test
    fun noDuplicateIds() {
        val ids = CliCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids in catalog")
    }

    // ------------------------------------------------------------------
    // Command-string building
    // ------------------------------------------------------------------

    @Test
    fun actionCommandsBuildBareText() {
        assertEquals("ver", CliCatalog.byId("ver")!!.buildCommand())
        assertEquals("clock sync", CliCatalog.byId("clock sync")!!.buildCommand())
        assertEquals("clear stats", CliCatalog.byId("clear stats")!!.buildCommand())
        assertEquals("start ota", CliCatalog.byId("start ota")!!.buildCommand())
    }

    @Test
    fun getSetCommandsBuildBothForms() {
        val freq = CliCatalog.byId("freq")!!
        assertEquals("get freq", freq.getCommand())
        assertEquals("set freq 910.525", freq.buildCommand("910.525"))

        val name = CliCatalog.byId("name")!!
        assertEquals("get name", name.getCommand())
        assertEquals("set name Blue Ridge", name.buildCommand("Blue Ridge"))
    }

    @Test
    fun getOnlyCommandsRefuseSet() {
        val pub = CliCatalog.byId("public.key")!!
        assertEquals("get public.key", pub.getCommand())
        assertEquals("get public.key", pub.buildCommand())
    }

    @Test
    fun actionWithArgRequiresArgument() {
        val time = CliCatalog.byId("time")!!
        assertEquals("time 1700000000", time.buildCommand("1700000000"))
        assertFailsWith<IllegalArgumentException> { time.buildCommand() }
        assertFailsWith<IllegalArgumentException> { time.buildCommand("  ") }

        val password = CliCatalog.byId("password")!!
        assertEquals("password hunter2", password.buildCommand("hunter2"))
        assertFailsWith<IllegalArgumentException> { password.buildCommand(null) }
    }

    @Test
    fun setWithoutValueThrows() {
        assertFailsWith<IllegalArgumentException> { CliCatalog.byId("freq")!!.buildCommand(null) }
    }

    @Test
    fun actionsHaveNoGetForm() {
        assertFailsWith<IllegalStateException> { CliCatalog.byId("ver")!!.getCommand() }
        assertFailsWith<IllegalStateException> { CliCatalog.byId("time")!!.getCommand() }
    }

    // ------------------------------------------------------------------
    // Role filtering — what each admin surface may display
    // ------------------------------------------------------------------

    @Test
    fun repeaterSeesRepeaterCommandsOnly() {
        val repeater = CliCatalog.forRole(NodeRole.Repeater).map { it.id }.toSet()
        // Repeater-specific present:
        assertTrue("repeat" in repeater)
        assertTrue("flood.max" in repeater)
        assertTrue("neighbors" in repeater)
        assertTrue("bridge.enabled" in repeater)
        // Room-only absent:
        assertTrue("allow.read.only" !in repeater)
        // Common present:
        assertTrue("ver" in repeater)
        assertTrue("guest.password" in repeater)
    }

    @Test
    fun roomSeesRoomCommandsOnly() {
        val room = CliCatalog.forRole(NodeRole.Room).map { it.id }.toSet()
        // Room-specific present:
        assertTrue("allow.read.only" in room)
        assertTrue("guest.password" in room)
        // Repeater-only absent:
        assertTrue("repeat" !in room)
        assertTrue("flood.max" !in room)
        assertTrue("neighbors" !in room)
        assertTrue("bridge.enabled" !in room)
        assertTrue("loop.detect" !in room)
        // Common present:
        assertTrue("ver" in room)
        assertTrue("advert.interval" in room)
    }

    @Test
    fun companionEntriesAllMapToCompanionFrames() {
        // Companion radios don't run the text CLI: every Companion-tagged
        // entry must document its companion-protocol equivalent (the
        // Settings screen implements those), so the UI never sends CLI
        // text to a companion node.
        val companion = CliCatalog.forRole(NodeRole.Companion)
        assertTrue(companion.isNotEmpty())
        for (c in companion) {
            assertNotNull(
                c.companionEquivalent,
                "Companion-tagged '${c.id}' lacks a companion-frame equivalent",
            )
        }
        // And CLI-only commands must NOT be companion-tagged.
        for (id in listOf("password", "guest.password", "erase", "board", "neighbors")) {
            assertTrue(
                NodeRole.Companion !in CliCatalog.byId(id)!!.roles,
                "'$id' is CLI-only and must not be companion-tagged",
            )
        }
    }

    @Test
    fun everyCommandHasAtLeastOneCliRole() {
        // Companion alone would be unreachable — nothing would display it.
        for (c in CliCatalog.all) {
            assertTrue(
                NodeRole.Repeater in c.roles || NodeRole.Room in c.roles,
                "'${c.id}' reachable by no CLI-speaking role",
            )
        }
    }

    @Test
    fun groupingPreservesCatalogOrderWithinCategories() {
        val flat = CliCatalog.forRole(NodeRole.Repeater)
        val grouped = CliCatalog.forRoleByCategory(NodeRole.Repeater)
        assertTrue(grouped.isNotEmpty())
        // Same command set, nothing lost or invented.
        assertEquals(flat.toSet(), grouped.values.flatten().toSet())
        // Within each category, order follows the catalog.
        for ((category, commands) in grouped) {
            assertEquals(flat.filter { it.category == category }, commands)
        }
        // Categories appear in first-appearance order.
        assertEquals(flat.map { it.category }.distinct(), grouped.keys.toList())
    }

    // ------------------------------------------------------------------
    // Safety metadata the UI depends on
    // ------------------------------------------------------------------

    @Test
    fun guestSessionsSeeOnlyReadCommands() {
        val guest = CliCatalog.forRole(NodeRole.Repeater, admin = false)
        assertTrue(guest.isNotEmpty())
        // Nothing that changes node state.
        for (c in guest) {
            assertTrue(!c.adminOnly, "'${'$'}{c.id}' is admin-only but offered to guests")
            assertTrue(c.kind == CliKind.GetOnly || c.kind == CliKind.Action)
        }
        val ids = guest.map { it.id }.toSet()
        // Reads survive…
        assertTrue("ver" in ids)
        assertTrue("role" in ids)
        assertTrue("acl" in ids)
        assertTrue("clock" in ids)
        // …mutations don't.
        for (id in listOf("password", "prv.key", "reboot", "erase", "freq", "name", "advert")) {
            assertTrue(id !in ids, "'${'$'}id' must not be offered to guests")
        }
        // Guest view is a strict subset of the admin view.
        assertTrue(CliCatalog.forRole(NodeRole.Repeater).map { it.id }.containsAll(ids))
    }

    @Test
    fun adminOnlyFlagCoversMutations() {
        assertTrue(CliCatalog.byId("freq")!!.adminOnly)        // get/set
        assertTrue(CliCatalog.byId("time")!!.adminOnly)        // action w/ arg
        assertTrue(CliCatalog.byId("reboot")!!.adminOnly)      // destructive
        assertTrue(CliCatalog.byId("advert")!!.adminOnly)      // bare but mutating
        assertTrue(!CliCatalog.byId("ver")!!.adminOnly)
        assertTrue(!CliCatalog.byId("public.key")!!.adminOnly)
    }

    @Test
    fun secretCarryingCommandsAreMarkedSensitive() {
        for (id in listOf("password", "guest.password", "prv.key", "bridge.secret")) {
            assertTrue(CliCatalog.byId(id)!!.sensitive, "'$id' must be sensitive")
        }
        // And ordinary ones aren't.
        assertTrue(!CliCatalog.byId("freq")!!.sensitive)
        assertTrue(!CliCatalog.byId("name")!!.sensitive)
    }

    @Test
    fun destructiveCommandsRequireConfirmation() {
        for (id in listOf("reboot", "erase", "start ota", "log erase", "clear stats", "prv.key")) {
            assertTrue(CliCatalog.byId(id)!!.requiresConfirm, "'$id' must require confirm")
        }
        assertTrue(!CliCatalog.byId("ver")!!.requiresConfirm)
    }

    @Test
    fun argHintPresentWhereverAnArgumentIsNeeded() {
        for (c in CliCatalog.all) {
            if (c.kind == CliKind.GetSet || c.kind == CliKind.ActionWithArg) {
                assertNotNull(c.argHint, "'${c.id}' needs an argHint")
            }
        }
    }
}
