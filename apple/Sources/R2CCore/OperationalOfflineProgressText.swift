import Foundation

/// Where AOL preparation is; drives the AOL progress line.
public enum OperationalOfflineAOLStage: Sendable, Equatable {
    case waiting, files, tiles, complete, reused, failed
}

/// Download Map job state, used only to choose wording.
public enum OperationalOfflineRunState: Sendable, Equatable {
    case running, cancelling, cancelled, finished, failed
}

/// Pure wording for the Download Map progress panel. Android mirrors this in
/// OfflinePrepProgressText.kt; keep the two in step.
public enum OperationalOfflineProgressText {
    public struct Snapshot: Sendable, Equatable {
        public var phase: String
        public var completed: Int
        public var total: Int
        public var fraction: Double
        public var bytesPerSecond: Double
        public var etaSeconds: Int?
        public var tileLayers: [String]
        public var tileCompleted: Int
        public var tileTotal: Int
        public var tileCached: Int
        public var tileDownloaded: Int
        public var tileFailed: Int
        public var includesDEM: Bool
        public var demCompleted: Int
        public var demTotal: Int
        public var demCached: Int
        public var demDownloaded: Int
        public var demFailed: Int
        public var includesAOL: Bool
        public var aolStage: OperationalOfflineAOLStage
        public var aolFilesCompleted: Int
        public var aolFilesTotal: Int
        public var aolTilesCompleted: Int
        public var aolTilesTotal: Int
        /// Lidar files reused from an earlier attempt of the same AOL selection (included in aolFilesCompleted).
        public var aolFilesKept: Int
        /// Plain-words AOL failure; the raw error goes to the log only.
        public var aolFailure: String?
        /// Plain-words reason the whole job stopped; the raw error goes to the log only.
        public var failure: String?

        public init(
            phase: String = "", completed: Int = 0, total: Int = 0, fraction: Double = 0,
            bytesPerSecond: Double = 0, etaSeconds: Int? = nil,
            tileLayers: [String] = [], tileCompleted: Int = 0, tileTotal: Int = 0,
            tileCached: Int = 0, tileDownloaded: Int = 0, tileFailed: Int = 0,
            includesDEM: Bool = false, demCompleted: Int = 0, demTotal: Int = 0,
            demCached: Int = 0, demDownloaded: Int = 0, demFailed: Int = 0,
            includesAOL: Bool = false, aolStage: OperationalOfflineAOLStage = .waiting,
            aolFilesCompleted: Int = 0, aolFilesTotal: Int = 0,
            aolTilesCompleted: Int = 0, aolTilesTotal: Int = 0, aolFilesKept: Int = 0,
            aolFailure: String? = nil, failure: String? = nil
        ) {
            self.phase = phase; self.completed = completed; self.total = total; self.fraction = fraction
            self.bytesPerSecond = bytesPerSecond; self.etaSeconds = etaSeconds
            self.tileLayers = tileLayers; self.tileCompleted = tileCompleted; self.tileTotal = tileTotal
            self.tileCached = tileCached; self.tileDownloaded = tileDownloaded; self.tileFailed = tileFailed
            self.includesDEM = includesDEM; self.demCompleted = demCompleted; self.demTotal = demTotal
            self.demCached = demCached; self.demDownloaded = demDownloaded; self.demFailed = demFailed
            self.includesAOL = includesAOL; self.aolStage = aolStage
            self.aolFilesCompleted = aolFilesCompleted; self.aolFilesTotal = aolFilesTotal
            self.aolTilesCompleted = aolTilesCompleted; self.aolTilesTotal = aolTilesTotal; self.aolFilesKept = aolFilesKept
            self.aolFailure = aolFailure; self.failure = failure
        }

        public var includesMap: Bool { !tileLayers.isEmpty || tileTotal > 0 }
    }

    /// Hides the sheet while the download keeps running in the background. Matches Android HIDE_LABEL.
    public static let hideLabel = "Hide"
    /// Stops the running download. Matches Android CANCEL_DOWNLOAD_LABEL.
    public static let cancelDownloadLabel = "Cancel download"
    public static let autoCloseDelaySeconds = 1.5

