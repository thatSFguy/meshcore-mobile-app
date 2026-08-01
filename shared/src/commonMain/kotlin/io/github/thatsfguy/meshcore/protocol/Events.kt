package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.model.BatteryAndStorage
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.model.DeviceInfo
import io.github.thatsfguy.meshcore.model.SelfInfo

/**
 * A parsed radio → client frame: either a response to a command
 * (code < 0x80) or an async push (code ≥ 0x80).
 *
 * Wrapped byte arrays intentionally use identity equality — events are
 * one-shot stream elements, never compared.
 */
sealed class DeviceEvent {
    /** True for async pushes (code has the high bit set). */
    abstract val isPush: Boolean

    // --- Responses ---

    data class Ok(val payload: ByteArray) : DeviceEvent() {
        override val isPush get() = false
    }

    data class Err(val errorCode: Int?) : DeviceEvent() {
        override val isPush get() = false
    }

    data class ContactsStart(val count: Long?) : DeviceEvent() {
        override val isPush get() = false
    }

    data class ContactReceived(val contact: Contact, val fromPush: Boolean) : DeviceEvent() {
        override val isPush get() = fromPush
    }

    object EndOfContacts : DeviceEvent() {
        override val isPush get() = false
    }

    data class SelfInfoReceived(val info: SelfInfo) : DeviceEvent() {
        override val isPush get() = false
    }

    data class DeviceInfoReceived(val info: DeviceInfo) : DeviceEvent() {
        override val isPush get() = false
    }

    /** RESP_CODE_SENT — the radio accepted an outbound message. */
    data class Sent(val isFlood: Boolean, val ackHash: Long, val timeoutMs: Long) : DeviceEvent() {
        override val isPush get() = false
    }

    /**
     * A direct message (or CLI reply) pulled via CMD_SYNC_NEXT_MESSAGE.
     * [senderPrefix] is the 6-byte pubkey prefix; resolve against the
     * contact list. [txtType] distinguishes plain text from CLI replies.
     */
    data class ContactMessage(
        val senderPrefix: ByteArray,
        val pathLen: Int,
        val txtType: Int,
        val timestamp: Long,
        val text: String,
        val snr: Double?,
        /**
         * Room-server posts (TXT_TYPE_SIGNED) carry the ORIGINAL
         * author's 4-byte pubkey prefix ahead of the text — the message
         * itself arrives from the room, not from whoever wrote it. Null
         * for ordinary direct messages.
         */
        val roomAuthorPrefix: ByteArray? = null,
    ) : DeviceEvent() {
        override val isPush get() = false
    }

    /**
     * A channel message delivered through the companion sync path.
     * SECURITY: [senderName] comes from unauthenticated "name: msg" text —
     * display only; never use for identity or contact mutation.
     */
    data class ChannelMessage(
        val channelIndex: Int,
        val senderName: String,
        val text: String,
        val timestamp: Long,
        val pathLen: Int,
        val pathBytes: ByteArray,
        val pathHashWidth: Int?,
    ) : DeviceEvent() {
        override val isPush get() = false
    }

    data class CurrentTime(val timestamp: Long) : DeviceEvent() {
        override val isPush get() = false
    }

    object NoMoreMessages : DeviceEvent() {
        override val isPush get() = false
    }

    /** RESP_CODE_EXPORT_CONTACT — a shareable advert blob (≥98 bytes). */
    data class ExportedContact(val advertBlob: ByteArray) : DeviceEvent() {
        override val isPush get() = false
    }

    data class BatteryAndStorageReceived(val info: BatteryAndStorage) : DeviceEvent() {
        override val isPush get() = false
    }

    data class ChannelInfoReceived(val channel: Channel) : DeviceEvent() {
        override val isPush get() = false
    }

    data class CustomVars(val vars: Map<String, String>) : DeviceEvent() {
        override val isPush get() = false
    }

    data class Stats(val statsType: Int, val payload: ByteArray) : DeviceEvent() {
        override val isPush get() = false
    }

    data class AutoAddConfig(val flags: Int) : DeviceEvent() {
        override val isPush get() = false
    }

    // --- Pushes ---

    /** PUSH_CODE_ADVERT — a known contact was re-heard. */
    data class AdvertReheard(val publicKey: ByteArray) : DeviceEvent() {
        override val isPush get() = true
    }

    data class PathUpdated(val publicKey: ByteArray) : DeviceEvent() {
        override val isPush get() = true
    }

    /** PUSH_CODE_SEND_CONFIRMED — end-to-end ACK arrived. */
    data class SendConfirmed(val ackHash: Long, val tripMs: Long) : DeviceEvent() {
        override val isPush get() = true
    }

    /** PUSH_CODE_MSG_WAITING — start CMD_SYNC_NEXT_MESSAGE loop. */
    object MessageWaiting : DeviceEvent() {
        override val isPush get() = true
    }

    data class LoginSuccess(
        val permissions: Int,
        val pubKeyPrefix: ByteArray,
        val serverTimestamp: Long?,
    ) : DeviceEvent() {
        override val isPush get() = true
    }

    object LoginFail : DeviceEvent() {
        override val isPush get() = true
    }

    data class StatusResponse(val payload: ByteArray) : DeviceEvent() {
        override val isPush get() = true
    }

    /**
     * PUSH_CODE_LOG_RX_DATA — a raw over-the-air packet the radio heard.
     * [packet] parses via [RawPacket.parse]; channel (GRP_TXT) payloads
     * decrypt via ChannelCrypto, adverts verify via Advert.
     */
    data class LogRxData(val snr: Double, val rssi: Int, val packet: ByteArray) : DeviceEvent() {
        override val isPush get() = true
    }

    data class TraceData(val payload: ByteArray) : DeviceEvent() {
        override val isPush get() = true
    }

    data class TelemetryResponse(val payload: ByteArray) : DeviceEvent() {
        override val isPush get() = true
    }

    data class BinaryResponse(val payload: ByteArray) : DeviceEvent() {
        override val isPush get() = true
    }

    data class ControlData(val payload: ByteArray) : DeviceEvent() {
        override val isPush get() = true
    }

    /** Anything with an unrecognized or unparseable code — kept for diagnostics. */
    data class Unknown(val code: Int, val frame: ByteArray) : DeviceEvent() {
        override val isPush get() = code >= 0x80
    }
}
