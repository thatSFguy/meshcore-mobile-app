package io.github.thatsfguy.meshcore.model

import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.util.toHex

/** A contact record as reported by the radio (RESP_CODE_CONTACT / PUSH_CODE_NEW_ADVERT). */
data class Contact(
    val publicKey: ByteArray,
    val type: Int,               // Codes.ADV_TYPE_*
    val flags: Int,
    /**
     * RAW `path_len` byte as the radio stores it — packed, not a length:
     * low 6 bits = hops, top 2 bits = hash-width mode. Kept raw so it can
     * be written straight back in CMD_ADD_UPDATE_CONTACT; read it through
     * [pathInfo] rather than using it as a count of anything.
     */
    val pathLen: Int,            // 0xFF/-1 = flood (no stored path)
    val path: ByteArray,
    val name: String,
    val timestamp: Long,         // epoch seconds (advert timestamp)
    val latitude: Double?,
    val longitude: Double?,
    val lastModified: Long,
) {
    val publicKeyHex: String get() = publicKey.toHex()

    /** Decoded view of [pathLen] — hops, hash width, and path byte length. */
    val pathInfo: io.github.thatsfguy.meshcore.protocol.PathCodec.PathInfo
        get() = io.github.thatsfguy.meshcore.protocol.PathCodec.decodePathLen(pathLen)

    /** The bytes of [path] that are actually the stored route. */
    val storedPath: ByteArray
        get() = pathInfo.byteLength
            .coerceAtMost(path.size)
            .let { if (it <= 0) ByteArray(0) else path.copyOfRange(0, it) }
    val isRepeater: Boolean get() = type == Codes.ADV_TYPE_REPEATER
    val isRoom: Boolean get() = type == Codes.ADV_TYPE_ROOM
    val isChat: Boolean get() = type == Codes.ADV_TYPE_CHAT

    /** Advertised location, when plausible (non-zero, in range). */
    val hasValidLocation: Boolean
        get() {
            val lat = latitude ?: return false
            val lon = longitude ?: return false
            return (kotlin.math.abs(lat) > 1e-6 || kotlin.math.abs(lon) > 1e-6) &&
                lat in -90.0..90.0 && lon in -180.0..180.0
        }

    override fun equals(other: Any?): Boolean =
        other is Contact && publicKey.contentEquals(other.publicKey) &&
            type == other.type && flags == other.flags && pathLen == other.pathLen &&
            path.contentEquals(other.path) && name == other.name &&
            timestamp == other.timestamp && latitude == other.latitude &&
            longitude == other.longitude && lastModified == other.lastModified

    override fun hashCode(): Int = publicKey.contentHashCode()
}

/** One channel slot on the radio (RESP_CODE_CHANNEL_INFO). */
data class Channel(
    val index: Int,
    val name: String,
    val psk: ByteArray,          // 16 bytes
) {
    val isEmpty: Boolean get() = name.isEmpty() && psk.all { it.toInt() == 0 }
    val pskHex: String get() = psk.toHex()

    override fun equals(other: Any?): Boolean =
        other is Channel && index == other.index && name == other.name &&
            psk.contentEquals(other.psk)

    override fun hashCode(): Int = index * 31 + name.hashCode()
}

/** The radio's own identity/config (RESP_CODE_SELF_INFO). */
data class SelfInfo(
    val advType: Int,
    val txPowerDbm: Int,
    val maxTxPowerDbm: Int,
    val publicKey: ByteArray,
    val latitude: Double,
    val longitude: Double,
    val multiAcks: Int,
    val advertLocPolicy: Int,
    val telemetryModes: Int,
    val manualAddContacts: Boolean,
    val freqKhz: Long,
    val bwHz: Long,
    val sf: Int,
    val cr: Int,
    val name: String,
) {
    val publicKeyHex: String get() = publicKey.toHex()

    override fun equals(other: Any?): Boolean =
        other is SelfInfo && advType == other.advType && txPowerDbm == other.txPowerDbm &&
            maxTxPowerDbm == other.maxTxPowerDbm && publicKey.contentEquals(other.publicKey) &&
            latitude == other.latitude && longitude == other.longitude &&
            multiAcks == other.multiAcks && advertLocPolicy == other.advertLocPolicy &&
            telemetryModes == other.telemetryModes &&
            manualAddContacts == other.manualAddContacts &&
            freqKhz == other.freqKhz && bwHz == other.bwHz && sf == other.sf && cr == other.cr &&
            name == other.name

    override fun hashCode(): Int = publicKey.contentHashCode()
}

/** RESP_CODE_DEVICE_INFO. */
data class DeviceInfo(
    val firmwareVerCode: Int,
    val maxContacts: Int,
    val maxChannels: Int,
    val clientRepeat: Boolean?,
    val pathHashByteWidth: Int,
)

/** RESP_CODE_BATT_AND_STORAGE. */
data class BatteryAndStorage(
    val batteryMillivolts: Int,
    val storageUsedKb: Long?,
    val storageTotalKb: Long?,
)
