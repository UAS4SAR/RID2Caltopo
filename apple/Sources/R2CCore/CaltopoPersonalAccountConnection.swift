import Darwin
import Foundation
import os
import Security

public enum CaltopoPersonalLoginMode: String, Sendable, Hashable {
    case credentials
    case sessionCookies

    /// A saved cookie personal login keeps Session Cookies until the operator chooses otherwise.
    public static func resolve(stored: String?, cookieSessionAlreadyInUse: Bool) -> CaltopoPersonalLoginMode {
        switch stored {
        case "credentials": return .credentials
        case "sessionCookies": return .sessionCookies
        default: return cookieSessionAlreadyInUse ? .sessionCookies : .credentials
        }
    }
}

/// Personal CalTopo account credential from the Desktop activation flow.
///
/// The signing key stays out of Teams credential storage and out of the cookie
/// login. Credentials mode is the default. Session Cookies mode does not read
/// this store, and personal map requests keep using the browser session.
///
/// Enrollment and the activation exchange run off the main thread. A flag-off
/// lookup is only a memory read.
public final class CaltopoPersonalAccountConnection: @unchecked Sendable {
    public static let shared = CaltopoPersonalAccountConnection()
    public static let featureFlagKey = "caltopo.personalAccountActivation"
    public static let defaultDeviceName = "RID2Caltopo"
    public static let credentialTitle = "Last synced by RID2Caltopo app"
    private static let credentialLog = Logger(subsystem: "org.ncssar.RID2Caltopo", category: "CalTopoAccount")
    private static let pendingLifetime: TimeInterval = 5 * 60

    private let lock = NSLock()
    private let storageLock = NSLock()
    private var storageRevision: UInt64 = 0
    private var featureEnabled = false
    private var store: any CaltopoPersonalAccountStore = CaltopoPersonalAccountMemoryStore()
    private var cached: CaltopoPersonalAccountRecord?
    private var cacheValid = false
    private var pending: PendingActivation?
    private var clock: () -> Date = { Date() }
    private var randomBytes: (Int) -> Data = { count in
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return Data(bytes)
    }

    public init() {}

    public func loadFeatureFlag(from defaults: UserDefaults = .standard) {
        lock.lock()
        featureEnabled = defaults.bool(forKey: Self.featureFlagKey)
        lock.unlock()
    }

    public func setFeatureEnabled(_ enabled: Bool) {
        lock.lock()
        featureEnabled = enabled
        lock.unlock()
    }

