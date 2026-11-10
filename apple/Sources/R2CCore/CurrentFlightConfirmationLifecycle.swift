import Foundation

public struct CurrentFlightConfirmationReconciliation: Sendable, Equatable {
    public let endedRemoteIDs: Set<String>
    public let candidateRemoteID: String?
}

/// Tracks prompt presentation for the current flight. Caller-supplied confirmations are
/// per flight; ignored decisions remain in force for the app session.
public struct CurrentFlightConfirmationLifecycle: Sendable, Equatable {
    private var activeRemoteIDs: Set<String> = []
    private var promptedRemoteIDs: Set<String> = []

    public init() {}

    public static func requiresStream(mode: String, incidentSelected: Bool,
                                      peerPresenceKnown: Bool, otherDeviceOnline: Bool) -> Bool {
        switch mode {
        case "streaming_device": return true
        case "streaming_shared_incident": return incidentSelected && (!peerPresenceKnown || otherDeviceOnline)
        default: return false
        }
    }

    /// Standalone tablets must obtain their own local flight confirmation.
    public static func acceptsPeerConfirmation(mapID: String) -> Bool {
        !mapID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    public mutating func reconcile(
        orderedRemoteIDs: [String],
        confirmedRemoteIDs: Set<String>,
        ignoredRemoteIDs: Set<String>,
        allowPrompt: Bool = true,
        promptEligibleRemoteIDs: Set<String>? = nil
    ) -> CurrentFlightConfirmationReconciliation {
        let currentRemoteIDs = Set(orderedRemoteIDs.filter { !$0.isEmpty })
        let endedRemoteIDs = activeRemoteIDs.subtracting(currentRemoteIDs)
        promptedRemoteIDs.subtract(endedRemoteIDs)
        activeRemoteIDs = currentRemoteIDs

        let decisionsAfterFlightEnd = confirmedRemoteIDs
            .subtracting(endedRemoteIDs)
            .union(ignoredRemoteIDs)
        // Don't Publish drones stay ignored for the session; only Aircraft and Reception
        // selection reopens the panel (outside this automatic reconcile path).
        let candidate = orderedRemoteIDs.first { remoteID in
            allowPrompt && !remoteID.isEmpty
                && (promptEligibleRemoteIDs == nil || promptEligibleRemoteIDs!.contains(remoteID))
                && !promptedRemoteIDs.contains(remoteID)
                && !decisionsAfterFlightEnd.contains(remoteID)
        }
        if let candidate {
            promptedRemoteIDs.insert(candidate)
        }
        return CurrentFlightConfirmationReconciliation(
            endedRemoteIDs: endedRemoteIDs,
            candidateRemoteID: candidate
        )
    }

    /// An explicit telemetry track end takes precedence over a still-listed video stream.
    public mutating func endFlight(remoteID: String) {
        activeRemoteIDs.remove(remoteID)
        promptedRemoteIDs.remove(remoteID)
    }

    /// A policy change may withdraw an automatic prompt without ending the flight.
    public mutating func rearmPrompt(remoteID: String) {
        promptedRemoteIDs.remove(remoteID)
    }

    public mutating func reset() {
        activeRemoteIDs.removeAll()
        promptedRemoteIDs.removeAll()
    }
}

/// A peer claim stays visible inside this tablet's confirmation panel.
/// A new video publisher on this tablet reopens that panel unless Mine or Not Mine was already chosen.
public enum TakeoverClaimNotice {
    /// The conflict that answers a takeover Save must not open a second panel for that aircraft.
    public static func claimConflictReopensPanel(
        alreadySavedThisFlight: Bool,
        panelAlreadyShowingForThisAircraft: Bool
    ) -> Bool {
        !alreadySavedThisFlight && !panelAlreadyShowingForThisAircraft
    }

    public static func message(callsign: String, deviceName: String) -> String {
        let pilot = callsign.trimmingCharacters(in: .whitespacesAndNewlines)
        let device = deviceName.trimmingCharacters(in: .whitespacesAndNewlines)
        return "Claimed by \(pilot.isEmpty ? "Unknown RPIC" : pilot) on \(device.isEmpty ? "another device" : device). Mine asks to take over that claim."
    }

    public static func remoteIDsRearmedByNewPublisher(
        locallyConfirmed: Set<String>,
        ignored: Set<String>,
        previous: [String: Set<String>],
        current: [String: Set<String>],
        foreignClaims: Set<String> = []
    ) -> Set<String> {
        Set(current.compactMap { remoteID, tokens in
            let blockedByLocalMine = locallyConfirmed.contains(remoteID) && !foreignClaims.contains(remoteID)
            guard !blockedByLocalMine, !ignored.contains(remoteID) else { return nil }
            let prior = previous[remoteID] ?? []
            let fresh = tokens.contains { token in
                !token.hasSuffix("|unknown") && !prior.contains(token) &&
                    !prior.contains(String(token.prefix(upTo: token.lastIndex(of: "|") ?? token.endIndex)) + "|unknown")
            }
            return fresh ? remoteID : nil
        })
    }
}
