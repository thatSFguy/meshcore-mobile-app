// SPDX-License-Identifier: AGPL-3.0-only
//
// Swift wrapper exposing CryptoKit's Ed25519 surface as C-callable
// functions, consumed from Kotlin/Native by cinterop.
//
// Why this file exists: CommonCrypto — which `IosCryptoProvider` already
// uses for SHA-2, HMAC and AES — has no Curve25519 at all. CryptoKit
// does, but its types are Swift-only and do not bridge to Obj-C, so a
// thin C ABI is the way across.
//
// Why it matters more here than it looks: MeshCore adverts are Ed25519
// signed, and this app REFUSES to import a contact from an advert it
// cannot verify (MESHCORE_PROTOCOL §12). Without these four functions
// the iOS build is not merely missing a feature — contact import has to
// stay switched off, because the alternative is trusting an unverified
// identity, which is the first finding the security review raised.
//
// Naming: `mch_` = MeshCore Hardened. Short, and unlikely to collide
// with anything else an app might link.
//
// Ported from the sibling reticulum-mobile-app's iosCryptoBridge, which
// is the same author's and the designated code foundation (REUSE.md).
// The X25519 key-agreement half is deliberately NOT carried over:
// MeshCore's companion protocol never does key agreement on the client,
// and unused crypto is attack surface.
//
// Return convention:
//   - keygen returns void (CryptoKit's `init()` cannot fail)
//   - functions taking a key or signature return Int32: 0 on success,
//     negative when CryptoKit rejected the input
//   - verify returns 1 = valid, 0 = invalid, -1 = public key unparseable
//
// NOTE ON KEY FORMAT: every function here takes a 32-byte SEED, which is
// what `CryptoProvider.ed25519Sign(message, seed)` passes and what
// Bouncy Castle's `Ed25519PrivateKeyParameters(seed, 0)` consumes on
// Android. MeshCore's *expanded* private key (SHA-512 of the seed, per
// `MeshIdentity.expandedPrivateKey`) is a different thing used for the
// firmware's identity format, and must never be handed to these.

import CryptoKit
import Foundation
import Security

// MARK: - Ed25519 (signing)

@_cdecl("mch_ed25519_keygen")
public func mch_ed25519_keygen(_ out: UnsafeMutablePointer<UInt8>) {
    let key = Curve25519.Signing.PrivateKey()
    key.rawRepresentation.withUnsafeBytes { src in
        if let base = src.baseAddress { memcpy(out, base, 32) }
    }
}

@_cdecl("mch_ed25519_pubkey")
public func mch_ed25519_pubkey(
    _ seed: UnsafePointer<UInt8>,
    _ out: UnsafeMutablePointer<UInt8>
) -> Int32 {
    let seedData = Data(bytes: seed, count: 32)
    do {
        let p = try Curve25519.Signing.PrivateKey(rawRepresentation: seedData)
        p.publicKey.rawRepresentation.withUnsafeBytes { src in
            if let base = src.baseAddress { memcpy(out, base, 32) }
        }
        return 0
    } catch {
        return -1
    }
}

@_cdecl("mch_ed25519_sign")
public func mch_ed25519_sign(
    _ seed: UnsafePointer<UInt8>,
    _ msg: UnsafePointer<UInt8>,
    _ msgLen: Int32,
    _ out: UnsafeMutablePointer<UInt8>
) -> Int32 {
    let seedData = Data(bytes: seed, count: 32)
    let msgData = Data(bytes: msg, count: Int(msgLen))
    do {
        let p = try Curve25519.Signing.PrivateKey(rawRepresentation: seedData)
        let sig = try p.signature(for: msgData)
        sig.withUnsafeBytes { src in
            if let base = src.baseAddress { memcpy(out, base, 64) }
        }
        return 0
    } catch {
        return -1
    }
}

@_cdecl("mch_ed25519_verify")
public func mch_ed25519_verify(
    _ sig: UnsafePointer<UInt8>,
    _ msg: UnsafePointer<UInt8>,
    _ msgLen: Int32,
    _ pub: UnsafePointer<UInt8>
) -> Int32 {
    let sigData = Data(bytes: sig, count: 64)
    let msgData = Data(bytes: msg, count: Int(msgLen))
    let pubData = Data(bytes: pub, count: 32)
    do {
        let pk = try Curve25519.Signing.PublicKey(rawRepresentation: pubData)
        return pk.isValidSignature(sigData, for: msgData) ? 1 : 0
    } catch {
        return -1
    }
}

// MARK: - Secret-vault wrapping key (Keychain)
//
// The iOS analogue of Android's Keystore-backed `KeystoreSecretVault`.
// A single random 32-byte AES key lives in the Keychain and everything
// this app seals — repeater login passwords, channel PSKs, community
// secrets, the identity seed — is encrypted under keys derived from it.
//
// `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` is the load-bearing
// part: readable only while the device is unlocked, never synced to
// iCloud Keychain, never restored onto a different device. An attacker
// who walks off with the app's database gets ciphertext.
//
// Returns 0 on success; negative on failure, and the caller must then
// DECLINE to store secrets rather than fall back to plaintext — the
// same rule Android follows when the Keystore is unavailable.
@_cdecl("mch_keychain_get_or_create_key")
public func mch_keychain_get_or_create_key(_ out: UnsafeMutablePointer<UInt8>) -> Int32 {
    let service = "io.github.thatsfguy.meshcore.secret-vault"
    let account = "master-key-v1"

    let lookup: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service,
        kSecAttrAccount as String: account,
        kSecReturnData as String: true,
        kSecMatchLimit as String: kSecMatchLimitOne,
    ]

    var item: CFTypeRef?
    let readStatus = SecItemCopyMatching(lookup as CFDictionary, &item)
    if readStatus == errSecSuccess, let data = item as? Data, data.count == 32 {
        data.withUnsafeBytes { src in
            if let base = src.baseAddress { memcpy(out, base, 32) }
        }
        return 0
    }
    // Anything other than "not found" — typically the device being
    // locked — is surfaced rather than swallowed. Minting a SECOND key
    // here would silently orphan every secret sealed under the first.
    if readStatus != errSecItemNotFound {
        return -2
    }

    var keyBytes = [UInt8](repeating: 0, count: 32)
    let rngStatus = SecRandomCopyBytes(kSecRandomDefault, 32, &keyBytes)
    if rngStatus != errSecSuccess { return -3 }
    let keyData = Data(keyBytes)

    let insert: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service,
        kSecAttrAccount as String: account,
        kSecValueData as String: keyData,
        kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
    ]
    let addStatus = SecItemAdd(insert as CFDictionary, nil)
    if addStatus != errSecSuccess { return -4 }
    keyData.withUnsafeBytes { src in
        if let base = src.baseAddress { memcpy(out, base, 32) }
    }
    return 0
}