    public var isFeatureEnabled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return featureEnabled
    }

    public func use(_ store: any CaltopoPersonalAccountStore) {
        lock.lock()
        self.store = store
        storageRevision &+= 1
        cached = nil
        cacheValid = false
        pending = nil
        lock.unlock()
    }

    /// Deterministic hooks for tests. Not used by the app.
    func useTestClock(_ clock: @escaping () -> Date, random: @escaping (Int) -> Data) {
        lock.lock()
        self.clock = clock
        self.randomBytes = random
        lock.unlock()
    }

    public func status(host: String) -> CaltopoPersonalAccountStatus? {
        guard let record = try? stored(), let normalized = try? normalizeHost(host), record.host == normalized else { return nil }
        return record.status
    }

    public func reconnect(host: String) -> CaltopoPersonalAccountStatus? {
        guard let current = status(host: host), !current.revoked else { return nil }
        return current
    }

    public func disconnect() {
        storageLock.lock()
        defer { storageLock.unlock() }
        lock.lock()
        let target = store
        cached = nil
        cacheValid = false
        pending = nil
        storageRevision &+= 1
        lock.unlock()
        try? target.clear()
    }

    /// UI configuration reads never enter Keychain or wait behind a Keychain operation.
    public func cachedStatus(host: String) -> CaltopoPersonalAccountStatus? {
        lock.lock()
        defer { lock.unlock() }
        guard cacheValid, let cached, cached.host == host else { return nil }
        return cached.status
    }

    public func cachedLookup(host: String, accountID: String) -> CaltopoPersonalSignedLookup {
        lock.lock()
        defer { lock.unlock() }
        guard featureEnabled else { return .inactive }
        guard cacheValid else { return .unavailable }
        guard let cached, cached.host == host, cached.accountID == accountID else { return .absent }
        return cached.revoked ? .revoked(accountID: cached.accountID) : .ready(cached)
    }

    public func markRevoked() {
        guard var record = try? stored() else { return }
        record.revoked = true
        try? write(record)
    }

    public func lookup(host: String, accountID: String) -> CaltopoPersonalSignedLookup {
        let enabled = isFeatureEnabled
        guard enabled else { return .inactive }
        let record: CaltopoPersonalAccountRecord?
        do { record = try stored() } catch { return .unavailable }
        guard let record else { return .absent }
        guard record.host == (try? normalizeHost(host)), record.accountID == accountID else { return .absent }
        return record.revoked ? .revoked(accountID: record.accountID) : .ready(record)
    }

    public func resolveLiveConfiguration(
        lookup: CaltopoPersonalSignedLookup,
        mapID: String,
        connectKey: String,
        personalSessionID: UUID?,
        personalAccountID: String,
        personalMediaOwnerID: String,
        useSignedPersonalAccount: Bool = false,
        fallback: CaltopoLiveConfiguration?
    ) -> CaltopoLiveConfiguration? {
        let signedPersonal = personalSessionID != nil || useSignedPersonalAccount
        switch lookup {
        case .inactive:
            return fallback
        case .absent:
            return useSignedPersonalAccount ? nil : fallback
        case .unavailable, .revoked:
            return signedPersonal ? nil : fallback
        case .ready(let record):
            guard signedPersonal, !record.revoked else { return fallback }
            let account = personalAccountID.isEmpty ? record.accountID : personalAccountID
            guard account == record.accountID else { return useSignedPersonalAccount ? nil : fallback }
            return CaltopoLiveConfiguration(
                domainAndPort: record.host,
                mapID: mapID,
                credentialID: record.credentialID,
                credentialSecretBase64: record.signingKey,
                connectKey: connectKey,
                personalSessionID: nil,
                personalAccountID: account,
                personalMediaOwnerID: personalMediaOwnerID.isEmpty ? record.accountID : personalMediaOwnerID,
                personalSignedAccount: true
            )
        }
    }

    public func teamMapConfiguration() throws -> CaltopoTeamMapConfiguration {
        guard isFeatureEnabled, case .ready(let record) = lookup(host: storedHost, accountID: storedAccount) else {
            throw CaltopoPersonalAccountError.rejected
        }
        return CaltopoTeamMapConfiguration(
            domainAndPort: record.host,
            teamID: record.accountID,
            credentialID: record.credentialID,
            credentialSecretBase64: record.signingKey
        )
    }

    public func begin(host: String = "caltopo.com", deviceName: String = defaultDeviceName, port: Int, now: Date? = nil) throws -> PendingActivation {
        let normalized = try normalizeHost(host)
        let name = try normalizeDeviceName(deviceName)
        guard (1...65535).contains(port) else { throw CaltopoPersonalAccountError.invalidReturn }
        let createdAt = now ?? currentDate()
        let pathToken = hex(random())
        // CalTopo appends "/client/finish-activate?code&name" onto this string.
        let loopback = "http://127.0.0.1:\(port)/\(pathToken)"
        let activation = try activationURL(host: normalized, loopback: loopback, deviceName: name)
        let created = PendingActivation(
            host: normalized,
            deviceName: name,
            port: port,
            pathToken: pathToken,
            activationURL: activation,
            expiresAt: createdAt.addingTimeInterval(Self.pendingLifetime)
        )
        lock.lock()
        pending = created
        lock.unlock()
        return created
    }

    public func acceptCallback(_ requestTarget: String, now: Date = Date()) throws -> CaltopoActivationReturn {
        lock.lock()
        let current = pending
        lock.unlock()
        guard let current else { throw CaltopoPersonalAccountError.rejected }
        if now > current.expiresAt { throw CaltopoPersonalAccountError.expired }
        let returned = try callbackCode(requestTarget, pending: current)
        lock.lock()
        pending = nil
        lock.unlock()
        return returned
    }

    public func completeExchange(_ body: Data, code: String, host: String, deviceName: String, now: Date = Date()) throws -> CaltopoPersonalAccountRecord {
        let parsed = try Self.parseActivationResponse(body)
        guard Self.activationCode.matches(code) else { throw CaltopoPersonalAccountError.rejected }
        return CaltopoPersonalAccountRecord(
            host: try normalizeHost(host),
            accountID: parsed.accountID,
            credentialID: parsed.credentialID,
            signingKey: parsed.signingKey,
            deviceName: try normalizeDeviceName(deviceName),
            enrolledAt: now,
            revoked: false,
            displayName: parsed.displayName
        )
    }

    public func enroll(
        host: String = "caltopo.com",
        deviceName: String = defaultDeviceName,
        browser: any CaltopoPersonalAccountBrowser,
        exchange: any CaltopoPersonalAccountExchange,
        timeout: TimeInterval = 5 * 60
    ) async throws -> CaltopoPersonalAccountStatus {
        let bound = try CaltopoPersonalAccountLoopback.bind()
        defer { Darwin.close(bound.fd) }
        let pending = try begin(host: host, deviceName: deviceName, port: bound.port)
        let gate = ActivationGate()
        let thread = Thread {
            gate.finish(Result {
                try CaltopoPersonalAccountLoopback.awaitCode(fd: bound.fd, pending: pending, timeout: timeout)
            })
        }
        thread.name = "caltopo-personal-activate"
        thread.start()
        try await browser.openActivationPage(pending.activationURL)
        let returned = try await Task.detached { try gate.wait() }.value
        let response = try await exchange.get(url: try exchangeURL(host: pending.host, code: returned.code, deviceName: returned.deviceName))
        guard response.status == 200 else { throw CaltopoPersonalAccountError.exchangeFailed(response.status) }
        let record = try completeExchange(response.body, code: returned.code, host: pending.host, deviceName: returned.deviceName)
        try write(record)
        await publishCredentialTitle(record)
        return record.status
    }

    public func credentialTitleRequest(_ record: CaltopoPersonalAccountRecord, now: Date = Date()) throws -> CaltopoCredentialTitleRequest {
        let path = "/api/v1/acct/\(record.accountID)/UserCredential/\(record.credentialID)"
        let json = "{\"id\":\"\(record.credentialID)\",\"properties\":{\"class\":\"UserCredential\",\"title\":\"\(Self.credentialTitle)\"}}"
        let expires = Int64(now.timeIntervalSince1970 * 1_000) + CaltopoRequestSigner.validityMilliseconds
        let signature = try CaltopoRequestSigner.signature(
            method: "POST", path: path, expiresMilliseconds: expires, payload: json,
            credentialSecretBase64: record.signingKey
        )
        guard let url = URL(string: "https://\(record.host)\(path)") else { throw CaltopoPersonalAccountError.rejected }
        return CaltopoCredentialTitleRequest(url: url, json: json, fields: [
            "id": record.credentialID,
            "expires": String(expires),
            "signature": signature,
            "json": json,
        ])
    }

    private func publishCredentialTitle(_ record: CaltopoPersonalAccountRecord) async {
        do {
            let request = try credentialTitleRequest(record)
            let status = try await CaltopoPersonalAccountHTTPSExchange().postForm(url: request.url, fields: request.fields)
            if status != 200 { Self.credentialLog.error("CalTopo credential title update failed") }
        } catch {
            Self.credentialLog.error("CalTopo credential title update failed")
        }
    }

    public static func parseActivationResponse(_ body: Data) throws -> ParsedActivation {
        guard body.count <= 65_536,
              let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else {
            throw CaltopoPersonalAccountError.rejected
        }
        if json["status"] as? String == "error" { throw CaltopoPersonalAccountError.rejected }
        let source = json["result"] as? [String: Any] ?? json
        let account = source["account"] as? [String: Any]
        let properties = account?["properties"] as? [String: Any]
        if let kind = properties?["class"] as? String, kind != "UserAccount" {
            throw CaltopoPersonalAccountError.notPersonalAccount
        }
        guard properties?["class"] as? String == "UserAccount" else { throw CaltopoPersonalAccountError.notPersonalAccount }
        let accountID = account?["id"] as? String ?? ""
        let accountType = account?["type"] as? String ?? ""
        let credentialID = source["code"] as? String ?? ""
        let signingKey = source["key"] as? String ?? ""
        if let expires = properties?["subscriptionExpires"], !(expires is NSNumber) {
            throw CaltopoPersonalAccountError.rejected
        }
        guard accountType == "account",
              accountID.range(of: "^[A-Za-z0-9]{6}$", options: .regularExpression) != nil,
              credentialID.range(of: "^[A-Za-z0-9]{12}$", options: .regularExpression) != nil,
              signingKey.count == 44,
              let key = Data(base64Encoded: signingKey), key.count == 32 else {
            throw CaltopoPersonalAccountError.rejected
        }
        let person = [properties?["firstName"] as? String, properties?["lastName"] as? String]
            .compactMap { $0?.nilIfBlank }.joined(separator: " ")
        let display = person.nilIfBlank
            ?? (properties?["title"] as? String)?.nilIfBlank
            ?? (properties?["email"] as? String)?.nilIfBlank
            ?? accountID
        return ParsedActivation(accountID: accountID, credentialID: credentialID, signingKey: signingKey, displayName: display, accountType: accountType)
    }

    public static func customSchemePrefix(scheme: String, nonce: String) throws -> String {
        guard scheme.range(of: "^[a-z][a-z0-9+.-]{0,31}$", options: .regularExpression) != nil,
              scheme != "http", scheme != "https",
              nonce.range(of: "^[0-9a-f]{32}$", options: .regularExpression) != nil else {
            throw CaltopoPersonalAccountError.invalidReturn
        }
        return "\(scheme)://return/\(nonce)"
    }

    public static func isActivationURL(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "https",
              url.user == nil, url.password == nil,
              url.port == nil || url.port == 443,
              let host = url.host?.lowercased(), ["caltopo.com", "sartopo.com"].contains(host),
              url.path == "/app/activate/offline" else { return false }
        guard let redirect = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?
            .first(where: { $0.name == "redirect" })?.value else { return false }
        return redirect.range(of: "^http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/[0-9a-f]{32}$", options: .regularExpression) != nil
    }

    public static func allowsPersonalHost(_ domainAndPort: String) -> Bool {
        let parts = domainAndPort.split(separator: ":", maxSplits: 1).map(String.init)
        guard let host = parts.first?.lowercased(), ["caltopo.com", "sartopo.com"].contains(host) else { return false }
        if parts.count == 1 { return true }
        return Int(parts[1]) == 443
    }

    func callbackCode(_ requestTarget: String, pending: PendingActivation) throws -> CaltopoActivationReturn {
        let target = requestTarget.components(separatedBy: "#").first ?? requestTarget
        if target.contains("\\") || target.contains("??") { throw CaltopoPersonalAccountError.invalidReturn }
        let query = target.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        if query.count == 2, query[1].contains("?") { throw CaltopoPersonalAccountError.invalidReturn }
        let path: String
        let rawQuery: String
        if target.hasPrefix("/") {
            path = String(query[0])
            rawQuery = query.count > 1 ? String(query[1]) : ""
        } else if let components = URLComponents(string: target),
                  let scheme = components.scheme?.lowercased(), scheme != "http", scheme != "https",
                  components.user == nil {
            path = components.path
            rawQuery = components.percentEncodedQuery ?? ""
        } else {
            throw CaltopoPersonalAccountError.invalidReturn
        }
        guard path == "/\(pending.pathToken)/client/finish-activate" else { throw CaltopoPersonalAccountError.invalidReturn }
        let items = queryItems(rawQuery)
        guard let code = items["code"], Self.activationCode.matches(code), let name = items["name"] else {
            throw CaltopoPersonalAccountError.invalidReturn
        }
        return CaltopoActivationReturn(code: code, deviceName: try normalizeDeviceName(name))
    }

    private var storedHost: String { (try? stored())?.host ?? "" }
    private var storedAccount: String { (try? stored())?.accountID ?? "" }

    private func stored() throws -> CaltopoPersonalAccountRecord? {
        storageLock.lock()
        defer { storageLock.unlock() }
        lock.lock()
        if cacheValid { let result = cached; lock.unlock(); return result }
        let target = store, revision = storageRevision
        lock.unlock()
        let value = try target.load()
        lock.lock()
        defer { lock.unlock() }
        guard revision == storageRevision else { throw CaltopoPersonalAccountError.unavailable }
        cached = value
        cacheValid = true
        return value
    }

    private func write(_ record: CaltopoPersonalAccountRecord) throws {
        storageLock.lock()
        defer { storageLock.unlock() }
        lock.lock()
        let target = store, revision = storageRevision
        lock.unlock()
        try target.save(record)
        lock.lock()
        defer { lock.unlock() }
        guard revision == storageRevision else { throw CaltopoPersonalAccountError.unavailable }
        cached = record
        cacheValid = true
        pending = nil
    }

    private func activationURL(host: String, loopback: String, deviceName: String) throws -> URL {
        guard loopback.range(of: "^http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/[0-9a-f]{32}$", options: .regularExpression) != nil else {
            throw CaltopoPersonalAccountError.invalidReturn
        }
        var components = URLComponents()
        components.scheme = "https"
        components.host = host
        components.path = "/app/activate/offline"
        components.queryItems = [
            URLQueryItem(name: "redirect", value: loopback),
            URLQueryItem(name: "name", value: deviceName),
        ]
        guard let url = components.url else { throw CaltopoPersonalAccountError.invalidReturn }
        return url
    }

    private func exchangeURL(host: String, code: String, deviceName: String) throws -> URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = host
        components.path = "/api/v1/activate"
        components.queryItems = [
            URLQueryItem(name: "code", value: code),
            URLQueryItem(name: "name", value: deviceName),
        ]
        guard let url = components.url else { throw CaltopoPersonalAccountError.rejected }
        return url
    }

    private func normalizeHost(_ host: String) throws -> String {
        let value = host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard ["caltopo.com", "sartopo.com"].contains(value) else { throw CaltopoPersonalAccountError.invalidHost }
        return value
    }

    private func normalizeDeviceName(_ name: String) throws -> String {
        let value = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.range(of: "^[A-Za-z0-9][A-Za-z0-9 ._-]{0,39}$", options: .regularExpression) != nil else {
            throw CaltopoPersonalAccountError.invalidDeviceName
        }
        return value
    }

    private func currentDate() -> Date {
        lock.lock()
        defer { lock.unlock() }
        return clock()
    }

    private func random() -> Data {
        lock.lock()
        defer { lock.unlock() }
        return randomBytes(16)
    }

    private func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

    private func formDecode(_ value: String) -> String {
        let spaced = value.replacingOccurrences(of: "+", with: " ")
        return spaced.removingPercentEncoding ?? spaced
    }

    private func queryItems(_ query: String) -> [String: String] {
        var items: [String: String] = [:]
        for part in query.split(separator: "&") where !part.isEmpty {
            let pieces = part.split(separator: "=", maxSplits: 1).map(String.init)
            let name = formDecode(pieces[0])
            let value = pieces.count > 1 ? formDecode(pieces[1]) : ""
            items[name] = value
        }
        return items
    }

    private static let activationCode = ActivationCodePattern()
}

