import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Read-only experiment; independent of operational service credentials.
public enum CaltopoPersonalProbe {
    public static let origin = "https://caltopo.com"
    private static func matches(_ value: String, _ pattern: String) -> Bool {
        value.range(of: pattern, options: .regularExpression) != nil
    }
    public static func browserURL(_ input: String) -> URL? {
        guard let url = URL(string: input.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme == "https", url.host == "caltopo.com", url.port == nil,
              url.user == nil, url.password == nil,
              matches(url.path, "^/group/[A-Za-z0-9]{6}/signup/[A-Za-z0-9]+/?$") ||
                matches(url.path, "^/m/[A-Za-z0-9]{3,16}/?$") else { return nil }
        return url
    }
    /// Accept only the CalTopo HTTPS origin; paths, queries, and fragments are preserved.
    public static func caltopoLinkURL(_ input: String) -> URL? {
        guard let url = URL(string: input.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme?.lowercased() == "https", url.host?.lowercased() == "caltopo.com",
              url.port == nil || url.port == 443,
              url.user == nil, url.password == nil else { return nil }
        return url
    }
    public static func mapID(_ input: String) -> String? {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if matches(value, "^[A-Za-z0-9]{3,16}$") { return value }
        guard let url = browserURL(value), url.path.hasPrefix("/m/") else { return nil }
        return String(url.path.dropFirst(3)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }
    public static func endpoint(_ id: String) -> URL? {
        guard matches(id, "^[A-Za-z0-9]{3,16}$") else { return nil }
        return URL(string: "\(origin)/api/v1/map/\(id)/since/0")
    }
    public static func accountEndpoint(_ id: String) -> URL? {
        guard matches(id, "^[A-Za-z0-9]{6}$") else { return nil }
        return URL(string: "\(origin)/api/v1/acct/\(id)/since/0")
    }
    public static func mediaID(_ path: String) -> String? {
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        guard (parts.count == 5 || (parts.count == 6 && ["data", "original"].contains(String(parts[5])))),
              parts[0].isEmpty, parts[1] == "api", parts[2] == "v1", parts[3] == "media",
              let id = UUID(uuidString: String(parts[4])) else { return nil }
        return id.uuidString.lowercased()
    }
    public static func cookieHeader(_ cookies: [HTTPCookie], for url: URL, now: Date = Date()) -> String? {
        guard url.scheme == "https", url.host == "caltopo.com", url.port == nil,
              url.user == nil, url.password == nil,
              (url.path.hasPrefix("/api/v1/map/") || mediaID(url.path) != nil || (url.query == nil && matches(url.path, "^/api/v1/acct/[A-Za-z0-9]{6}/since/0$"))) else { return nil }
        let matching = cookies.filter { cookie in
            let domain = cookie.domain.lowercased()
            let path = cookie.path
            let pathMatches = url.path == path || (url.path.hasPrefix(path) &&
                (path.hasSuffix("/") || url.path.dropFirst(path.count).hasPrefix("/")))
            return (domain == "caltopo.com" || domain == ".caltopo.com") && pathMatches &&
                (cookie.expiresDate == nil || cookie.expiresDate! > now)
        }.sorted { $0.path.count > $1.path.count }
        guard !matching.isEmpty else { return nil }
        return HTTPCookie.requestHeaderFields(with: matching)["Cookie"]
    }
    public struct Result: Sendable {
        public let code: Int
        public let featureCount: Int?
        public var readable: Bool { (200...299).contains(code) && featureCount != nil }
        public var summary: String {
            if readable { return "HTTP \(code), \(featureCount!) map objects" }
            if code == 401 || code == 403 { return "HTTP \(code), access denied or login expired" }
            if (300...399).contains(code) { return "HTTP \(code), redirect refused (login may be required)" }
            return "HTTP \(code), no recognized map response"
        }
    }
    public static func result(code: Int, data: Data) -> Result {
        var count: Int?
        if (200...299).contains(code),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           json["status"] as? String == "ok",
           let result = json["result"] as? [String: Any],
           let state = result["state"] as? [String: Any],
           let features = state["features"] as? [Any] { count = features.count }
        return Result(code: code, featureCount: count)
    }
    public static func comparison(personal: Result, anonymous: Result) -> String {
        if personal.readable && (anonymous.code == 401 || anonymous.code == 403) {
            return "Personal session read succeeded; anonymous access was denied. Publishing is not tested."
        }
        if personal.readable && anonymous.readable {
            return "Both requests can read this map. Use a private map to establish that login grants access."
        }
        if personal.readable { return "Personal request read succeeded, but the anonymous result is inconclusive." }
        return "Personal session API access is not established. Sign in and check this account's map access."
    }
}

/// Process-local authorization; configuration stores an opaque handle, never cookies.
public actor CaltopoPersonalSessions {
    public static let shared = CaltopoPersonalSessions()
    private struct Grant {
        let revision = UUID()
        let provider: @Sendable (URL) async -> String?
    }
    private var generation = UUID()
    private var providers: [UUID: Grant] = [:]
    private var mediaGrants: [UUID: Set<String>] = [:]
    private var requests: [UUID: URLSessionDataTask] = [:]
    public func epoch() -> UUID { generation }
    @discardableResult
    public func register(_ id: UUID, epoch: UUID? = nil, provider: @escaping @Sendable (URL) async -> String?) -> Bool {
        guard epoch == nil || epoch == generation else { return false }
        providers[id] = Grant(provider: provider)
        mediaGrants[id] = nil
        return true
    }
    public func clear() {
        generation = UUID()
        providers.removeAll(); mediaGrants.removeAll()
        for task in requests.values { task.cancel() }
        requests.removeAll()
    }
    private var expired: CaltopoLiveClientError {
        .httpStatus(401, "Personal login expired. Reopen Personal CalTopo login; Team credentials were not used.")
    }
    public func authorizeMedia(_ id: UUID, mediaID: UUID) throws {
        guard providers[id] != nil else { throw expired }
        mediaGrants[id, default: []].insert(mediaID.uuidString.lowercased())
    }
    public func cookie(_ id: UUID, url: URL) async throws -> String {
        if url.path.hasPrefix("/api/v1/media/") {
            guard let mediaID = CaltopoPersonalProbe.mediaID(url.path), mediaGrants[id]?.contains(mediaID) == true
            else { throw CaltopoLiveClientError.invalidURL }
        }
        guard let grant = providers[id] else { throw expired }
        let epoch = generation
        guard let cookie = await grant.provider(url), !cookie.isEmpty,
              generation == epoch, providers[id]?.revision == grant.revision,
              !Task.isCancelled else { throw expired }
        return cookie
    }
    /// Revalidation and resume happen in one actor turn; clear cancels dispatched tasks.
    /// Bytes already delivered to the server cannot be recalled.
    public func perform(_ id: UUID, cookieURL: URL, request original: URLRequest,
                        session: URLSession, sendCookie: Bool) async throws -> (Data, URLResponse) {
        guard let destination = original.url,
              destination.scheme == "https", destination.host == "caltopo.com", destination.port == nil,
              destination.user == nil, destination.password == nil,
              destination.path.split(separator: "/").allSatisfy({ $0 != "." && $0 != ".." }),
              !destination.absoluteString.lowercased().contains("%2f"),
              (sendCookie ? destination == cookieURL : destination.path.hasPrefix("/api/v1/position/report/"))
        else { throw CaltopoLiveClientError.invalidURL }
        let epoch = generation
        let revision = providers[id]?.revision
        let cookie = try await cookie(id, url: cookieURL)
        guard generation == epoch, let revision, providers[id]?.revision == revision,
              !Task.isCancelled else { throw expired }
        var request = original
        if sendCookie { request.setValue(cookie, forHTTPHeaderField: "Cookie") }
        let requestID = UUID()
        defer { requests[requestID] = nil }
        let result: (Data, URLResponse) = try await withCheckedThrowingContinuation { continuation in
            let task = session.dataTask(with: request) { data, response, error in
                if let error { continuation.resume(throwing: error) }
                else if let data, let response { continuation.resume(returning: (data, response)) }
                else { continuation.resume(throwing: URLError(.badServerResponse)) }
            }
            task.delegate = CaltopoPersonalNoRedirect()
            requests[requestID] = task
            task.resume()
        }
        guard generation == epoch, providers[id]?.revision == revision,
              !Task.isCancelled else { throw expired }
        return result
    }

}

final class CaltopoPersonalNoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

public enum CaltopoPersonalCatalog {
    /// Network, server, decoding, and cancellation failures stay in the native
    /// browser. Only an authentication rejection calls for interactive sign-in.
    public static func requiresInteractiveLogin(after error: Error) -> Bool {
        guard case CaltopoLiveClientError.httpStatus(let status, _) = error else { return false }
        return status == 401 || status == 403
    }
    public static let identityScript = """
    (function(){if(location.origin!=='https://caltopo.com')return null;var s=window.sarsoft;var id=s&&(s.account_id||(s.account&&s.account.id));return id?JSON.stringify({id:String(id)}):null;})()
    """
    /// Account API objects are processed on a worker before any UI state or grants change.
    public struct Prepared: Sendable {
        public let username: String
        public let mediaOwners: [String: String]
        public let maps: [CaltopoTeamMapNode]
    }
    public static func prepare(_ data: Data, expectedID: String) throws -> Prepared {
        let normalized = try normalize(data, expectedID: expectedID)
        let root = try JSONSerialization.jsonObject(with: normalized) as! [String: Any]
        let maps = try CaltopoTeamMapDecoder.decode(data: normalized).sorted {
            func rank(_ node: CaltopoTeamMapNode) -> Int {
                node.id == "virtual_recents" ? 0 : (node.id == expectedID ? 1 : 2)
            }
            return rank($0) < rank($1)
        }
        return Prepared(username: root["username"] as! String,
                        mediaOwners: root["mediaOwners"] as! [String: String], maps: maps)
    }
    public static func normalize(_ data: Data, expectedID: String) throws -> Data {
        guard CaltopoPersonalProbe.accountEndpoint(expectedID) != nil,
              let envelope = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              envelope["status"] as? String == "ok", envelope["error"] == nil,
              let result = envelope["result"] as? [String: Any],
              let sourceAccounts = result["accounts"] as? [[String: Any]],
              let sourceFeatures = result["features"] as? [[String: Any]],
              let personal = sourceAccounts.first(where: { $0["id"] as? String == expectedID }),
              let personalProperties = personal["properties"] as? [String: Any],
              personalProperties["class"] as? String == "UserAccount"
        else { throw CaltopoLiveClientError.invalidConfiguration }
        func text(_ value: Any?) -> String? {
            guard let value = value as? String else { return nil }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        let fullName = [text(personalProperties["firstName"]), text(personalProperties["lastName"])].compactMap { $0 }.joined(separator: " ")
        let username = text(fullName) ?? text(personalProperties["title"]) ?? text(personalProperties["alias"]) ?? text(personalProperties["email"]) ?? expectedID
        let relations = result["rels"] as? [[String: Any]] ?? []
        let groups = result["groups"] as? [[String: Any]] ?? []
        var membership = [String: Int]()
        for relation in groups + relations {
            guard let props = relation["properties"] as? [String: Any],
                  props["class"] as? String == "UserAccountGroupRel",
                  props["accountId"] as? String == expectedID,
                  let id = props["referenceAccountId"] as? String,
                  CaltopoPersonalProbe.accountEndpoint(id) != nil,
                  let permission = props["type"] as? Int else { continue }
            // Conflicting duplicate metadata must never increase photo ownership rights.
            membership[id] = min(membership[id] ?? permission, permission)
        }
        let visible = [personal] + sourceAccounts.filter {
            guard let id = $0["id"] as? String, id != expectedID, (membership[id] ?? 0) > 0,
                  let props = $0["properties"] as? [String: Any] else { return false }
            return props["class"] as? String == "UserAccount"
        }
        var mapsByID = [String: [String: Any]]()
        for feature in sourceFeatures {
            guard let id = feature["id"] as? String, let props = feature["properties"] as? [String: Any],
                  props["class"] as? String == "CollaborativeMap", CaltopoPersonalProbe.mapID(id) != nil else { continue }
            mapsByID[id] = feature
        }
        var accounts = [[String: Any]](), features = [[String: Any]](), mediaOwners = [String: String]()
        for account in visible {
            let id = account["id"] as! String
            let props = account["properties"] as? [String: Any] ?? [:]
            accounts.append(["id": id, "properties": ["title": id == expectedID ? username : (text(props["alias"]) ?? text(props["title"]) ?? id)]])
            for feature in sourceFeatures {
                guard let props = feature["properties"] as? [String: Any], props["accountId"] as? String == id,
                      let kind = props["class"] as? String, let featureID = feature["id"] as? String else { continue }
                if kind == "UserFolder" { features.append(feature) }
                else if kind == "CollaborativeMap", CaltopoPersonalProbe.mapID(featureID) != nil {
                    features.append(feature)
                    mediaOwners[featureID] = (membership[id] ?? 0) >= 16 ? id : expectedID
                }
            }
            for relation in relations {
                guard let rel = relation["properties"] as? [String: Any], rel["class"] as? String == "UserAccountMapRel",
                      rel["accountId"] as? String == id, let mapID = rel["mapId"] as? String,
                      CaltopoPersonalProbe.mapID(mapID) != nil else { continue }
                let original = mapsByID[mapID]?["properties"] as? [String: Any] ?? [:]
                let owner = original["accountId"] as? String ?? expectedID
                mediaOwners[mapID] = (membership[owner] ?? 0) >= 16 ? owner : expectedID
                var map = original
                map["class"] = "CollaborativeMap"; map["accountId"] = id
                map["folderId"] = rel["folderId"] // Bookmark placement belongs to the listing account.
                map["title"] = text(rel["title"]) ?? original["title"] ?? "Untitled Map"
                map["updated"] = rel["mapUpdated"] ?? original["updated"] ?? 0
                map["type"] = rel["type"]
                features.append(["id": mapID, "type": "Feature", "properties": map])
            }
        }
        return try JSONSerialization.data(withJSONObject: ["username": username, "accountID": expectedID,
            "accounts": accounts, "features": features, "mediaOwners": mediaOwners])
    }
}

public enum CaltopoPhotoMediaState {
    public static func isReady(_ data: Data, mediaID: UUID, ownerID: String) throws -> Bool {
        guard let envelope = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              envelope["status"] as? String == "ok", envelope["error"] == nil,
              let result = envelope["result"] as? [String: Any],
              (result["id"] as? String)?.lowercased() == mediaID.uuidString.lowercased(),
              let properties = result["properties"] as? [String: Any], properties["creator"] as? String == ownerID
        else { throw CaltopoLiveClientError.invalidConfiguration }
        return properties["mediaIsReady"] as? Bool == true && ((result["metadata"] as? [String: Any])?["filesize"] as? Int ?? 0) > 0
    }
}
