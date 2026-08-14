package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.model.BatteryAndStorage
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.model.DeviceInfo
import io.github.thatsfguy.meshcore.model.SelfInfo
import io.github.thatsfguy.meshcore.util.sanitizeDisplayName

/**
 * Radio → client frame parser. One entry point, [parse], that never
 * throws: malformed/truncated frames degrade to [DeviceEvent.Unknown]
 * (or null for an empty frame) instead of crashing the RX path.
 *
 * Layouts ported from the MeshCore Open reference client
 * (`meshcore_connector.dart` handlers) per MESHCORE_PROTOCOL.md §5–§8.
 */
object ResponseParser {

    fun parse(frame: ByteArray): DeviceEvent? {
        if (frame.isEmpty()) return null
        val code = frame[0].toInt() and 0xFF
        return try {
            parseInner(code, frame)
        } catch (_: TruncatedFrameException) {
            DeviceEvent.Unknown(code, frame)
        } catch (_: IllegalArgumentException) {
            DeviceEvent.Unknown(code, frame)
        }
    }

    private fun parseInner(code: Int, frame: ByteArray): DeviceEvent = when (code) {
        Codes.RESP_CODE_OK -> DeviceEvent.Ok(frame.copyOfRange(1, frame.size))
        Codes.RESP_CODE_ERR ->
            DeviceEvent.Err(if (frame.size > 1) frame[1].toInt() and 0xFF else null)

        Codes.RESP_CODE_CONTACTS_START -> {
            val r = BufferReader(frame)
            r.skipBytes(1)
            DeviceEvent.ContactsStart(if (r.remaining >= 4) r.readUInt32LE() else null)
        }

        Codes.RESP_CODE_CONTACT ->
            parseContact(frame)?.let { DeviceEvent.ContactReceived(it, fromPush = false) }
                ?: DeviceEvent.Unknown(code, frame)

        Codes.PUSH_CODE_NEW_ADVERT ->
            parseContact(frame)?.let { DeviceEvent.ContactReceived(it, fromPush = true) }
                ?: DeviceEvent.Unknown(code, frame)

        Codes.RESP_CODE_END_OF_CONTACTS -> DeviceEvent.EndOfContacts

        Codes.RESP_CODE_SELF_INFO -> parseSelfInfo(frame)

        Codes.RESP_CODE_DEVICE_INFO -> parseDeviceInfo(frame)

        Codes.RESP_CODE_SENT -> {
            val r = BufferReader(frame)
            r.skipBytes(1)
            val isFlood = r.readByte() != 0
            DeviceEvent.Sent(isFlood, r.readUInt32LE(), r.readUInt32LE())
        }

        Codes.RESP_CODE_CONTACT_MSG_RECV, Codes.RESP_CODE_CONTACT_MSG_RECV_V3 ->
            parseContactMessage(code, frame)

        Codes.RESP_CODE_CHANNEL_MSG_RECV, Codes.RESP_CODE_CHANNEL_MSG_RECV_V3 ->
            parseChannelMessage(code, frame)

        Codes.RESP_CODE_CURR_TIME -> {
            val r = BufferReader(frame)
            r.skipBytes(1)
            DeviceEvent.CurrentTime(r.readUInt32LE())
        }

        Codes.RESP_CODE_NO_MORE_MESSAGES -> DeviceEvent.NoMoreMessages

        Codes.RESP_CODE_EXPORT_CONTACT ->
            DeviceEvent.ExportedContact(frame.copyOfRange(1, frame.size))

        Codes.RESP_CODE_BATT_AND_STORAGE -> {
            val r = BufferReader(frame)
            r.skipBytes(1)
            val mv = r.readUInt16LE()
            val used = if (r.remaining >= 4) r.readUInt32LE() else null
            val total = if (r.remaining >= 4) r.readUInt32LE() else null
            DeviceEvent.BatteryAndStorageReceived(BatteryAndStorage(mv, used, total))
        }

        Codes.RESP_CODE_CHANNEL_INFO -> parseChannelInfo(frame)

        Codes.RESP_CODE_CUSTOM_VARS -> {
            val r = BufferReader(frame)
            r.skipBytes(1)
            DeviceEvent.CustomVars(parseKeyValueString(r.readCString()))
        }

        Codes.RESP_CODE_STATS -> {
            val r = BufferReader(frame)
            r.skipBytes(1)
            val type = r.readByte()
            DeviceEvent.Stats(type, r.readRemainingBytes())
        }

        Codes.RESP_CODE_AUTO_ADD_CONFIG -> {
            val r = BufferReader(frame)
            r.skipBytes(1)
            DeviceEvent.AutoAddConfig(r.readByte())
        }

        Codes.PUSH_CODE_ADVERT -> {
            val r = BufferReader(frame)
            r.skipBytes(1)
            DeviceEvent.AdvertReheard(r.readBytes(Codes.PUB_KEY_SIZE))
        }

        Codes.PUSH_CODE_PATH_UPDATED -> {
            val r = BufferReader(frame)
            r.skipBytes(1)
            DeviceEvent.PathUpdated(r.readBytes(Codes.PUB_KEY_SIZE))
        }

        Codes.PUSH_CODE_SEND_CONFIRMED -> {
            val r = BufferReader(frame)
            r.skipBytes(1)
            DeviceEvent.SendConfirmed(r.readUInt32LE(), r.readUInt32LE())
        }

        Codes.PUSH_CODE_MSG_WAITING -> DeviceEvent.MessageWaiting

        Codes.PUSH_CODE_LOGIN_SUCCESS -> {
            val r = BufferReader(frame)
            r.skipBytes(1)
            val perm = r.readByte()
            val prefix = r.readBytes(6)
            val ts = if (r.remaining >= 4) r.readUInt32LE() else null
            DeviceEvent.LoginSuccess(perm, prefix, ts?.takeIf { it != 0L })
        }

        Codes.PUSH_CODE_LOGIN_FAIL -> DeviceEvent.LoginFail

        Codes.PUSH_CODE_STATUS_RESPONSE ->
            DeviceEvent.StatusResponse(frame.copyOfRange(1, frame.size))

        Codes.PUSH_CODE_LOG_RX_DATA -> {
            val r = BufferReader(frame)
            r.skipBytes(1)
            val snrRaw = r.readInt8()
            val rssi = r.readInt8()
            DeviceEvent.LogRxData(snrRaw / 4.0, rssi, r.readRemainingBytes())
        }

        Codes.PUSH_CODE_TRACE_DATA -> DeviceEvent.TraceData(frame.copyOfRange(1, frame.size))
        Codes.PUSH_CODE_TELEMETRY_RESPONSE ->
            DeviceEvent.TelemetryResponse(frame.copyOfRange(1, frame.size))
        Codes.PUSH_CODE_BINARY_RESPONSE ->
            DeviceEvent.BinaryResponse(frame.copyOfRange(1, frame.size))
        Codes.PUSH_CODE_CONTROL_DATA ->
            DeviceEvent.ControlData(frame.copyOfRange(1, frame.size))

        else -> DeviceEvent.Unknown(code, frame)
    }