public protocol CaltopoPersonalAccountStore: Sendable {
    func load() throws -> CaltopoPersonalAccountRecord?
    func save(_ record: CaltopoPersonalAccountRecord) throws
    func clear() throws
}

public protocol CaltopoPersonalAccountBrowser: Sendable {
    /// System browser. Google sign-in must not run in an embedded web view.
    func openActivationPage(_ url: URL) async throws
}

public protocol CaltopoPersonalAccountExchange: Sendable {
    func get(url: URL) async throws -> (status: Int, body: Data)
}

public struct CaltopoPersonalAccountRecord: Equatable, Sendable {
    public var host: String
    public var accountID: String
    public var credentialID: String
    public var signingKey: String
    public var deviceName: String
    public var enrolledAt: Date
    public var revoked: Bool
    public var displayName: String

    public var status: CaltopoPersonalAccountStatus {
        CaltopoPersonalAccountStatus(host: host, accountID: accountID, credentialID: credentialID, deviceName: deviceName, enrolledAt: enrolledAt, revoked: revoked, displayName: displayName)
    }

    public func jsonData() throws -> Data {
        try JSONSerialization.data(withJSONObject: [
            "version": 1,
            "host": host,
            "accountId": accountID,
            "credentialId": credentialID,
            "signingKey": signingKey,
            "deviceName": deviceName,
            "enrolledAtEpochMs": Int64(enrolledAt.timeIntervalSince1970 * 1000),
            "revoked": revoked,
            "displayName": displayName,
        ])
    }