    /// Download Map closes itself after a fully successful run (map, terrain and any AOL processing), but only
    /// when it is showing at that moment. Failures stay open for Retry. Auto-close counts as Close: it ends the
    /// session and deletes raw AOL lidar files. Matches Android OfflinePrepProgressText.shouldAutoClose.
    public static func shouldAutoClose(phase: String, failed: Int, aolFailed: Bool, sheetShown: Bool) -> Bool {
        sheetShown && phase == "Complete" && failed == 0 && !aolFailed
    }

    /// True when the headline should be shown as an error.
    public static func hasFailure(_ p: Snapshot, _ state: OperationalOfflineRunState) -> Bool {
        switch state {
        case .failed: true
        case .finished: mapFailed(p) || terrainFailed(p) || p.aolFailure != nil
        default: false
        }
    }

    public static func headline(_ p: Snapshot, _ state: OperationalOfflineRunState) -> String {
        switch state {
        case .running: runningHeadline(p)
        case .cancelling: "Cancelling download…"
        case .cancelled: "Download cancelled"
        case .failed: "Download failed — retry available"
        case .finished: finishedHeadline(p)
        }
    }

    /// One clearly labelled line per selected part, in download order.
    public static func partLines(_ p: Snapshot, _ state: OperationalOfflineRunState) -> [String] {
        var lines: [String] = []
        if p.includesMap {
            let layers = p.tileLayers.isEmpty ? "" : " (\(p.tileLayers.joined(separator: ", ")))"
            lines.append("Map tiles\(layers): \(p.tileCompleted)/\(p.tileTotal) — \(counts(p.tileCached, p.tileDownloaded, p.tileFailed))")
        }
        if p.includesDEM {
            lines.append("Terrain (DEM): \(p.demCompleted)/\(p.demTotal) — \(counts(p.demCached, p.demDownloaded, p.demFailed))")
        }
        if p.includesAOL { lines.append(aolLine(p, state)) }
        return lines
    }

    public static func aolLine(_ p: Snapshot, _ state: OperationalOfflineRunState) -> String {
        let files = "lidar files \(p.aolFilesCompleted)/\(p.aolFilesTotal)"
        let tiles = "building 1 m tiles \(p.aolTilesCompleted)/\(p.aolTilesTotal)"
        let keptNote = p.aolFilesKept > 0 ? " — \(p.aolFilesKept) kept from last attempt" : ""
        switch p.aolStage {
        case .waiting:
            if state != .running && state != .cancelling { return "AOL: not started" }
            return p.aolFilesTotal > 0
                ? "AOL: waiting for map and terrain — \(p.aolFilesTotal) lidar files, \(p.aolTilesTotal) 1 m tiles"
                : "AOL: waiting for map and terrain"
        case .files: return "AOL: \(files)\(keptNote)"
        case .tiles: return "AOL: \(tiles)"
        case .complete: return "AOL: complete — \(p.aolTilesTotal) 1 m tiles from \(p.aolFilesTotal) lidar files"
        case .reused: return "AOL: already prepared — using cached tiles"
        case .failed:
            let reason = p.aolFailure ?? "stopped"
            let location: String
            if p.aolFilesTotal > 0 && p.aolFilesCompleted >= p.aolFilesTotal { location = " — stopped at \(tiles)" }
            else if p.aolFilesTotal > 0 { location = " — stopped at \(files)" }
            else { location = "" }
            return "AOL failed: \(reason)\(location)"
        }
    }

    /// Whole-job line, labelled as overall. Rate and ETA only while actively downloading.
    public static func overallLine(_ p: Snapshot, _ state: OperationalOfflineRunState, formatRate: (Int64) -> String) -> String {
        let items = "\(p.completed)/\(p.total) items"
        guard state == .running else { return "Overall: \(percent(p, state))% · \(items)" }
        if p.includesAOL {
            return "Overall: \(items) — map and terrain first, then AOL; time remaining varies with lidar size"
        }
        let eta = p.etaSeconds.map(duration) ?? "--:--"
        return "Overall: \(percent(p, state))% · \(items) · \(formatRate(Int64(p.bytesPerSecond)))/s · ETA \(eta)"
    }

