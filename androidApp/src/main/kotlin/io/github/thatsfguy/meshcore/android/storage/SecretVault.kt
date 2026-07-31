package io.github.thatsfguy.meshcore.android.storage

/**
 * Seal/unseal small secrets with a hardware-backed key. Implemented by
 * [KeystoreSecretVault] (Android Keystore AES-GCM; key never leaves the
 * TEE/StrongBox). Everything SCOPE.md lists as keystore-resident flows
 * through this: identity seed, repeater login passwords, channel PSKs,
 * community secrets.
 */
interface SecretVault {
    suspend fun seal(plaintext: ByteArray): ByteArray
    suspend fun unseal(sealed: ByteArray): ByteArray
}