    public static func from(json data: Data) throws -> CaltopoPersonalAccountRecord {
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let host = json["host"] as? String,
              let accountID = json["accountId"] as? String,
              let credentialID = json["credentialId"] as? String,
              let signingKey = json["signingKey"] as? String,
              let deviceName = json["deviceName"] as? String else {
            throw CaltopoPersonalAccountError.rejected
        }
        let enrolled = Date(timeIntervalSince1970: TimeInterval((json["enrolledAtEpochMs"] as? NSNumber)?.int64Value ?? 0) / 1000)
        return CaltopoPersonalAccountRecord(
            host: host, accountID: accountID, credentialID: credentialID, signingKey: signingKey,
            deviceName: deviceName, enrolledAt: enrolled, revoked: json["revoked"] as? Bool ?? false,
            displayName: json["displayName"] as? String ?? accountID
        )
    }
}

extension CaltopoPersonalAccountRecord: CustomStringConvertible, CustomDebugStringConvertible {
    public var description: String { "CaltopoPersonalAccountRecord(host: \(host), accountID: \(accountID), credentialID: \(credentialID), revoked: \(revoked))" }
    public var debugDescription: String { description }
}

public struct CaltopoPersonalAccountStatus: Equatable, Sendable, CustomStringConvertible {
    public let host: String
    public let accountID: String
    public let credentialID: String
    public let deviceName: String
    public let enrolledAt: Date
    public let revoked: Bool
    public let displayName: String
    public var description: String { "CaltopoPersonalAccountStatus(host: \(host), accountID: \(accountID), credentialID: \(credentialID), revoked: \(revoked))" }
}