    /**
     * Fixed 148-byte contact record (RESP_CODE_CONTACT / PUSH_CODE_NEW_ADVERT).
     * Returns null for undersized frames or garbage records (all/mostly-zero
     * pubkey, fully non-printable name) per the §8 validation guidance.
     */
    fun parseContact(frame: ByteArray): Contact? {
        if (frame.size < 148) return null
        val r = BufferReader(frame)
        r.skipBytes(1)
        val pubKey = r.readBytes(32)
        val type = r.readByte()
        val flags = r.readByte()
        val pathLen = r.readInt8()
        val path = r.readBytes(64)
        val name = sanitizeDisplayName(r.readFixedCString(32))
        val timestamp = r.readUInt32LE()
        val lat = r.readInt32LE() / 1e6
        val lon = r.readInt32LE() / 1e6
        val lastMod = r.readUInt32LE()

        // Reject junk records: >16 zero bytes in the pubkey, or a non-empty
        // name with no printable characters.
        if (pubKey.count { it.toInt() == 0 } > 16) return null
        if (name.isNotEmpty() && name.none { it.code in 0x20..0x7E || it.code > 0x9F }) return null

        return Contact(
            publicKey = pubKey,
            type = type,
            flags = flags,
            pathLen = pathLen,
            path = path,
            name = name,
            timestamp = timestamp,
            latitude = lat.takeIf { it != 0.0 || lon != 0.0 },
            longitude = lon.takeIf { it != 0.0 || lat != 0.0 },
            lastModified = lastMod,
        )
    }

