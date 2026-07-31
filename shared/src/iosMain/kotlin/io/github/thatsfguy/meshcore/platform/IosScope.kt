package io.github.thatsfguy.meshcore.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * App-lifetime coroutine scope for the Swift side (exported as
 * `IosScopeKt.meshCoreMainScope()`). The SwiftUI store owns exactly
 * one; Phase 2 should add a cancel hook tied to store deinit.
 */
fun meshCoreMainScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main)