public struct PendingActivation: Equatable, Sendable, CustomStringConvertible {
    public let host: String
    public let deviceName: String
    public let port: Int
    public let pathToken: String
    public let activationURL: URL
    public let expiresAt: Date
    public var description: String { "PendingActivation(host: \(host), port: \(port))" }
}

public struct CaltopoCredentialTitleRequest: Equatable, Sendable {
    public let url: URL
    public let json: String
    public let fields: [String: String]
}

public struct CaltopoActivationReturn: Equatable, Sendable, CustomStringConvertible {
    public let code: String
    public let deviceName: String
    public var description: String { "CaltopoActivationReturn(codeLength: \(code.count))" }
}

public struct ParsedActivation: Equatable, Sendable, CustomStringConvertible {
    public let accountID: String
    public let credentialID: String
    public let signingKey: String
    public let displayName: String
    public let accountType: String
    public var description: String { "ParsedActivation(accountID: \(accountID), credentialID: \(credentialID), accountType: \(accountType))" }
}

public enum CaltopoPersonalSignedLookup: Equatable, Sendable {
    case inactive
    case absent
    case revoked(accountID: String)
    case ready(CaltopoPersonalAccountRecord)
    case unavailable
}

public enum CaltopoPersonalAccountError: Error, Equatable, LocalizedError {
    case invalidHost
    case invalidDeviceName
    case invalidReturn
    case expired
    case rejected
    case notPersonalAccount
    case exchangeFailed(Int)
    case listenerFailed
    case unavailable

