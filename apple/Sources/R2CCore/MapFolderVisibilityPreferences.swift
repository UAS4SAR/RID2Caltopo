import Foundation

public struct MapFolderVisibilityScope: Hashable, Sendable {
    public let domainAndPort: String
    public let mapID: String

    public init(domainAndPort: String, mapID: String) {
        self.domainAndPort = domainAndPort.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        self.mapID = mapID.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // Length-prefix the server, preserving every character and any explicit port.
    // This is a preference key, not a filename or a credential.
    public var storageKey: String {
        let identity = "\(domainAndPort.utf8.count):\(domainAndPort)\(mapID)"
        return "map.visibility.v3." + Data(identity.utf8).base64EncodedString()
    }

    fileprivate var legacyKey: String {
        let safe = mapID.filter { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
        return "map.visibility.v2.\(safe.isEmpty ? "map" : safe)"
    }
}

public struct MapFolderVisibilitySelection: Codable, Equatable, Sendable {
    public var hiddenFolderIDs: Set<String>
    public var hiddenItemIDs: Set<String>
    public var folderVisibilityOverrides: [String: Bool]

    public init(hiddenFolderIDs: Set<String> = [], hiddenItemIDs: Set<String> = [],
                folderVisibilityOverrides: [String: Bool] = [:]) {
        self.hiddenFolderIDs = hiddenFolderIDs
        self.hiddenItemIDs = hiddenItemIDs
        self.folderVisibilityOverrides = folderVisibilityOverrides
    }
}

/// All preferences access, migration, encoding and decoding is confined to one worker.
/// Main submits immutable copy-on-write values; the mailbox never holds its lock during I/O.
/// Queued writes belong to the application, so closing a map view cannot cancel them.
public final class MapFolderVisibilityPreferences: @unchecked Sendable {
    private let worker = DispatchQueue(label: "org.ncssar.map-folder-preferences", qos: .utility)
    private let suiteName: String?
    private var defaults: UserDefaults? // worker only
    private let onWriteFailure: @Sendable (String) -> Void
    private let gate = NSLock()
    private var pending: [MapFolderVisibilityScope: MapFolderVisibilitySelection] = [:]
    private var draining = false
    private var migrationScheduled = false
    private static let legacyOwnerKey = "map.visibility.v3.legacyDomainAndPort"

    public init(suiteName: String? = nil, onWriteFailure: @escaping @Sendable (String) -> Void = { _ in }) {
        self.suiteName = suiteName
        self.onWriteFailure = onWriteFailure
    }

    private func preferences() throws -> UserDefaults {
        dispatchPrecondition(condition: .onQueue(worker))
        if let defaults { return defaults }
        let value: UserDefaults
        if let suiteName {
            guard let suite = UserDefaults(suiteName: suiteName) else { throw CocoaError(.fileReadUnknown) }
            value = suite
        } else { value = .standard }
        defaults = value
        return value
    }

    /// Anchor old map-only choices to the server configured when the upgraded app first opens.
    /// A legacy record has no server provenance: never copy it to multiple servers.
    public func prepareLegacyMigration(domainAndPort: String) {
        gate.lock()
        let schedule = !migrationScheduled
        migrationScheduled = true
        gate.unlock()
        guard schedule else { return }
        let domain = MapFolderVisibilityScope(domainAndPort: domainAndPort, mapID: "").domainAndPort
        worker.async { [self] in
            do {
                let defaults = try preferences()
                if defaults.string(forKey: Self.legacyOwnerKey) == nil {
                    defaults.set(domain.isEmpty ? "caltopo.com" : domain, forKey: Self.legacyOwnerKey)
                }
                let obsolete = CaltopoArtifactVisibilityPolicy.legacyPersistedSelectionKeys(
                    Array(defaults.dictionaryRepresentation().keys))
                for key in obsolete { defaults.removeObject(forKey: key) }
            } catch { onWriteFailure(error.localizedDescription) }
        }
    }

    public func load(_ scope: MapFolderVisibilityScope) async throws -> MapFolderVisibilitySelection {
        try await withCheckedThrowingContinuation { continuation in
            worker.async { [self] in
                continuation.resume(with: Result { try read(scope) })
            }
        }
    }

    private func read(_ scope: MapFolderVisibilityScope) throws -> MapFolderVisibilitySelection {
        guard !scope.mapID.isEmpty else { return .init() }
        let defaults = try preferences()
        if let data = defaults.data(forKey: scope.storageKey) {
            return try JSONDecoder().decode(MapFolderVisibilitySelection.self, from: data)
        }
        let owner = defaults.string(forKey: Self.legacyOwnerKey) ?? "caltopo.com"
        guard owner == scope.domainAndPort, let legacy = defaults.dictionary(forKey: scope.legacyKey)
        else { return .init() }
        guard let folders = legacy["hiddenFolders"] as? [String],
              let items = legacy["hiddenItems"] as? [String],
              let overrides = legacy["folderOverrides"] as? [String: Bool]
        else { throw CocoaError(.fileReadCorruptFile) }
        let selection = MapFolderVisibilitySelection(hiddenFolderIDs: Set(folders), hiddenItemIDs: Set(items),
            folderVisibilityOverrides: overrides)
        let data = try JSONEncoder().encode(selection)
        defaults.set(data, forKey: scope.storageKey)
        // Both keys belong to this preference domain. Keep the source if installation fails.
        if defaults.data(forKey: scope.storageKey) == data { defaults.removeObject(forKey: scope.legacyKey) }
        return selection
    }

    public func save(_ selection: MapFolderVisibilitySelection, for scope: MapFolderVisibilityScope) {
        guard !scope.mapID.isEmpty else { return }
        gate.lock()
        pending[scope] = selection
        let schedule = !draining
        draining = true
        gate.unlock()
        if schedule { worker.async { [self] in drain() } }
    }

    private func drain() {
        while true {
            gate.lock()
            let entry = pending.first
            if let entry { pending.removeValue(forKey: entry.key) } else { draining = false }
            gate.unlock()
            guard let entry else { return }
            do { try preferences().set(JSONEncoder().encode(entry.value), forKey: entry.key.storageKey) }
            catch { onWriteFailure(error.localizedDescription) }
        }
    }
}
