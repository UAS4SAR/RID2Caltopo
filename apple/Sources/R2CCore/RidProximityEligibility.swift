import Foundation

public enum RidProximityEligibility {
    /// Local confirmation establishes responsibility in standalone mode. A coordinated
    /// incident additionally requires the existing local owner/confirmation lease gate.
    public static func allows(locallyConfirmed: Bool, coordinationRequired: Bool, coordinatorEligible: Bool) -> Bool {
        locallyConfirmed && (!coordinationRequired || coordinatorEligible)
    }
}
