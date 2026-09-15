import R2CCore
import AVKit
import CoreVideo
import CryptoKit
import Foundation
import SwiftUI
import UniformTypeIdentifiers

enum AppleReviewVerdict: String, CaseIterable, Identifiable, Codable {
    case good, bad, unsure
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
}

enum AppleReviewKind: String, CaseIterable, Identifiable, Codable {
    case missedTarget = "missed_target"
    case falsePositive = "false_positive"
    case correctDetection = "correct_detection"
    case unsure
    var id: String { rawValue }
    var label: String {
        switch self {
        case .missedTarget: "Missed Target"
        case .falsePositive: "False Positive"
        case .correctDetection: "Correct Detection"
        case .unsure: "Unsure"
        }
    }
}

enum AppleReviewObjectType: String, CaseIterable, Identifiable, Codable {
    case person, team, tree, vehicle, artifact, unknown
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
}

enum AppleReviewScenario: String, CaseIterable, Identifiable, Codable {
    case easyTrueTarget = "easy_true_target"
    case subtleUnderCanopy = "subtle_under_canopy"
    case treeFalsePositive = "tree_false_positive"
    case groundFalsePositive = "ground_false_positive"
    case vehicleFalsePositive = "vehicle_false_positive"
    case cameraMotionShift = "camera_motion_shift"
    case other
    var id: String { rawValue }
    var label: String {
        rawValue.split(separator: "_").map { $0.capitalized }.joined(separator: " ")
    }
}

struct AppleReviewAnnotation: Codable, Identifiable, Equatable {
    let id: UUID
    let xNorm: Double
    let yNorm: Double
    let verdict: AppleReviewVerdict
    let reviewKind: AppleReviewKind
    let objectType: AppleReviewObjectType
    let scenario: AppleReviewScenario?
    let note: String
    let createdAtMilliseconds: Int64
    let anomalyDebugSummary: String?
    let box: AppleReviewNormalizedBox?

    enum CodingKeys: String, CodingKey {
        case xNorm = "x_norm", yNorm = "y_norm", verdict
        case reviewKind = "review_kind", objectType = "object_type", scenario, note
        case createdAtMilliseconds = "created_at_ms"
        case anomalyDebugSummary = "anomaly_debug_summary", box
    }

    init(
        id: UUID = UUID(), xNorm: Double, yNorm: Double, verdict: AppleReviewVerdict,
        reviewKind: AppleReviewKind, objectType: AppleReviewObjectType,
        scenario: AppleReviewScenario?, note: String, createdAtMilliseconds: Int64,
        anomalyDebugSummary: String? = nil, box: AppleReviewNormalizedBox? = nil
    ) {
        self.id = id
        self.xNorm = xNorm
        self.yNorm = yNorm
        self.verdict = verdict
        self.reviewKind = reviewKind
        self.objectType = objectType
        self.scenario = scenario
        self.note = note
        self.createdAtMilliseconds = createdAtMilliseconds
        self.anomalyDebugSummary = anomalyDebugSummary
        self.box = box
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = UUID()
        xNorm = try values.decode(Double.self, forKey: .xNorm)
        yNorm = try values.decode(Double.self, forKey: .yNorm)
        verdict = try values.decode(AppleReviewVerdict.self, forKey: .verdict)
        reviewKind = try values.decodeIfPresent(AppleReviewKind.self, forKey: .reviewKind) ?? .unsure
        objectType = try values.decodeIfPresent(AppleReviewObjectType.self, forKey: .objectType) ?? .unknown
        scenario = try values.decodeIfPresent(AppleReviewScenario.self, forKey: .scenario)
        note = try values.decodeIfPresent(String.self, forKey: .note) ?? ""
        createdAtMilliseconds = try values.decodeIfPresent(Int64.self, forKey: .createdAtMilliseconds) ?? 0
        anomalyDebugSummary = try values.decodeIfPresent(String.self, forKey: .anomalyDebugSummary)
        box = try values.decodeIfPresent(AppleReviewNormalizedBox.self, forKey: .box)
    }
}

