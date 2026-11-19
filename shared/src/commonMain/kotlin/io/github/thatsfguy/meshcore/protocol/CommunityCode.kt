package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.util.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A community QR code: `{"type":"meshcore_community","v":1,"name":…,"k":<base64url 32 bytes>}`.
 *
 * The 32-byte secret K derives every channel key in the community; the
 * join writes the community's public channel and must KEEP K (in the
 * platform keystore) — without it the others can never be derived, and
 * the QR is usually gone by the time anyone notices (Android's rule).
 *
 * Parsed here rather than with Android's org.json, so iOS reads the same
 * codes the same way (2026-11-18). Everything in a code is attacker-
 * controlled: wrong type, version, name or key length is refused.
 */
object CommunityCode {

    class Community(val name: String, val secret: ByteArray)

    private const val TYPE = "meshcore_community"
    private const val MAX_TEXT = 1024

    fun isCommunity(text: String): Boolean = ScannedCode.classify(text) == ScannedCode.Community

    @OptIn(ExperimentalEncodingApi::class)
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    @OptIn(ExperimentalEncodingApi::class)
    fun parse(text: String): Community? {
        val t = text.trim()
        if (t.length > MAX_TEXT) return null
        val obj = runCatching { Json.parseToJsonElement(t) as? JsonObject }.getOrNull() ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != TYPE) return null
        if (obj["v"]?.jsonPrimitive?.intOrNull != 1) return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() && it.encodeToByteArray().size < Codes.MAX_NAME_SIZE } ?: return null
        val k = obj["k"]?.jsonPrimitive?.contentOrNull ?: return null
        val secret = runCatching { base64Url.decode(k) }.getOrNull()?.takeIf { it.size == 32 } ?: return null
        return Community(name, secret)
    }

    fun idHex(crypto: CryptoProvider, secret: ByteArray): String = ChannelCrypto.communityId(crypto, secret).toHex()

    fun publicPsk(crypto: CryptoProvider, secret: ByteArray): ByteArray = ChannelCrypto.communityPublicPsk(crypto, secret)
}
