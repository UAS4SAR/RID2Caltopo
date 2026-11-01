import Foundation

public enum CaltopoMapArtifactReason: String, Sendable, Equatable {
    case firstTrack = "first-track"
    case clue = "clue"
}

public struct CaltopoTrackFolderIDs: Sendable, Equatable {
    public let active: String
    public let archive: String
    /// True when this resolution POSTed at least one new folder.
    public let created: Bool

    public init(active: String, archive: String, created: Bool = false) {
        self.active = active
        self.archive = archive
        self.created = created
    }
}

public actor CaltopoTrackFolderResolver {
    public typealias FetchSnapshot = @Sendable () async throws -> CaltopoArtifactSnapshot
    public typealias CreateFolder = @Sendable (
        _ title: String,
        _ visible: Bool,
        _ labelVisible: Bool
    ) async throws -> String
    public typealias DeleteFolder = @Sendable (_ folderID: String) async throws -> Void

    private var resolutionTask: Task<CaltopoTrackFolderIDs, Error>?
    private var resolutionID: UUID?

    public init() {}

    public func reset() {
        resolutionTask?.cancel()
        resolutionTask = nil
        resolutionID = nil
    }

    public func resolve(
        trackFolderName: String,
        date: Date = Date(),
        timeZone: TimeZone = .current,
        settleDelay: Duration = .zero,
        fetchSnapshot: @escaping FetchSnapshot,
        createFolder: @escaping CreateFolder,
        deleteFolder: @escaping DeleteFolder = { _ in }
    ) async throws -> CaltopoTrackFolderIDs {
        if let resolutionTask {
            return try await resolutionTask.value
        }

        let activeName = Self.normalizedTrackFolderName(trackFolderName)
        let archiveName = Self.archiveFolderName(
            trackFolderName: activeName,
            date: date,
            timeZone: timeZone
        )
        let taskID = UUID()
        let task = Task {
            var snapshot = try await fetchSnapshot()
            var activeID = Self.preferredFolderID(
                named: activeName,
                in: snapshot
            )
            var archiveID = Self.preferredFolderID(
                named: archiveName,
                in: snapshot
            )
            var createdFolder = false
            var createdActiveID: String?
            var createdArchiveID: String?

            // Create returns the GUID; that response is the acknowledgement — do not
            // re-read the map to confirm. Optional settleDelay / deleteFolder are unused.
            _ = settleDelay
            _ = deleteFolder
            if activeID == nil {
                let created = try await createFolder(activeName, true, true)
                createdActiveID = created
                activeID = created
                createdFolder = true
            }
            if archiveID == nil {
                let created = try await createFolder(archiveName, false, false)
                createdArchiveID = created
                archiveID = created
                createdFolder = true
            }
            _ = createdActiveID
            _ = createdArchiveID

            return CaltopoTrackFolderIDs(
                active: activeID!,
                archive: archiveID!,
                created: createdFolder
            )
        }
        resolutionID = taskID
        resolutionTask = task

        do {
            return try await task.value
        } catch {
            if resolutionID == taskID {
                resolutionTask = nil
                resolutionID = nil
            }
            throw error
        }
    }

    public static func archiveFolderName(
        trackFolderName: String,
        date: Date = Date(),
        timeZone: TimeZone = .current
    ) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        formatter.dateFormat = "ddMMM"
        return normalizedTrackFolderName(trackFolderName) + " " + formatter.string(from: date)
    }

    public static func normalizedTrackFolderNameForAdoption(_ value: String) -> String {
        normalizedTrackFolderName(value)
    }

    private static func normalizedTrackFolderName(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Drone Tracks" : trimmed
    }

    private static func folderTitle(_ title: String, matches expected: String) -> Bool {
        title.compare(expected, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
    }

    private static func preferredFolderID(
        named expectedTitle: String,
        in snapshot: CaltopoArtifactSnapshot,
        excluding excludedID: String? = nil
    ) -> String? {
        let occupied = occupiedFolderIDs(in: snapshot)
        return snapshot.folders
            .filter {
                $0.id != excludedID
                    && folderTitle($0.title, matches: expectedTitle)
            }
            .sorted {
                let lhsOccupied = occupied.contains($0.id)
                let rhsOccupied = occupied.contains($1.id)
                if lhsOccupied != rhsOccupied { return lhsOccupied }
                return $0.id < $1.id
            }
            .first?
            .id
    }

    private static func occupiedFolderIDs(in snapshot: CaltopoArtifactSnapshot) -> Set<String> {
        var occupied = Set(snapshot.items.map(\.folderID))
        occupied.formUnion(snapshot.folders.compactMap(\.parentID))
        var changed = true
        while changed {
            changed = false
            for folder in snapshot.folders {
                guard occupied.contains(folder.id),
                      let parentID = folder.parentID,
                      occupied.insert(parentID).inserted
                else { continue }
                changed = true
            }
        }
        return occupied
    }
}
