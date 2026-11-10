import Foundation

/// Whether a remembered Mine confirmation may be put back into the unacknowledged
/// claim set. A Save Tracker has already verified for this owner session must not
/// be reopened. A claim that moved to another tablet, expired, or conflicted stays
/// a hold and is not replayed. An unacknowledged Save is resent so the next
/// acknowledgement can clear it.
public enum RememberedConfirmationReplay: Equatable, Sendable {
    case alreadyVerified
    case ownershipLost
    case resend

    public static func decide(
        alertEligible: Bool,
        ownerZoneID: String?,
        localZoneID: String,
        claimOutcome: String
    ) -> RememberedConfirmationReplay {
        if alertEligible { return .alreadyVerified }
        if let ownerZoneID, !ownerZoneID.isEmpty, ownerZoneID != localZoneID {
            return .ownershipLost
        }
        if FlightOwnershipContext.isAdverseClaim(claimOutcome) { return .ownershipLost }
        return .resend
    }
}

/// Flight-end live-track choice. A verified Mine archives the shape and uploads it.
/// An unverified Mine, or ownership that was lost, still discards the live track
/// and leaves the flight pending.
public enum FlightEndPublication: Equatable, Sendable {
    case archiveShapeAndUpload
    case hold

    public static func decide(
        allowsPublication: Bool,
        coordinatorAllowsPublication: Bool,
        claimPending: Bool,
        blockedByLedger: Bool
    ) -> FlightEndPublication {
        if !allowsPublication || !coordinatorAllowsPublication || claimPending || blockedByLedger {
            return .hold
        }
        return .archiveShapeAndUpload
    }

    public var discardsLiveTrack: Bool { self == .hold }
}
