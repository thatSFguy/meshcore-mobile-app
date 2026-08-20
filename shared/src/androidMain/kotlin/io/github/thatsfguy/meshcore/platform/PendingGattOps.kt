package io.github.thatsfguy.meshcore.platform

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The GATT callbacks a BLE operation is currently parked on.
 *
 * Android delivers every GATT step asynchronously, so each step is a
 * suspending await on a continuation that the matching callback
 * resumes. The failure this class exists to remove: when a radio walks
 * out of range mid-connect the stack fires `onConnectionStateChange`
 * and NOTHING else — no `onMtuChanged`, no `onDescriptorWrite`. A step
 * whose only resume path was its own success callback therefore stayed
 * parked forever, and with it `connect()`, and with it the reconnect
 * supervisor that was waiting on `connect()`. The radio came back into
 * range and the app never noticed, because the app was still inside the
 * attempt it had started while the radio was leaving.
 *
 * Registering every step in one place means the disconnect path cannot
 * forget one: [failAll] resumes whatever is still outstanding.
 */
internal class PendingGattOps {

    /** One awaited GATT callback. At most one of each is outstanding. */
    enum class Slot { Services, Mtu, Descriptor, CharacteristicWrite }

    private val lock = Any()
    private val pending = LinkedHashMap<Slot, CancellableContinuation<Any?>>()

    /** Slots currently awaiting a callback. Test observability. */
    fun outstanding(): Set<Slot> = synchronized(lock) { pending.keys.toSet() }

    /**
     * Register [slot], run [start], and suspend until a callback
     * resumes it. [start] runs AFTER registration because a GATT
     * callback can land before the initiating call returns; if it
     * throws (the synchronous `returned false` case) the slot is
     * released and the exception propagates.
     */
    suspend fun <T> await(slot: Slot, start: () -> Unit): T =
        suspendCancellableCoroutine { cont ->
            synchronized(lock) {
                // A slot left over from a torn-down attempt would
                // otherwise be resumed by our callback and leak.
                @Suppress("UNCHECKED_CAST")
                pending.put(slot, cont as CancellableContinuation<Any?>)
                    ?.let { stale ->
                        stale.resumeWithException(
                            IllegalStateException("Superseded by a new $slot operation"),
                        )
                    }
            }
            cont.invokeOnCancellation { take(slot) }
            try {
                start()
            } catch (t: Throwable) {
                take(slot)?.resumeWithException(t)
            }
        }

    /** Resume [slot] with [value]; a no-op if nothing is awaiting it. */
    fun succeed(slot: Slot, value: Any?) {
        take(slot)?.resume(value)
    }

    /** Fail [slot]; a no-op if nothing is awaiting it. */
    fun fail(slot: Slot, cause: Throwable) {
        take(slot)?.resumeWithException(cause)
    }

    /**
     * Fail everything still outstanding. Called from the disconnect
     * callback and from teardown, so no connect step can outlive the
     * link it was running on.
     */
    fun failAll(cause: Throwable) {
        val all = synchronized(lock) {
            val copy = pending.values.toList()
            pending.clear()
            copy
        }
        all.forEach { runCatching { it.resumeWithException(cause) } }
    }

    private fun take(slot: Slot): CancellableContinuation<Any?>? =
        synchronized(lock) { pending.remove(slot) }
}
