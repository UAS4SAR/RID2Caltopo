import Combine
import Foundation

public enum ShortFlightRecordingPolicy {
    public static func qualifies(seconds: Double, meters: Double) -> Bool {
        seconds.isFinite && meters.isFinite && seconds >= 0 && meters >= 0 && seconds < 60 && meters < 160.9344
    }
}

public struct ShortFlightPrompt: Identifiable {
    public let id: UUID
    public let aircraft: String
    public let deadline: TimeInterval
}

@MainActor
public final class ShortFlightRecordingGate: ObservableObject {
    @Published public private(set) var prompts: [ShortFlightPrompt] = []
    private var callbacks: [UUID: () -> Void] = [:]
    private var active = false
    private let now: () -> TimeInterval
    public init(now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) { self.now = now }
    public func setActive(_ value: Bool) {
        active = value
        if !value { for prompt in prompts { decide(prompt.id, record: true) } }
    }
    // False means the caller should record immediately, preserving background/shutdown behavior.
    public func request(aircraft: String, seconds: Double, meters: Double, keep: @escaping () -> Void) -> Bool {
        guard active, ShortFlightRecordingPolicy.qualifies(seconds: seconds, meters: meters) else { return false }
        let prompt = ShortFlightPrompt(id: UUID(), aircraft: aircraft, deadline: now() + 10)
        callbacks[prompt.id] = keep
        prompts.append(prompt)
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(10))
            self?.expire()
        }
        return true
    }
    public func decide(_ id: UUID, record: Bool) {
        guard let prompt = prompts.first(where: { $0.id == id }), let keep = callbacks.removeValue(forKey: id) else { return }
        prompts.removeAll { $0.id == id }
        if record || now() >= prompt.deadline { keep() }
    }
    public func expire() {
        for prompt in prompts where now() >= prompt.deadline { decide(prompt.id, record: true) }
    }
    public func remainingSeconds(_ prompt: ShortFlightPrompt) -> Int { max(0, min(10, Int(ceil(prompt.deadline - now())))) }
}
