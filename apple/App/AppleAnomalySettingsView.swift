import Foundation
import R2CCore
import SwiftUI

enum AppleAnomalyStrideMode: Int, CaseIterable, Identifiable, Codable, Sendable {
    case fixed = 0, adaptive = 1
    var id: Int { rawValue }
    var label: String { self == .fixed ? "Fixed" : "Adaptive" }
}

enum AppleThermalPolarity: Int, CaseIterable, Identifiable, Codable, Sendable {
    case whiteHot = 1, blackHot = 2
    var id: Int { rawValue }
    var label: String { self == .whiteHot ? "White Hot" : "Black Hot" }
}

enum AppleMotionRegistration: Int, CaseIterable, Identifiable, Codable, Sendable {
    case gmv = 1, affine = 2
    var id: Int { rawValue }
    var label: String { self == .gmv ? "GMV" : "Affine" }
}

enum AppleMovementEstimator: Int, CaseIterable, Identifiable, Codable, Sendable {
    case legacy = 0, layeredShadow = 1, layeredActive = 2
    var id: Int { rawValue }
    var label: String {
        switch self { case .legacy: "Legacy"; case .layeredShadow: "Shadow"; case .layeredActive: "Active" }
    }
}

enum AppleTargetColor: Int, CaseIterable, Identifiable, Sendable {
    case red = 0x01, blue = 0x02, yellow = 0x04, green = 0x08, black = 0x10
    case white = 0x20, grey = 0x40, brown = 0x80, pink = 0x100, orange = 0x200, purple = 0x400
    var id: Int { rawValue }
    var label: String { String(describing: self).capitalized }
}

struct AppleAnomalyConfiguration: Codable, Equatable, Sendable {
    private static let legacyStorageKey = "video.anomalyConfiguration.v1"

    var showGuideBoxes = true
    var showHotOverlay = false
    var showCandidateBlobs = false
    var troubleshootingDebug = false
    var motionEnabled = true
    var saliencyEnabled = false
    var strideMode = AppleAnomalyStrideMode.fixed
    var frameStride = 1
    var adaptiveMinStrideFrames = 2
    var adaptiveMaxStrideSeconds = 1.0
    var pixelStep = 0
    var sensitivity = AnomalyConfigurationParity.defaultSensitivity
    var motionEvidenceSensitivity = AnomalyConfigurationParity.defaultMotionEvidenceSensitivity
    var minimumAreaFraction = AnomalyConfigurationParity.defaultMinimumAreaFraction
    var thermalPolarity = AppleThermalPolarity.blackHot
    var registrationMode = AppleMotionRegistration.affine
    var movementEstimator = AppleMovementEstimator.layeredActive
    var scanZone = 0.50
    var minimumHits = 2
    var thermalMinimumDelta = 10.0
    var smallTargetScreenFraction = 1.0 / 200.0
    var colorCandidateLimit = 1
    var targetColorMask = 0

    var normalized: Self {
        var value = self
        value.frameStride = min(max(1, frameStride), 33)
        value.adaptiveMinStrideFrames = min(max(2, adaptiveMinStrideFrames), 60)
        value.adaptiveMaxStrideSeconds = min(max(0.1, adaptiveMaxStrideSeconds), 10)
        value.pixelStep = min(max(0, pixelStep), 4)
        value.sensitivity = min(max(0, sensitivity), 1)
        value.motionEvidenceSensitivity = min(max(0, motionEvidenceSensitivity), 1)
        value.minimumAreaFraction = min(max(0.0001, minimumAreaFraction), 0.1)
        value.scanZone = min(max(0.5, scanZone), 1)
        value.minimumHits = min(max(1, minimumHits), 5)
        value.thermalMinimumDelta = min(max(1, thermalMinimumDelta), 64)
        value.smallTargetScreenFraction = min(max(0.0015, smallTargetScreenFraction), 0.03)
        value.colorCandidateLimit = min(max(1, colorCandidateLimit), 4)
        value.targetColorMask &= AppleTargetColor.allCases.reduce(0) { $0 | $1.rawValue }
        return value
    }