    /// Extra context: current AOL activity while running, or why the whole job stopped.
    public static func noteLine(_ p: Snapshot, _ state: OperationalOfflineRunState) -> String? {
        if state == .running, p.includesAOL, p.aolStage == .files || p.aolStage == .tiles, !p.phase.isEmpty {
            return "Now: \(p.phase)"
        }
        if state == .failed, let failure = p.failure { return "Stopped: \(failure)" }
        return nil
    }

    public static func percent(_ p: Snapshot, _ state: OperationalOfflineRunState) -> Int {
        if state == .finished && !hasFailure(p, state) { return 100 }
        return min(100, max(0, Int((p.fraction * 100).rounded(.down))))
    }

    /// Plain words for a transfer failure. `service` names who we were talking to, e.g. "USGS".
    public static func describeFailure(_ error: Error, service: String) -> String {
        if let urlError = error as? URLError {
            switch urlError.code {
            case .timedOut: return "couldn't reach \(service) (timed out)"
            case .notConnectedToInternet, .dataNotAllowed, .internationalRoamingOff:
                return "couldn't reach \(service) (no network)"
            case .cannotFindHost, .dnsLookupFailed:
                return "couldn't reach \(service) (no network or name lookup failed)"
            case .cannotConnectToHost: return "couldn't reach \(service)"
            case .networkConnectionLost: return "connection to \(service) was lost"
            default: break
            }
        }
        let text = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        if let match = text.range(of: #"HTTP [0-9]{3}"#, options: .regularExpression) {
            return "\(service) returned \(text[match])"
        }
        return text
    }

    public static func duration(_ seconds: Int) -> String {
        let clamped = max(0, seconds)
        let hours = clamped / 3_600, minutes = (clamped % 3_600) / 60, remainder = clamped % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, remainder)
            : String(format: "%02d:%02d", minutes, remainder)
    }

    private static func mapFailed(_ p: Snapshot) -> Bool { p.includesMap && p.tileFailed > 0 }
    private static func terrainFailed(_ p: Snapshot) -> Bool { p.includesDEM && p.demFailed > 0 }

    private static func runningHeadline(_ p: Snapshot) -> String {
        if p.includesAOL && (p.aolStage == .files || p.aolStage == .tiles) { return "Preparing AOL…" }
        if p.total <= 0 { return "Preparing…" }
        var active: [String] = []
        if p.includesMap && p.tileCompleted < p.tileTotal { active.append("map tiles") }
        if p.includesDEM && p.demCompleted < p.demTotal { active.append("terrain") }
        if !active.isEmpty { return "Downloading \(joinWords(active))…" }
        if p.includesAOL && p.aolStage == .waiting { return "Preparing AOL…" }
        return "Finishing…"
    }

    private static func finishedHeadline(_ p: Snapshot) -> String {
        var complete: [String] = [], failed: [String] = []
        if p.includesMap { if mapFailed(p) { failed.append("map tiles") } else { complete.append("map") } }
        if p.includesDEM { if terrainFailed(p) { failed.append("terrain") } else { complete.append("terrain") } }
        let aolFailed = p.includesAOL && p.aolFailure != nil
        if p.includesAOL && !aolFailed { complete.append("AOL") }
        var segments: [String] = []
        if !complete.isEmpty { segments.append("\(joinWords(complete)) complete") }
        if !failed.isEmpty { segments.append("\(joinWords(failed)) had failures") }
        if aolFailed { segments.append("AOL failed") }
        var text = segments.isEmpty ? "Download complete" : segments.joined(separator: "; ")
        text = text.prefix(1).uppercased() + text.dropFirst()
        return failed.isEmpty && !aolFailed ? text : "\(text) — retry available"
    }

    private static func counts(_ cached: Int, _ downloaded: Int, _ failed: Int) -> String {
        "\(cached) cached, \(downloaded) downloaded, \(failed) failed"
    }

    private static func joinWords(_ words: [String]) -> String {
        switch words.count {
        case 0: ""
        case 1: words[0]
        default: words.dropLast().joined(separator: ", ") + " and " + words[words.count - 1]
        }
    }
}
