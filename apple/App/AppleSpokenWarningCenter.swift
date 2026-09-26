import AVFoundation
import R2CCore

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
        do {
            try audioSession.setCategory(
                .playback,
                mode: .spokenAudio,
                options: [.duckOthers]
            )
            try audioSession.setActive(true)
        } catch {
            AppleLog.error("SpokenWarning", "Unable to activate alarm audio session: \(error.localizedDescription)")
        }

        speech.stopSpeaking(at: .immediate)
        speakingProximity = phrases == ["Proximity warning"]
        let configuredVolume = OperationalAlarmAudioPolicy.volumeMultiplier(forPercent: volumePercent)
        let utteranceVolume = min(1, max(0, volumeFraction)) * configuredVolume
        let outputs = audioSession.currentRoute.outputs.map { $0.portType.rawValue }.joined(separator: ",")
        AppleLog.info("SpokenWarning", "Requested phrases=\(phrases.joined(separator: " | ")) alarmVolume=\(volumePercent) systemVolume=\(audioSession.outputVolume) outputs=\(outputs)")
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
            AppleLog.info("SpokenWarning", "Speech started phrase=\(phrase)")
        }
    }

    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        didFinish utterance: AVSpeechUtterance
    ) {
        let identifier = ObjectIdentifier(utterance)
        Task { @MainActor in
            AppleLog.info("SpokenWarning", "Speech finished")
            finish(identifier)
        }
    }

    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        didCancel utterance: AVSpeechUtterance
    ) {
        let identifier = ObjectIdentifier(utterance)
        Task { @MainActor in
            AppleLog.info("SpokenWarning", "Speech canceled")
            finish(identifier)
        }
    }

    private func finish(_ identifier: ObjectIdentifier) {
        pendingUtterances.remove(identifier)
        guard pendingUtterances.isEmpty else { return }
        do {
            try audioSession.setActive(false, options: [.notifyOthersOnDeactivation])
        } catch {
            AppleLog.warning("SpokenWarning", "Unable to release alarm audio session: \(error.localizedDescription)")
        }
    }
}
