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
///
/// Directory walks and purge run only on `work`, never on MainActor and never on
/// clue/track `OperationalWorkQueue` actors. Locks cover only in-memory state.
public final class AppleFlightStorage: @unchecked Sendable {
    public static let shared = AppleFlightStorage()
    private static let workKey = DispatchSpecificKey<UInt8>()
    private let stateLock = NSRecursiveLock()
    private var observerGeneration = 0
    private var maintainGeneration = 0
    private var protections: [String: String] = [:]
    private var recorderStartedAt: Date?
    private var fileSizes: [String: Int64] = [:]
    private var estimatedUsed: Int64 = 0
    private var sweepUsed: Int64 = 0
    private var sweepAvailable: Int64?
    private var initialized = false
    private var blocked = false
    private var lastSweepDay = ""
    private var checkQueued = false
    private var forceQueued = false
    /// Once set (quit / cancel), prepareWrite must not wait on directory walks.
    private var refuseBlockingMaintain = false
    private let work: DispatchQueue
    private var callback: (@Sendable (Snapshot) -> Void)?
    #if canImport(Darwin)
    private var monitor: FlightStorageFileMonitor?
    #endif

    private init() {
        let queue = DispatchQueue(label: "flight-storage-demand", qos: .utility)
        queue.setSpecific(key: Self.workKey, value: 1)
        work = queue
    }

    public static func observe(_ callback: @escaping @Sendable (Snapshot) -> Void) {
        shared.stateLock.lock()
        shared.callback = callback; shared.observerGeneration += 1
        let generation = shared.observerGeneration
        shared.stateLock.unlock()
        #if canImport(Darwin)
        // Form the work-queue body off any actor so it cannot inherit MainActor.
        scheduleObserveInstall(generation: generation)
        #endif
    }

