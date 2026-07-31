package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.util.sanitizeDisplayName
import io.github.thatsfguy.meshcore.util.toHex

/**
 * A parsed over-the-air ADVERT payload (MESHCORE_PROTOCOL.md §9):
 * `[pub_key x32][timestamp u32][signature x64][app_data]` where
 * app_data = `[flags][lat i32, lon i32]?[name…]?`.
 */
data class AdvertInfo(
    val publicKey: ByteArray,
    val timestamp: Long,
    val type: Int,
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
) {
    val publicKeyHex: String get() = publicKey.toHex()

    override fun equals(other: Any?): Boolean =
        other is AdvertInfo && publicKey.contentEquals(other.publicKey) &&
            timestamp == other.timestamp && type == other.type && name == other.name &&
            latitude == other.latitude && longitude == other.longitude

    override fun hashCode(): Int = publicKey.contentHashCode()
}

object Advert {
    private const val SIG_OFFSET = 36           // 32 pub_key + 4 timestamp
    private const val APP_DATA_OFFSET = 100     // SIG_OFFSET + 64

    private const val FLAG_TYPE_MASK = 0x0F
    private const val FLAG_HAS_LOCATION = 0x10
    private const val FLAG_HAS_NAME = 0x80

    /**
     * Verify the advert's Ed25519 signature: signed message is
     * `pub_key ‖ timestamp ‖ app_data` (payload with the signature
     * spliced out), key = the enclosed pub_key.
     *
     * SECURITY: callers MUST gate contact import/update on this —
     * skipping it enables identity/GPS spoofing (§12).
     */
    fun verifySignature(crypto: CryptoProvider, advertPayload: ByteArray): Boolean {
        if (advertPayload.size < APP_DATA_OFFSET) return false
        val publicKey = advertPayload.copyOfRange(0, 32)
        val signature = advertPayload.copyOfRange(SIG_OFFSET, APP_DATA_OFFSET)
        val signedMessage = advertPayload.copyOfRange(0, SIG_OFFSET) +
            advertPayload.copyOfRange(APP_DATA_OFFSET, advertPayload.size)
        return crypto.ed25519Verify(signature, signedMessage, publicKey)
    }

    /**
     * Parse WITHOUT verifying. Use [parseVerified] on any advert that
     * will mutate contact state.
     */
    fun parse(advertPayload: ByteArray): AdvertInfo? {
        return try {
            val r = BufferReader(advertPayload)
            val publicKey = r.readBytes(32)
            val timestamp = r.readUInt32LE()
            r.skipBytes(64) // signature
            val flags = r.readByte()
            var lat: Double? = null
            var lon: Double? = null
            if ((flags and FLAG_HAS_LOCATION) != 0) {
                // Claimed location with too few bytes left: reject rather
                // than fall through and read the coordinates as the name.
                if (r.remaining < 8) return null
                lat = r.readInt32LE() / 1e6
                lon = r.readInt32LE() / 1e6
            }
            val plausible = lat != null && lon != null &&
                (kotlin.math.abs(lat) > 1e-6 || kotlin.math.abs(lon) > 1e-6) &&
                lat in -90.0..90.0 && lon in -180.0..180.0
            val name = if ((flags and FLAG_HAS_NAME) != 0 && r.remaining > 0) {
                sanitizeDisplayName(r.readCString(Codes.MAX_NAME_SIZE))
            } else {
                ""
            }
            AdvertInfo(
                publicKey = publicKey,
                timestamp = timestamp,
                type = flags and FLAG_TYPE_MASK,
                name = name,
                latitude = if (plausible) lat else null,
                longitude = if (plausible) lon else null,
            )
        } catch (_: TruncatedFrameException) {
            null
        }
    }

    /** Parse + verify in one step; null when either fails. */
    fun parseVerified(crypto: CryptoProvider, advertPayload: ByteArray): AdvertInfo? {
        if (!verifySignature(crypto, advertPayload)) return null
        return parse(advertPayload)
    }

    /**
     * Build a signed advert payload — used for tests and QR "share self"
     * flows. [appData] = flags byte + optional latlon + optional name.
     */
    fun build(
        crypto: CryptoProvider,
        seed: ByteArray,
        timestamp: Long,
        appData: ByteArray,
    ): ByteArray {
        val publicKey = crypto.ed25519PublicKey(seed)
        val w = BufferWriter()
        w.writeBytes(publicKey)
        w.writeUInt32LE(timestamp)
        val unsigned = w.toBytes() + appData
        val signature = crypto.ed25519Sign(unsigned, seed)
        return w.toBytes() + signature + appData
    }

    /** Encode advert app_data. */
    fun buildAppData(type: Int, name: String?, lat: Double?, lon: Double?): ByteArray {
        var flags = type and FLAG_TYPE_MASK
        val hasLocation = lat != null && lon != null
        if (hasLocation) flags = flags or FLAG_HAS_LOCATION
        if (!name.isNullOrEmpty()) flags = flags or FLAG_HAS_NAME
        val w = BufferWriter()
        w.writeByte(flags)
        if (hasLocation) {
            w.writeInt32LE((lat!! * 1e6).toInt())
            w.writeInt32LE((lon!! * 1e6).toInt())
        }
        if (!name.isNullOrEmpty()) w.writeString(name)
        return w.toBytes()
    }
}
