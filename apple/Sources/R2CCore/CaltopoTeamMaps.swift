import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct CaltopoTeamMap: Sendable, Equatable, Identifiable {
    public let id: String
    public let title: String
    public let personalSessionID: UUID?
    public let personalAccountID: String
    public let personalMediaOwnerID: String
    public let updatedMilliseconds: Int64

    public init(id: String, title: String, updatedMilliseconds: Int64, personalSessionID: UUID? = nil, personalAccountID: String = "", personalMediaOwnerID: String = "") {
        self.personalSessionID = personalSessionID
        self.personalAccountID = personalAccountID
        self.personalMediaOwnerID = personalMediaOwnerID
        self.id = id
        self.title = title
        self.updatedMilliseconds = updatedMilliseconds
    }
}

public indirect enum CaltopoTeamMapNode: Sendable, Equatable, Identifiable {
    case directory(id: String, title: String, children: [CaltopoTeamMapNode])
    case map(CaltopoTeamMap)

    public var id: String {
        switch self {
        case let .directory(id, _, _): id
        case let .map(map): map.id
        }
    }

    public var title: String {
        switch self {
        case let .directory(_, title, _): title
        case let .map(map): map.title
        }
    }

    public var children: [CaltopoTeamMapNode]? {
        guard case let .directory(_, _, children) = self else { return nil }
        return children
    }

    public var map: CaltopoTeamMap? {
        guard case let .map(map) = self else { return nil }
        return map
    }
}

public enum CaltopoTeamMapDecoder {
    public static func decode(data: Data, now: Date = Date()) throws -> [CaltopoTeamMapNode] {
        guard let rawRoot = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CaltopoLiveClientError.missingResult
        }
        let root = rawRoot["result"] as? [String: Any] ?? rawRoot
        let features = root["features"] as? [[String: Any]] ?? []
        let accounts = root["accounts"] as? [[String: Any]] ?? []
        let rels = root["rels"] as? [[String: Any]] ?? []

        var titles: [String: String] = [:]
        var directoryIDs = Set<String>()
        var maps: [String: CaltopoTeamMap] = [:]
        var parentByID: [String: String] = [:]
        var relationFolderByMap: [String: String] = [:]
        var relationAccountByMap: [String: String] = [:]

        for account in accounts {
            guard let id = cleanString(account["id"]), !id.isEmpty else { continue }
            let properties = account["properties"] as? [String: Any] ?? [:]
            titles[id] = firstString(properties, keys: ["title", "alias", "lastName"]) ?? "Private Account"
            directoryIDs.insert(id)
        }

        for relation in rels {
            let properties = relation["properties"] as? [String: Any] ?? [:]
            guard cleanString(properties["class"]) == "UserAccountMapRel",
                  let mapID = cleanString(properties["mapId"]), !mapID.isEmpty
            else { continue }
            if let folderID = cleanString(properties["folderId"]), !folderID.isEmpty {
                relationFolderByMap[mapID] = folderID
            }
            if let accountID = cleanString(properties["accountId"]), !accountID.isEmpty {
                relationAccountByMap[mapID] = accountID
            }
        }

        for feature in features {
            guard let id = cleanString(feature["id"]), !id.isEmpty,
                  let properties = feature["properties"] as? [String: Any]
            else { continue }
            let explicitFolder = cleanString(properties["folderId"])
            let explicitAccount = cleanString(properties["accountId"])
            let parent = [explicitFolder, relationFolderByMap[id], explicitAccount, relationAccountByMap[id]]
                .compactMap { $0 }
                .first(where: { !$0.isEmpty })
            if let parent { parentByID[id] = parent }

            switch cleanString(properties["class"]) {
            case "UserFolder":
                directoryIDs.insert(id)
                titles[id] = firstString(properties, keys: ["label", "name", "title"])
                    ?? "Unnamed Folder [ID: \(id)]"
            case "CollaborativeMap":
                let updated = int64(properties["updated"]) ?? int64(feature["Updated"]) ?? 0
                maps[id] = CaltopoTeamMap(
                    id: id,
                    title: cleanString(properties["title"]) ?? "Untitled Map",
                    updatedMilliseconds: updated
                )
            default:
                break
            }
        }

        var childIDs: [String: [String]] = [:]
        for id in directoryIDs.union(maps.keys) {
            if let parent = parentByID[id], directoryIDs.contains(parent) {
                childIDs[parent, default: []].append(id)
            }
        }

        func node(id: String, visited: Set<String> = []) -> CaltopoTeamMapNode? {
            guard !visited.contains(id) else { return nil }
            if let map = maps[id] { return .map(map) }
            guard directoryIDs.contains(id) else { return nil }
            var nextVisited = visited
            nextVisited.insert(id)
            let children = (childIDs[id] ?? []).compactMap { node(id: $0, visited: nextVisited) }
                .sorted(by: nodeSort)
            return .directory(id: id, title: titles[id] ?? "Private Account", children: children)
        }

