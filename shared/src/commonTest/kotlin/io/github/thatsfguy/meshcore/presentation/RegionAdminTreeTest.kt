package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.CliExchange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegionAdminTreeTest {

    @Test
    fun aRefusalOrSilenceStopsTheSequence() {
        assertTrue(RegionAdmin.treeStepRefused(null))
        assertTrue(RegionAdmin.treeStepRefused("Err - ??")) // firmware before 1.16
        assertTrue(RegionAdmin.treeStepRefused("Err - unknown jump: x"))
        assertTrue(RegionAdmin.treeStepRefused("Err - save failed"))
        assertTrue(RegionAdmin.treeStepRefused("??: region"))
        assertFalse(RegionAdmin.treeStepRefused("*\n midwest F\n  mi F"))
        assertFalse(RegionAdmin.treeStepRefused(" default scope is now mi"))
        assertFalse(RegionAdmin.treeStepRefused("OK"))
    }

    @Test
    fun theTreeReplyIsNotMistakenForADefaultScopeReply() {
        // `region def` looks like `region default` and is not: it answers
        // with the tree. The matcher first took it for the other command.
        val def = "region def midwest mi west grr"
        assertTrue(CliExchange.accepts(def, "*\n midwest F\n  mi F"))
        assertFalse(CliExchange.accepts(def, " default scope is now mi"))
        assertFalse(CliExchange.accepts(def, "OK"))
        assertTrue(CliExchange.accepts("region default mi", " default scope is now mi"))
        assertFalse(CliExchange.isRead(def))
    }
}
