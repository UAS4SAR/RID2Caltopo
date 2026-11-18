import Foundation

/// Why the archive (flight storage) cannot be written. Matches Android `ArchiveWriteProblem`.
public enum ArchiveWriteProblem: String, Sendable { case readOnly, ioError, notWritable }

/// The only events that may clear a "not writable" verdict. Matches Android `ArchiveRecheckTrigger`.
public enum ArchiveRecheckTrigger: String, Sendable { case remount, folderPicked, periodic }

public struct ArchiveWritabilityVerdict: Equatable, Sendable {
    public var writable: Bool
    public var problem: ArchiveWriteProblem?
    public var detail: String?
    public var since: Date
    /// On a removable volume (an SD card). Always false on iOS, where flight storage is the app container.
    public var removable: Bool
    /// Increments on every state change; the UI shows one alert per generation.
    public var generation: Int

    public init(writable: Bool, problem: ArchiveWriteProblem? = nil, detail: String? = nil,
                since: Date = .distantPast, removable: Bool = false, generation: Int = 0) {
        self.writable = writable; self.problem = problem; self.detail = detail
        self.since = since; self.removable = removable; self.generation = generation
    }
}

/// Sticky "flight storage cannot be written" verdict. Same rules as Android
/// `ArchiveWritabilityTracker`: a read-only or I/O error sticks until a remount, a folder pick
/// or a periodic probe (at most every 5 minutes) succeeds. Estimates and scans never clear it.
public final class ArchiveWritabilityTracker: @unchecked Sendable {
    public static let recheckInterval: TimeInterval = 5 * 60
    public typealias Listener = (ArchiveWritabilityVerdict, ArchiveWritabilityVerdict) -> Void

    private let lock = NSLock()
    private let clock: () -> Date
    private let log: (String) -> Void
    private var current = ArchiveWritabilityVerdict(writable: true)
    private var lastRecheck: Date?
    private var listeners: [Listener] = []

    public init(clock: @escaping () -> Date = Date.init, log: @escaping (String) -> Void = { _ in }) {
        self.clock = clock
        self.log = log
    }

    public var verdict: ArchiveWritabilityVerdict { lock.lock(); defer { lock.unlock() }; return current }
    public var writable: Bool { verdict.writable }

    public func addListener(_ listener: @escaping Listener) {
        lock.lock(); listeners.append(listener); lock.unlock()
    }

    /// True when `error` means not writable (read-only or I/O); the verdict then sticks.
    @discardableResult
    public func noteFailure(_ error: Error?, where place: String, removable: Bool = false) -> Bool {
        guard let problem = Self.classify(error) else { return false }
        notWritable(problem, detail: Self.describe(error), where: place, removable: removable)
        return true
    }

    public func markNotWritable(_ problem: ArchiveWriteProblem, detail: String, where place: String, removable: Bool = false) {
        notWritable(problem, detail: detail, where: place, removable: removable)
    }

    private func notWritable(_ problem: ArchiveWriteProblem, detail: String, where place: String, removable: Bool) {
        lock.lock()
        guard current.writable else { lock.unlock(); return }
        let now = clock()
        lastRecheck = now
        let old = current
        current = ArchiveWritabilityVerdict(writable: false, problem: problem, detail: detail, since: now,
                                            removable: removable, generation: old.generation + 1)
        let new = current
        let toNotify = listeners
        lock.unlock()
        log("Archive not writable (\(problem.rawValue)) after \(place): \(detail). Next check on remount, folder pick or in \(Int(Self.recheckInterval / 60)) min.")
        toNotify.forEach { $0(old, new) }
    }

    public func recheckDue(_ trigger: ArchiveRecheckTrigger) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return dueLocked(trigger)
    }

    private func dueLocked(_ trigger: ArchiveRecheckTrigger) -> Bool {
        switch trigger {
        case .remount, .folderPicked: return true
        case .periodic:
            guard !current.writable else { return false }
            guard let lastRecheck else { return true }
            return clock().timeIntervalSince(lastRecheck) >= Self.recheckInterval
        }
    }

    /// Runs `probe` when `trigger` allows it: a probe that returns clears the verdict, one that throws sets it.
    @discardableResult
    public func recheck(_ trigger: ArchiveRecheckTrigger, removable: Bool = false, probe: () throws -> Void) -> Bool {
        lock.lock()
        guard dueLocked(trigger) else { let w = current.writable; lock.unlock(); return w }
        lastRecheck = clock()
        lock.unlock()
        do {
            try probe()
            lock.lock()
            if current.writable { lock.unlock(); return true }
            let old = current
            current = ArchiveWritabilityVerdict(writable: true, since: clock(), removable: removable, generation: old.generation + 1)
            let new = current
            let toNotify = listeners
            lock.unlock()
            log("Archive writable again after \(trigger.rawValue) check.")
            toNotify.forEach { $0(old, new) }
        } catch {
            let problem = Self.classify(error) ?? .notWritable
            if writable {
                notWritable(problem, detail: Self.describe(error), where: "\(trigger.rawValue) check", removable: removable)
            } else if trigger != .periodic {
                log("Archive still not writable after \(trigger.rawValue) check: \(Self.describe(error))")
            }
        }
        return writable
    }

    /// Read-only or I/O failure anywhere in the underlying-error chain; nil otherwise.
    public static func classify(_ error: Error?) -> ArchiveWriteProblem? {
        var next: Error? = error
        var depth = 0
        while let e = next, depth < 12 {
            depth += 1
            let ns = e as NSError
            if ns.domain == NSPOSIXErrorDomain {
                if ns.code == Int(EROFS) { return .readOnly }
                if ns.code == Int(EIO) { return .ioError }
            }
            if ns.domain == NSCocoaErrorDomain && ns.code == NSFileWriteVolumeReadOnlyError { return .readOnly }
            let text = "\(type(of: e)) \(ns.localizedDescription) \(String(describing: e))"
            if text.contains("EROFS") || text.range(of: "Read-only file system", options: .caseInsensitive) != nil { return .readOnly }
            if text.range(of: #"\bEIO\b"#, options: .regularExpression) != nil || text.range(of: "I/O error", options: .caseInsensitive) != nil { return .ioError }
            next = ns.userInfo[NSUnderlyingErrorKey] as? Error
        }
        return nil
    }

    public static func describe(_ error: Error?) -> String {
        guard let error else { return "unknown" }
        return (error as NSError).localizedDescription
    }
}

