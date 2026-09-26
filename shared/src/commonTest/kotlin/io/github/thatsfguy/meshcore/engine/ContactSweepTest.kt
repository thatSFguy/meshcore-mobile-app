package io.github.thatsfguy.meshcore.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactSweepTest {

    @Test
    fun `only a read that delivered the radio's count is complete`() {
        assertTrue(ContactSweep.isComplete(delivered = 177, declared = 177))
        assertTrue(ContactSweep.isComplete(delivered = 0, declared = 0))
        // A changed-only read: a handful of 177.
        assertFalse(ContactSweep.isComplete(delivered = 3, declared = 177))
        // More than declared is not a complete read either.
        assertFalse(ContactSweep.isComplete(delivered = 178, declared = 177))
    }

    @Test
    fun `without a count nothing is ever complete`() {
        // Forgetting contacts needs proof; no count is no proof.
        assertFalse(ContactSweep.isComplete(delivered = 5, declared = null))
        assertFalse(ContactSweep.isComplete(delivered = 0, declared = -1))
    }
}