    public var errorDescription: String? {
        switch self {
        case .invalidHost: return "CalTopo account activation only supports caltopo.com."
        case .invalidDeviceName: return "CalTopo device name is invalid."
        case .invalidReturn: return "CalTopo activation return was rejected."
        case .expired: return "CalTopo activation expired. Start it again."
        case .rejected: return "CalTopo activation response was rejected."
        case .notPersonalAccount: return "CalTopo activation did not return a personal account."
        case .exchangeFailed(let status): return "CalTopo activation exchange failed (HTTP \(status))."
        case .listenerFailed: return "CalTopo activation could not listen for the browser return."
        case .unavailable: return "Personal CalTopo credential could not be read. Team credentials were not used."
        }
    }
}

public final class CaltopoPersonalAccountMemoryStore: CaltopoPersonalAccountStore, @unchecked Sendable {
    private let lock = NSLock()
    private var record: CaltopoPersonalAccountRecord?
    public init() {}
    public func load() throws -> CaltopoPersonalAccountRecord? {
        lock.lock(); defer { lock.unlock() }
        return record
    }
    public func save(_ record: CaltopoPersonalAccountRecord) throws {
        lock.lock(); defer { lock.unlock() }
        self.record = record
    }
    public func clear() throws {
        lock.lock(); defer { lock.unlock() }
        record = nil
    }
}

