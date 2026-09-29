import Foundation

public struct AirspaceServiceFailure: LocalizedError, Sendable {
    public let message: String
    public let rateLimited: Bool
    public init(_ message: String, rateLimited: Bool = false) {
        self.message = message
        self.rateLimited = rateLimited || message.lowercased().contains("too many requests")
    }
    public var errorDescription: String? { message }
}

public struct AirspaceRetryPolicy: Sendable {
    public private(set) var failures = 0
    public private(set) var retryAt = Date.distantPast
    public init() {}
    public func permits(_ now: Date = Date()) -> Bool { now >= retryAt }
    public mutating func succeeded() { failures = 0; retryAt = .distantPast }
    public mutating func failed(rateLimited: Bool, retryAfter: String?, now: Date = Date()) -> TimeInterval {
        failures = min(failures + 1, 5)
        let delay = 2.0 * pow(2, Double(failures - 1))
        let wait = max(delay, Self.serverDelay(retryAfter, now: now) ?? 0)
        retryAt = now.addingTimeInterval(wait)
        return wait
    }
    public static func serverDelay(_ value: String?, now: Date) -> TimeInterval? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines) else { return nil }
        if let seconds = Double(value), seconds.isFinite, seconds >= 0 { return seconds }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return formatter.date(from: value).map { max(0, $0.timeIntervalSince(now)) }
    }
}