    #if canImport(Darwin)
    private nonisolated static func scheduleObserveInstall(generation: Int) {
        shared.work.async {
            let roots = [root] + reviewRoots
            for directory in roots { try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) }
            let candidate = FlightStorageFileMonitor(roots: roots) { url, size in fileChanged(url, size: size) }
            shared.stateLock.lock(); defer { shared.stateLock.unlock() }
            if shared.observerGeneration == generation && shared.monitor == nil { shared.monitor = candidate }
            else { candidate.stop() }
        }
    }
    #endif

    public static func stopObserving() {
        shared.stateLock.lock(); defer { shared.stateLock.unlock() }
        shared.callback = nil; shared.observerGeneration += 1
        #if canImport(Darwin)
        shared.monitor?.stop(); shared.monitor = nil
        #endif
    }

    /// Abort an in-flight incremental maintain so save/upload/quit never wait on it.
    /// After this, `prepareWrite` also refuses to block on a reclaim pass.
    public static func cancelPendingMaintenance() {
        shared.stateLock.lock()
        shared.maintainGeneration += 1
        shared.refuseBlockingMaintain = true
        shared.stateLock.unlock()
    }

    public static func fileChanged(_ url: URL, size: Int64? = nil) {
        let value = size ?? (((try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber)?.int64Value ?? 0)
        let key = url.resolvingSymlinksInPath().standardizedFileURL.path
        shared.stateLock.lock(); defer { shared.stateLock.unlock() }
        guard shared.initialized else { return }
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

    /// Known-size retained writes can reclaim space before allocating. Runs on the
    /// flight-storage work queue (not clue/track disk queues) and never holds a lock across I/O.
    public static func prepareWrite(_ incoming: Int64) {
        shared.stateLock.lock()
        let refuseBlocking = shared.refuseBlockingMaintain
        let needed = !shared.initialized || (!shared.blocked && needsDemandCheck(incoming: incoming))
        shared.stateLock.unlock()
        guard needed else { return }
        if refuseBlocking {
            // Quit / cancel path: durable writes proceed; reclaim continues in the background if at all.
            requestCheck(force: true)
            return
        }
        let snapshot = maintain(reserving: max(0, incoming))
        shared.stateLock.lock(); let callback = shared.callback; shared.stateLock.unlock()
        callback?(snapshot)
    }

    /// Allowance nudge for app-private clue writes. Matches Android
    /// `FlightStorage.prepareAppPrivateWrite`: never waits on directory walks or purge.
    public static func prepareAppPrivateWrite(_ incoming: Int64) {
        shared.stateLock.lock()
        let needed = !shared.initialized || (!shared.blocked && needsDemandCheck(incoming: incoming))
        shared.stateLock.unlock()
        guard needed else { return }
        requestCheck(force: true)
    }

    public static func requestCheck(force: Bool = true) {
        shared.stateLock.lock()
        shared.forceQueued = shared.forceQueued || force
        let shouldEnqueue = !shared.checkQueued
        if shouldEnqueue { shared.checkQueued = true }
        shared.stateLock.unlock()
        guard shouldEnqueue else { return }
        scheduleMaintenancePass()
    }

    /// nonisolated trampoline: work-queue body must not inherit MainActor isolation.
    private nonisolated static func scheduleMaintenancePass() {
        shared.work.async(execute: runScheduledMaintenance)
    }

    private nonisolated static func runScheduledMaintenance() {
        shared.stateLock.lock()
        let force = shared.forceQueued
        shared.forceQueued = false; shared.checkQueued = false
        let shouldRun = force || !shared.initialized || (!shared.blocked && needsDemandCheck())
        shared.stateLock.unlock()
        guard shouldRun else { return }
        let result = maintainOnWorker(purge: true, customRoot: nil, maximum: nil, days: nil, now: Date(), reserving: 0)
        shared.stateLock.lock(); let callback = shared.callback; shared.stateLock.unlock()
        callback?(result)
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
        shared.stateLock.lock(); defer { shared.stateLock.unlock() }; shared.protections[owner] = name
    }
    public static func release(owner: String) {
        shared.stateLock.lock(); defer { shared.stateLock.unlock() }; shared.protections[owner] = nil
        if shared.blocked { requestCheck() }
    }
    /// Pass the time captured before starting the native recorder. Repeated
    /// status updates must not advance the boundary past a live segment.
    public static func setRecorderRunning(_ running: Bool, startedAt: Date = Date()) {
        shared.stateLock.lock(); defer { shared.stateLock.unlock() }
        if running {
            if shared.recorderStartedAt == nil {
                // File timestamps can have whole-second resolution.
                shared.recorderStartedAt = Date(timeIntervalSince1970: floor(startedAt.timeIntervalSince1970))
            }
        } else { shared.recorderStartedAt = nil }
    }
    public static func isProtected(_ name: String) -> Bool {
        protectionReason(name) != nil
    }

    /// Why manual deletion of this day is blocked, or nil when it may be deleted.
    /// Disk probes run unlocked; only owner/recorder snapshots take stateLock.
    public static func protectionReason(_ name: String, at customRoot: URL? = nil, now: Date = Date()) -> FlightFolderProtection? {
        let folder = (customRoot ?? root).appendingPathComponent(name)
        shared.stateLock.lock(); let owners = shared.protections; shared.stateLock.unlock()
        return FlightFolderProtection.evaluate(
            name: name,
            today: dayName(now),
            owners: owners,
            incompleteRecording: hasIncompleteRecording(folder, now: now),
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

    private static func hasIncompleteRecording(_ folder: URL, now: Date) -> Bool {
        // MediaMTX uses UTC names until completion, including files abandoned by
        // an earlier invocation. A raw name alone does not establish activity.
        // Keep this run's segments protected across local midnight, but do not
        // pin old folders merely because the media server has started again.
        shared.stateLock.lock(); let start = shared.recorderStartedAt; shared.stateLock.unlock()
        guard let startedAt = start,
              let files = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: [.contentModificationDateKey]) else { return false }
        for case let url as URL in files where ["mp4", "fmp4"].contains(url.pathExtension.lowercased()) {
            guard !ManagedVideoRecordingIdentity.isCompletedRecordingPath(url.path) else { continue }
            if let modified = try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
               modified < startedAt || now.timeIntervalSince(modified) >= 60 * 60 { continue }
            // If metadata is unavailable, preserve the conservative protection.
            return true
        }
        return false
    }

    public static func deleteDay(_ name: String) throws {
        enum Outcome: Sendable { case ok; case denied; case failed(String) }
        let outcome: Outcome = runOnWorkQueue {
            guard date(name) != nil, protectionReason(name) == nil else { return .denied }
            do {
                try FileManager.default.removeItem(at: root.appendingPathComponent(name))
                return .ok
            } catch {
                return .failed(error.localizedDescription)
            }
        }
        switch outcome {
        case .ok:
            requestCheck()
        case .denied:
            throw CocoaError(.fileWriteNoPermission)
        case .failed(let message):
            throw NSError(domain: NSPOSIXErrorDomain, code: 1, userInfo: [NSLocalizedDescriptionKey: message])
        }
    }

    public static var maximumBytes: Int64 {
        Int64(min(1_000, max(0.1, UserDefaults.standard.object(forKey: "flight.maximumGB") as? Double ?? FlightStoragePolicy.defaultGB)) * 1_000_000_000)
    }
    public static var maximumDays: Int {
        min(3650, max(1, UserDefaults.standard.object(forKey: "flight.maximumDays") as? Int ?? FlightStoragePolicy.defaultDays))
    }
    public static func bytes(_ root: URL) -> Int64 {
        bytes(root, cancelled: { false })
    }

    private static func bytes(_ root: URL, cancelled: () -> Bool) -> Int64 {
        let files = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        var size: Int64 = 0
        while let url = files?.nextObject() as? URL {
            if cancelled() { return size }
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
        // Hop through a nonisolated trampoline so callers on MainActor do not
        // form a MainActor-isolated work-queue body (980e4a00 class).
        runMaintainOnWorkQueue(
            purge: purge,
            customRoot: customRoot,
            maximum: maximum,
            days: days,
            now: now,
            reserving: reserving
        )
    }

    private nonisolated static func runMaintainOnWorkQueue(
        purge: Bool,
        customRoot: URL?,
        maximum: Int64?,
        days: Int?,
        now: Date,
        reserving: Int64
    ) -> Snapshot {
        runOnWorkQueue {
            maintainOnWorker(
                purge: purge,
                customRoot: customRoot,
                maximum: maximum,
                days: days,
                now: now,
                reserving: reserving
            )
        }
    }

    private nonisolated static func runOnWorkQueue<T>(_ body: () -> T) -> T {
        if DispatchQueue.getSpecific(key: workKey) != nil {
            return body()
        }
        return shared.work.sync(execute: body)
    }

    /// Incremental, cancellable inventory/purge. Must run on `work`. Never holds
    /// stateLock across directory walks, clue-index reads, or deletes.
    private nonisolated static func maintainOnWorker(
        purge: Bool,
        customRoot: URL?,
        maximum: Int64?,
        days: Int?,
        now: Date,
        reserving: Int64
    ) -> Snapshot {
        let root = customRoot ?? Self.root
        let maximumBytes = maximum ?? Self.maximumBytes
        let maximumDays = days ?? Self.maximumDays
        shared.stateLock.lock()
        let generation = shared.maintainGeneration
        let owners = shared.protections
        shared.stateLock.unlock()
        let cancelled = { () -> Bool in
            shared.stateLock.lock(); defer { shared.stateLock.unlock() }
            return shared.maintainGeneration != generation
        }
        let estimatedSnapshot = { () -> Snapshot in
            shared.stateLock.lock(); defer { shared.stateLock.unlock() }
            return Snapshot(
                used: shared.estimatedUsed,
                available: shared.sweepAvailable,
                blocked: shared.blocked,
                allowanceInsufficient: shared.blocked,
                failures: []
            )
        }

        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let dirs = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey])) ?? []
        var folders: [FlightStoragePolicy.Folder] = []
        folders.reserveCapacity(dirs.count)
        for url in dirs {
            if cancelled() { return estimatedSnapshot() }
            guard let date = date(url.lastPathComponent),
                  let v = try? url.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]),
                  v.isDirectory == true, v.isSymbolicLink != true else { continue }
            let name = url.lastPathComponent
            // Owners snapshotted above; incomplete-recording / clue-index probes stay unlocked.
            let protected = FlightFolderProtection.evaluate(
                name: name,
                today: dayName(now),
                owners: owners,
                incompleteRecording: hasIncompleteRecording(url, now: now),
                clueStates: clueStates(url)
            ) != nil || FlightFolderProtection.blocksAutomaticCleanup(clueStates: clueStates(url))
            if cancelled() { return estimatedSnapshot() }
            folders.append(.init(name: name, date: date, bytes: bytes(url, cancelled: cancelled), protected: protected))
        }
        if cancelled() { return estimatedSnapshot() }
        let extra = (customRoot == nil ? reviewRoots : []).reduce(Int64(0)) { partial, url in
            if cancelled() { return partial }
            return partial + bytes(url, cancelled: cancelled)
        }
        var used = folders.reduce(Int64(0)) { $0 + $1.bytes } + extra
        // Prefer a volume-capacity probe when available; fall back to summing roots.
        if customRoot == nil {
            used = bytes(root, cancelled: cancelled) + extra
        }
        var failures: [String] = []
        let available = (try? root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?.volumeAvailableCapacityForImportantUsage
        let target = min(max(0, maximumBytes * 9 / 10 - reserving), max(0, used + (available ?? FlightStoragePolicy.reserveBytes) - FlightStoragePolicy.reserveBytes - reserving))
        if purge {
            for name in FlightStoragePolicy.candidates(folders, used: used, maximum: target, maxDays: maximumDays, now: now) {
                if cancelled() { break }
                do {
                    try FileManager.default.removeItem(at: root.appendingPathComponent(name))
                    used -= folders.first { $0.name == name }!.bytes
                } catch { failures.append(name) }
            }
        }
        let free = (try? root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?.volumeAvailableCapacityForImportantUsage
        let result = Snapshot(used: used, available: free,
                        blocked: used + reserving > maximumBytes * 9 / 10 || (free.map { $0 - reserving < FlightStoragePolicy.reserveBytes } ?? false), allowanceInsufficient: used + reserving > maximumBytes * 9 / 10, failures: failures)
        if purge && customRoot == nil && !cancelled() {
            var ledger: [String: Int64] = [:]
            for directory in [root] + reviewRoots {
                if cancelled() { break }
                if let files = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey]) {
                    for case let url as URL in files {
                        if cancelled() { break }
                        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey]), values.isRegularFile == true, values.isSymbolicLink != true else { continue }
                        ledger[url.resolvingSymlinksInPath().standardizedFileURL.path] = Int64(values.fileSize ?? 0)
                    }
                }
            }
            if !cancelled() {
                shared.stateLock.lock()
                shared.fileSizes = ledger
                shared.estimatedUsed = ledger.values.reduce(0, +)
                shared.sweepUsed = shared.estimatedUsed; shared.sweepAvailable = free
                shared.initialized = true; shared.blocked = result.blocked
                shared.lastSweepDay = dayName(now)
                shared.stateLock.unlock()
            }
        }
        return result
    }

    private static var reviewRoots: [URL] {
        // Temporary review copies are removed by the review player; include them while open.
        [TemporaryWorkspace.root.appendingPathComponent("CapturedVideo")]
    }
}
