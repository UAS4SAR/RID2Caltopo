import Foundation

/// In-memory edits; setters are never invoked before an explicit commit.
@MainActor
public final class OperationalSettingsEdits {
    private struct Entry {
        let value: Any
        let original: Any
        let apply: () -> Void
    }
    private var entries: [String: Entry] = [:]
    public init() {}
    public var hasChanges: Bool { !entries.isEmpty }
    public func value<T>(for key: String, fallback: T) -> T { entries[key]?.value as? T ?? fallback }
    public func stage<T: Equatable>(_ key: String, value: T, original: T, apply: @escaping (T) -> Void) {
        let baseline = entries[key]?.original as? T ?? original
        if value == baseline { entries.removeValue(forKey: key) }
        else { entries[key] = Entry(value: value, original: baseline, apply: { apply(value) }) }
    }
    public func discard() { entries.removeAll() }
    public func commit() {
        let pending = entries.sorted { $0.key < $1.key }.map { $0.value }
        entries.removeAll()
        pending.forEach { $0.apply() }
    }
}
