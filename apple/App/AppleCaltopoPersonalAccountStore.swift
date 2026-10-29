import AuthenticationServices
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

/// Auth session over the app. Google sign-in stays in that session, not in a WKWebView.
/// The session stays in the foreground, so the loopback listener is not frozen.
struct AppleCaltopoPersonalAccountBrowser: CaltopoPersonalAccountBrowser {
    private let session = AppleCaltopoAuthSession()

    func openActivationPage(_ url: URL) async throws {
        guard CaltopoPersonalAccountConnection.isActivationURL(url) else {
            throw CaltopoPersonalAccountError.invalidReturn
        }
        try await session.present(url)
    }

    func dismissActivation() {
        session.dismiss()
    }
}

final class AppleCaltopoAuthSession: NSObject, ASWebAuthenticationPresentationContextProviding, @unchecked Sendable {
    private var authSession: ASWebAuthenticationSession?
    private var intentionalDismiss = false

    func present(_ url: URL) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            DispatchQueue.main.async {
                let session = ASWebAuthenticationSession(
                    url: url,
                    callbackURLScheme: CaltopoPersonalAccountConnection.returnScheme
                ) { callback, _ in
                    if self.intentionalDismiss { return }
                    if callback?.scheme?.lowercased() == CaltopoPersonalAccountConnection.returnScheme { return }
                    CaltopoPersonalAccountConnection.shared.cancelActiveEnrollment()
                }
                session.presentationContextProvider = self
                session.prefersEphemeralWebBrowserSession = false
                self.authSession = session
                if session.start() {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: CaltopoPersonalAccountError.listenerFailed)
                }
            }
        }
    }

    func dismiss() {
        DispatchQueue.main.async {
            self.intentionalDismiss = true
            self.authSession?.cancel()
            self.authSession = nil
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        return scene?.windows.first { $0.isKeyWindow } ?? scene?.windows.first ?? ASPresentationAnchor()
    }
}
