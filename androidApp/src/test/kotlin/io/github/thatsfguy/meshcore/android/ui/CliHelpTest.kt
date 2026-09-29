package io.github.thatsfguy.meshcore.android.ui

import io.github.thatsfguy.meshcore.android.ui.screens.usageOf
import io.github.thatsfguy.meshcore.protocol.CliCatalog
import io.github.thatsfguy.meshcore.protocol.CliKind
import io.github.thatsfguy.meshcore.protocol.NodeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The command reference shown in repeater/room administration. */
class CliHelpTest {

    @Test
    fun `usage lines are what you would actually type`() {
        val get = CliCatalog.all.first { it.kind == CliKind.GetOnly }
        assertEquals("get ${get.id}", usageOf(get))

        val set = CliCatalog.all.first { it.kind == CliKind.GetSet }
        assertTrue(usageOf(set).startsWith("set ${set.id} "))

        val action = CliCatalog.all.first { it.kind == CliKind.Action }
        assertEquals(action.id, usageOf(action))
    }

    @Test
    fun `a set command always shows an argument placeholder`() {
        for (command in CliCatalog.all.filter { it.kind == CliKind.GetSet }) {
            val usage = usageOf(command)
            assertTrue("no placeholder in '$usage'", usage.contains("<"))
        }
    }

    @Test
    fun `every listed command belongs to the role it is listed under`() {
        for (role in NodeRole.entries) {
            for (command in CliCatalog.forRole(role)) {
                assertTrue(
                    "${command.id} listed under $role but not declared for it",
                    role in command.roles,
                )
            }
        }
    }

    @Test
    fun `the reference is the same catalogue whoever is looking`() {
        // There is no guest subset to show: over the air a guest runs no
        // CLI at all (simple_repeater/MyMesh.cpp:689). The old filter
        // offered guests `get` commands the node then dropped.
        for (role in listOf(NodeRole.Repeater, NodeRole.Room)) {
            val listed = CliCatalog.forRole(role).map { it.id }.toSet()
            assertEquals(CliCatalog.all.filter { role in it.roles }.map { it.id }.toSet(), listed)
            assertTrue("reads missing for $role", listed.any { !CliCatalog.byId(it)!!.changesState })
        }
    }

    @Test
    fun `categories partition the command list without loss`() {
        for (role in NodeRole.entries) {
            val flat = CliCatalog.forRole(role).map { it.id }.sorted()
            val grouped = CliCatalog.forRoleByCategory(role)
                .values.flatten().map { it.id }.sorted()
            assertEquals(flat, grouped)
        }
    }

    @Test
    fun `secrets and destructive commands are flagged for the UI to warn on`() {
        // The help panel badges these; if the catalog stops marking them
        // the badges silently disappear.
        assertTrue(CliCatalog.all.any { it.sensitive })
        assertTrue(CliCatalog.all.any { it.requiresConfirm })
        assertTrue(CliCatalog.all.first { it.id == "prv.key" }.sensitive)
    }
}
