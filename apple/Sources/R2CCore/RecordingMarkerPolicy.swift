import Foundation

/// The always-visible recording marker (Ken, Nov 25 2026: two searches were flown with stream
/// capture off and nothing on screen said so). Red "● REC" only while MediaMTX's recorder is
/// actually writing the stream; amber "NOT RECORDING" otherwise, with the reason. Same rules,
/// text and timings as Android's RecordingMarkerPolicy (video/RecordingMarker.kt).
public enum RecordingMarkerState: Equatable, Sendable { case hidden, recording, starting, notRecording }

public enum NotRecordingReason: CaseIterable, Equatable, Sendable {
    case captureOff, storageBlocked, archiveNotWritable, recorderNotReady, recorderNotStarted
}

public struct RecordingMarker: Equatable, Sendable {
    public let state: RecordingMarkerState
    public let reason: NotRecordingReason?
    public init(_ state: RecordingMarkerState, reason: NotRecordingReason? = nil) { self.state = state; self.reason = reason }

    public var label: String? {
        switch state {
        case .hidden: return nil
        case .recording: return RecordingMarkerPolicy.recLabel
        case .starting: return RecordingMarkerPolicy.startingLabel
        case .notRecording: return RecordingMarkerPolicy.notRecordingLabel
        }
    }
}

/// What the media server and storage say about recording right now.
public struct RecordingInputs: Equatable, Sendable {
    public var serverRunning: Bool
    public var captureEnabled: Bool
    public var storageBlocked: Bool
    public var recorderReady: Bool
    public var archiveWritable: Bool
    public init(serverRunning: Bool = false, captureEnabled: Bool = true, storageBlocked: Bool = false,
                recorderReady: Bool = false, archiveWritable: Bool = true) {
        self.serverRunning = serverRunning; self.captureEnabled = captureEnabled; self.storageBlocked = storageBlocked
        self.recorderReady = recorderReady; self.archiveWritable = archiveWritable
    }
}

public enum RecordingMarkerPolicy {
    public static let recLabel = "● REC"
    public static let startingLabel = "● REC…"
    public static let notRecordingLabel = "NOT RECORDING"
    public static let spokenPhrase = "Video not recording"
    public static let promptTitle = "Video is not being recorded"
    public static let turnOn = "Turn on"
    public static let notNow = "Not now"
    public static let ok = "OK"
    /// The recorder normally starts within a second of a publish; wait this long before saying it did not.
    public static let startGraceSeconds: TimeInterval = 10

    /// `waitingFor`: time since the stream went live or the recorder last stopped, whichever is later.
    public static func marker(live: Bool, localPlayback: Bool, recorderActive: Bool,
                              inputs: RecordingInputs, waitingFor: TimeInterval) -> RecordingMarker {
        guard live, !localPlayback else { return RecordingMarker(.hidden) }
        if recorderActive { return RecordingMarker(.recording) }
        let reason: NotRecordingReason
        if !inputs.captureEnabled { reason = .captureOff }
        else if inputs.storageBlocked && !inputs.archiveWritable { reason = .archiveNotWritable }
        else if inputs.storageBlocked { reason = .storageBlocked }
        else if waitingFor < startGraceSeconds { return RecordingMarker(.starting) }
        else if !inputs.recorderReady { reason = .recorderNotReady }
        else { reason = .recorderNotStarted }
        return RecordingMarker(.notRecording, reason: reason)
    }

    /// Tapping the amber marker: the setting can be turned on in place; anything else is explained.
    public static func tapTurnsOnCapture(_ marker: RecordingMarker) -> Bool {
        marker.state == .notRecording && marker.reason == .captureOff
    }

    public static func reasonText(_ reason: NotRecordingReason) -> String {
        switch reason {
        case .captureOff: return "Capture Streams is off, so this video is not being saved."
        case .storageBlocked: return "Flight storage is full or at its limit, so recording is paused. Free space in Manage Storage."
        case .archiveNotWritable: return "The archive folder cannot be written and tablet storage is low, so recording is paused. Free space or choose another folder in Manage Storage."
        case .recorderNotReady: return "Recording storage is not ready, so this video is not being saved. Check Manage Storage."
        case .recorderNotStarted: return "The media server has not started recording this stream. Close and reopen the stream, or restart the media server."
        }
    }

