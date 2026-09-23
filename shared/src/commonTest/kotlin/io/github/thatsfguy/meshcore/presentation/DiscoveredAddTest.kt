package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine.AddOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveredAddTest {

    @Test
    fun everyOutcomeSaysSomethingDifferent() {
        // The old code had one message for every failure, and it blamed
        // the signature for all of them.
        val messages = AddOutcome.entries.map { DiscoveredAdd.message(it, "Node") }
        assertEquals(messages.size, messages.toSet().size)
    }

    @Test
    fun aFullRadioIsNotBlamedOnTheSignature() {
        val full = DiscoveredAdd.message(AddOutcome.TableFull, "Node")
        assertTrue("full" in full)
        assertFalse("signature" in full)
        assertFalse("verify" in full)
    }

    @Test
    fun successNamesTheNode() {
        assertEquals("Added Kent Hill", DiscoveredAdd.message(AddOutcome.Added, "Kent Hill"))
    }
}