    static func realtimeDefaults(for mode: AppleAnomalyMode) -> Self {
        var value = Self()
        if mode == .colorUniqueness || mode == .targetColors {
            value.strideMode = .adaptive
            value.frameStride = AnomalyConfigurationParity.colorAdaptiveMinimumFrames
            value.adaptiveMinStrideFrames = AnomalyConfigurationParity.colorAdaptiveMinimumFrames
            value.adaptiveMaxStrideSeconds = AnomalyConfigurationParity.colorAdaptiveMaximumSeconds
        }
        if mode == .targetColors, value.targetColorMask == 0 { value.targetColorMask = AppleTargetColor.red.rawValue }
        return value
    }

    func resetToRealtimeDefaults(for mode: AppleAnomalyMode) -> Self {
        var value = Self()
        value.thermalPolarity = thermalPolarity
        value.targetColorMask = targetColorMask
        return value.transitioning(to: mode)
    }

    func transitioning(to mode: AppleAnomalyMode) -> Self {
        var value = self
        switch mode {
        case .off:
            break
        case .colorUniqueness:
            value.targetColorMask = 0
            value.applyColorRealtimeCadenceIfUnmodified()
        case .targetColors:
            if value.targetColorMask == 0 {
                value.targetColorMask = AppleTargetColor.red.rawValue
            }
            value.applyColorRealtimeCadenceIfUnmodified()
        case .infrared:
            value.targetColorMask = 0
        }
        return value
    }

    private mutating func applyColorRealtimeCadenceIfUnmodified() {
        let isBaseDefault = strideMode == .fixed
            && frameStride == 1
            && adaptiveMinStrideFrames == 2
            && abs(adaptiveMaxStrideSeconds - 1) < 0.001
        let isColorDefault = strideMode == .adaptive
            && frameStride == AnomalyConfigurationParity.colorAdaptiveMinimumFrames
            && adaptiveMinStrideFrames == AnomalyConfigurationParity.colorAdaptiveMinimumFrames
            && abs(adaptiveMaxStrideSeconds - AnomalyConfigurationParity.colorAdaptiveMaximumSeconds) < 0.001
        guard isBaseDefault || isColorDefault else { return }
        strideMode = .adaptive
        frameStride = AnomalyConfigurationParity.colorAdaptiveMinimumFrames
        adaptiveMinStrideFrames = AnomalyConfigurationParity.colorAdaptiveMinimumFrames
        adaptiveMaxStrideSeconds = AnomalyConfigurationParity.colorAdaptiveMaximumSeconds
    }

    func algorithmMask(for mode: AppleAnomalyMode) -> Int32 {
        var mask = mode.algorithmMask
        if mode != .off, motionEnabled { mask |= R2C_ANOMALY_ALGORITHM_MOTION }
        if mode != .off, saliencyEnabled { mask |= R2C_ANOMALY_ALGORITHM_SALIENCY }
        return mask
    }

