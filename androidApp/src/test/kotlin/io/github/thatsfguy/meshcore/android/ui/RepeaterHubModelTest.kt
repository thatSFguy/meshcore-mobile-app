package io.github.thatsfguy.meshcore.android.ui

import io.github.thatsfguy.meshcore.android.ui.screens.AdminSession
import io.github.thatsfguy.meshcore.android.ui.screens.cliHelpSummary
import io.github.thatsfguy.meshcore.android.ui.screens.decodePrefill
import io.github.thatsfguy.meshcore.android.ui.screens.encodePrefill
import io.github.thatsfguy.meshcore.android.ui.screens.repeaterHubTiles
import io.github.thatsfguy.meshcore.android.ui.screens.repeaterRoleLabel
import io.github.thatsfguy.meshcore.android.ui.screens.usageOf
import io.github.thatsfguy.meshcore.protocol.CliCatalog
import io.github.thatsfguy.meshcore.protocol.NodeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repeater hub's tool gating, and the route argument that carries a
 * command from Command help to the Console.
 *
 * The gating is the part worth pinning: it replaced an implicit rule
 * spread across six tab bodies, and the interesting cases are the ones
 * where a tool must be WITHHELD — which is exactly the shape that
 * passes when the feature does nothing (LESSONS §10), so the positive
 * controls come first.
 */
class RepeaterHubModelTest {

    private fun routes(role: NodeRole, session: AdminSession) =
        repeaterHubTiles(role, session).map { it.route }

    // --- positive controls: the tools must actually appear ---------------

    @Test
    fun `an admin on a repeater is offered every tool`() {
        assertEquals(
            listOf("status", "settings", "regions", "identity", "console", "help"),
            routes(NodeRole.Repeater, AdminSession.Admin),
        )
    }

    @Test
    fun `a guest is offered the read-only tools`() {
        val guest = routes(NodeRole.Repeater, AdminSession.Guest)
        assertTrue("status" in guest)
        assertTrue("settings" in guest)
        assertTrue("regions" in guest)
    }

    // --- the withholding rules ------------------------------------------

    @Test
    fun `a guest is not offered the tools that need admin`() {
        val guest = routes(NodeRole.Repeater, AdminSession.Guest)
        // Identity edits keys and Console runs `erase`/`set prv.key`.
        // A guest session cannot run either, so offering them would be
        // a control that exists only to fail (PLAYBOOK §6.1).
        assertFalse("identity" in guest)
        assertFalse("console" in guest)
    }

    @Test
    fun `no session offers nothing that talks to the node`() {
        for (role in NodeRole.entries) {
            val none = routes(role, AdminSession.None)
            // Command help is a local catalogue — no node round-trip.
            assertEquals(listOf("help"), none)
        }
    }

    @Test
    fun `regions are repeater-only`() {
        // A room server and a sensor do not run the `region` CLI, so the
        // tile would open a screen whose every request the node refuses.
        for (role in listOf(NodeRole.Room, NodeRole.Sensor, NodeRole.Companion)) {
            assertFalse(
                "regions offered to $role",
                "regions" in routes(role, AdminSession.Admin),
            )
        }
        assertTrue("regions" in routes(NodeRole.Repeater, AdminSession.Admin))
    }

    @Test
    fun `command help is reachable in every state`() {
        for (role in NodeRole.entries) {
            for (session in AdminSession.entries) {
                assertTrue(
                    "help missing for $role/$session",
                    "help" in routes(role, session),
                )
            }
        }
    }

    @Test
    fun `no tile is ever listed twice`() {
        for (role in NodeRole.entries) {
            for (session in AdminSession.entries) {
                val r = routes(role, session)
                assertEquals("duplicate tile for $role/$session", r.size, r.distinct().size)
            }
        }
    }

    @Test
    fun `a guest sees read-only stated once, on the tools it applies to`() {
        // PLAYBOOK §6.3 budgets one line per control. The grant is on the
        // chip; the tiles say it only where it changes what you can do.
        val guest = repeaterHubTiles(NodeRole.Repeater, AdminSession.Guest)
        val admin = repeaterHubTiles(NodeRole.Repeater, AdminSession.Admin)
        for (tile in guest.filter { it.route in setOf("settings", "regions") }) {
            assertTrue(
                "${tile.route} does not say it is read-only",
                tile.subtitle.endsWith("read-only"),
            )
        }
        assertTrue(admin.none { it.subtitle.contains("read-only") })
        // Every subtitle is one line's worth of text, not a paragraph.
        for (tile in guest + admin) {
            assertTrue("${tile.route} subtitle too long", tile.subtitle.length <= 72)
            assertFalse("${tile.route} subtitle has two sentences", tile.subtitle.contains(". "))
        }
    }

