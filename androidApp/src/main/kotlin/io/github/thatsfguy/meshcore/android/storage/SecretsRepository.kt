package io.github.thatsfguy.meshcore.android.storage

import android.util.Base64

/**
 * Keystore-sealed secret storage (SCOPE.md security carry-over: login
 * passwords, channel PSKs, community secrets, identity seed — never
 * plaintext prefs).
 *
 * Blobs are sealed by [SecretVault] (AES-GCM, key in TEE) and the
 * sealed form is stored base64 in SharedPreferences. If the Keystore is
 * unavailable on a device (vault throws), secrets are simply NOT stored
 * — the user re-enters passwords per session rather than degrading to
 * plaintext.
 */
class SecretsRepository(
    private val prefs: Preferences,
    private val vault: SecretVault,
) {

    // --- Repeater/room login passwords (per repeater pubkey) ---

    suspend fun storeLoginPassword(repeaterKeyHex: String, password: String): Boolean =
        putSecret("login_$repeaterKeyHex", password.encodeToByteArray())

    suspend fun loginPassword(repeaterKeyHex: String): String? =
        getSecret("login_$repeaterKeyHex")?.decodeToString()

    fun forgetLoginPassword(repeaterKeyHex: String) {
        prefs.putSealed("login_$repeaterKeyHex", null)
    }

    // --- Community secrets (32-byte K per community id) ---

    suspend fun storeCommunitySecret(communityIdHex: String, secret: ByteArray): Boolean =
        putSecret("community_$communityIdHex", secret)

    suspend fun communitySecret(communityIdHex: String): ByteArray? =
        getSecret("community_$communityIdHex")

    suspend fun communityIds(): List<String> =
        prefs.sealedKeys("community_").map { it.removePrefix("community_") }

    fun forgetCommunitySecret(communityIdHex: String) {
        prefs.putSealed("community_$communityIdHex", null)
    }

    // --- App-side identity seed (for vanity keygen / repeater import) ---

    suspend fun storeIdentitySeed(seed: ByteArray): Boolean =
        putSecret("identity_seed", seed)

    suspend fun identitySeed(): ByteArray? = getSecret("identity_seed")

    // --- Channel PSK sealing for the DB cache ---

    suspend fun sealPsk(psk: ByteArray): ByteArray? =
        runCatching { vault.seal(psk) }.getOrNull()

    suspend fun unsealPsk(sealed: ByteArray): ByteArray? =
        runCatching { vault.unseal(sealed) }.getOrNull()

    private suspend fun putSecret(key: String, value: ByteArray): Boolean =
        runCatching {
            prefs.putSealed(key, Base64.encodeToString(vault.seal(value), Base64.NO_WRAP))
            true
        }.getOrDefault(false)

    private suspend fun getSecret(key: String): ByteArray? =
        runCatching {
            val b64 = prefs.getSealed(key) ?: return null
            vault.unseal(Base64.decode(b64, Base64.NO_WRAP))
        }.getOrNull()
}
