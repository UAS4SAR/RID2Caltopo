import Foundation

public enum AircraftReceptionPromptDecision: Equatable, Sendable {
    /// Panel is closed. Caller should show Aircraft and Reception.
    case open
    /// Panel is already showing. Do not stack another presentation.
    case alreadyVisible
    /// Known team aircraft, already dispositioned, or already presented.
    case skip
}

/// Session-only memory of aircraft already presented in Aircraft and Reception.
/// The set lives in this object and is cleared by `reset` (a new process starts empty).
/// Nothing here is written to disk.
///
/// Mirrors app/src/main/java/org/ncssar/rid2caltopo/ui/AircraftReceptionPromptSession.kt.
public final class AircraftReceptionPromptSession: @unchecked Sendable {
    private let lock = NSLock()
    private var presented = Set<String>()

    public init() {}

    public func reset() {
        lock.lock()
        presented.removeAll()
        lock.unlock()
    }

    /// - Parameter unidentified: not on the team list (no distinct mapped id and no organization)
    /// - Parameter dispositionedThisSession: Don't Publish, or the confirmation panel already
    ///   owns this aircraft. Remembered so a later packet cannot open over that flow.
    public func observe(
        aircraftID: String,
        unidentified: Bool,
        dispositionedThisSession: Bool,
        panelOpen: Bool
    ) -> AircraftReceptionPromptDecision {
        let id = aircraftID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return .skip }
        lock.lock()
        defer { lock.unlock() }
        if presented.contains(id) { return .skip }
        if dispositionedThisSession || !unidentified {
            if dispositionedThisSession { presented.insert(id) }
            return .skip
        }
        presented.insert(id)
        return panelOpen ? .alreadyVisible : .open
    }

    /// Team-list aircraft have a mapped id other than the remote id, or an organization.
    public static func isUnidentified(remoteID: String, mappedID: String, organization: String) -> Bool {
        let id = remoteID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return false }
        let mapped = mappedID.trimmingCharacters(in: .whitespacesAndNewlines)
        let knownMapped = !mapped.isEmpty && mapped.caseInsensitiveCompare(id) != .orderedSame
        return !knownMapped && organization.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Stable short token for logs. Not the aircraft id and not a location.
    public static func logToken(_ aircraftID: String) -> String {
        var hash: UInt32 = 2_166_136_261
        for byte in aircraftID.trimmingCharacters(in: .whitespacesAndNewlines).utf8 {
            hash ^= UInt32(byte)
            hash = hash &* 16_777_619
        }
        return String(format: "%08x", hash)
    }
}
