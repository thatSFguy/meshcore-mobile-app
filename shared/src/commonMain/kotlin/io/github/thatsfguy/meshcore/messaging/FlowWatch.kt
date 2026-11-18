package io.github.thatsfguy.meshcore.messaging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * A subscription Swift can hold and cancel.
 *
 * Kotlin/Native does not export suspend `collect` in a form SwiftUI can
 * use directly, and the iOS store used to poll every StateFlow's
 * `.value` once a second instead — which both lagged and missed every
 * SharedFlow event outright (an inbound message is an event, not a
 * state, so polling can never see it). [watch] turns any flow into a
 * callback on [scope]'s dispatcher; iOS passes its main-thread scope so
 * the callback can touch `@Published` state directly.
 */
class FlowWatch internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
    val isActive: Boolean get() = job.isActive
}

/**
 * Call [onEach] with every value [flow] emits, until cancelled. A
 * StateFlow delivers its current value first, so the subscriber starts
 * in sync. A throw from [onEach] ends only this watch, never the scope.
 */
fun <T> watch(flow: Flow<T>, scope: CoroutineScope, onEach: (T) -> Unit): FlowWatch =
    FlowWatch(scope.launch { runCatching { flow.collect { onEach(it) } } })
