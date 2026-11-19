// Community secrets in the iOS Keychain (iOS after 0.1.0).
//
// A community code's 32-byte secret derives every channel key in the
// community, so it is kept — and kept as a secret: Keychain, readable
// only while this device is unlocked, never synced to iCloud Keychain
// and never restored to another device (WhenUnlockedThisDeviceOnly).
// If it can't be stored, the join is refused (shared ChannelJoin rule):
// a community joined without its secret can never derive the rest.

import Foundation
import Security
import Shared

enum CommunityKeychain {
    private static let service = "io.github.thatsfguy.meshcore.community"

    /// Purge with "also forget stored keys": every community secret.
    static func forgetAll() {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword,
                       kSecAttrService as String: service] as CFDictionary)
    }

    /// Store [secret] under the community's id; true when it is stored.
    static func keep(idHex: String, secret: Data) -> Bool {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: idHex,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = secret
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }
}

extension KotlinByteArray {
    /// The bytes as Data (Kotlin bytes are signed; Data's are not).
    func toData() -> Data {
        var data = Data(count: Int(size))
        for i in 0..<size { data[Int(i)] = UInt8(bitPattern: get(index: i)) }
        return data
    }
}