    // --- session semantics ----------------------------------------------

    @Test
    fun `None is not a guest`() {
        // The bug this type exists to prevent: treating "we have never
        // asked" as "the node said read-only".
        assertFalse(AdminSession.None.signedIn)
        assertTrue(AdminSession.Guest.signedIn)
        assertTrue(AdminSession.Admin.signedIn)
        assertFalse(AdminSession.Guest.isAdmin)
        assertTrue(AdminSession.Admin.isAdmin)
    }

    @Test
    fun `role labels name the thing being administered`() {
        assertEquals("Repeater", repeaterRoleLabel(NodeRole.Repeater))
        assertEquals("Room server", repeaterRoleLabel(NodeRole.Room))
        assertEquals("Sensor", repeaterRoleLabel(NodeRole.Sensor))
    }

    // --- console prefill transport ---------------------------------------

    @Test
    fun `every real command usage survives the trip to the console`() {
        // The whole catalogue, not an example: these strings contain
        // spaces, angle brackets and slashes, and they are the only
        // things that ever go through this encoding.
        var checked = 0
        for (command in CliCatalog.all) {
            val usage = usageOf(command)
            assertEquals(usage, decodePrefill(encodePrefill(usage)))
            checked++
        }
        assertTrue("catalogue was empty", checked > 0)
    }

    @Test
    fun `the encoding has no characters a route can misread`() {
        for (command in CliCatalog.all) {
            val encoded = encodePrefill(usageOf(command))
            assertTrue(
                "unsafe route argument: $encoded",
                encoded.all { it in '0'..'9' || it in 'a'..'f' },
            )
        }
    }

    @Test
    fun `hostile prefill arguments decode to nothing rather than garbage`() {
        // This value arrives from a route, so it is not trusted.
        assertEquals("", decodePrefill(""))
        assertEquals("", decodePrefill("abc"))          // odd length
        assertEquals("", decodePrefill("zz"))           // not hex
        assertEquals("", decodePrefill("6g"))           // half not hex
        assertEquals("", decodePrefill("../../etc"))    // path traversal
        assertEquals("", decodePrefill("%20"))          // percent-encoding
    }

    @Test
    fun `a non-ascii command round-trips`() {
        val name = "set name Café–Repeater"
        assertEquals(name, decodePrefill(encodePrefill(name)))
    }

    // --- command help copy ------------------------------------------------

    @Test
    fun `command help does not call an unsigned session a guest`() {
        // "Guest" is something the node says, not a default. Reaching
        // Command help without signing in must not report a grant.
        val none = cliHelpSummary(12, NodeRole.Repeater, AdminSession.None)
        assertFalse(none.contains("guest"))
        assertTrue(none.contains("12 commands this repeater accepts"))

        assertEquals(
            "12 commands this repeater accepts as a guest.",
            cliHelpSummary(12, NodeRole.Repeater, AdminSession.Guest),
        )
        assertEquals(
            "12 commands this repeater accepts.",
            cliHelpSummary(12, NodeRole.Repeater, AdminSession.Admin),
        )
    }

    @Test
    fun `a single command result is not called "1 commands"`() {
        // Searching Command help on a live repeater narrowed the
        // catalogue to one entry and the header read "1 commands".
        assertTrue(
            cliHelpSummary(1, NodeRole.Repeater, AdminSession.Admin)
                .startsWith("1 command this"),
        )
        assertTrue(
            cliHelpSummary(0, NodeRole.Repeater, AdminSession.Admin)
                .startsWith("0 commands this"),
        )
        assertTrue(
            cliHelpSummary(65, NodeRole.Repeater, AdminSession.Admin)
                .startsWith("65 commands this"),
        )
    }

    @Test
    fun `command help names the node type it is describing`() {
        assertTrue(
            cliHelpSummary(1, NodeRole.Room, AdminSession.Admin).contains("room server"),
        )
        assertTrue(
            cliHelpSummary(1, NodeRole.Sensor, AdminSession.Admin).contains("sensor"),
        )
    }
}