        let attachedIDs = Set(parentByID.compactMap { id, parent in directoryIDs.contains(parent) ? id : nil })
        let roots = directoryIDs.union(maps.keys).subtracting(attachedIDs)
            .compactMap { node(id: $0) }
            .filter {
                if case let .directory(_, _, children) = $0 { return !children.isEmpty }
                return true
            }
            .sorted(by: nodeSort)

        let recentCutoff = Int64(now.timeIntervalSince1970 * 1_000) - 7 * 24 * 60 * 60 * 1_000
        let recent = maps.values.filter { $0.updatedMilliseconds > recentCutoff }
            .sorted { $0.updatedMilliseconds > $1.updatedMilliseconds }
            .map(CaltopoTeamMapNode.map)
        if recent.isEmpty { return roots }
        return [.directory(id: "virtual_recents", title: "Recent Activity", children: recent)] + roots
    }

    private static func nodeSort(_ lhs: CaltopoTeamMapNode, _ rhs: CaltopoTeamMapNode) -> Bool {
        switch (lhs, rhs) {
        case (.directory, .map): return true
        case (.map, .directory): return false
        case let (.map(left), .map(right)) where left.updatedMilliseconds != right.updatedMilliseconds:
            return left.updatedMilliseconds > right.updatedMilliseconds
        default:
            return lhs.title.localizedCaseInsensitiveCompare(rhs.title) == .orderedAscending
        }
    }

    private static func firstString(_ object: [String: Any], keys: [String]) -> String? {
        keys.lazy.compactMap { cleanString(object[$0]) }.first(where: { !$0.isEmpty })
    }

    private static func cleanString(_ value: Any?) -> String? {
        guard let value, !(value is NSNull) else { return nil }
        let result = String(describing: value).trimmingCharacters(in: .whitespacesAndNewlines)
        return result.isEmpty || result == "null" ? nil : result
    }

    private static func int64(_ value: Any?) -> Int64? {
        if let number = value as? NSNumber { return number.int64Value }
        if let text = cleanString(value) { return Int64(text) }
        return nil
    }
}

public struct CaltopoTeamMapConfiguration: Sendable, Equatable {
    public let domainAndPort: String
    public let teamID: String
    public let credentialID: String
    public let credentialSecretBase64: String

    public init(domainAndPort: String, teamID: String, credentialID: String, credentialSecretBase64: String) {
        self.domainAndPort = domainAndPort
        self.teamID = teamID
        self.credentialID = credentialID
        self.credentialSecretBase64 = credentialSecretBase64
    }
}

public actor CaltopoTeamMapClient {
    private let configuration: CaltopoTeamMapConfiguration
    private let session: URLSession

    public init(configuration: CaltopoTeamMapConfiguration, session: URLSession = .shared) throws {
        guard !configuration.domainAndPort.isEmpty, !configuration.teamID.isEmpty,
              !configuration.credentialID.isEmpty, !configuration.credentialSecretBase64.isEmpty
        else { throw CaltopoLiveClientError.invalidConfiguration }
        self.configuration = configuration
        self.session = session
    }

    public func fetch(now: Date = Date()) async throws -> [CaltopoTeamMapNode] {
        let (data, response) = try await session.data(for: makeRequest(now: now))
        guard let http = response as? HTTPURLResponse else { throw CaltopoLiveClientError.httpStatus(-1, "No HTTP response") }
        guard (200 ... 299).contains(http.statusCode) else {
            throw CaltopoLiveClientError.httpStatus(http.statusCode, String(decoding: data, as: UTF8.self))
        }
        return try CaltopoTeamMapDecoder.decode(data: data, now: now)
    }

    public func makeRequest(now: Date) throws -> URLRequest {
        let path = "/api/v1/acct/\(configuration.teamID)/since/0"
        let expires = Int64(now.timeIntervalSince1970 * 1_000)
            + CaltopoRequestSigner.validityMilliseconds
        let signature = try CaltopoRequestSigner.signature(
            method: "GET", path: path, expiresMilliseconds: expires, payload: "",
            credentialSecretBase64: configuration.credentialSecretBase64
        )
        let rawDomain = configuration.domainAndPort
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard var components = URLComponents(string: "https://\(rawDomain)\(path)") else {
            throw CaltopoLiveClientError.invalidURL
        }
        components.percentEncodedQuery = CaltopoRequestSigner.percentEncodedQuery([
            ("id", configuration.credentialID),
            ("expires", String(expires)),
            ("signature", signature),
        ])
        guard let url = components.url else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        return request
    }
}