    public static func promptText(designator: String, reason: NotRecordingReason) -> String {
        "\(designator): \(reasonText(reason))"
    }

    /// Map status chip: the worst state across live streams (NOT RECORDING, then starting, then REC).
    public static func aggregate(_ markers: [(designator: String, marker: RecordingMarker)]) -> (designator: String?, marker: RecordingMarker)? {
        let shown = markers.filter { $0.marker.state != .hidden }
        guard !shown.isEmpty else { return nil }
        if let bad = shown.first(where: { $0.marker.state == .notRecording }) {
            return (shown.count > 1 ? bad.designator : nil, bad.marker)
        }
        if let starting = shown.first(where: { $0.marker.state == .starting }) { return (nil, starting.marker) }
        return (nil, shown[0].marker)
    }
}

/// Recorder state per stream path, fed by MediaMTX recorder log lines. Pure so it is tested
/// the same way as Android's RecordingStatus.
public struct RecordingStatusSnapshot: Equatable, Sendable {
    public private(set) var inputs = RecordingInputs()
    public private(set) var recordingPaths: Set<String> = []
    public private(set) var waitingSince: [String: TimeInterval] = [:]
    public init() {}

    public static func key(_ path: String) -> String { path.trimmingCharacters(in: CharacterSet(charactersIn: "/ ")) }

    public mutating func update(_ inputs: RecordingInputs) {
        self.inputs = inputs
        if !inputs.serverRunning { recordingPaths.removeAll() }
    }
    public mutating func streamStarted(_ path: String, at now: TimeInterval) { waitingSince[Self.key(path)] = now }
    public mutating func recorderStarted(_ path: String) { recordingPaths.insert(Self.key(path)) }
    public mutating func recorderStopped(_ path: String, at now: TimeInterval) {
        recordingPaths.remove(Self.key(path)); waitingSince[Self.key(path)] = now
    }
    public mutating func serverStarted(at now: TimeInterval) {
        recordingPaths.removeAll()
        for path in waitingSince.keys { waitingSince[path] = now }
    }

    public func marker(sourcePath: String, live: Bool, localPlayback: Bool = false, now: TimeInterval) -> RecordingMarker {
        let key = Self.key(sourcePath)
        let since = waitingSince[key] ?? now
        return RecordingMarkerPolicy.marker(live: live, localPlayback: localPlayback,
                                            recorderActive: recordingPaths.contains(key),
                                            inputs: inputs, waitingFor: max(0, now - since))
    }
}

/// When a live publish gets the spoken warning and prompt: once per publish (path and publisher
/// connection), as soon as the marker says NOT RECORDING. Same as Android's RecordingConnectWarningPolicy.
public struct RecordingConnectWarningPolicy: Sendable {
    public struct Stream: Equatable, Sendable {
        public let key: String; public let designator: String; public let marker: RecordingMarker
        public init(key: String, designator: String, marker: RecordingMarker) { self.key = key; self.designator = designator; self.marker = marker }
    }
    public struct Warning: Equatable, Sendable {
        public let key: String; public let designator: String; public let reason: NotRecordingReason
        public init(key: String, designator: String, reason: NotRecordingReason) { self.key = key; self.designator = designator; self.reason = reason }
    }

    private var settled: Set<String> = []
    public init() {}

    public mutating func evaluate(_ streams: [Stream]) -> [Warning] {
        let liveKeys = Set(streams.filter { $0.marker.state != .hidden }.map(\.key))
        settled.formIntersection(liveKeys)
        var warnings: [Warning] = []
        for stream in streams where !settled.contains(stream.key) {
            switch stream.marker.state {
            case .recording: settled.insert(stream.key)
            case .notRecording:
                settled.insert(stream.key)
                warnings.append(Warning(key: stream.key, designator: stream.designator, reason: stream.marker.reason ?? .recorderNotStarted))
            case .starting, .hidden: break
            }
        }
        return warnings
    }
}
