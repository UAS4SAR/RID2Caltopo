import Foundation
import R2CCore
import Security
import UIKit

/// Keychain item for one personal-account signing key.
/// Distinct service from the Teams credential (`org.ncssar.RID2CaltopoApple.caltopo`).
/// ThisDeviceOnly keeps it out of iCloud Keychain. Configuration backup does not export this service.
struct AppleCaltopoPersonalAccountKeychain: CaltopoPersonalAccountStore {
    private static let service = "org.ncssar.RID2CaltopoApple.caltopo-personal-account"
    private static let account = "binding"

    func load() throws -> CaltopoPersonalAccountRecord? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: Self.account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else {
            throw CaltopoPersonalAccountError.unavailable
        }
        return try CaltopoPersonalAccountRecord.from(json: data)
    }

    func save(_ record: CaltopoPersonalAccountRecord) throws {
        let data = try record.jsonData()
        let key: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: Self.account,
        ]
        let update = SecItemUpdate(key as CFDictionary, [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ] as CFDictionary)
        if update == errSecSuccess { return }
        guard update == errSecItemNotFound else { throw CaltopoPersonalAccountError.unavailable }
        var insert = key
        insert[kSecValueData as String] = data
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let added = SecItemAdd(insert as CFDictionary, nil)
        guard added == errSecSuccess else { throw CaltopoPersonalAccountError.unavailable }
    }

    func clear() throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: Self.account,
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw CaltopoPersonalAccountError.unavailable
        }
    }
}

/// Opens activation in Safari or the system browser. Not a WKWebView.
struct AppleCaltopoPersonalAccountBrowser: CaltopoPersonalAccountBrowser {
    func openActivationPage(_ url: URL) async throws {
        guard CaltopoPersonalAccountConnection.isActivationURL(url) else {
            throw CaltopoPersonalAccountError.invalidReturn
        }
        let opened: Bool = await withCheckedContinuation { continuation in
            DispatchQueue.main.async {
                UIApplication.shared.open(url, options: [:]) { success in
                    continuation.resume(returning: success)
                }
            }
        }
        if !opened { throw CaltopoPersonalAccountError.listenerFailed }
    }
}
