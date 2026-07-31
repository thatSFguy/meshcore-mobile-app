package io.github.thatsfguy.meshcore.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Common abstraction over every way the app can reach the attached
 * MeshCore radio: BLE (Nordic UART Service), USB serial, and TCP.
 *
 * Implementations are responsible for link framing. The bytes flowing
 * through this interface are RAW companion frames (starting with the
 * code byte) — no start-byte/length header, no GATT chunking.
 *
 * Structure carried over from reticulum-mobile-app's Transport; the
 * payload semantics changed from Reticulum packets to MeshCore frames.
 */
interface Transport {
    val state: StateFlow<TransportState>

    /** Stream of inbound companion frames (radio → client). */
    val incoming: Flow<IncomingFrame>

    // @Throws pinned on the interface so K/N's Swift bridge can wrap a
    // failed connect/disconnect as NSError instead of aborting (see the
    // reticulum-mobile-app v1.0.70 SIGABRT note).
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    suspend fun connect()

    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    suspend fun disconnect()

    /** Send one companion frame. Returns when the bytes are handed to the
     *  link, NOT when the radio acknowledged. */
    suspend fun send(frame: ByteArray)

    /**
     * True when this link crosses a network in cleartext (TCP). The UI
     * keeps flagging such connections as unencrypted (SCOPE.md).
     */
    val isPlaintextLink: Boolean get() = false
}

/** One inbound companion frame. */
class IncomingFrame(val frame: ByteArray)

enum class TransportState { Disconnected, Connecting, Connected, Error }
