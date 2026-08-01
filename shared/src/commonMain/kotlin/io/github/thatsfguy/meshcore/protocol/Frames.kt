package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.protocol.Codes.CIPHER_MAC_SIZE
import io.github.thatsfguy.meshcore.protocol.Codes.MAX_FRAME_SIZE
import io.github.thatsfguy.meshcore.protocol.Codes.MAX_NAME_SIZE
import io.github.thatsfguy.meshcore.protocol.Codes.MAX_PATH_SIZE
import io.github.thatsfguy.meshcore.protocol.Codes.MAX_TEXT_PAYLOAD_BYTES
import io.github.thatsfguy.meshcore.protocol.Codes.PUB_KEY_SIZE

/**
 * Companion command-frame builders (client → radio).
 *
 * Byte layouts are ported 1:1 from the MeshCore Open reference client
 * (`meshcore_protocol.dart`), which is what MESHCORE_PROTOCOL.md §7 was
 * derived from. All timestamps are epoch seconds supplied by the caller
 * so builders stay pure and testable.
 */
object Frames {

    /** CMD_APP_START: [cmd][app_ver][reserved x6][app_name…]\0 */
    fun appStart(appName: String = "MeshCoreHardened", appVersion: Int = 1): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.CMD_APP_START)
        w.writeByte(appVersion)
        w.writeBytes(ByteArray(6))
        w.writeString(appName)
        w.writeByte(0)
        return w.toBytes()
    }

    /** CMD_DEVICE_QUERY: [cmd][app_protocol_version] */
    fun deviceQuery(appProtocolVersion: Int = Codes.APP_PROTOCOL_VERSION): ByteArray =
        byteArrayOf(Codes.CMD_DEVICE_QUERY.toByte(), appProtocolVersion.toByte())

    /**
     * CMD_SEND_TXT_MSG (plain DM):
     * [cmd][txt_type][attempt][timestamp x4][pubkey_prefix x6][text…]\0
     */
    fun sendTextMessage(
        recipientPubKey: ByteArray,
        text: String,
        timestampSeconds: Long,
        attempt: Int = 0,
    ): ByteArray {
        require(recipientPubKey.size >= 6) { "recipient pubkey too short" }
        val w = BufferWriter()
        w.writeByte(Codes.CMD_SEND_TXT_MSG)
        w.writeByte(Codes.TXT_TYPE_PLAIN)
        w.writeByte(attempt.coerceIn(0, 255))
        w.writeUInt32LE(timestampSeconds)
        w.writeBytes(recipientPubKey.copyOfRange(0, 6))
        w.writeString(text)
        w.writeByte(0)
        return w.toBytes()
    }

    /**
     * CMD_SEND_TXT_MSG with txt_type=cli_data — a raw CLI command to a
     * repeater/room. NOTE: `set prv.key` and login-adjacent commands
     * carry secrets; callers must redact before logging.
     */
    fun sendCliCommand(
        repeaterPubKey: ByteArray,
        command: String,
        timestampSeconds: Long,
        attempt: Int = 0,
    ): ByteArray {
        require(repeaterPubKey.size >= 6) { "repeater pubkey too short" }
        val w = BufferWriter()
        w.writeByte(Codes.CMD_SEND_TXT_MSG)
        w.writeByte(Codes.TXT_TYPE_CLI_DATA)
        w.writeByte(attempt.coerceIn(0, 255))
        w.writeUInt32LE(timestampSeconds)
        w.writeBytes(repeaterPubKey.copyOfRange(0, 6))
        w.writeString(command)
        w.writeByte(0)
        return w.toBytes()
    }

    /** CMD_SEND_CHANNEL_TXT_MSG: [cmd][txt_type][channel_idx][timestamp x4][text…]\0 */
    fun sendChannelTextMessage(channelIndex: Int, text: String, timestampSeconds: Long): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.CMD_SEND_CHANNEL_TXT_MSG)
        w.writeByte(Codes.TXT_TYPE_PLAIN)
        w.writeByte(channelIndex)
        w.writeUInt32LE(timestampSeconds)
        w.writeString(text)
        w.writeByte(0)
        return w.toBytes()
    }

    /** CMD_GET_CONTACTS: [cmd][since x4]? */
    fun getContacts(since: Long? = null): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.CMD_GET_CONTACTS)
        if (since != null) w.writeUInt32LE(since)
        return w.toBytes()
    }

    /**
     * CMD_SEND_LOGIN: [cmd][pubkey x32][password…]\0
     * SECURITY: the password is CLEARTEXT on the wire (and therefore on
     * a TCP link, cleartext on the network). Never log this frame.
     */
    fun sendLogin(recipientPubKey: ByteArray, password: String): ByteArray {
        require(recipientPubKey.size == PUB_KEY_SIZE) { "pubkey must be 32 bytes" }
        val w = BufferWriter()
        w.writeByte(Codes.CMD_SEND_LOGIN)
        w.writeBytes(recipientPubKey)
        w.writeString(password)
        w.writeByte(0)
        return w.toBytes()
    }

    /** CMD_SEND_STATUS_REQ: [cmd][pubkey x32] */
    fun sendStatusRequest(recipientPubKey: ByteArray): ByteArray =
        cmdWithPubKey(Codes.CMD_SEND_STATUS_REQ, recipientPubKey)

    /** CMD_GET_DEVICE_TIME: [cmd] */
    fun getDeviceTime(): ByteArray = byteArrayOf(Codes.CMD_GET_DEVICE_TIME.toByte())

    /** CMD_SET_DEVICE_TIME: [cmd][timestamp x4] */
    fun setDeviceTime(timestampSeconds: Long): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.CMD_SET_DEVICE_TIME)
        w.writeUInt32LE(timestampSeconds)
        return w.toBytes()
    }

    /** CMD_SEND_SELF_ADVERT: [cmd][flood 0/1] */
    fun sendSelfAdvert(flood: Boolean = false): ByteArray =
        byteArrayOf(Codes.CMD_SEND_SELF_ADVERT.toByte(), if (flood) 1 else 0)

    /** CMD_SET_ADVERT_NAME: [cmd][name ≤31 bytes] */
    fun setAdvertName(name: String): ByteArray {
        val encoded = name.encodeToByteArray()
        val len = minOf(encoded.size, MAX_NAME_SIZE - 1)
        val w = BufferWriter()
        w.writeByte(Codes.CMD_SET_ADVERT_NAME)
        w.writeBytes(encoded.copyOfRange(0, len))
        return w.toBytes()
    }

    /** CMD_SET_ADVERT_LATLON: [cmd][lat*1e6 i32][lon*1e6 i32] */
    fun setAdvertLatLon(lat: Double, lon: Double): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.CMD_SET_ADVERT_LATLON)
        w.writeInt32LE((lat * 1_000_000).toInt())
        w.writeInt32LE((lon * 1_000_000).toInt())
        return w.toBytes()
    }

    /** CMD_SET_RADIO_PARAMS: [cmd][freq_hz x4][bw_hz x4][sf][cr][client_repeat?] */
    fun setRadioParams(
        freqHz: Long,
        bwHz: Long,
        sf: Int,
        cr: Int,
        clientRepeat: Boolean? = null,
    ): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.CMD_SET_RADIO_PARAMS)
        w.writeUInt32LE(freqHz)
        w.writeUInt32LE(bwHz)
        w.writeByte(sf)
        w.writeByte(cr)
        if (clientRepeat != null) w.writeByte(if (clientRepeat) 1 else 0)
        return w.toBytes()
    }

    /** CMD_SET_RADIO_TX_POWER: [cmd][power_dbm] */
    fun setRadioTxPower(powerDbm: Int): ByteArray =
        byteArrayOf(Codes.CMD_SET_RADIO_TX_POWER.toByte(), powerDbm.toByte())

    /** CMD_RESET_PATH: [cmd][pubkey x32] */
    fun resetPath(pubKey: ByteArray): ByteArray =
        cmdWithPubKey(Codes.CMD_RESET_PATH, pubKey)

    /** CMD_REMOVE_CONTACT: [cmd][pubkey x32] */
    fun removeContact(pubKey: ByteArray): ByteArray =
        cmdWithPubKey(Codes.CMD_REMOVE_CONTACT, pubKey)

    /** CMD_SHARE_CONTACT (zero-hop share): [cmd][pubkey x32] */
    fun shareContact(pubKey: ByteArray): ByteArray =
        cmdWithPubKey(Codes.CMD_SHARE_CONTACT, pubKey)

    /** CMD_EXPORT_CONTACT: [cmd][pubkey x32]; empty pubkey exports self. */
    fun exportContact(pubKey: ByteArray = ByteArray(0)): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.CMD_EXPORT_CONTACT)
        w.writeBytes(pubKey)
        return w.toBytes()
    }

    /** CMD_IMPORT_CONTACT: [cmd][advert blob ≥98 bytes] */
    fun importContact(advertBlob: ByteArray): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.CMD_IMPORT_CONTACT)
        w.writeBytes(advertBlob)
        return w.toBytes()
    }

    /** CMD_GET_CONTACT_BY_KEY: [cmd][pubkey x32] */
    fun getContactByKey(pubKey: ByteArray): ByteArray =
        cmdWithPubKey(Codes.CMD_GET_CONTACT_BY_KEY, pubKey)

    /**
     * CMD_ADD_UPDATE_CONTACT:
     * [cmd][pubkey x32][type][flags][path_len][path x64][name cstr32]
     * [timestamp x4][lat i32, lon i32]?[lastmod x4]?
     */
    fun addUpdateContact(
        pubKey: ByteArray,
        type: Int,
        flags: Int,
        pathLen: Int,
        path: ByteArray,
        name: String,
        timestampSeconds: Long,
        lat: Double? = null,
        lon: Double? = null,
        lastModifiedSeconds: Long? = null,
    ): ByteArray {
        require(pubKey.size == PUB_KEY_SIZE) { "pubkey must be 32 bytes" }
        val w = BufferWriter()
        w.writeByte(Codes.CMD_ADD_UPDATE_CONTACT)
        w.writeBytes(pubKey)
        w.writeByte(type)
        w.writeByte(flags)
        w.writeByte(pathLen)
        w.writeBytesPadded(path, MAX_PATH_SIZE)
        w.writeFixedCString(name, MAX_NAME_SIZE)
        w.writeUInt32LE(timestampSeconds)
        val hasLocation = lat != null && lon != null
        if (hasLocation || lastModifiedSeconds != null) {
            w.writeInt32LE(if (hasLocation) ((lat!! * 1e6).toInt()) else 0)
            w.writeInt32LE(if (hasLocation) ((lon!! * 1e6).toInt()) else 0)
            if (lastModifiedSeconds != null) w.writeUInt32LE(lastModifiedSeconds)
        }
        return w.toBytes()
    }

    /** CMD_REBOOT: [cmd]["reboot"] */
    fun reboot(): ByteArray =
        byteArrayOf(Codes.CMD_REBOOT.toByte()) + "reboot".encodeToByteArray()

    /** CMD_GET_BATT_AND_STORAGE: [cmd] */
    fun getBattAndStorage(): ByteArray = byteArrayOf(Codes.CMD_GET_BATT_AND_STORAGE.toByte())

    /** CMD_SYNC_NEXT_MESSAGE: [cmd] */
    fun syncNextMessage(): ByteArray = byteArrayOf(Codes.CMD_SYNC_NEXT_MESSAGE.toByte())

    /** CMD_GET_CHANNEL: [cmd][channel_idx] */
    fun getChannel(channelIndex: Int): ByteArray =
        byteArrayOf(Codes.CMD_GET_CHANNEL.toByte(), channelIndex.toByte())

    /** CMD_SET_CHANNEL: [cmd][idx][name cstr32][psk x16] */
    fun setChannel(channelIndex: Int, name: String, psk: ByteArray): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.CMD_SET_CHANNEL)
        w.writeByte(channelIndex)
        w.writeFixedCString(name, MAX_NAME_SIZE)
        w.writeBytesPadded(psk, Codes.CIPHER_BLOCK_SIZE)
        return w.toBytes()
    }

    /** CMD_GET_STATS: [cmd][stats_type] */
    fun getStats(statsType: Int): ByteArray =
        byteArrayOf(Codes.CMD_GET_STATS.toByte(), (statsType and 0xFF).toByte())

    /** CMD_SET_PATH_HASH_MODE: [cmd][0][mode 0..3] */
    fun setPathHashMode(mode: Int): ByteArray =
        byteArrayOf(Codes.CMD_SET_PATH_HASH_MODE.toByte(), 0, mode.coerceIn(0, 3).toByte())

    /**
     * CMD_SET_FLOOD_SCOPE: [cmd][0][scope x16]? — scope is
     * SHA256("#region")[0..15]; empty region resets the scope.
     * [scopeHash16] must be pre-computed by the caller (crypto lives
     * outside the pure builders).
     */
    fun setFloodScope(scopeHash16: ByteArray?): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.CMD_SET_FLOOD_SCOPE)
        w.writeByte(0)
        if (scopeHash16 != null) {
            require(scopeHash16.size == 16) { "flood scope must be 16 bytes" }
            w.writeBytes(scopeHash16)
        }
        return w.toBytes()
    }

    /** CMD_SEND_TRACE_PATH: [cmd][tag u32][auth u32][flags][payload?] */
    fun sendTracePath(tag: Long, auth: Long, flags: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.CMD_SEND_TRACE_PATH)
        w.writeUInt32LE(tag)
        w.writeUInt32LE(auth)
        w.writeByte(flags)
        if (payload.isNotEmpty()) w.writeBytes(payload)
        return w.toBytes()
    }

    /** CMD_GET_CUSTOM_VAR: [cmd] (reads all custom vars) */
    fun getCustomVars(): ByteArray = byteArrayOf(Codes.CMD_GET_CUSTOM_VAR.toByte())

    /** CMD_SET_CUSTOM_VAR: [cmd][key:value…]\0 */
    fun setCustomVar(keyValue: String): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.CMD_SET_CUSTOM_VAR)
        w.writeString(keyValue)
        w.writeByte(0)
        return w.toBytes()
    }

    /**
     * CMD_SET_OTHER_PARAMS:
     * [cmd][manual_add=0x01][telemetry_flags][advert_loc_policy][multi_acks]
     * (Auto-add uses inverted logic: 0x01 = manual/disabled, matching the
     * reference client which always writes 0x01 and drives auto-add via
     * CMD_SET_AUTO_ADD_CONFIG.)
     */
    fun setOtherParams(
        allowTelemetryFlags: Int,
        advertLocationPolicy: Int,
        multiAcks: Int,
    ): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.CMD_SET_OTHER_PARAMS)
        w.writeByte(0x01)
        w.writeByte(allowTelemetryFlags)
        w.writeByte(advertLocationPolicy)
        w.writeByte(multiAcks)
        return w.toBytes()
    }

    /** CMD_SET_AUTO_ADD_CONFIG: [cmd][flags] */
    fun setAutoAddConfig(flags: Int): ByteArray =
        byteArrayOf(Codes.CMD_SET_AUTO_ADD_CONFIG.toByte(), (flags and 0xFF).toByte())

    /** CMD_GET_AUTO_ADD_CONFIG: [cmd] */
    fun getAutoAddConfig(): ByteArray = byteArrayOf(Codes.CMD_GET_AUTO_ADD_CONFIG.toByte())

    /** CMD_SEND_BINARY_REQ: [cmd][pubkey x32][payload] (payload[0] = req_type) */
    fun sendBinaryRequest(pubKey: ByteArray, payload: ByteArray): ByteArray {
        require(pubKey.size == PUB_KEY_SIZE) { "pubkey must be 32 bytes" }
        val w = BufferWriter()
        w.writeByte(Codes.CMD_SEND_BINARY_REQ)
        w.writeBytes(pubKey)
        w.writeBytes(payload)
        return w.toBytes()
    }

    /** Binary payload for a keep-alive to a room server. */
    fun keepAlivePayload(): ByteArray = byteArrayOf(Codes.REQ_TYPE_KEEP_ALIVE.toByte())

    /** Binary payload requesting repeater status. */
    fun statusRequestPayload(): ByteArray = byteArrayOf(Codes.REQ_TYPE_GET_STATUS.toByte())

    private fun cmdWithPubKey(cmd: Int, pubKey: ByteArray): ByteArray {
        require(pubKey.size == PUB_KEY_SIZE) { "pubkey must be 32 bytes" }
        val w = BufferWriter()
        w.writeByte(cmd)
        w.writeBytes(pubKey)
        return w.toBytes()
    }

    // --- Size limits (mirror the reference client's overhead math) ---

    private const val SEND_TEXT_MSG_OVERHEAD = 1 + 1 + 1 + 4 + 6 + 1 + 2
    private const val SEND_CHANNEL_TEXT_MSG_OVERHEAD = 1 + 1 + 1 + 4 + 1 + 2

    fun maxContactMessageBytes(): Int =
        minOf(MAX_FRAME_SIZE - SEND_TEXT_MSG_OVERHEAD, MAX_TEXT_PAYLOAD_BYTES)

    fun maxChannelMessageBytes(senderName: String?): Int {
        val nameLen = senderName?.encodeToByteArray()?.size?.coerceAtMost(MAX_NAME_SIZE - 1)
            ?: (MAX_NAME_SIZE - 1)
        val byPayload = MAX_TEXT_PAYLOAD_BYTES - (nameLen + 2)
        val byFrame = MAX_FRAME_SIZE - SEND_CHANNEL_TEXT_MSG_OVERHEAD
        return maxOf(0, minOf(byPayload, byFrame))
    }
}

