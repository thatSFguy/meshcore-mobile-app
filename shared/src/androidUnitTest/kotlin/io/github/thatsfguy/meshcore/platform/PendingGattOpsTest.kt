package io.github.thatsfguy.meshcore.platform

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The registry that keeps a BLE connect from outliving its link.
 *
 * Every test here is the same scenario in a different slot: the radio
 * goes out of range mid-connect, so the ONLY callback Android delivers
 * is the disconnect. Anything still parked on a success callback that
 * is no longer coming must be failed, or `connect()` never returns and
 * the reconnect supervisor sits behind it while the radio is walked
 * back into range.
 */
class PendingGattOpsTest {

    @Test
    fun aDisconnectFailsAStepParkedOnMtuNegotiation() = runTest {
        val ops = PendingGattOps()
        val awaiting = async {
            assertFailsWith<IllegalStateException> {
                ops.await<Int>(PendingGattOps.Slot.Mtu) {}
            }
        }
        runCurrentAndAssertParked(ops, PendingGattOps.Slot.Mtu)

        ops.failAll(IllegalStateException("BLE disconnected before ready (status=8)"))

        assertEquals(
            "BLE disconnected before ready (status=8)",
            awaiting.await().message,
        )
        assertTrue(ops.outstanding().isEmpty())
    }

    @Test
    fun aDisconnectFailsAStepParkedOnTheCccdWrite() = runTest {
        val ops = PendingGattOps()
        val awaiting = async {
            assertFailsWith<IllegalStateException> {
                ops.await<Unit>(PendingGattOps.Slot.Descriptor) {}
            }
        }
        runCurrentAndAssertParked(ops, PendingGattOps.Slot.Descriptor)

        ops.failAll(IllegalStateException("gone"))

        assertEquals("gone", awaiting.await().message)
    }

    @Test
    fun aDisconnectFailsAStepParkedOnServiceDiscovery() = runTest {
        val ops = PendingGattOps()
        val awaiting = async {
            assertFailsWith<IllegalStateException> {
                ops.await<Unit>(PendingGattOps.Slot.Services) {}
            }
        }
        runCurrentAndAssertParked(ops, PendingGattOps.Slot.Services)

        ops.failAll(IllegalStateException("gone"))

        assertEquals("gone", awaiting.await().message)
    }

    /**
     * The one that carries the suite. A registry that failed only the
     * slot someone remembered would pass the three tests above and
     * still hang the app: what matters is that a single sweep clears
     * EVERY slot, including ones added later.
     */
    @Test
    fun failAllClearsEverySlotThereIs() = runTest {
        val ops = PendingGattOps()
        val results = mutableMapOf<PendingGattOps.Slot, Throwable?>()
        for (slot in PendingGattOps.Slot.entries) {
            launch {
                results[slot] = runCatching { ops.await<Any?>(slot) {} }.exceptionOrNull()
            }
        }
        testScheduler.runCurrent()
        assertEquals(PendingGattOps.Slot.entries.toSet(), ops.outstanding())

        ops.failAll(IllegalStateException("link torn down"))
        testScheduler.runCurrent()

        assertEquals(PendingGattOps.Slot.entries.size, results.size)
        for (slot in PendingGattOps.Slot.entries) {
            assertNotNull(results[slot], "$slot was left parked by failAll")
        }
    }

    /**
     * The firmware-update jump is the deliberate exception: the radio
     * disconnects BECAUSE it took the command, so that slot is resumed
     * successfully before the sweep. Proven end to end on hardware
     * 2026-08-14, so it must survive this refactor unchanged.
     */
    @Test
    fun theBootloaderJumpTreatsTheDisconnectAsItsAcknowledgement() = runTest {
        val ops = PendingGattOps()
        val jump = async { ops.await<Unit>(PendingGattOps.Slot.CharacteristicWrite) {} }
        val discovery = async {
            runCatching { ops.await<Unit>(PendingGattOps.Slot.Services) {} }.exceptionOrNull()
        }
        testScheduler.runCurrent()

        // Exactly what onConnectionStateChange(DISCONNECTED) does.
        ops.succeed(PendingGattOps.Slot.CharacteristicWrite, Unit)
        ops.failAll(IllegalStateException("BLE disconnected before ready (status=19)"))

        jump.await() // completes normally — no exception
        assertNotNull(discovery.await())
    }

    @Test
    fun aStepThatFailsSynchronouslyDoesNotLeaveItsSlotParked() = runTest {
        val ops = PendingGattOps()
        val thrown = assertFailsWith<IllegalStateException> {
            ops.await<Unit>(PendingGattOps.Slot.Descriptor) {
                throw IllegalStateException("writeDescriptor returned false")
            }
        }
        assertEquals("writeDescriptor returned false", thrown.message)
        assertTrue(
            ops.outstanding().isEmpty(),
            "a slot left behind here would be resumed by the NEXT attempt's callback",
        )
    }

    /**
     * `start` can be answered before it returns — the GATT callback
     * arrives on a binder thread. Registering first is what makes that
     * safe; resolving inside `start` must still be delivered.
     */
    @Test
    fun aCallbackThatLandsInsideStartIsNotLost() = runTest {
        val ops = PendingGattOps()
        val mtu = ops.await<Int>(PendingGattOps.Slot.Mtu) {
            ops.succeed(PendingGattOps.Slot.Mtu, 247)
        }
        assertEquals(247, mtu)
        assertTrue(ops.outstanding().isEmpty())
    }

    @Test
    fun succeedingAnEmptySlotIsHarmless() = runTest {
        val ops = PendingGattOps()
        ops.succeed(PendingGattOps.Slot.CharacteristicWrite, Unit)
        ops.fail(PendingGattOps.Slot.Mtu, IllegalStateException("x"))
        ops.failAll(IllegalStateException("y"))
        assertTrue(ops.outstanding().isEmpty())
    }

    /**
     * Ordinary frames go out no-response and never register, so a
     * disconnect must not conjure a resume for a write nobody awaited.
     */
    @Test
    fun aSecondAwaitOnOneSlotDoesNotStrandTheFirst() = runTest {
        val ops = PendingGattOps()
        val first = async {
            runCatching { ops.await<Unit>(PendingGattOps.Slot.Descriptor) {} }.exceptionOrNull()
        }
        testScheduler.runCurrent()
        val second = async {
            runCatching { ops.await<Unit>(PendingGattOps.Slot.Descriptor) {} }.exceptionOrNull()
        }
        testScheduler.runCurrent()

        assertNotNull(first.await(), "the superseded await must not hang")
        ops.failAll(IllegalStateException("gone"))
        assertEquals("gone", second.await()?.message)
    }

    @Test
    fun cancellingAnAwaitReleasesItsSlot() = runTest {
        val ops = PendingGattOps()
        val timedOut = withTimeoutOrNull(50) {
            ops.await<Unit>(PendingGattOps.Slot.Services) {}
        }
        assertEquals(null, timedOut)
        assertTrue(
            ops.outstanding().isEmpty(),
            "a cancelled step must release its slot or the next attempt's callback goes nowhere",
        )
    }

    private fun kotlinx.coroutines.test.TestScope.runCurrentAndAssertParked(
        ops: PendingGattOps,
        slot: PendingGattOps.Slot,
    ) {
        testScheduler.runCurrent()
        assertEquals(setOf(slot), ops.outstanding())
    }
}
