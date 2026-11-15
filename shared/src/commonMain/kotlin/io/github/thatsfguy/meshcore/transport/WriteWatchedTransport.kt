package io.github.thatsfguy.meshcore.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A transport whose failed writes count as a dead link.
 *
 * [ReconnectSupervisor] used to learn that a link had died only from the
 * transport's own [Transport.state]. On Android that is fed by GATT
 * callbacks, and when the phone's Bluetooth service itself dies those
 * callbacks never come: every `writeCharacteristic` throws
 * `DeadObjectException` (and returns false), while the state stays
 * `Connected` forever. Seen on a Pixel running 0.10.16 on 2026-11-15: an
 * hour of failed DM retries and routing changes, an app that still said it
 * was connected, and a DM the radio had acknowledged sitting in the
 * radio's queue until the user force-stopped the app.
 *
 * The write failing is the one signal that does arrive, so it is the one
 * this listens to. It only listens once [arm]ed — after `connect()` has
 * returned — because the engine can try a write before the link is up,
 * and that failure means "not yet", not "dead".
 *
 * An [IllegalArgumentException] is the caller's mistake (a frame too big
 * for the link), not the link's, and is passed through without tripping.
 */
class WriteWatchedTransport(val inner: Transport) : Transport by inner {

    private val _broken = MutableStateFlow<Throwable?>(null)

    /** The first write failure after [arm], or null while the link is fine. */
    val broken: StateFlow<Throwable?> = _broken

    private var armed = false

    /** The link is up: from now on a failed write means it is dead. */
    fun arm() {
        armed = true
    }

    override suspend fun send(frame: ByteArray) {
        try {
            inner.send(frame)
        } catch (t: CancellationException) {
            throw t
        } catch (t: IllegalArgumentException) {
            throw t
        } catch (t: Throwable) {
            if (armed && _broken.value == null) _broken.value = t
            throw t
        }
    }
}
