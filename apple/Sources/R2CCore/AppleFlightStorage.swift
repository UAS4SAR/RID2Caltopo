import Foundation

/// Why a dated flight folder cannot be deleted right now. Same reasons and
/// wording as Android `FlightFolderProtection` (FlightStorage.kt).
public enum FlightFolderProtection: Equatable, Sendable {
    case today
    case recording
    case inUse(String)
    case clueUploading

    /// Short text shown in the flight-folder list, e.g. "protected: today".
    public var label: String {
        switch self {
        case .today: "today"
        case .recording: "recording in progress"
        case let .inUse(owner): "in use by \(owner)"
        case .clueUploading: "clue upload in progress"
        }
    }

    /// Human-readable name for a protect() owner key.
    public static func ownerLabel(_ owner: String) -> String {
        switch owner {
        case "diagnostic-log": return "diagnostic log"
        case "video-review": return "video review"
        case "recorder": return "recording"
        case "session": return "app session"
        default:
            if owner.hasPrefix("upload-") || owner.hasPrefix("archive-upload-") { return "archive upload" }
            if owner.hasPrefix("finalize-") { return "recording finalization" }
            return owner.replacingOccurrences(of: "-", with: " ")
        }
    }

    /// Manual deletion is blocked only by these reasons. Pending or failed clue
    /// uploads do not block it; the confirmation warns that they will be lost.
    public static func evaluate(
        name: String,
        today: String,
        owners: [String: String],
        incompleteRecording: Bool,
        clueStates: [OperationalClueUploadState]
    ) -> FlightFolderProtection? {
        if name == today { return .today }
        if incompleteRecording { return .recording }
        if let owner = owners.filter({ $0.value == name }).keys.map(ownerLabel).sorted().first { return .inUse(owner) }
        if clueStates.contains(.uploading) { return .clueUploading }
        return nil
    }

    /// Automatic cleanup also keeps days whose clues are still queued for upload.
    /// Failed uploads are permanent and no longer hold a day forever.
    public static func blocksAutomaticCleanup(clueStates: [OperationalClueUploadState]) -> Bool {
        clueStates.contains { $0 == .pending || $0 == .uploading }
    }

    /// Clues in this day that never reached CalTopo and would be lost on delete.
    public static func unuploadedClueCount(_ clueStates: [OperationalClueUploadState]) -> Int {
        clueStates.count { $0 == .pending || $0 == .uploading || $0 == .failed }
    }
}

/// All flight artifacts share a daily root. No legacy-root migration is performed.
public final class AppleFlightStorage: @unchecked Sendable {
    public static let shared = AppleFlightStorage()
    private let lock = NSRecursiveLock()
    private var protections: [String: String] = [:]
    private var recorderRunning = false
    private var fileSizes: [String: Int64] = [:]
    private var estimatedUsed: Int64 = 0
    private var sweepUsed: Int64 = 0
    private var sweepAvailable: Int64?
    private var initialized = false
    private var blocked = false
    private var lastSweepDay = ""
    private var checkQueued = false
    private var forceQueued = false
    private let work = DispatchQueue(label: "flight-storage-demand", qos: .utility)
    private var callback: (@Sendable (Snapshot) -> Void)?
    #if canImport(Darwin)
    private var monitor: FlightStorageFileMonitor?
    #endif