struct AppleReviewNormalizedBox: Codable, Equatable {
    let xMin: Double, yMin: Double, xMax: Double, yMax: Double
    enum CodingKeys: String, CodingKey {
        case xMin = "x_min", yMin = "y_min", xMax = "x_max", yMax = "y_max"
    }
}

struct AppleReviewFrame: Codable, Equatable {
    let sourceTimestampMicroseconds: Int64
    var annotations: [AppleReviewAnnotation]

    enum CodingKeys: String, CodingKey {
        case sourceTimestampMicroseconds = "source_timestamp_us"
        case annotations
    }
}

struct AppleReviewSidecar: Codable, Equatable {
    var schemaVersion = 2
    var sourceDisplayName: String
    var originalSourceURI: String?
    var playbackURI: String?
    var annotationSidecarPath: String?
    var updatedAtMilliseconds: Int64
    var frames: [AppleReviewFrame]

    enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version", sourceDisplayName = "source_display_name"
        case originalSourceURI = "original_source_uri", playbackURI = "playback_uri"
        case annotationSidecarPath = "annotation_sidecar_path"
        case updatedAtMilliseconds = "updated_at_ms", frames
    }
}

struct ApplePendingReviewPoint: Identifiable {
    let id = UUID()
    let x: Double
    let y: Double
    let timestampMicroseconds: Int64
    let anomalyDebugSummary: String?
    let box: AppleReviewNormalizedBox?
}

private enum CapturedVideoError: LocalizedError {
    case inaccessible, staleSelection
    var errorDescription: String? {
        switch self {
        case .inaccessible: "The selected video could not be copied into the local review cache."
        case .staleSelection: "A newer captured video selection replaced this one."
        }
    }
}

@MainActor
final class AppleCapturedVideoReviewModel: ObservableObject {
    @Published private(set) var player: AVPlayer?
    @Published private(set) var displayName = "No captured video selected"
    @Published private(set) var state = "Select a captured video to begin."
    @Published private(set) var isPaused = true
    @Published private(set) var isStaging = false
    @Published private(set) var currentSeconds = 0.0
    @Published private(set) var durationSeconds = 0.0
    @Published private(set) var videoAspectRatio = 16.0 / 9.0
    @Published private(set) var sidecar = AppleReviewSidecar(
        sourceDisplayName: "", updatedAtMilliseconds: 0, frames: []
    )
    @Published var pendingPoint: ApplePendingReviewPoint?
    @Published var pauseOnOpen = UserDefaults.standard.bool(forKey: "capturedVideo.pauseOnOpen") {
        didSet { UserDefaults.standard.set(pauseOnOpen, forKey: "capturedVideo.pauseOnOpen") }
    }
    @Published private(set) var anomalyBoxes: [AppleAnomalyBox] = []
    @Published private(set) var analyzedFrameCount = 0
    @Published private(set) var droppedAnalysisFrameCount = 0
    @Published private(set) var anomalyMode: AppleAnomalyMode

    private var openGeneration = 0
    private var stagedURL: URL?
    private var sidecarURL: URL?
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var presentationSizeObservation: NSKeyValueObservation?
    private var videoOutput: AVPlayerItemVideoOutput?
    private var analysisTimer: Timer?
    private let anomalyProcessor: AppleAnomalyProcessor

