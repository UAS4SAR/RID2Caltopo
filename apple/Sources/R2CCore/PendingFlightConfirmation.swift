import Foundation

/// Presentation has its own lifetime: retiring eligibility alone does not dismiss a sheet.
public struct PendingFlightConfirmation: Sendable, Equatable {
    public private(set) var remoteID: String?
    public private(set) var isAutomatic = false

    public init() {}

    public mutating func present(remoteID: String?, automatic: Bool = false) {
        self.remoteID = remoteID
        self.isAutomatic = remoteID != nil && automatic
    }

    @discardableResult
    public mutating func endFlight(remoteID: String) -> Bool {
        guard self.remoteID == remoteID else { return false }
        self.remoteID = nil
        isAutomatic = false
        return true
    }
}
