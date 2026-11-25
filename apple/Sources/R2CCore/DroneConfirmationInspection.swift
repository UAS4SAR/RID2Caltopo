import Foundation

/// Hide the Drone Confirmation Panel while Inspect is open, but keep the pending
/// confirmation so cancelling inspection brings the panel back until Mine or
/// Not Mine (Ken, Nov 25 A5 test of 450).
public enum DroneConfirmationInspection: Sendable {
    public static func shouldPresentPanel(
        hasPendingConfirmation: Bool,
        activeInspectionRemoteID: String?
    ) -> Bool {
        hasPendingConfirmation && activeInspectionRemoteID == nil
    }

    public static func shouldQueueNextConfirmation(heldRemoteID: String?) -> Bool {
        heldRemoteID == nil
    }

    public static func shouldRestoreHeldConfirmation(
        heldRemoteID: String?,
        alreadyConfirmed: Bool,
        alreadyIgnored: Bool
    ) -> Bool {
        guard let heldRemoteID, !heldRemoteID.isEmpty else { return false }
        return !alreadyConfirmed && !alreadyIgnored
    }
}