    func nativeConfiguration(for mode: AppleAnomalyMode) -> R2CAnomalyConfiguration {
        let value = normalized
        let isColorMode = mode == .colorUniqueness || mode == .targetColors
        let usesColorRealtimeCadence = AnomalyConfigurationParity.usesColorRealtimeCadence(
            isColorMode: isColorMode,
            strideMode: value.strideMode.rawValue,
            frameStride: value.frameStride,
            adaptiveMinimumFrames: value.adaptiveMinStrideFrames,
            adaptiveMaximumSeconds: value.adaptiveMaxStrideSeconds
        )
        let nativeStrideMode = usesColorRealtimeCadence ? AppleAnomalyStrideMode.adaptive.rawValue : value.strideMode.rawValue
        let nativeFrameStride = usesColorRealtimeCadence
            ? max(value.frameStride, AnomalyConfigurationParity.colorAdaptiveMinimumFrames)
            : value.frameStride
        let nativeAdaptiveMinimum = usesColorRealtimeCadence
            ? AnomalyConfigurationParity.colorAdaptiveMinimumFrames
            : value.adaptiveMinStrideFrames
        let nativeAdaptiveMaximumSeconds = usesColorRealtimeCadence
            ? AnomalyConfigurationParity.colorAdaptiveMaximumSeconds
            : value.adaptiveMaxStrideSeconds
        return R2CAnomalyConfiguration(
            enabled: mode == .off ? 0 : 1,
            show_hot_overlay: value.showHotOverlay ? 1 : 0,
            show_candidate_blobs: value.showCandidateBlobs ? 1 : 0,
            algorithm_mask: value.algorithmMask(for: mode),
            registration_mode: Int32(value.registrationMode.rawValue),
            movement_estimator_mode: Int32(value.movementEstimator.rawValue),
            stride_mode: Int32(nativeStrideMode),
            frame_stride: Int32(nativeFrameStride),
            adaptive_min_stride_frames: Int32(nativeAdaptiveMinimum),
            adaptive_max_stride_seconds: Float(nativeAdaptiveMaximumSeconds),
            pixel_step: Int32(AnomalyConfigurationParity.pixelStep(
                isColorMode: isColorMode,
                configuredStep: value.pixelStep
            )),
            score_threshold: AnomalyConfigurationParity.scoreThreshold(sensitivity: value.sensitivity),
            motion_evidence_scale: AnomalyConfigurationParity.motionEvidenceScale(
                sensitivity: value.motionEvidenceSensitivity
            ),
            min_area_fraction: AnomalyConfigurationParity.minimumAreaFraction(
                base: value.minimumAreaFraction,
                sensitivity: value.sensitivity
            ),
            thermal_polarity: Int32(value.thermalPolarity.rawValue),
            scan_zone: Float(value.scanZone),
            min_hits: Int32(value.minimumHits),
            thermal_min_delta: Float(value.thermalMinimumDelta),
            small_target_screen_fraction: Float(value.smallTargetScreenFraction),
            color_frontend_mode: Int32(AnomalyConfigurationParity.colorFrontendMode(
                isColorMode: isColorMode,
                configuredMode: 0
            )),
            color_target_candidate_limit: Int32(value.colorCandidateLimit),
            target_color_family_mask: UInt32(value.targetColorMask)
        )
    }

    func diagnosticSummary(for mode: AppleAnomalyMode) -> String {
        let native = nativeConfiguration(for: mode)
        return String(
            format: "mode=%@ algorithms=0x%02X sensitivity=%.0f%% threshold=%.3f motion=%.0f%% scan=%.0f%% target=1/%.0f stride=%d/%d adaptive=%d-%.1fs pixel=%d candidates=%d",
            mode.label,
            native.algorithm_mask,
            sensitivity * 100,
            native.score_threshold,
            motionEvidenceSensitivity * 100,
            scanZone * 100,
            1 / smallTargetScreenFraction,
            native.stride_mode,
            native.frame_stride,
            native.adaptive_min_stride_frames,
            native.adaptive_max_stride_seconds,
            native.pixel_step,
            native.color_target_candidate_limit
        )
    }

    static func load(from defaults: UserDefaults) -> Self {
        // Match Android AnomalyPrefs: detector tuning is process-session state,
        // never a persisted operator preference. Remove values written by
        // earlier Apple builds and always begin from the canonical defaults.
        defaults.removeObject(forKey: legacyStorageKey)
        return realtimeDefaults(for: .off)
    }
}

struct AppleAnomalySettingsView: View {
    @ObservedObject var model: AppleVideoFrameSource
    @State private var configuration: AppleAnomalyConfiguration

    init(model: AppleVideoFrameSource) {
        self.model = model
        _configuration = State(initialValue: model.anomalyConfiguration)
    }

