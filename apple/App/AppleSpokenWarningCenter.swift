import AVFoundation
import UIKit
import R2CCore
@preconcurrency import WebRTC

@MainActor
final class AppleSpokenWarningCenter: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    static let shared = AppleSpokenWarningCenter()
    static let volumeDefaultsKey = "alerts.audioAlarmVolumePercent"

    @Published private(set) var volumePercent: Int

    private let defaults: UserDefaults
    private let audioSession: AVAudioSession
    private let speech = AVSpeechSynthesizer()
    private var speakingProximity = false
    private var pendingUtterances: Set<ObjectIdentifier> = []
    /// True while speech is using the WebRTC managed-video session instead of
    /// its own short-lived playback session.
    private var usingSharedWebRTCSession = false

    init(
        defaults: UserDefaults = .standard,
        audioSession: AVAudioSession = .sharedInstance()
    ) {
        self.defaults = defaults
        self.audioSession = audioSession
        let stored = defaults.object(forKey: Self.volumeDefaultsKey) as? Int
        volumePercent = OperationalAlarmAudioPolicy.normalizedVolumePercent(
            stored ?? OperationalAlarmAudioPolicy.defaultVolumePercent
        )
        super.init()
        speech.delegate = self
    }

    func setVolumePercent(_ value: Int) {
        let normalized = OperationalAlarmAudioPolicy.normalizedVolumePercent(value)
        volumePercent = normalized
        defaults.set(normalized, forKey: Self.volumeDefaultsKey)
    }

    func cancelProximityWarning() {
        guard speakingProximity else { return }
        speakingProximity = false
        speech.stopSpeaking(at: .immediate)
    }

    func requestAudioAlarmTest() {
        speak(OperationalAlarmAudioPolicy.testKinds.map(\.phrase))
    }

    func speak(_ phrase: String, volumeFraction: Float = 1) {
        speak([phrase], volumeFraction: volumeFraction)
    }

    func speak(_ phrases: [String], volumeFraction: Float = 1) {
        guard !phrases.isEmpty else { return }
        if Self.webRTCOwnsAudioSession(audioSession) {
            // Managed video (WebRTC) holds an active .playAndRecord/.voiceChat
            // session. Changing the category or deactivating it would stop the
            // WebRTC audio unit, so speak through that session and leave it alone.
            usingSharedWebRTCSession = true
        } else {
            usingSharedWebRTCSession = false
            do {
                // .duckOthers makes the session mixable, which iOS allows to be
                // activated while the app runs in background (audio background
                // mode). The session is active only while speaking.
                try audioSession.setCategory(
                    .playback,
                    mode: .spokenAudio,
                    options: [.duckOthers]
                )
                try audioSession.setActive(true)
                AppleLog.info("SpokenWarning", "Audio session activated category=playback mode=spokenAudio options=duckOthers appState=\(Self.applicationStateDescription)")
            } catch {
                AppleLog.error("SpokenWarning", "Unable to activate alarm audio session appState=\(Self.applicationStateDescription) \(Self.describe(error))")
            }
        }

        speech.stopSpeaking(at: .immediate)
        speakingProximity = phrases == [OperationalSpokenWarningKind.proximity.phrase]
        let configuredVolume = OperationalAlarmAudioPolicy.volumeMultiplier(forPercent: volumePercent)
        let utteranceVolume = min(1, max(0, volumeFraction)) * configuredVolume
        let outputs = audioSession.currentRoute.outputs.map { $0.portType.rawValue }.joined(separator: ",")
        AppleLog.info("SpokenWarning", "Requested phrases=\(phrases.joined(separator: " | ")) alarmVolume=\(volumePercent) systemVolume=\(audioSession.outputVolume) outputs=\(outputs) session=\(usingSharedWebRTCSession ? "webrtc" : "playback") appState=\(Self.applicationStateDescription)")
        for phrase in phrases {
            let utterance = AVSpeechUtterance(string: phrase)
            utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 0.88
            utterance.pitchMultiplier = 0.82
            utterance.volume = utteranceVolume
            pendingUtterances.insert(ObjectIdentifier(utterance))
            speech.speak(utterance)
        }
    }

    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        didStart utterance: AVSpeechUtterance
    ) {
        let phrase = utterance.speechString
        Task { @MainActor in
            AppleLog.info("SpokenWarning", "Speech started phrase=\(phrase) appState=\(Self.applicationStateDescription)")
        }
    }

    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        didFinish utterance: AVSpeechUtterance
    ) {
        let identifier = ObjectIdentifier(utterance)
        let phrase = utterance.speechString
        Task { @MainActor in
            AppleLog.info("SpokenWarning", "Speech finished phrase=\(phrase) appState=\(Self.applicationStateDescription)")
            finish(identifier)
        }
    }

    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        didCancel utterance: AVSpeechUtterance
    ) {
        let identifier = ObjectIdentifier(utterance)
        let phrase = utterance.speechString
        Task { @MainActor in
            AppleLog.warning("SpokenWarning", "Speech canceled phrase=\(phrase) appState=\(Self.applicationStateDescription)")
            finish(identifier)
        }
    }

    static var applicationStateDescription: String {
        switch UIApplication.shared.applicationState {
        case .active: "foreground"
        case .inactive: "inactive"
        case .background: "background"
        @unknown default: "unknown"
        }
    }

    /// AVAudioSession errors carry a four-character OSStatus such as '!int'
    /// (insufficient priority) or '!pla' (cannot start playing).
    static func describe(_ error: Error) -> String {
        let nsError = error as NSError
        let code = UInt32(bitPattern: Int32(truncatingIfNeeded: nsError.code))
        let bytes = [24, 16, 8, 0].map { UInt8((code >> UInt32($0)) & 0xFF) }
        let fourCC = bytes.allSatisfy { (0x20...0x7E).contains($0) }
            ? " ('\(String(decoding: bytes, as: UTF8.self))')"
            : ""
        return "domain=\(nsError.domain) code=\(nsError.code)\(fourCC) \(nsError.localizedDescription)"
    }

    private static func webRTCOwnsAudioSession(_ session: AVAudioSession) -> Bool {
        session.category == .playAndRecord && RTCAudioSession.sharedInstance().isAudioEnabled
    }

    private func finish(_ identifier: ObjectIdentifier) {
        pendingUtterances.remove(identifier)
        guard pendingUtterances.isEmpty else { return }
        guard !usingSharedWebRTCSession else {
            usingSharedWebRTCSession = false
            return
        }
        do {
            try audioSession.setActive(false, options: [.notifyOthersOnDeactivation])
        } catch {
            AppleLog.warning("SpokenWarning", "Unable to release alarm audio session \(Self.describe(error))")
        }
    }
}
