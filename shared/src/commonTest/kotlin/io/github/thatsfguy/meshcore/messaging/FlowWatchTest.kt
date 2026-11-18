package io.github.thatsfguy.meshcore.messaging

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FlowWatchTest {

    @Test
    fun aStateFlowDeliversItsCurrentValueThenChanges() = runTest {
        val state = MutableStateFlow(1)
        val seen = mutableListOf<Int>()
        val w = watch(state, this) { seen += it }
        runCurrent()
        state.value = 2
        runCurrent()
        assertEquals(listOf(1, 2), seen)
        w.cancel()
    }

    @Test
    fun anEventFlowDeliversEveryEvent() = runTest {
        // The reason this exists: polling a value can never see an event.
        val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val seen = mutableListOf<String>()
        val w = watch(events, this) { seen += it }
        runCurrent()
        events.emit("a"); events.emit("b")
        runCurrent()
        assertEquals(listOf("a", "b"), seen)
        w.cancel()
    }

    @Test
    fun cancellingStopsDelivery() = runTest {
        val state = MutableStateFlow(1)
        val seen = mutableListOf<Int>()
        val w = watch(state, this) { seen += it }
        runCurrent()
        w.cancel()
        state.value = 2
        runCurrent()
        assertEquals(listOf(1), seen)
        assertFalse(w.isActive)
    }

    @Test
    fun aThrowingCallbackEndsOnlyItsOwnWatch() = runTest {
        val state = MutableStateFlow(1)
        val other = mutableListOf<Int>()
        val bad = watch(state, this) { error("boom") }
        val good = watch(state, this) { other += it }
        runCurrent()
        state.value = 2
        runCurrent()
        assertFalse(bad.isActive)
        assertEquals(listOf(1, 2), other)
        good.cancel()
    }
}