/**
 * LoRa airtime (Semtech SX127x formula) and the derived ACK timeouts —
 * ported from the reference client so send-timeout behaviour matches.
 */
object Airtime {
    fun loRaAirtimeMs(
        payloadBytes: Int,
        spreadingFactor: Int,
        bandwidthHz: Int,
        codingRate: Int,
        preambleSymbols: Int = 8,
        lowDataRateOptimize: Boolean = false,
        explicitHeader: Boolean = true,
    ): Int {
        val symbolDuration = (1 shl spreadingFactor) / (bandwidthHz / 1000.0)
        val preambleTime = (preambleSymbols + 4.25) * symbolDuration
        val headerBytes = if (explicitHeader) 0 else 20
        val crc = 1
        val de = if (lowDataRateOptimize) 1 else 0
        val numerator = 8 * payloadBytes - 4 * spreadingFactor + 28 + 16 * crc - headerBytes
        val denominator = 4 * (spreadingFactor - 2 * de)
        var payloadSymbols = 8 +
            kotlin.math.ceil(numerator.toDouble() / denominator).toInt() * (codingRate + 4)
        if (payloadSymbols < 0) payloadSymbols = 8
        val payloadTime = payloadSymbols * symbolDuration
        return kotlin.math.ceil(preambleTime + payloadTime).toInt()
    }

    /** ACK timeout: flood = 500 + 16×airtime; direct = 500 + (6×airtime+250)×(hops+1). */
    fun messageTimeoutMs(
        bwHz: Int,
        sf: Int,
        cr: Int,
        pathLength: Int,
        messageBytes: Int = 100,
    ): Int {
        val airtime = loRaAirtimeMs(
            payloadBytes = messageBytes,
            spreadingFactor = sf,
            bandwidthHz = bwHz,
            codingRate = cr,
            lowDataRateOptimize = sf >= 11,
        )
        return if (pathLength < 0) {
            500 + 16 * airtime
        } else {
            500 + (airtime * 6 + 250) * (pathLength + 1)
        }
    }
}