    private fun parseSelfInfo(frame: ByteArray): DeviceEvent {
        val r = BufferReader(frame)
        r.skipBytes(1)
        val advType = r.readByte()
        val txPower = r.readInt8()
        val maxTxPower = r.readInt8()
        val pubKey = r.readBytes(Codes.PUB_KEY_SIZE)
        val lat = r.readInt32LE() / 1e6
        val lon = r.readInt32LE() / 1e6
        val multiAcks = r.readByte()
        val advertLocPolicy = r.readByte()
        val telemetryModes = r.readByte()
        // Inverted on the wire: bit0 set = auto-add disabled ("manual").
        val manualAdd = (r.readByte() and 0x01) == 0x01
        val freq = r.readUInt32LE()
        val bw = r.readUInt32LE()
        val sf = r.readByte()
        val cr = r.readByte()
        val name = r.readCString()
        return DeviceEvent.SelfInfoReceived(
            SelfInfo(
                advType = advType,
                txPowerDbm = txPower,
                maxTxPowerDbm = maxTxPower,
                publicKey = pubKey,
                latitude = lat,
                longitude = lon,
                multiAcks = multiAcks,
                advertLocPolicy = advertLocPolicy,
                telemetryModes = telemetryModes,
                manualAddContacts = manualAdd,
                freqKhz = freq,
                bwHz = bw,
                sf = sf,
                cr = cr,
                name = name,
            ),
        )
    }

    private fun parseDeviceInfo(frame: ByteArray): DeviceEvent {
        if (frame.size < 4) return DeviceEvent.Unknown(Codes.RESP_CODE_DEVICE_INFO, frame)
        val fwVer = frame[1].toInt() and 0xFF
        // Firmware reports MAX_CONTACTS / 2 in byte 2 for v3+.
        val reportedContacts = frame[2].toInt() and 0xFF
        val reportedChannels = frame[3].toInt() and 0xFF
        // Bytes 4..7 are the configured BLE pin — the firmware writes it
        // with memcpy(&out_frame[i], &_prefs.ble_pin, 4). It was being
        // skipped, which is why this app claimed the PIN could not be
        // read back.
        val r = BufferReader(frame)
        r.skipBytes(4)
        val blePin = if (frame.size >= 8) r.readUInt32LE() else null
        // Bytes 8..79 identify the build. MyMesh.cpp writes them right
        // after the pin and unconditionally: a 12-byte FIRMWARE_BUILD_DATE,
        // the board's getManufacturerName() in 40, then FIRMWARE_VERSION
        // in 20. Each is NUL-padded, and a value that exactly fills its
        // field has no terminator — readFixedCString handles both.
        val buildDate = if (frame.size >= 20) r.readFixedCString(12).ifEmpty { null } else null
        val boardName = if (frame.size >= 60) r.readFixedCString(40).ifEmpty { null } else null
        val fwVersion = if (frame.size >= 80) r.readFixedCString(20).ifEmpty { null } else null
        val clientRepeat = if (frame.size >= 81) frame[80].toInt() != 0 else null
        val pathHashWidth = if (frame.size >= 82) {
            ((frame[81].toInt() and 0xFF).coerceIn(0, 3)) + 1
        } else {
            1
        }
        return DeviceEvent.DeviceInfoReceived(
            DeviceInfo(
                firmwareVerCode = fwVer,
                maxContacts = if (reportedContacts > 0) reportedContacts * 2 else 0,
                maxChannels = reportedChannels,
                clientRepeat = clientRepeat,
                pathHashByteWidth = pathHashWidth,
                blePin = blePin,
                firmwareBuildDate = buildDate,
                boardName = boardName,
                firmwareVersion = fwVersion,
            ),
        )
    }

