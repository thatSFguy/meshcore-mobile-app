package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.protocol.AckTimeout
import io.github.thatsfguy.meshcore.protocol.DeliveryWatch
import io.github.thatsfguy.meshcore.protocol.SendRetry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The direct-message retry ladder, platform-neutral.
 *
 * This is Android's `MessageRepository.sendDirectWithRetry` lifted into
 * `shared` so the iOS app runs the same rules instead of a second
 * reading of them: three attempts, the last one resetting the path and
 * flooding ([SendRetry]), ACK waits widened for distance ([AckTimeout]),
 * one hot ACK watch for the whole send ([DeliveryWatch]), and a late-ACK
 * grace window that can turn Failed back into Delivered.
 *
 * It talks to the radio through [Radio] and reports through [onStatus],
 * so it is testable with a fake and knows nothing about storage. Path
 * scoring (Android's routing-history column) is left to the caller —
 * iOS has no path history yet.
 */
object DirectSend {

    /** The three things the ladder needs from a radio. */
    interface Radio {
        /** Send one attempt. Null when the radio did not accept it. */
        suspend fun send(text: String, attempt: Int, timestampSeconds: Long): Receipt?

        /** Clear the radio's stored path for this contact. */
        suspend fun resetPath(): Boolean

        /** Every end-to-end ACK hash the radio reports, from now on. */
        val deliveries: Flow<Long>
    }

    /** What the radio said when it accepted an attempt. */
    data class Receipt(val ackHash: Long, val timeoutMs: Long, val isFlood: Boolean)

    /** One step of the ladder, as the UI should show it. */
    data class Update(
        val status: MessageStatus,
        val ackHash: Long?,
        val attempts: Int,
    )

    /**
     * Run the ladder to its end and return the final status.
     *
     * [hasStoredPath] decides whether the last attempt resets and floods
     * — with no stored path the radio is already flooding and there is
     * nothing to fall back to. [knownHops] is the contact's distance for
     * the ACK wait; see [AckTimeout.estimateHops].
     */
    suspend fun run(
        radio: Radio,
        text: String,
        timestampSeconds: Long,
        hasStoredPath: Boolean,
        knownHops: Int?,
        maxAttempts: Int = SendRetry.DEFAULT_MAX_ATTEMPTS,
        floodFallbackEnabled: Boolean = true,
        onStatus: (Update) -> Unit,
    ): MessageStatus = coroutineScope {
        val watch = DeliveryWatch()
        // One collector for the whole send: meshEvents has no replay, so
        // an ACK arriving between waits would otherwise be lost. Started
        // UNDISPATCHED so it is subscribed before the first attempt goes
        // out — a plain launch leaves a window where a fast ACK is gone.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            radio.deliveries.collect { watch.record(it) }
        }
        val ourHashes = mutableSetOf<Long>()
        try {
            var attempt = 0
            while (attempt < maxAttempts) {
                val route = SendRetry.routeFor(
                    attempt = attempt,
                    maxAttempts = maxAttempts,
                    hasStoredPath = hasStoredPath,
                    floodFallbackEnabled = floodFallbackEnabled,
                )
                if (route == SendRetry.Route.ResetAndFlood) {
                    runCatching { radio.resetPath() }
                }
                val sent = runCatching { radio.send(text, attempt, timestampSeconds) }.getOrNull()
                if (sent == null) {
                    // The radio refused it outright; retrying fast won't help.
                    // An earlier attempt may still be in flight, so keep the
                    // hash we have and allow it the grace window.
                    onStatus(Update(MessageStatus.Failed, ourHashes.lastOrNull(), attempt))
                    return@coroutineScope lateAck(watch, ourHashes, attempt, onStatus)
                }
                ourHashes += sent.ackHash
                onStatus(Update(MessageStatus.Sent, sent.ackHash, attempt + 1))

                val timeout = AckTimeout.waitFor(sent.timeoutMs, sent.isFlood, knownHops)
                if (watch.awaitAny(ourHashes, timeout)) {
                    onStatus(Update(MessageStatus.Delivered, sent.ackHash, attempt + 1))
                    return@coroutineScope MessageStatus.Delivered
                }
                attempt++
                if (attempt < maxAttempts) {
                    delay(1_000L * attempt)
                    if (watch.awaitAny(ourHashes, 0)) {
                        onStatus(Update(MessageStatus.Delivered, ourHashes.last(), attempt))
                        return@coroutineScope MessageStatus.Delivered
                    }
                }
            }
            // Failed is the honest report of what is known NOW; a late ACK
            // can still correct it.
            onStatus(Update(MessageStatus.Failed, ourHashes.lastOrNull(), maxAttempts))
            lateAck(watch, ourHashes, maxAttempts, onStatus)
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun lateAck(
        watch: DeliveryWatch,
        hashes: Set<Long>,
        attempts: Int,
        onStatus: (Update) -> Unit,
    ): MessageStatus {
        if (hashes.isEmpty()) return MessageStatus.Failed
        return if (watch.awaitAny(hashes, SendRetry.LATE_ACK_GRACE_MS)) {
            onStatus(Update(MessageStatus.Delivered, hashes.last(), attempts))
            MessageStatus.Delivered
        } else {
            MessageStatus.Failed
        }
    }
}
