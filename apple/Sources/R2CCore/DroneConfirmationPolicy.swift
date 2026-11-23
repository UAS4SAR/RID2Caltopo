/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

/// Shared Mine / Save mapping rules for the Drone Confirmation Panel.
/// Not Mine and "Pair for this session" do not require a callsign.
public enum DroneConfirmationPolicy {
    public static let pilotCallsignRequiredHint = "Pilot callsign required"

    public static func hasRequiredPilotCallsign(_ callsign: String) -> Bool {
        !callsign.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Remembered operator callsign is never replaced with a blank value.
    public static func preferredCallsignToPersist(_ value: String) -> String? {
        PilotDisplayPreference.normalizePilotCallsign(value)
    }

    /// Flight confirmation may record an unverified (unresolved) callsign, but never a blank one.
    public static func canConfirmIdentity(_ identity: RidAircraftIdentity, recordUnresolvedPilot: Bool) -> Bool {
        guard !identity.remoteID.isEmpty, hasRequiredPilotCallsign(identity.pilotCallsign) else { return false }
        return identity.isComplete || recordUnresolvedPilot
    }
}
