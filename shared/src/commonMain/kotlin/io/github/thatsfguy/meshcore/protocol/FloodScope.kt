package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.crypto.CryptoProvider

/**
 * The companion radio's SAVED flood scope — the region it tags a flood
 * with when nothing else is set.
 *
 * The companion firmware keeps two scope slots
 * (companion_radio/MyMesh.cpp:500-522):
 *
 *  - an override, `send_scope` (`CMD_SET_FLOOD_SCOPE`, 54): a bare key,
 *    in RAM only, cleared at boot (MyMesh.cpp:875). It cannot be read
 *    back. This is the one the app sets.
 *  - a default, `default_scope_name` + `default_scope_key` in the saved
 *    prefs (`CMD_SET/GET_DEFAULT_FLOOD_SCOPE`, 63/64). Used whenever the
 *    override is empty — including after the app "clears" the override.
 *    A firmware build can also bake one in (`DEFAULT_FLOOD_SCOPE_NAME`).
 *
 * So what the radio does with "no scope set in the app" depends on this
 * value, and the app has to read it to say anything true about it.
 */
sealed interface RadioDefaultScope {
    /** Not read yet (not connected, or the read is in flight). */
    data object NotRead : RadioDefaultScope

    /** The radio refused the read: firmware older than the command. */
    data object Unsupported : RadioDefaultScope

    /** No saved default: with no override, floods go out untagged. */
    data object None : RadioDefaultScope

    /**
     * A saved default. [name] is for display only — it is whatever the
     * radio was told, and the KEY is what floods are tagged with.
     * [isPublicRegion] is true when the key is the public hash of that
     * name (SHA256("#name")[0..15], TransportKeyStore.cpp:44-47), i.e.
     * repeaters that know the region name will recognise it. False means
     * a private key, or a name that does not match its key.
     */
    data class Set(val name: String, val isPublicRegion: Boolean) : RadioDefaultScope
}

object FloodScope {

    /** Longest name the firmware stores: `n > 0 && n < 31` (MyMesh.cpp:1943). */
    const val MAX_DEFAULT_NAME = 30

    fun defaultFrom(event: DeviceEvent.DefaultFloodScope, crypto: CryptoProvider): RadioDefaultScope {
        val rawName = event.name
        val key = event.key
        if (rawName.isNullOrEmpty() || key == null) return RadioDefaultScope.None
        val canonical = Regions.canonical(rawName)
        val public = canonical != null &&
            ChannelCrypto.floodScopeHash(crypto, canonical).contentEquals(key)
        // Off the radio, not the mesh — but still shown on screen, so no
        // control characters and no more than the firmware can hold.
        val shown = canonical
            ?: rawName.filter { !it.isISOControl() }.trim().take(MAX_DEFAULT_NAME)
        return RadioDefaultScope.Set(shown, public)
    }
}
