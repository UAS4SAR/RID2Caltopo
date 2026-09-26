import CryptoKit
import Foundation

/// Local acceptance is deliberately separate from transferable organization configuration.
public struct ApplicationTermsAcceptance: Codable, Equatable, Sendable {
    public static let currentVersion = "2026-09-25.1"
    public let version: String
    public let text: String
    public let acceptedAt: Date

    public init(version: String = Self.currentVersion, text: String = ApplicationLaunchDisclaimer.text, acceptedAt: Date = Date()) {
        self.version = version
        self.text = text
        self.acceptedAt = acceptedAt
    }

    public func isCurrent(version: String = Self.currentVersion, text: String = ApplicationLaunchDisclaimer.text) -> Bool {
        self.version == version && self.text == text && acceptedAt.timeIntervalSince1970 > 0
    }

    public var fingerprint: String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    public func logMessage(_ event: String) -> String {
        "\(event) version=\(version) sha256=\(fingerprint) acceptedAt=\(ISO8601DateFormatter().string(from: acceptedAt)) scope=local-installation"
    }

    public static func load(from defaults: UserDefaults = .standard) -> Self? {
        guard let data = defaults.data(forKey: storageKey) else { return nil }
        return try? JSONDecoder().decode(Self.self, from: data)
    }

    public func save(to defaults: UserDefaults = .standard) throws {
        defaults.set(try JSONEncoder().encode(self), forKey: Self.storageKey)
    }

    private static let storageKey = "applicationTerms.acceptance"
}