    private fun parseContactMessage(code: Int, frame: ByteArray): DeviceEvent {
        val r = BufferReader(frame)
        r.skipBytes(1)
        var snr: Double? = null
        if (code == Codes.RESP_CODE_CONTACT_MSG_RECV_V3) {
            snr = r.readInt8() / 4.0
            r.skipBytes(2) // reserved
        }
        val prefix = r.readBytes(6)
        val pathLen = r.readByte()
        val txtType = r.readByte()
        val timestamp = r.readUInt32LE()
        val isSigned = (txtType shr 2) == Codes.TXT_TYPE_SIGNED || txtType == Codes.TXT_TYPE_SIGNED
        // Signed contact messages put the room author's pubkey prefix
        // where a signature would sit; the text starts after it.
        val roomAuthorPrefix = if (isSigned && r.remaining >= 4) r.readBytes(4) else null
        val text = r.readCString()
        return DeviceEvent.ContactMessage(
            senderPrefix = prefix,
            pathLen = pathLen,
            txtType = txtType,
            timestamp = timestamp,
            text = text,
            snr = snr,
            roomAuthorPrefix = roomAuthorPrefix,
        )
    }

    private fun parseChannelMessage(code: Int, frame: ByteArray): DeviceEvent {
        val r = BufferReader(frame)
        r.skipBytes(1)
        val channelIdx: Int
        val pathLen: Int
        val txtType: Int
        var pathBytes = ByteArray(0)
        var pathHashWidth: Int? = null
        if (code == Codes.RESP_CODE_CHANNEL_MSG_RECV_V3) {
            r.skipBytes(1) // SNR
            val flags = r.readByte()
            val hasPath = (flags and 0x01) != 0
            r.skipBytes(1) // reserved
            channelIdx = r.readByte()
            val pathByte = r.readByte()
            // Top 2 bits = hash-width mode, low 6 bits = hop count.
            pathHashWidth = ((pathByte and 0xC0) shr 6) + 1
            val hopCount = pathByte and 0x3F
            pathLen = hopCount
            if (hasPath && hopCount > 0) {
                pathBytes = r.readBytes(hopCount * pathHashWidth)
            }
            txtType = r.readByte()
        } else {
            channelIdx = r.readByte()
            pathLen = r.readInt8()
            txtType = r.readByte()
        }
        val timestamp = r.readUInt32LE()
        if (txtType != Codes.TXT_TYPE_PLAIN) {
            return DeviceEvent.Unknown(code, frame)
        }
        val text = r.readCString()
        val (sender, body) = splitSenderText(text)
        return DeviceEvent.ChannelMessage(
            channelIndex = channelIdx,
            senderName = sender,
            text = body,
            timestamp = timestamp,
            pathLen = pathLen,
            pathBytes = pathBytes,
            pathHashWidth = pathHashWidth,
        )
    }

    private fun parseChannelInfo(frame: ByteArray): DeviceEvent {
        if (frame.size < 50) return DeviceEvent.Unknown(Codes.RESP_CODE_CHANNEL_INFO, frame)
        val r = BufferReader(frame)
        r.skipBytes(1)
        val index = r.readByte()
        val name = r.readFixedCString(32)
        val psk = r.readBytes(16)
        return DeviceEvent.ChannelInfoReceived(Channel(index, name, psk))
    }

    /**
     * Split unauthenticated channel text of the form "name: message".
     * Mirrors the reference client's heuristics (colon within 50 chars,
     * sender must not contain ':', '[' or ']'). Falls back to
     * ("Unknown", whole text).
     */
    fun splitSenderText(text: String): Pair<String, String> {
        val colonIndex = text.indexOf(':')
        if (colonIndex in 1 until minOf(text.length - 1, 50)) {
            val potentialSender = text.substring(0, colonIndex)
            if (!potentialSender.any { it == ':' || it == '[' || it == ']' }) {
                // Sender names are unauthenticated; strip spoofing chars.
                val offset =
                    if (colonIndex + 1 < text.length && text[colonIndex + 1] == ' ') colonIndex + 2
                    else colonIndex + 1
                return sanitizeDisplayName(potentialSender) to text.substring(offset)
            }
        }
        return "Unknown" to text
    }

    /** "k1:v1,k2:v2" → map (used by RESP_CODE_CUSTOM_VARS). */
    fun parseKeyValueString(input: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (pair in input.split(',')) {
            val trimmed = pair.trim()
            if (trimmed.isEmpty()) continue
            val sep = trimmed.indexOf(':')
            if (sep == -1) continue
            val key = trimmed.substring(0, sep).trim()
            val value = trimmed.substring(sep + 1).trim()
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }
}