    var body: some View {
        Form {
            Section("Detector") {
                SettingsPicker("AD Mode", selection: modeBinding) {
                    ForEach(AppleAnomalyMode.allCases) { Text($0.label).tag($0) }
                }
                if model.anomalyMode != .off {
                    HStack {
                        SettingsHelpLabel("Realtime Defaults")
                        Spacer()
                        Button("Reset") {
                            configuration = configuration.resetToRealtimeDefaults(for: model.anomalyMode)
                            apply()
                        }
                    }
                    SettingsToggle("Motion", isOn: $configuration.motionEnabled)
                    SettingsToggle("Saliency", isOn: $configuration.saliencyEnabled)
                    SettingsToggle("Show Guide Boxes", isOn: $configuration.showGuideBoxes)
                    if model.anomalyMode == .infrared {
                        SettingsToggle("Show Hottest Region", isOn: $configuration.showHotOverlay)
                    }
                    SettingsToggle("Show Candidate Blobs", isOn: $configuration.showCandidateBlobs)
                    SettingsToggle("Troubleshooting Debug", isOn: $configuration.troubleshootingDebug)
                }
            }
            if model.anomalyMode == .targetColors { targetColorsSection }
            if model.anomalyMode != .off {
                Section("Sensitivity") {
                    valueSlider("Sensitivity", value: $configuration.sensitivity, range: 0 ... 1, format: .percent)
                    valueSlider("Motion Evidence", value: $configuration.motionEvidenceSensitivity, range: 0 ... 1, format: .percent)
                    SettingsStepper("Min Hits: \(configuration.minimumHits)", value: $configuration.minimumHits, in: 1 ... 5)
                }
                Section("Scan Frame & Target Size") {
                    valueSlider("Scan Zone", value: $configuration.scanZone, range: 0.5 ... 1, format: .percent)
                    valueSlider(
                        "Small Target Scale",
                        value: $configuration.smallTargetScreenFraction,
                        range: 0.0015 ... 0.03,
                        format: .fraction
                    )
                    Text("The outer cyan frame is scanned. The centered square is the largest target treated as small.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    ZStack {
                        Color.black.opacity(0.18)
                        AnomalyGuideOverlay(
                            scanZone: configuration.scanZone,
                            smallTargetScreenFraction: configuration.smallTargetScreenFraction,
                            maximumTargetFraction: 0.40,
                            opacity: 0.80,
                            lineWidth: 2
                        )
                    }
                    .aspectRatio(16 / 9, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }
                Section("Cadence") {
                    SettingsPicker("Stride", selection: $configuration.strideMode) {
                        ForEach(AppleAnomalyStrideMode.allCases) { Text($0.label).tag($0) }
                    }
                    SettingsStepper("Frame Stride: \(configuration.frameStride)x", value: $configuration.frameStride, in: 1 ... 33)
                    if configuration.strideMode == .adaptive {
                        SettingsStepper("Adaptive Minimum: \(configuration.adaptiveMinStrideFrames) frames", value: $configuration.adaptiveMinStrideFrames, in: 2 ... 33)
                        valueSlider("Adaptive Maximum", value: $configuration.adaptiveMaxStrideSeconds, range: 0.1 ... 10, format: .seconds)
                    }
                    SettingsStepper("Detail: \(configuration.pixelStep == 0 ? "Auto" : String(configuration.pixelStep))", value: $configuration.pixelStep, in: 0 ... 4)
                }
                Section("Motion Registration") {
                    SettingsPicker("Registration", selection: $configuration.registrationMode) {
                        ForEach(AppleMotionRegistration.allCases) { Text($0.label).tag($0) }
                    }
                    SettingsPicker("Movement Estimator", selection: $configuration.movementEstimator) {
                        ForEach(AppleMovementEstimator.allCases) { Text($0.label).tag($0) }
                    }
                }
                if model.anomalyMode == .infrared {
                    Section("Thermal") {
                        SettingsPicker("Infrared Palette", selection: $configuration.thermalPolarity) {
                            ForEach(AppleThermalPolarity.allCases) { Text($0.label).tag($0) }
                        }
                        valueSlider("Thermal Min Delta", value: $configuration.thermalMinimumDelta, range: 1 ... 64, format: .plain)
                    }
                }
                if model.anomalyMode == .colorUniqueness || model.anomalyMode == .targetColors {
                    Section("Color") {
                        SettingsStepper("Color Candidates: \(configuration.colorCandidateLimit)", value: $configuration.colorCandidateLimit, in: 1 ... 4)
                    }
                }
                Section {
                    Button("Apply") { apply() }
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .navigationTitle("Anomaly Detector")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: model.anomalyConfiguration) { _, value in configuration = value }
    }

    private var modeBinding: Binding<AppleAnomalyMode> {
        Binding(get: { model.anomalyMode }, set: { mode in
            model.setAnomalyMode(mode)
        })
    }

    private var targetColorsSection: some View {
        Section("Target Colors") {
            ForEach(AppleTargetColor.allCases) { color in
                SettingsToggle(color.label, isOn: Binding(
                    get: { configuration.targetColorMask & color.rawValue != 0 },
                    set: { enabled in
                        if enabled { configuration.targetColorMask |= color.rawValue }
                        else { configuration.targetColorMask &= ~color.rawValue }
                    }
                ), helpKey: "Target Colors")
            }
        }
    }

    private enum SliderFormat { case percent, seconds, fraction, plain }

    private func valueSlider(_ title: String, value: Binding<Double>, range: ClosedRange<Double>, format: SliderFormat) -> some View {
        VStack(alignment: .leading) {
            SettingsHelpLabel(title, centered: true).font(.subheadline.weight(.semibold)).foregroundStyle(.tint)
            VStack {
                Text(label(value.wrappedValue, format: format)).monospacedDigit()
            Slider(value: value, in: range)
            }.modifier(SettingsFieldBox())
        }
    }

    private func label(_ value: Double, format: SliderFormat) -> String {
        switch format {
        case .percent: String(format: "%.0f%%", value * 100)
        case .seconds: String(format: "%.1fs", value)
        case .fraction: "1/\(Int((1 / value).rounded()))"
        case .plain: String(format: "%.1f", value)
        }
    }

    private func apply() { model.applyAnomalyConfiguration(configuration) }
}

struct AppleAnomalyHelpView: View {
    private let entries: [(String, String)] = [
        ("AD mode", "The stream legend shows the current mode. Open settings to select Off, Color Uniqueness, Target Colors, or Infrared."),
        ("Session settings", "Detector settings last for this app session. Startup returns AD to Off with realtime defaults."),
        ("Sensitivity", "Lower values are stricter and require a stronger outlier before drawing a box."),
        ("Scan Zone", "The centered portion of the frame scanned for anomalies. Lower values ignore more of the outer frame."),
        ("Min Hits", "Consecutive analyzed-frame hits required in roughly the same motion-stabilized region before a detection is promoted."),
        ("Frame Stride", "Analyze every Nth frame. Higher stride reduces CPU load but may miss brief motion."),
        ("Detail", "Pixel sampling step for appearance analysis. Auto chooses a default from frame size; smaller steps inspect more detail at higher cost."),
        ("Show Hottest Region", "Draws a red ring around the hottest region as an infrared debug aid."),
        ("Guide Boxes", "Shows cyan outlines for the centered scan zone and maximum small-target size."),
        ("Troubleshooting Debug", "Adds detailed anomaly frame and ROI information to the application diagnostics."),
        ("Saliency", "Enables the unified saliency detector."),
        ("Motion", "Higher motion-evidence values strengthen the motion detector and its influence in combined anomaly scoring."),
        ("Registration", "Affine usually tracks camera motion more accurately; GMV is simpler and may be cheaper."),
        ("Movement Estimator", "Legacy keeps current behavior. Shadow computes layered parallax telemetry without changing detections. Active applies experimental layered parallax suppression."),
        ("Infrared Palette", "White Hot means brighter pixels are hotter; Black Hot means darker pixels are hotter."),
        ("Thermal Min Delta", "Minimum infrared contrast before thermal or saliency evidence is considered."),
        ("Small Target Scale", "Maximum on-screen small-target box size, measured against the screen diagonal. Larger targets are down-ranked and can disappear as the camera zooms in.")
    ]

    var body: some View {
        List(entries, id: \.0) { title, explanation in
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline)
                Text(explanation).foregroundStyle(.secondary)
            }
            .padding(.vertical, 2)
        }
        .navigationTitle("Anomaly Detection Help")
        .navigationBarTitleDisplayMode(.inline)
    }
}
