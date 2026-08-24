package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.BinaryRequestBudget
import kotlinx.coroutines.delay

/**
 * What is actually known while waiting on a node — the one honest
 * progress report a LoRa request has.
 *
 * There is no partly-arrived response to draw a bar from, so this shows
 * the three things the radio really told us: that it put the request on
 * the air, how it sent it, and how long it expects the answer to take.
 * Once that estimate passes it says so, rather than letting a spinner
 * keep implying the confidence it had at second one.
 *
 * Renders nothing when no request to [keyHex] is in flight.
 */
@Composable
fun RequestProgressHint(vm: MeshCoreViewModel, keyHex: String) {
    val inFlight = vm.requestInFlight.collectAsState().value[keyHex] ?: return

    // A new attempt is a new value, which restarts the clock — so
    // "attempt 2 of 2" counts down from its own budget, not the first
    // attempt's leftovers.
    val startedAt = remember(inFlight) { System.currentTimeMillis() }
    var now by remember(inFlight) { mutableLongStateOf(startedAt) }
    LaunchedEffect(inFlight) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }

    HintText(
        BinaryRequestBudget.progressLabel(inFlight, inFlight.budgetMs - (now - startedAt)),
    )
}

/** Twice a second: the label is in whole seconds and must not stutter. */
private const val TICK_MS = 500L