    public static func observe(_ callback: @escaping @Sendable (Snapshot) -> Void) {
        shared.lock.lock(); defer { shared.lock.unlock() }
        shared.callback = callback
        #if canImport(Darwin)
        if shared.monitor == nil {
            let roots = [root] + reviewRoots
            for directory in roots { try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) }
            shared.monitor = FlightStorageFileMonitor(roots: roots) { url, size in fileChanged(url, size: size) }
        }
        #endif
    }
    public static func stopObserving() {
        shared.lock.lock(); defer { shared.lock.unlock() }
        shared.callback = nil
        #if canImport(Darwin)
        shared.monitor?.stop(); shared.monitor = nil
        #endif
    }
    public static func fileChanged(_ url: URL, size: Int64? = nil) {
        let value = size ?? (((try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber)?.int64Value ?? 0)
        shared.lock.lock(); defer { shared.lock.unlock() }
        guard shared.initialized else { return }
        let key = url.resolvingSymlinksInPath().standardizedFileURL.path
        let old = shared.fileSizes[key] ?? 0
        guard old != value else { return }
        shared.fileSizes[key] = value > 0 ? value : nil
        shared.estimatedUsed = max(0, shared.estimatedUsed + value - old)
        let newDay = shared.lastSweepDay != dayName(Date())
        if newDay || (shared.blocked && value < old) || (!shared.blocked && needsDemandCheck()) {
            requestCheck(force: newDay || value < old)
        }
    }
    private static func needsDemandCheck(incoming: Int64 = 0) -> Bool {
        FlightStoragePolicy.needsCleanup(used: shared.estimatedUsed, incoming: incoming, maximum: maximumBytes) ||
            (shared.sweepAvailable.map { $0 - (shared.estimatedUsed - shared.sweepUsed) - incoming < FlightStoragePolicy.reserveBytes } ?? false)
    }
    /// Known-size writes can reclaim space before allocating their data.
    public static func prepareWrite(_ incoming: Int64) {
        shared.lock.lock()
        let needed = !shared.initialized || (!shared.blocked && needsDemandCheck(incoming: incoming))
        shared.lock.unlock()
        guard needed else { return }
        let snapshot = maintain(reserving: max(0, incoming))
        shared.lock.lock(); let callback = shared.callback; shared.lock.unlock()
        callback?(snapshot)
    }
    public static func requestCheck(force: Bool = true) {
        shared.lock.lock(); defer { shared.lock.unlock() }
        shared.forceQueued = shared.forceQueued || force
        guard !shared.checkQueued else { return }
        shared.checkQueued = true
        shared.work.async {
            shared.lock.lock()
            let force = shared.forceQueued
            shared.forceQueued = false; shared.checkQueued = false
            let shouldRun = force || !shared.initialized || (!shared.blocked && needsDemandCheck())
            shared.lock.unlock()
            guard shouldRun else { return }
            let result = maintain()
            shared.lock.lock(); let callback = shared.callback; shared.lock.unlock()
            callback?(result)
        }
    }
    public static var root: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
            .appendingPathComponent("RID2Caltopo/FlightStorage", isDirectory: true)
    }
    public static func dayName(_ date: Date) -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"; return f.string(from: date)
    }
    public static func date(_ name: String) -> Date? {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"; f.isLenient = false
        guard let value = f.date(from: name), f.string(from: value) == name else { return nil }
        return value
    }
    public static func protect(_ name: String, owner: String = "session") {
        shared.lock.lock(); defer { shared.lock.unlock() }; shared.protections[owner] = name
    }
    public static func release(owner: String) {
        shared.lock.lock(); defer { shared.lock.unlock() }; shared.protections[owner] = nil
        if shared.blocked { requestCheck() }
    }
    public static func setRecorderRunning(_ running: Bool) {
        shared.lock.lock(); defer { shared.lock.unlock() }; shared.recorderRunning = running
    }
    public static func isProtected(_ name: String) -> Bool {
        protectionReason(name) != nil
    }

    /// Why manual deletion of this day is blocked, or nil when it may be deleted.
    public static func protectionReason(_ name: String, at customRoot: URL? = nil, now: Date = Date()) -> FlightFolderProtection? {
        shared.lock.lock(); defer { shared.lock.unlock() }
        let folder = (customRoot ?? root).appendingPathComponent(name)
        return FlightFolderProtection.evaluate(
            name: name,
            today: dayName(now),
            owners: shared.protections,
            incompleteRecording: hasIncompleteRecording(folder),
            clueStates: clueStates(folder)
        )
    }

    /// Clues in this day that never uploaded (pending, uploading, or failed).
    public static func unuploadedClueCount(_ name: String, at customRoot: URL? = nil) -> Int {
        FlightFolderProtection.unuploadedClueCount(clueStates((customRoot ?? root).appendingPathComponent(name)))
    }

    private static func clueStates(_ folder: URL) -> [OperationalClueUploadState] {
        guard let data = try? Data(contentsOf: folder.appendingPathComponent("clues.json")),
              let clues = try? JSONDecoder().decode([OperationalClueRecord].self, from: data) else { return [] }
        return clues.map(\.uploadState)
    }

    private static func hasIncompleteRecording(_ folder: URL) -> Bool {
        // MediaMTX uses UTC paths until completion. Protect raw segments across local midnight.
        guard shared.recorderRunning,
              let files = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: nil) else { return false }
        for case let url as URL in files where ["mp4", "fmp4"].contains(url.pathExtension.lowercased()) {
            if !ManagedVideoRecordingIdentity.isCompletedRecordingPath(url.path) { return true }
        }
        return false
    }
    public static func deleteDay(_ name: String) throws {
        shared.lock.lock(); defer { shared.lock.unlock() }
        guard date(name) != nil, !isProtected(name) else { throw CocoaError(.fileWriteNoPermission) }
        try FileManager.default.removeItem(at: root.appendingPathComponent(name))
        requestCheck()
    }
    public static var maximumBytes: Int64 {
        Int64(min(1_000, max(0.1, UserDefaults.standard.object(forKey: "flight.maximumGB") as? Double ?? FlightStoragePolicy.defaultGB)) * 1_000_000_000)
    }
    public static var maximumDays: Int {
        min(3650, max(1, UserDefaults.standard.object(forKey: "flight.maximumDays") as? Int ?? FlightStoragePolicy.defaultDays))
    }
    public static func bytes(_ root: URL) -> Int64 {
        let files = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        var size: Int64 = 0
        while let url = files?.nextObject() as? URL {
            guard let v = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey]),
                  v.isRegularFile == true, v.isSymbolicLink != true else { continue }
            size += Int64(v.fileSize ?? 0)
        }
        return size
    }
    public struct Snapshot: Sendable {
        public let used: Int64
        public let available: Int64?
        public let blocked: Bool
        public let allowanceInsufficient: Bool
        public var message: String {
            allowanceInsufficient
                ? "Cleanup could not restore 10% free within the Flight Storage allowance. Today and active files stay protected. Please increase the allowance."
                : "Device storage is low. Delete unneeded files in Manage Storage or elsewhere on the device. Increasing the allowance will not create free device space."
        }
        public let failures: [String]
    }
    public static func maintain(purge: Bool = true, at customRoot: URL? = nil,
                                maximum: Int64? = nil, days: Int? = nil, now: Date = Date(), reserving: Int64 = 0) -> Snapshot {
        let root = customRoot ?? Self.root
        let maximumBytes = maximum ?? Self.maximumBytes
        let maximumDays = days ?? Self.maximumDays
        shared.lock.lock(); defer { shared.lock.unlock() }
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let dirs = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey])) ?? []
        let folders = dirs.compactMap { url -> FlightStoragePolicy.Folder? in
            guard let date = date(url.lastPathComponent),
                  let v = try? url.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]),
                  v.isDirectory == true, v.isSymbolicLink != true else { return nil }
            let name = url.lastPathComponent
            let protected = protectionReason(name, at: root, now: now) != nil
                || FlightFolderProtection.blocksAutomaticCleanup(clueStates: clueStates(url))
            return .init(name: name, date: date, bytes: bytes(url), protected: protected)
        }
        let extra = (customRoot == nil ? reviewRoots : []).reduce(Int64(0)) { $0 + bytes($1) }
        var used = bytes(root) + extra
        var failures: [String] = []
        let available = (try? root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?.volumeAvailableCapacityForImportantUsage
        let target = min(max(0, maximumBytes * 9 / 10 - reserving), max(0, used + (available ?? FlightStoragePolicy.reserveBytes) - FlightStoragePolicy.reserveBytes - reserving))
        if purge {
            for name in FlightStoragePolicy.candidates(folders, used: used, maximum: target, maxDays: maximumDays, now: now) {
                do {
                    try FileManager.default.removeItem(at: root.appendingPathComponent(name))
                    used -= folders.first { $0.name == name }!.bytes
                } catch { failures.append(name) }
            }
        }
        let free = (try? root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?.volumeAvailableCapacityForImportantUsage
        let result = Snapshot(used: used, available: free,
                        blocked: used + reserving > maximumBytes * 9 / 10 || (free.map { $0 - reserving < FlightStoragePolicy.reserveBytes } ?? false), allowanceInsufficient: used + reserving > maximumBytes * 9 / 10, failures: failures)
        if purge && customRoot == nil {
            shared.fileSizes.removeAll()
            for directory in [root] + reviewRoots {
                if let files = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey]) {
                    for case let url as URL in files {
                        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey]), values.isRegularFile == true, values.isSymbolicLink != true else { continue }
                        shared.fileSizes[url.resolvingSymlinksInPath().standardizedFileURL.path] = Int64(values.fileSize ?? 0)
                    }
                }
            }
            shared.estimatedUsed = shared.fileSizes.values.reduce(0, +)
            shared.sweepUsed = shared.estimatedUsed; shared.sweepAvailable = free
            shared.initialized = true; shared.blocked = result.blocked
            shared.lastSweepDay = dayName(now)
        }
        return result
    }
    private static var reviewRoots: [URL] {
        // Temporary review copies are removed by the review player; include them while open.
        [FileManager.default.temporaryDirectory.appendingPathComponent("RID2Caltopo/CapturedVideo")]
    }
}
