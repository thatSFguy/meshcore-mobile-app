package io.github.thatsfguy.meshcore.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which attempt this is, so a transport can adjust how it tries.
 *
 * BLE is the one that cares: the first try after a link that was up
 * should be a fast direct connect, and every try after that should be
 * the patient background kind that completes by itself when the radio
 * comes back into range (see `BleTransport`'s `autoConnect`). TCP and
 * USB ignore it.
 */
data class ReconnectAttempt(
    /** Failed attempts since the last link that stayed up. 0 on the first try. */
    val consecutiveFailures: Int,
) {
    /**
     * True once a straightforward attempt has already failed, i.e. the
     * radio is probably not in range right now and the transport should
     * wait for it rather than poll for it.
     */
    val patient: Boolean get() = consecutiveFailures > 0
}

/**
 * Build → connect → run → wait for the link to die → back off → rebuild.
 *
 * Lifted out of `MeshCoreService` so the policy can be tested without a
 * radio, a Service, or an emulator. Two bugs it exists to hold fixed:
 *
 *  - **The hold time is measured from the moment the link came UP**, not
 *    from the moment the attempt began. Measured from the attempt, a
 *    connect that spent 40s failing counted as a link that "held" for
 *    40s, so the backoff reset to 1s and the app hammered an
 *    out-of-range radio at full speed instead of backing off.
 *  - **Cancellation is not swallowed.** The old loop caught `Throwable`,
 *    which includes `CancellationException`, so a supervisor being shut
 *    down could take one more lap. Transports must therefore not let a
 *    `withTimeout` leak out of `connect()` either — a timeout is a
 *    failed attempt, and has to arrive as an ordinary exception.
 */
class ReconnectSupervisor(
    private val scope: CoroutineScope,
    /** Construct a fresh transport for this attempt. May throw. */
    private val build: (ReconnectAttempt) -> Transport,
    /** Publish the transport and attach the engine, before connecting. */
    private val onTransport: (Transport) -> Unit,
    /** Surface a user-visible error, or null to clear it. */
    private val onError: (String?) -> Unit,
    private val log: (String) -> Unit,
    /** Monotonic-enough millis; injectable so tests can use virtual time. */
    private val now: () -> Long,
) {

    private var job: Job? = null

    /** True while a supervisor loop is running. */
    val running: Boolean get() = job?.isActive == true

    /** Restart the loop, dropping any attempt already in flight. */
    fun start() {
        stop()
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run() {
        var failures = 0
        while (true) {
            val transport = try {
                build(ReconnectAttempt(failures))
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                onError(t.message)
                log("Transport build failed: ${t.message}")
                failures++
                delay(backoffMs(failures))
                continue
            }

            // Null until the link is actually up — see the class doc.
            var upAt: Long? = null
            try {
                onTransport(transport)
                transport.connect()
                upAt = now()
                onError(null)
                // Park until the link drops.
                transport.state.collect { st ->
                    if (st == TransportState.Disconnected || st == TransportState.Error) {
                        throw LinkDownException()
                    }
                }
            } catch (t: CancellationException) {
                withContext(NonCancellable) { runCatching { transport.disconnect() } }
                throw t
            } catch (t: Throwable) {
                if (t !is LinkDownException) onError(t.message)
                log("Link down: ${t.message ?: "disconnected"}")
            }
            runCatching { transport.disconnect() }

            val heldMs = upAt?.let { now() - it } ?: -1L
            failures = if (heldMs >= HELD_LONG_ENOUGH_MS) 0 else failures + 1
            delay(backoffMs(failures))
        }
    }

    private class LinkDownException : Exception()

    companion object {
        /** First retry, and the pause after a link that had settled. */
        const val FIRST_RETRY_MS = 1_000L

        /** Ceiling on the exponential backoff. */
        const val MAX_BACKOFF_MS = 60_000L

        /**
         * A link that stayed up this long counts as real, so the next
         * drop starts backing off from scratch rather than from
         * whatever the last bad patch left behind.
         */
        const val HELD_LONG_ENOUGH_MS = 30_000L

        /** 1s, 2s, 4s, 8s, 16s, 32s, then 60s forever. */
        fun backoffMs(consecutiveFailures: Int): Long {
            if (consecutiveFailures <= 0) return FIRST_RETRY_MS
            val shift = (consecutiveFailures - 1).coerceAtMost(20)
            val scaled = FIRST_RETRY_MS shl shift
            return if (scaled <= 0 || scaled > MAX_BACKOFF_MS) MAX_BACKOFF_MS else scaled
        }
    }
}
