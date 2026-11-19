package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.presentation.RepeaterSignals
import io.github.thatsfguy.meshcore.protocol.Codes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepeaterAutoAddTest {
    private var clock = 10_000_000L
    private val relay = "b389" + "00".repeat(30)

    private class Radio(var flags: Int? = 0, var outcome: MeshCoreEngine.AddOutcome = MeshCoreEngine.AddOutcome.Added) : RepeaterAutoAdd.Radio {
        val added = mutableListOf<String>()
        override val contactKeys: Set<String> = emptySet()
        override val autoAddFlags: Int? get() = flags
        override val contactsFull: Boolean = false
        override suspend fun addFromAdvert(advert: ByteArray): MeshCoreEngine.AddOutcome { added += advert.size.toString(); return outcome }
    }

    /** Three received messages routed through the relay's 2-byte hash — the MIN_RELAYS bar. */
    private fun book(): MessageBook {
        val b = MessageBook(nowMs = { clock })
        repeat(RepeaterSignals.MIN_RELAYS) { i ->
            b.receive("ch|0", true, "Bob", "m$i", i.toLong(), "k$i", arrivalPathHex = "b389c985", arrivalHashWidth = 2)
        }
        return b
    }

    private fun heard(): DiscoveredBook = DiscoveredBook({ clock }).apply {
        heard(relay, "Ridge", Codes.ADV_TYPE_REPEATER, null, null, 1.0, -90, ByteArray(8) { 1 }, hops = 2)
    }

    @Test
    fun aRepeaterThatRelayedMyTrafficIsAddedAndLeavesTheInbox() = runTest {
        val radio = Radio()
        val inbox = heard()
        val log = RepeaterAutoAdd(radio, { clock }).run(book(), inbox, RepeaterSignals.Rules(relaysMyTraffic = true, heardDirect = false))
        assertEquals(1, radio.added.size)
        assertTrue(log.single().contains("b389"), log.toString())
        assertNull(inbox.get(relay), "added, so no longer only heard")
    }

    @Test
    fun nothingWhenTheRadioAddsRepeatersItselfOrNoRuleIsOn() = runTest {
        val radio = Radio(flags = Codes.AUTO_ADD_REPEATER)
        RepeaterAutoAdd(radio, { clock }).run(book(), heard(), RepeaterSignals.Rules(true, true))
        assertTrue(radio.added.isEmpty())
        val off = Radio()
        RepeaterAutoAdd(off, { clock }).run(book(), heard(), RepeaterSignals.Rules(false, false))
        assertTrue(off.added.isEmpty())
    }

    @Test
    fun aFailedNodeIsNotRetriedAndAThrottledPassDoesNothing() = runTest {
        val radio = Radio(outcome = MeshCoreEngine.AddOutcome.Failed)
        val pass = RepeaterAutoAdd(radio, { clock })
        val b = book()
        val inbox = heard()
        val rules = RepeaterSignals.Rules(true, false)
        pass.run(b, inbox, rules)
        pass.run(b, inbox, rules)
        assertEquals(1, radio.added.size, "tried once this session")
        val fresh = Radio()
        val throttled = RepeaterAutoAdd(fresh, { clock })
        throttled.run(MessageBook(nowMs = { clock }), heard(), rules)
        throttled.run(b, heard(), rules, throttle = true)
        assertTrue(fresh.added.isEmpty(), "within a minute of the last pass")
    }
}
