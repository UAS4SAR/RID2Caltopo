import Foundation

/// Clues captured while the bound drone's current flight was ignored ("Don't publish") in the Drone
/// Confirmation Panel. Android mirrors this in data/IgnoredFlightClue.kt; keep wording identical.
///
/// The clue is always captured (snapshot and binding kept). The operator is asked whether to publish
/// the flight: Yes reopens the Drone Confirmation Panel for that drone, No keeps the flight ignored.
/// After No, Submit keeps the clue on this device only (no CalTopo upload).
public enum IgnoredFlightClue {
    public static let title = "Current flight ignored"
    public static let message = "Do you want to publish it?"
    public static let yes = "Yes"
    public static let no = "No"
    public static let localOnlyNote = "Current flight ignored: Submit saves this clue on this device only (no CalTopo upload)."
    public static let localOnlySaved = "Clue saved on this device only; the current flight is ignored, so it was not uploaded to CalTopo."

    public enum SubmitAction: Equatable, Sendable { case upload, ask, localOnly }

    /// What Submit does: upload normally, ask first, or (after No) keep the clue local only.
    public static func submitAction(flightIgnored: Bool, keptIgnored: Bool) -> SubmitAction {
        if !flightIgnored { return .upload }
        return keptIgnored ? .localOnly : .ask
    }
}
