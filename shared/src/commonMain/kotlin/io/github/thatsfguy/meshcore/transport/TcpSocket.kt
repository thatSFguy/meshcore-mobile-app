package io.github.thatsfguy.meshcore.transport

import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic TCP socket used by [TcpInterface] to reach a
 * WiFi/Ethernet-attached MeshCore base-station radio.
 *
 * Plain raw TCP — no TLS, no auth (the MeshCore companion TCP link is
 * protocol-plaintext; see SCOPE.md's stern-warning requirement). JDK
 * Socket on Android, NSStream/NWConnection on iOS, zero dependencies.
 *
 * [incoming] is a cold flow that begins reading on first collection and
 * emits raw chunks; framing is [SerialFrameDecoder]'s job. Lifecycle:
 * [connect] before [write]/collect; after [close] the socket is
 * single-shot — create a new instance to reconnect.
 */
expect class TcpSocket(host: String, port: Int) {
    val host: String
    val port: Int

    suspend fun connect()
    suspend fun close()
    suspend fun write(bytes: ByteArray)

    /** Cold flow of raw byte chunks as they arrive. Completes when the
     *  remote drops the connection or [close] is called. */
    fun incoming(): Flow<ByteArray>
}