/// User-facing wording shared by the alert, Flight Storage and the map chip. Matches Android
/// `ArchiveWritabilityText`.
public enum ArchiveWritabilityText {
    public static let useTabletStorage = "Use tablet storage"
    public static let manageStorage = "Manage Storage"

    public static func subject(_ v: ArchiveWritabilityVerdict) -> String { v.removable ? "SD card" : "Archive storage" }

    static func condition(_ v: ArchiveWritabilityVerdict) -> String {
        switch v.problem {
        case .readOnly: return "is read-only (likely failing)"
        case .ioError: return "has I/O errors (likely failing)"
        default: return "cannot be written"
        }
    }

    static func shortCondition(_ v: ArchiveWritabilityVerdict) -> String {
        switch v.problem {
        case .readOnly: return "read-only"
        case .ioError: return "I/O errors"
        default: return "not writable"
        }
    }

    public static func alertTitle(_ v: ArchiveWritabilityVerdict) -> String {
        "\(subject(v)) \(v.problem == .ioError ? "has I/O errors" : "is \(shortCondition(v))")"
    }

    public static func alertMessage(_ v: ArchiveWritabilityVerdict, fallbackAvailable: Bool) -> String {
        "\(subject(v)) \(condition(v)). " + (fallbackAvailable
            ? "Recording to it is paused; live video still works. New recordings and logs are kept in tablet storage and copied to your archive folder once it can be written again. Choose \"\(useTabletStorage)\" to move the archive folder to this tablet."
            : "Recording is paused; live video still works. Tablet storage is also low, so nothing is recorded until you free space or choose another folder in \(manageStorage).")
    }

    public static func chip(_ v: ArchiveWritabilityVerdict, fallbackAvailable: Bool) -> String {
        "\(subject(v)) \(shortCondition(v)) · " + (fallbackAvailable ? "saving to tablet" : "recording paused")
    }

    public static func status(_ v: ArchiveWritabilityVerdict, fallbackAvailable: Bool, time: (Date) -> String) -> String {
        "\(subject(v)) \(condition(v)) since \(time(v.since)). " + (fallbackAvailable
            ? "Recordings and logs are kept in tablet storage and copied when the archive folder can be written again."
            : "Recording is paused: tablet storage is also low.")
            + " Checked again on remount, folder change, or every \(Int(ArchiveWritabilityTracker.recheckInterval / 60)) minutes."
    }
}

/// A live stream that is not recorded because Capture Streams is off. Matches Android `NotCapturingStream`.
public struct NotCapturingStream: Equatable, Sendable, Identifiable {
    public let key: String
    public let designator: String
    public var id: String { key }
    public init(key: String, designator: String) { self.key = key; self.designator = designator }
}

/// "Not capturing stream" panel policy. Matches Android `NotCapturingStreams.pending`.
public enum NotCapturingStreamsPolicy {
    public static let confirm = "Confirm"
    public static let turnOn = "Turn on Stream capture"

    public struct Stream: Sendable {
        public let sourcePath: String
        public let designator: String
        public let publisherID: String?
        public let live: Bool
        public init(sourcePath: String, designator: String, publisherID: String?, live: Bool) {
            self.sourcePath = sourcePath; self.designator = designator; self.publisherID = publisherID; self.live = live
        }
        /// One publish of one stream: a reconnect with a new publisher asks again.
        public var key: String { "\(sourcePath)#\(publisherID ?? "")" }
    }

    public static func title(_ stream: NotCapturingStream) -> String { "Not capturing stream: \(stream.designator)" }

    public static func pending(_ streams: [Stream], captureEnabled: Bool, dismissed: Set<String>) -> [NotCapturingStream] {
        guard !captureEnabled else { return [] }
        var seen = Set<String>()
        return streams.filter { $0.live && !dismissed.contains($0.key) && seen.insert($0.key).inserted }
            .map { NotCapturingStream(key: $0.key, designator: $0.designator) }
            .sorted { $0.designator.lowercased() < $1.designator.lowercased() }
    }
}

/// Capture Streams change log text. Matches Android `CaltopoClient.SetCaptureVideoStreamsFlag`.
public enum CaptureStreamsLog {
    public static let settings = "Settings UI"
    public static let panel = "Not capturing panel"
    public static let restore = "restore"
    public static let `import` = "import"
    public static let load = "load"
    public static let reset = "reset"

    public static func change(old: Bool, new: Bool, source: String) -> String {
        "Capture Streams changed old=\(old) new=\(new) source=\(source)"
    }

    public static func serverStart(_ enabled: Bool) -> String {
        enabled ? "Capture Streams setting at media server start: ON"
                : "Capture Streams setting at media server start: OFF (streams are not recorded)"
    }

    /// A stored value missing from the saved settings means ON.
    public static func loaded(stored: Bool?) -> Bool { stored ?? true }

    public static let confirmOffTitle = "Turn off Capture Streams?"
    public static let confirmOffMessage = "Live video still works, but incoming drone streams will not be recorded on this tablet until you turn capture back on."
}
