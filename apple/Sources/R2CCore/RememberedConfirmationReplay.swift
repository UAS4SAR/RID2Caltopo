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
/// A segment this tablet owned while it flew still archives after another tablet
/// takes the aircraft. An unverified Mine, or a flight this tablet never owned,
/// discards the live track and leaves the local record unpublished.
public enum FlightEndPublication: Equatable, Sendable {
    case archiveShapeAndUpload
    case hold

    public static func decide(
        allowsPublication: Bool,
        coordinatorAllowsPublication: Bool,
        claimPending: Bool,
        blockedByLedger: Bool,
        ownedDuringFlight: Bool = false
    ) -> FlightEndPublication {
        if !allowsPublication || claimPending || blockedByLedger {
            return .hold
        }
        if ownedDuringFlight || coordinatorAllowsPublication {
            return .archiveShapeAndUpload
        }
        return .hold
    }

    public var discardsLiveTrack: Bool { self == .hold }
}