    init(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: "video.anomalyMode")
        let mode = AppleAnomalyMode.off
        anomalyMode = mode
        anomalyProcessor = AppleAnomalyProcessor(mode: mode, configuration: AppleAnomalyConfiguration.load(from: defaults))
    }

    var annotationCount: Int { sidecar.frames.reduce(0) { $0 + $1.annotations.count } }
    var shareSidecarURL: URL? { annotationCount > 0 ? sidecarURL : nil }
    var annotationSummary: String {
        let annotations = sidecar.frames.flatMap(\.annotations)
        let good = annotations.count { $0.verdict == .good }
        let bad = annotations.count { $0.verdict == .bad }
        let unsure = annotations.count { $0.verdict == .unsure }
        return "Good \(good)  Bad \(bad)  Unsure \(unsure)"
    }

    func open(_ sourceURL: URL) async {
        openGeneration += 1
        let generation = openGeneration
        closePlayback(deleteStagedVideo: true)
        if let day = sourceURL.pathComponents.first(where: { AppleFlightStorage.date($0) != nil }) { AppleFlightStorage.protect(day, owner: "video-review") }
        displayName = sourceURL.lastPathComponent
        state = "Preparing captured video…"
        isStaging = true
        let access = sourceURL.startAccessingSecurityScopedResource()
        defer { if access { sourceURL.stopAccessingSecurityScopedResource() } }
        do {
            let destination = try await Task.detached(priority: .utility) {
                let root = FileManager.default.temporaryDirectory
                    .appendingPathComponent("RID2Caltopo/CapturedVideo", isDirectory: true)
                try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
                let suffix = sourceURL.pathExtension.isEmpty ? "mov" : sourceURL.pathExtension
                let destination = root.appendingPathComponent("review-\(UUID().uuidString).\(suffix)")
                let coordinator = NSFileCoordinator()
                var coordinationError: NSError?
                var copyError: Error?
                coordinator.coordinate(readingItemAt: sourceURL, options: [], error: &coordinationError) { readableURL in
                    do { try FileManager.default.copyItem(at: readableURL, to: destination) }
                    catch { copyError = error }
                }
                if let error = coordinationError ?? copyError as NSError? { throw error }
                guard FileManager.default.fileExists(atPath: destination.path) else { throw CapturedVideoError.inaccessible }
                return destination
            }.value
            guard generation == openGeneration else {
                try? FileManager.default.removeItem(at: destination)
                throw CapturedVideoError.staleSelection
            }
            install(destination, original: sourceURL)
        } catch is CancellationError {
            state = "Captured-video preparation cancelled."
        } catch CapturedVideoError.staleSelection {
            // A later picker result owns the UI and status.
        } catch {
            state = "Unable to open captured video: \(error.localizedDescription)"
        }
        if generation == openGeneration { isStaging = false }
    }

    func togglePlayback() {
        guard let player else { return }
        if isPaused {
            if durationSeconds > 0, currentSeconds >= durationSeconds - 0.05 { seek(to: 0) }
            player.play()
            isPaused = false
        } else {
            player.pause()
            isPaused = true
        }
    }

    func stepFrame() {
        guard player != nil else { return }
        player?.pause()
        isPaused = true
        seek(to: min(durationSeconds, currentSeconds + (1.0 / 30.0)))
    }

    func stepBackFrame() {
        guard player != nil else { return }
        player?.pause()
        isPaused = true
        seek(to: max(0, currentSeconds - (1.0 / 30.0)))
    }

    func setAnomalyMode(_ mode: AppleAnomalyMode) {
        anomalyMode = mode
        anomalyBoxes = []
        analyzedFrameCount = 0
        droppedAnalysisFrameCount = 0
        anomalyProcessor.configure(mode: mode, configuration: AppleAnomalyConfiguration.load(from: .standard))
    }

    func seek(to seconds: Double) {
        let bounded = min(max(0, seconds), max(0, durationSeconds))
        player?.seek(to: CMTime(seconds: bounded, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        currentSeconds = bounded
    }

    func proposeAnnotation(x: Double, y: Double) {
        guard player != nil, isPaused else { return }
        pendingPoint = ApplePendingReviewPoint(
            x: min(max(0, x), 1), y: min(max(0, y), 1),
            timestampMicroseconds: Int64((currentSeconds * 1_000_000).rounded()),
            anomalyDebugSummary: anomalyMode == .off ? nil : "\(anomalyMode.label): \(anomalyBoxes.count) guide box(es)",
            box: anomalyBoxes.first(where: { x >= $0.left && x <= $0.right && y >= $0.top && y <= $0.bottom }).map {
                .init(xMin: $0.left, yMin: $0.top, xMax: $0.right, yMax: $0.bottom)
            }
        )
    }

    func saveAnnotation(
        point: ApplePendingReviewPoint, verdict: AppleReviewVerdict, kind: AppleReviewKind,
        objectType: AppleReviewObjectType, scenario: AppleReviewScenario?, note: String
    ) {
        let annotation = AppleReviewAnnotation(
            xNorm: point.x, yNorm: point.y, verdict: verdict, reviewKind: kind,
            objectType: objectType, scenario: scenario, note: note.trimmingCharacters(in: .whitespacesAndNewlines),
            createdAtMilliseconds: Int64(Date().timeIntervalSince1970 * 1_000),
            anomalyDebugSummary: point.anomalyDebugSummary, box: point.box
        )
        if let index = sidecar.frames.firstIndex(where: { $0.sourceTimestampMicroseconds == point.timestampMicroseconds }) {
            sidecar.frames[index].annotations.append(annotation)
        } else {
            sidecar.frames.append(.init(sourceTimestampMicroseconds: point.timestampMicroseconds, annotations: [annotation]))
            sidecar.frames.sort { $0.sourceTimestampMicroseconds < $1.sourceTimestampMicroseconds }
        }
        sidecar.updatedAtMilliseconds = Int64(Date().timeIntervalSince1970 * 1_000)
        persistSidecar()
        pendingPoint = nil
    }

    func clearAnnotations() {
        sidecar.frames = []
        sidecar.updatedAtMilliseconds = Int64(Date().timeIntervalSince1970 * 1_000)
        if let sidecarURL { try? FileManager.default.removeItem(at: sidecarURL) }
        state = "Cleared captured-video review annotations."
    }

    func close() {
        openGeneration += 1
        closePlayback(deleteStagedVideo: true)
        displayName = "No captured video selected"
        state = "Select a captured video to begin."
        sidecar = .init(sourceDisplayName: "", updatedAtMilliseconds: 0, frames: [])
    }

    private func install(_ playbackURL: URL, original: URL) {
        stagedURL = playbackURL
        let item = AVPlayerItem(url: playbackURL)
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        ])
        item.add(output)
        videoOutput = output
        let player = AVPlayer(playerItem: item)
        self.player = player
        isPaused = pauseOnOpen
        state = pauseOnOpen ? "Paused. Tap the video to annotate the current frame." : "Playing captured video."
        let generation = openGeneration
        Task { [weak self] in
            guard let duration = try? await item.asset.load(.duration) else { return }
            guard let self, generation == self.openGeneration else { return }
            let seconds = duration.seconds
            self.durationSeconds = seconds.isFinite ? max(0, seconds) : 0
        }
        presentationSizeObservation = item.observe(\.presentationSize, options: [.initial, .new]) { [weak self] item, _ in
            Task { @MainActor in
                let size = item.presentationSize
                guard size.width > 0, size.height > 0 else { return }
                self?.videoAspectRatio = size.width / size.height
            }
        }

        let digest = SHA256.hash(data: Data(original.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
        let originalDay = original.pathComponents.first { AppleFlightStorage.date($0) != nil }
        let root = AppleFlightStorage.root.appendingPathComponent(originalDay ?? AppleFlightStorage.dayName(Date()))
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        sidecarURL = root.appendingPathComponent("\(digest).review.json")
        if let sidecarURL, let data = try? Data(contentsOf: sidecarURL),
           let decoded = try? JSONDecoder().decode(AppleReviewSidecar.self, from: data) {
            sidecar = decoded
        } else {
            sidecar = .init(
                sourceDisplayName: original.lastPathComponent,
                originalSourceURI: original.absoluteString,
                playbackURI: playbackURL.absoluteString,
                annotationSidecarPath: sidecarURL?.path,
                updatedAtMilliseconds: 0,
                frames: []
            )
        }
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.1, preferredTimescale: 600), queue: .main
        ) { [weak self] time in
            Task { @MainActor in self?.currentSeconds = max(0, time.seconds.isFinite ? time.seconds : 0) }
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main
        ) { [weak self] _ in Task { @MainActor in self?.isPaused = true } }
        analysisTimer = .scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.analyzeCurrentFrame() }
        }
        if !pauseOnOpen { player.play() }
    }

    private func analyzeCurrentFrame() {
        guard anomalyMode != .off, let player, let videoOutput else {
            anomalyBoxes = []
            return
        }
        let time = player.currentTime()
        guard videoOutput.hasNewPixelBuffer(forItemTime: time),
              let pixelBuffer = videoOutput.copyPixelBuffer(forItemTime: time, itemTimeForDisplay: nil)
        else { return }
        let accepted = anomalyProcessor.submit(
            pixelBuffer: pixelBuffer,
            timestampMicroseconds: Int64((max(0, time.seconds) * 1_000_000).rounded())
        ) { [weak self] result in
            self?.analyzedFrameCount += 1
            self?.anomalyBoxes = result.boxes
        }
        if !accepted { droppedAnalysisFrameCount += 1 }
    }

    private func persistSidecar() {
        guard let sidecarURL else { return }
        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            try encoder.encode(sidecar).write(to: sidecarURL, options: .atomic)
            state = "Saved \(annotationCount) review annotation(s)."
        } catch { state = "Review annotation could not be saved: \(error.localizedDescription)" }
    }

    private func closePlayback(deleteStagedVideo: Bool) {
        AppleFlightStorage.release(owner: "video-review")
        player?.pause()
        if let timeObserver, let player { player.removeTimeObserver(timeObserver) }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        timeObserver = nil
        endObserver = nil
        presentationSizeObservation = nil
        analysisTimer?.invalidate()
        analysisTimer = nil
        videoOutput = nil
        anomalyBoxes = []
        player = nil
        currentSeconds = 0
        durationSeconds = 0
        videoAspectRatio = 16.0 / 9.0
        isPaused = true
        if deleteStagedVideo, let stagedURL { try? FileManager.default.removeItem(at: stagedURL) }
        stagedURL = nil
    }
}

