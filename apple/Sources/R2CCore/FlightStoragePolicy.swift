import Foundation

/// Retention is based on the calendar date of the activity, never directory mtime.
public enum FlightStoragePolicy {
    public static let defaultGB = 10.0
    public static let defaultDays = 30
    public static let reserveBytes: Int64 = 1_000_000_000
    public static func needsCleanup(used: Int64, incoming: Int64 = 0, maximum: Int64) -> Bool {
        used >= maximum * 9 / 10 - max(0, incoming)
    }
    public struct Folder: Sendable {
        public let name: String
        public let date: Date
        public let bytes: Int64
        public let protected: Bool
        public init(name: String, date: Date, bytes: Int64, protected: Bool) {
            self.name = name; self.date = date; self.bytes = bytes; self.protected = protected
        }
    }
    public static func candidates(_ folders: [Folder], used: Int64, maximum: Int64,
                                  maxDays: Int, now: Date, calendar: Calendar = .current) -> [String] {
        let cutoff = calendar.date(byAdding: .day, value: -maxDays, to: calendar.startOfDay(for: now))!
        var remaining = used
        var result: [String] = []
        for folder in folders.sorted(by: { $0.date < $1.date }) where !folder.protected {
            if folder.date < cutoff || remaining > maximum {
                result.append(folder.name)
                remaining -= folder.bytes
            }
        }
        return result
    }
}
