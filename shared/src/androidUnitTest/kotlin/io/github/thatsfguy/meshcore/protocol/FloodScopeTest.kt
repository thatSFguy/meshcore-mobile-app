package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import io.github.thatsfguy.meshcore.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the app concludes from the radio's saved default (real SHA-256). */
class FloodScopeTest {

    private val crypto = AndroidCryptoProvider()

    /** SHA256("#mi")[0..15], computed outside this codebase. */
    private val miKey = hexToBytes("b95485d889ee9da51719e02ec556bf97")

    @Test
    fun theAppsOwnScopeHashMatchesTheRealOne() {
        // TransportKeyStore.cpp:44-47 hashes the name with its '#'.
        kotlin.test.assertContentEquals(miKey, ChannelCrypto.floodScopeHash(crypto, "mi"))
    }

    @Test
    fun aNameWithItsPublicKeyIsAPublicRegion() {
        assertEquals(
            RadioDefaultScope.Set("mi", isPublicRegion = true),
            FloodScope.defaultFrom(DeviceEvent.DefaultFloodScope("mi", miKey), crypto),
        )
        // A build-time default or another client may send "#MI".
        assertEquals(
            RadioDefaultScope.Set("mi", isPublicRegion = true),
            FloodScope.defaultFrom(DeviceEvent.DefaultFloodScope("#MI", miKey), crypto),
        )
    }

    @Test
    fun aNameThatIsNotItsKeysNameIsNotCalledPublic() {
        // The key is what floods carry. A label that doesn't match it must
        // not be reported as that region.
        val other = hexToBytes("8e18054c6de24a0944887b3b8c81f817") // "#grr"
        assertEquals(
            RadioDefaultScope.Set("mi", isPublicRegion = false),
            FloodScope.defaultFrom(DeviceEvent.DefaultFloodScope("mi", other), crypto),
        )
    }

    @Test
    fun anUnnameableLabelIsShownCleanAndNotPublic() {
        val ev = DeviceEvent.DefaultFloodScope("Bad\u001B[2J Name", miKey)
        assertEquals(
            RadioDefaultScope.Set("Bad[2J Name", isPublicRegion = false),
            FloodScope.defaultFrom(ev, crypto),
        )
    }

    @Test
    fun noNameOrNoKeyIsNoDefault() {
        assertEquals(RadioDefaultScope.None, FloodScope.defaultFrom(DeviceEvent.DefaultFloodScope(null, null), crypto))
        assertEquals(RadioDefaultScope.None, FloodScope.defaultFrom(DeviceEvent.DefaultFloodScope("", miKey), crypto))
    }
}