public struct CaltopoPersonalAccountHTTPSExchange: CaltopoPersonalAccountExchange {
    public init() {}
    public func get(url: URL) async throws -> (status: Int, body: Data) {
        guard url.scheme == "https",
              let host = url.host?.lowercased(), ["caltopo.com", "sartopo.com"].contains(host),
              url.path == "/api/v1/activate" || url.path.range(of: "^/api/v1/acct/[A-Za-z0-9]{6}/since/0$", options: .regularExpression) != nil,
              url.user == nil else { throw CaltopoPersonalAccountError.rejected }
        let session = URLSession(configuration: .ephemeral, delegate: CaltopoPersonalNoRedirect(), delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw CaltopoPersonalAccountError.rejected }
        guard data.count <= 65_536 else { throw CaltopoPersonalAccountError.rejected }
        return (http.statusCode, data)
    }

    public func postForm(url: URL, fields: [String: String]) async throws -> Int {
        guard url.scheme == "https",
              let host = url.host?.lowercased(), ["caltopo.com", "sartopo.com"].contains(host),
              url.path.range(of: "^/api/v1/acct/[A-Za-z0-9]{6}/UserCredential/[A-Za-z0-9]{12}$", options: .regularExpression) != nil,
              url.user == nil else { throw CaltopoPersonalAccountError.rejected }
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        let body = ["id", "expires", "signature", "json"].compactMap { key -> String? in
            guard let value = fields[key] else { return nil }
            let encoded = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
            return "\(key)=\(encoded)"
        }.joined(separator: "&")
        let session = URLSession(configuration: .ephemeral, delegate: CaltopoPersonalNoRedirect(), delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        request.httpBody = Data(body.utf8)
        let (_, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw CaltopoPersonalAccountError.rejected }
        return http.statusCode
    }
}

enum CaltopoPersonalAccountLoopback {
    static func bind() throws -> (fd: Int32, port: Int) {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw CaltopoPersonalAccountError.listenerFailed }
        var yes: Int32 = 1
        _ = setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout.size(ofValue: yes)))
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, listen(fd, 8) == 0 else {
            close(fd)
            throw CaltopoPersonalAccountError.listenerFailed
        }
        var actual = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &actual) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(fd, $0, &length)
            }
        }
        guard named == 0 else {
            close(fd)
            throw CaltopoPersonalAccountError.listenerFailed
        }
        return (fd, Int(UInt16(bigEndian: actual.sin_port)))
    }

    static func awaitCode(fd: Int32, pending: PendingActivation, timeout: TimeInterval) throws -> CaltopoActivationReturn {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            var pollInfo = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            let remaining = Int32(max(1, deadline.timeIntervalSinceNow * 1000))
            let ready = poll(&pollInfo, 1, min(remaining, 1000))
            if ready == 0 { continue }
            if ready < 0 { continue }
            let client = accept(fd, nil, nil)
            if client < 0 { continue }
            defer { close(client) }
            var header = [UInt8](repeating: 0, count: 8192)
            var used = 0
            var target: String?
            while used < header.count {
                let remaining = header.count - used
                let readCount = header.withUnsafeMutableBytes { buffer in
                    Darwin.read(client, buffer.baseAddress!.advanced(by: used), remaining)
                }
                if readCount <= 0 { break }
                used += readCount
                let text = String(bytes: header.prefix(used), encoding: .isoLatin1) ?? ""
                if let end = text.range(of: "\r\n") {
                    let line = String(text[..<end.lowerBound])
                    let parts = line.split(separator: " ")
                    if parts.count >= 2, parts[0] == "GET" { target = String(parts[1]) }
                    break
                }
            }
            let returned = target.flatMap { try? CaltopoPersonalAccountConnection.shared.callbackCode($0, pending: pending) }
            let html = returned == nil
                ? "<html><body>RID2Caltopo did not accept this activation request.</body></html>"
                : "<html><body>RID2Caltopo is connected. You can return to the app.</body></html>"
            let status = returned == nil ? "404 Not Found" : "200 OK"
            let payload = html.data(using: .utf8) ?? Data()
            let response = "HTTP/1.1 \(status)\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: \(payload.count)\r\nConnection: close\r\n\r\n"
            _ = response.withCString { Darwin.write(client, $0, Int(strlen($0))) }
            _ = payload.withUnsafeBytes { buffer in
                _ = Darwin.write(client, buffer.baseAddress, payload.count)
            }
            if let returned { return returned }
        }
        throw CaltopoPersonalAccountError.expired
    }
}

private struct ActivationCodePattern {
    func matches(_ value: String) -> Bool {
        value.range(of: "^[A-Za-z0-9]{8}$", options: .regularExpression) != nil
    }
}

private final class ActivationGate: @unchecked Sendable {
    private let condition = NSCondition()
    private var value: Result<CaltopoActivationReturn, Error>?
    func finish(_ value: Result<CaltopoActivationReturn, Error>) {
        condition.lock()
        self.value = value
        condition.signal()
        condition.unlock()
    }
    func wait() throws -> CaltopoActivationReturn {
        condition.lock()
        while value == nil { condition.wait() }
        let result = value!
        condition.unlock()
        return try result.get()
    }
}

private extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