struct AppleCapturedVideoReviewView: View {
    @StateObject private var model = AppleCapturedVideoReviewModel()
    @State private var importing = false
    @State private var confirmingClear = false

    var body: some View {
        VStack(spacing: 0) {
            video
            controls
            List {
                Section("Review") {
                    LabeledContent("Video", value: model.displayName)
                    LabeledContent("Annotations", value: String(model.annotationCount))
                    if model.annotationCount > 0 { Text(model.annotationSummary).font(.caption.monospacedDigit()) }
                    Toggle("Pause on Open", isOn: $model.pauseOnOpen)
                    Picker("AD Mode", selection: Binding(
                        get: { model.anomalyMode }, set: { model.setAnomalyMode($0) }
                    )) {
                        ForEach(AppleAnomalyMode.allCases) { Text($0.label).tag($0) }
                    }
                    if model.anomalyMode != .off {
                        LabeledContent("Analyzed / dropped", value: "\(model.analyzedFrameCount) / \(model.droppedAnalysisFrameCount)")
                    }
                    Text(model.state).font(.caption).foregroundStyle(.secondary)
                    if let sidecarURL = model.shareSidecarURL {
                        ShareLink(item: sidecarURL) { Label("Export Review Sidecar", systemImage: "square.and.arrow.up") }
                    }
                    if model.annotationCount > 0 {
                        Button("Clear Review Annotations", systemImage: "trash", role: .destructive) { confirmingClear = true }
                    }
                }
                Section {
                    Text("Pause on the frame to review, then tap the subject or false positive. Review sidecars use Android's schema version 2 field names.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Captured Video Review")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("Open", systemImage: "folder") { importing = true }
                if model.player != nil { Button("Close", systemImage: "xmark") { model.close() } }
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.movie]) { result in
            if case let .success(url) = result { Task { await model.open(url) } }
        }
        .sheet(item: $model.pendingPoint) { point in
            AppleReviewAnnotationSheet(point: point) { verdict, kind, object, scenario, note in
                model.saveAnnotation(point: point, verdict: verdict, kind: kind, objectType: object, scenario: scenario, note: note)
            }
        }
        .confirmationDialog("Clear all review annotations for this video?", isPresented: $confirmingClear) {
            Button("Clear Annotations", role: .destructive) { model.clearAnnotations() }
        }
    }

    private var video: some View {
        GeometryReader { geometry in
            ZStack {
                Color.black
                if let player = model.player { VideoPlayer(player: player).allowsHitTesting(false) }
                else { ContentUnavailableView("No Captured Video", systemImage: "film", description: Text("Choose Open to select a movie.")) }
                if model.isStaging { ProgressView("Preparing…").padding().background(.ultraThinMaterial, in: .rect(cornerRadius: 8)) }
                if model.player != nil, model.isPaused {
                    AnomalyBoxOverlay(boxes: model.anomalyBoxes)
                        .allowsHitTesting(false)
                    ForEach(model.sidecar.frames.first(where: { abs(Double($0.sourceTimestampMicroseconds) / 1_000_000 - model.currentSeconds) < 0.05 })?.annotations ?? []) { annotation in
                        Circle().stroke(annotationColor(annotation.verdict), lineWidth: 3)
                            .frame(width: 28, height: 28)
                            .position(x: annotation.xNorm * geometry.size.width, y: annotation.yNorm * geometry.size.height)
                    }
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { location in
                model.proposeAnnotation(x: location.x / max(1, geometry.size.width), y: location.y / max(1, geometry.size.height))
            }
        }
        .aspectRatio(model.videoAspectRatio, contentMode: .fit)
    }

    private var controls: some View {
        VStack(spacing: 4) {
            Slider(
                value: Binding(
                    get: { model.currentSeconds },
                    set: { value in model.seek(to: value) }
                ),
                in: 0 ... max(0.01, model.durationSeconds)
            )
            HStack {
                Button("Back", systemImage: "backward.frame.fill") { model.stepBackFrame() }
                    .disabled(model.player == nil || !model.isPaused)
                Button(model.isPaused ? "Run" : "Pause", systemImage: model.isPaused ? "play.fill" : "pause.fill") { model.togglePlayback() }
                    .disabled(model.player == nil)
                Button("Step", systemImage: "forward.frame.fill") { model.stepFrame() }
                    .disabled(model.player == nil || !model.isPaused)
                Spacer()
                Text("\(time(model.currentSeconds)) / \(time(model.durationSeconds))").font(.caption.monospacedDigit())
            }
        }
        .padding(.horizontal).padding(.vertical, 6).background(.bar)
    }

    private func time(_ seconds: Double) -> String {
        guard seconds.isFinite else { return "00:00" }
        return String(format: "%02d:%02d", Int(seconds) / 60, Int(seconds) % 60)
    }

    private func annotationColor(_ verdict: AppleReviewVerdict) -> Color {
        switch verdict { case .good: .green; case .bad: .red; case .unsure: .yellow }
    }
}

private struct AppleReviewAnnotationSheet: View {
    let point: ApplePendingReviewPoint
    let onSave: (AppleReviewVerdict, AppleReviewKind, AppleReviewObjectType, AppleReviewScenario?, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var verdict = AppleReviewVerdict.unsure
    @State private var kind = AppleReviewKind.unsure
    @State private var object = AppleReviewObjectType.unknown
    @State private var scenario: AppleReviewScenario?
    @State private var note = ""

    var body: some View {
        NavigationStack {
            Form {
                Picker("Verdict", selection: $verdict) { ForEach(AppleReviewVerdict.allCases) { Text($0.label).tag($0) } }
                Picker("Review kind", selection: $kind) { ForEach(AppleReviewKind.allCases) { Text($0.label).tag($0) } }
                Picker("Object", selection: $object) { ForEach(AppleReviewObjectType.allCases) { Text($0.label).tag($0) } }
                Picker("Scenario", selection: $scenario) {
                    Text("None").tag(AppleReviewScenario?.none)
                    ForEach(AppleReviewScenario.allCases) { Text($0.label).tag(Optional($0)) }
                }
                TextField("Note", text: $note, axis: .vertical)
            }
            .navigationTitle("Review Annotation")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { onSave(verdict, kind, object, scenario, note); dismiss() }
                }
            }
        }
    }
}
