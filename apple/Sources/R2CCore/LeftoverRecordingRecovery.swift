import Foundation

/// Finds MediaMTX segments that an earlier app run never finished (the app was killed,
/// updated or crashed mid-segment) so they can go through the normal completed-recording
/// steps after the RTMP listener is open. Matches Android's startup orphan sync
/// (`MediaMTXRecordingSync.snapshotOrphanedRecordings`, 1c366086).
///
/// A leftover is a raw UTC-named MediaMTX file (anywhere under Flight Storage, including
/// UTC-date folders) that the current run has not touched: modified before this run's
/// recorder started and quiet for `quietSeconds`. Localized (completed) names, hidden
/// temporaries and research originals are never candidates, so recovery is idempotent.
public enum LeftoverRecordingRecovery {
    public struct Candidate: Equatable, Sendable {
        public let url: URL
        public let size: Int64
        public let modified: Date

        public init(url: URL, size: Int64, modified: Date) {
            self.url = url
            self.size = size
            self.modified = modified
        }

        /// MediaMTX writes `<root>/<day>/<path>/<path>_<utc>.mp4`.
        public var streamPath: String { url.deletingLastPathComponent().lastPathComponent }
    }

    public enum HeaderCheck: Equatable, Sendable {
        case playable
        case noMovieHeader
        case noMediaData
        case unreadable(String)

        public var reason: String {
            switch self {
            case .playable: return "playable"
            case .noMovieHeader: return "no moov box"
            case .noMediaData: return "no media data"
            case let .unreadable(detail): return "unreadable: \(detail)"
            }
        }
    }

    public static func candidates(
        root: URL,
        notTouchedSince cutoff: Date,
        now: Date = Date(),
        quietSeconds: TimeInterval = 10,
        excludingPaths: Set<String> = [],
        failures: LeftoverRecordingFailureLedger? = nil,
        fileManager: FileManager = .default
    ) -> [Candidate] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .contentModificationDateKey, .fileSizeKey]
        guard let enumerator = fileManager.enumerator(
            at: root,
            includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles]
        ) else { return [] }
        var found: [Candidate] = []
        for case let url as URL in enumerator {
            guard isRawRecordingName(url),
                  !excludingPaths.contains(url.standardizedFileURL.path),
                  let values = try? url.resourceValues(forKeys: Set(keys)),
                  values.isRegularFile == true,
                  let size = values.fileSize, size > 0,
                  let modified = values.contentModificationDate,
                  modified < cutoff,
                  now.timeIntervalSince(modified) >= quietSeconds
            else { continue }
            if let started = ManagedVideoRecordingIdentity.recordingStartedAt(forPath: url.path),
               started >= cutoff { continue }
            let candidate = Candidate(url: url.standardizedFileURL, size: Int64(size), modified: modified)
            if failures?.isKnownFailure(candidate) == true { continue }
            found.append(candidate)
        }
        return found.sorted {
            (ManagedVideoRecordingIdentity.recordingStartedAt(forPath: $0.url.path) ?? $0.modified)
                < (ManagedVideoRecordingIdentity.recordingStartedAt(forPath: $1.url.path) ?? $1.modified)
        }
    }

    /// Raw MediaMTX segment name (UTC timestamp), not a localized completed name, not a
    /// conversion temporary and not a preserved research original.
    public static func isRawRecordingName(_ url: URL) -> Bool {
        let name = url.lastPathComponent
        guard ["mp4", "fmp4"].contains(url.pathExtension.lowercased()),
              !name.hasPrefix("."),
              !name.contains(".sei-original"),
              !name.contains("-ios-compatible-")
        else { return false }
        return !ManagedVideoRecordingIdentity.isCompletedRecordingPath(url.path)
    }

    /// Walks the top-level MP4 boxes. A segment cut off mid-write still has the `moov`
    /// written at segment start (fMP4) plus complete fragments; one without `moov` or
    /// without any media data cannot be converted and is left untouched.
    public static func checkHeader(_ url: URL) -> HeaderCheck {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return .unreadable("open failed") }
        defer { try? handle.close() }
        guard let length = try? handle.seekToEnd() else { return .unreadable("size unknown") }
        guard length >= 16 else { return .unreadable("\(length) bytes") }
        var offset: UInt64 = 0
        var sawMovie = false
        var sawMedia = false
        while offset + 8 <= length {
            do { try handle.seek(toOffset: offset) } catch { return .unreadable("seek failed") }
            guard let header = try? handle.read(upToCount: 16), header.count >= 8 else { break }
            var size = UInt64(header[0]) << 24 | UInt64(header[1]) << 16 | UInt64(header[2]) << 8 | UInt64(header[3])
            let type = String(decoding: header[4..<8], as: UTF8.self)
            var headerSize: UInt64 = 8
            if size == 1 {
                guard header.count >= 16 else { break }
                size = header[8..<16].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
                headerSize = 16
            } else if size == 0 {
                size = length - offset
            }
            guard size >= headerSize else { break }
            let available = min(size, length - offset)
            if type == "moov", available == size { sawMovie = true }
            if type == "mdat", available > headerSize { sawMedia = true }
            offset += size
        }
        if !sawMovie { return .noMovieHeader }
        if !sawMedia { return .noMediaData }
        return .playable
    }
}

/// Remembers leftovers that could not be recovered so each launch does not retry the
/// same broken file. An entry only matches while the file's size and mtime are unchanged.
public struct LeftoverRecordingFailureLedger: @unchecked Sendable {
    public static let defaultsKey = "video.leftoverRecordingFailures"
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = LeftoverRecordingFailureLedger.defaultsKey) {
        self.defaults = defaults
        self.key = key
    }

    private static func fingerprint(_ candidate: LeftoverRecordingRecovery.Candidate) -> String {
        "\(candidate.size):\(Int64(candidate.modified.timeIntervalSince1970 * 1000))"
    }

    private var entries: [String: [String: String]] {
        defaults.dictionary(forKey: key) as? [String: [String: String]] ?? [:]
    }

    public func isKnownFailure(_ candidate: LeftoverRecordingRecovery.Candidate) -> Bool {
        entries[candidate.url.path]?["fingerprint"] == Self.fingerprint(candidate)
    }

    public func reason(forPath path: String) -> String? { entries[path]?["reason"] }

    public func record(_ candidate: LeftoverRecordingRecovery.Candidate, reason: String) {
        var all = entries
        all[candidate.url.path] = ["fingerprint": Self.fingerprint(candidate), "reason": reason]
        defaults.set(all, forKey: key)
    }

    public func clear(path: String) {
        var all = entries
        guard all.removeValue(forKey: path) != nil else { return }
        defaults.set(all, forKey: key)
    }
}
