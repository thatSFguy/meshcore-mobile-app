package io.github.thatsfguy.meshcore.android.ui

import io.github.thatsfguy.meshcore.presentation.AdminSession
import io.github.thatsfguy.meshcore.presentation.decodePrefill
import io.github.thatsfguy.meshcore.presentation.encodePrefill
import io.github.thatsfguy.meshcore.presentation.repeaterHubTiles
import io.github.thatsfguy.meshcore.presentation.repeaterRoleLabel
import io.github.thatsfguy.meshcore.presentation.showsGuestQueries
import io.github.thatsfguy.meshcore.presentation.floodRegionsRoute
import io.github.thatsfguy.meshcore.presentation.floodRegionsSummary
import io.github.thatsfguy.meshcore.protocol.Regions
import org.junit.Assert.assertNull
import io.github.thatsfguy.meshcore.android.ui.screens.cliHelpSummary
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
            listOf("status", "settings", "regions", "identity", "console", "firmware", "help"),
            routes(NodeRole.Repeater, AdminSession.Admin),
        )
    }

    @Test
    fun `a guest is offered what the node answers a guest`() {
        // Status is a binary request the repeater answers for guests
        // (MyMesh.cpp:219). Nothing else: no CLI, so no Command help.
        assertEquals(
            listOf("status"),
            routes(NodeRole.Repeater, AdminSession.Guest),
        )
    }

    @Test
    fun `a guest on a repeater gets the guest card`() {
        assertTrue(showsGuestQueries(NodeRole.Repeater, AdminSession.Guest))
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
        // Firmware sends `start ota`, which takes the node off the mesh
        // until someone stands next to it. A guest cannot run it.
        assertFalse("firmware" in guest)
    }

    @Test
    fun `a guest is not offered the screens that read over CLI`() {
        // The defect this pins: Settings and Regions were offered to
        // guests as "read-only", and every value stayed empty — the
        // repeater runs CLI text from admins only, reads included
        // (simple_repeater/MyMesh.cpp:689, `client->isAdmin()`).
        for (role in NodeRole.entries) {
            val guest = routes(role, AdminSession.Guest)
            assertFalse("settings offered to a $role guest", "settings" in guest)
            assertFalse("regions offered to a $role guest", "regions" in guest)
        }
        // And an admin still gets both — the rule withholds, it does not delete.
        val admin = routes(NodeRole.Repeater, AdminSession.Admin)
        assertTrue("settings" in admin)
        assertTrue("regions" in admin)
    }

    @Test
    fun `the guest card is only where it will be answered and needed`() {
        // Repeater-only: room servers and sensors handle neither
        // REQ_TYPE_GET_OWNER_INFO nor ANON_REQ_TYPE_REGIONS. Admin sees
        // both, and more, in Settings and Regions, and
        // no session means no request at all.
        for (role in listOf(NodeRole.Room, NodeRole.Sensor, NodeRole.Companion)) {
            for (session in AdminSession.entries) {
                assertFalse("$role/$session", showsGuestQueries(role, session))
            }
        }
        assertFalse(showsGuestQueries(NodeRole.Repeater, AdminSession.Admin))
        assertFalse(showsGuestQueries(NodeRole.Repeater, AdminSession.None))
    }

    @Test
    fun `no session offers nothing`() {
        for (role in NodeRole.entries) {
            assertEquals(emptyList<String>(), routes(role, AdminSession.None))
        }
    }

    @Test
    fun `every infrastructure role has regions`() {
        // The `region` CLI is in CommonCLI, which a room server and a
        // sensor run too (CommonCLI.cpp:320), and both forward packets
        // unless `repeat off`. This test used to assert the opposite.
        for (role in listOf(NodeRole.Repeater, NodeRole.Room, NodeRole.Sensor)) {
            assertTrue("regions withheld from $role", "regions" in routes(role, AdminSession.Admin))
        }
        assertFalse("regions" in routes(NodeRole.Companion, AdminSession.Admin))
        // Still admin-only: it is CLI.
        assertFalse("regions" in routes(NodeRole.Room, AdminSession.Member))
    }

    @Test
    fun `command help goes where the console goes`() {
        // Help documents the console. Without CLI access it would list
        // commands the node drops, so it is offered exactly when the
        // console is.
        for (role in NodeRole.entries) {
            for (session in AdminSession.entries) {
                val r = routes(role, session)
                assertEquals("$role/$session", "console" in r, "help" in r)
            }
        }
        assertTrue("help" in routes(NodeRole.Repeater, AdminSession.Admin))
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
    fun `no tile claims to be read-only`() {
        // There is no read-only CLI tool left to label: a guest cannot
        // read over CLI at all, so "— read-only" would promise data.
        for (session in AdminSession.entries) {
            for (tile in repeaterHubTiles(NodeRole.Repeater, session)) {
                assertFalse("${tile.route} says read-only", tile.subtitle.contains("read-only"))
                // One line's worth of text, not a paragraph.
                assertTrue("${tile.route} subtitle too long", tile.subtitle.length <= 72)
                assertFalse("${tile.route} subtitle has two sentences", tile.subtitle.contains(". "))
            }
        }
    }

    // --- the guest card's regions request ----------------------------------

    @Test
    fun `no stored route means the regions request is not sent`() {
        // The firmware answers it only when it arrives direct
        // (MyMesh.cpp:584); the companion floods it without a path. Both
        // spellings of "flood" the radio uses.
        assertNull(floodRegionsRoute(0xFF))
        assertNull(floodRegionsRoute(-1))
    }

    @Test
    fun `a stored route is used at its own width`() {
        // Positive controls. Zero hops is a direct send with no path.
        assertEquals(0, floodRegionsRoute(0)?.hops)
        // 0x44 is a real captured path_len: 4 hops at 2-byte hashes. A
        // width read as 1 here would make 4 hops look like 68 bytes of
        // nonsense — the recurring defect in this codebase.
        val real = floodRegionsRoute(0x44)
        assertEquals(4, real?.hops)
        assertEquals(2, real?.hashWidth)
        assertEquals(8, real?.byteLength)
    }

    @Test
    fun `the summary says floods, never has`() {
        // A region set to deny flooding is missing from the answer, not
        // from the repeater. Every wording must keep that distinction.
        val cases = listOf(
            Regions.FloodList(listOf("grr", "kent"), untaggedFloods = true) to
                "Floods grr, kent, and untagged traffic.",
            Regions.FloodList(listOf("grr"), untaggedFloods = false) to
                "Floods grr. Untagged traffic is not flooded.",
            Regions.FloodList(emptyList(), untaggedFloods = true) to
                "Floods untagged traffic only — no named regions.",
            Regions.FloodList(emptyList(), untaggedFloods = false) to
                "Floods no regions, and not untagged traffic.",
        )
        for ((list, expected) in cases) {
            val text = floodRegionsSummary(list)
            assertEquals(expected, text)
            assertFalse("'$text' claims possession", Regex("\\bhas\\b").containsMatchIn(text))
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

        // Not "accepts as a guest": the node takes no CLI from a guest.
        assertEquals(
            "12 commands this repeater accepts from an admin — none from a guest session.",
            cliHelpSummary(12, NodeRole.Repeater, AdminSession.Guest),
        )
        assertFalse(
            cliHelpSummary(12, NodeRole.Repeater, AdminSession.Guest).contains("as a guest"),
        )
        assertEquals(
            "12 commands this repeater accepts.",
            cliHelpSummary(12, NodeRole.Repeater, AdminSession.Admin),
        )
    }

    @Test
    fun `command help calls a room member a member, not a guest`() {
        // A room member may post; "guest" would repeat the read-only claim.
        assertEquals(
            "12 commands this room server accepts from an admin — none from a member session.",
            cliHelpSummary(12, NodeRole.Room, AdminSession.Member),
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
