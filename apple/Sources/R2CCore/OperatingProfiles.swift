import Foundation

/// Readable, immutable authority snapshots. None of these advisories gates recording.
public enum OperatingProfiles {
    public static var standard: [String: Any] { ["id": "standard-part-107", "version": 1, "name": "Standard Part 107 — no operational waiver", "authorityType": "part107", "conditions": [[String: Any]]()] }
    public static var bvlos: [String: Any] { ["id": "bvlos-pending", "version": 1, "name": "BVLOS — authority details pending", "authorityType": "unresolved", "conditions": [[String: Any]]()] }
    public static var other: [String: Any] { ["id": "other-pending", "version": 1, "name": "Other / details pending", "authorityType": "unresolved", "conditions": [[String: Any]]()] }
    public static func json(_ value: Any) -> String { (try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])).map { String(decoding: $0, as: UTF8.self) } ?? "{}" }
    public static func object(_ value: String?) -> [String: Any] { (try? JSONSerialization.jsonObject(with: Data((value ?? "{}").utf8))) as? [String: Any] ?? [:] }
    public static func choices(_ state: [String: Any]) -> [[String: Any]] {
        [standard] + ((state["operatingProfiles"] as? [String: Any])?["profiles"] as? [[String: Any]] ?? []) + [bvlos, other]
    }
    public static func defaultProfile(_ state: [String: Any]) -> [String: Any] {
        let id = (state["operatingProfiles"] as? [String: Any])?["defaultProfileId"] as? String ?? "standard-part-107"
        return choices(state).first { $0["id"] as? String == id } ?? other.merging(["missingProfileId": id]) { _, new in new }
    }
    /// Restore the choice, using current catalog details rather than a previous flight snapshot.
    public static func preferredProfile(_ state: [String: Any], savedID: String?) -> [String: Any] {
        guard let id = savedID, !id.isEmpty else { return defaultProfile(state) }
        return choices(state).first { $0["id"] as? String == id }
            ?? other.merging(["missingProfileId": id]) { _, new in new }
    }
    public static func preferenceKey(organization: String) -> String {
        "operating-profile-choice:" + organization.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }
    public static func warnings(_ profile: [String: Any], state: [String: Any], pilotID: String, aircraftID: String, incidentID: String, managed: Bool, now: Date = Date()) -> [String] {
        var issues: [String] = []
        let authority = profile["authorityType"] as? String ?? "unresolved"
        let today = String(ISO8601DateFormatter().string(from: now).prefix(10))
        if !["part107", "part107_waiver"].contains(authority) { issues.append("Operating authority and qualification requirements need review.") }
        if profile["missingProfileId"] != nil { issues.append("Selected profile is missing; confirm authority details.") }
        for key in ["effectiveFrom", "effectiveUntil"] {
            if let value = profile[key] as? String, !value.isEmpty {
                if ISO8601DateFormatter().date(from: value + "T00:00:00Z") == nil { issues.append("Profile effective date needs review.") }
                else if (key == "effectiveFrom" && value > today) || (key == "effectiveUntil" && value < today) { issues.append("Profile is outside its effective dates.") }
            }
        }
        if authority == "part107_waiver" && ["waiverNumber", "holder", "document", "effectiveFrom", "effectiveUntil"].contains(where: { (profile[$0] as? String ?? "").isEmpty }) { issues.append("Waiver details are incomplete; review the source document.") }
        for (key, selected) in [("pilotIds", pilotID), ("aircraftIds", aircraftID), ("locationIds", incidentID)] {
            if let ids = profile[key] as? [String], !ids.isEmpty, !ids.contains(selected) { issues.append("Profile applicability needs review: \(key).") }
        }
        if managed {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            let fetched = formatter.date(from: state["fetchedAt"] as? String ?? "") ?? ISO8601DateFormatter().date(from: state["fetchedAt"] as? String ?? "")
            if fetched == nil || now.timeIntervalSince(fetched!) > 86400 || now.timeIntervalSince(fetched!) < -300 { issues.append("Saved profiles are missing or stale; synchronize when available.") }
            let current = choices(state).first { $0["id"] as? String == profile["id"] as? String }
            if current == nil || current?["version"] as? Int != profile["version"] as? Int { issues.append("Profile has changed or is unavailable; review the saved version.") }
        }
        return issues
    }
    public static func snapshot(_ profile: [String: Any], state: [String: Any], pilotID: String, aircraftID: String, incidentID: String, assignmentID: String, managed: Bool, checked: Set<Int>) -> [String: Any] {
        ["profile": profile, "selectedAt": ISO8601DateFormatter().string(from: Date()), "organizationId": state["organizationId"] as? String ?? "",
         "incidentId": incidentID, "assignmentId": assignmentID, "catalogRevision": (state["operatingProfiles"] as? [String: Any])?["revision"] as? Int ?? 0,
         "fetchedAt": state["fetchedAt"] as? String ?? "", "checkedConditions": checked.sorted(),
         "reviewIssues": warnings(profile, state: state, pilotID: pilotID, aircraftID: aircraftID, incidentID: incidentID, managed: managed) + (((profile["conditions"] as? [[String: Any]] ?? []).indices.contains { !checked.contains($0) }) ? ["One or more briefing conditions have not been acknowledged."] : [])]
    }
    public static func active(_ readiness: FlightReadiness?) -> [String: Any] {
        let changes = (try? JSONSerialization.jsonObject(with: Data((readiness?.operatingProfileChangesJSON ?? "[]").utf8))) as? [[String: Any]] ?? []
        return changes.last?["after"] as? [String: Any] ?? object(readiness?.operatingProfileJSON)
    }
    public static func retainingHistory(previous: FlightReadiness?, next: FlightReadiness) -> FlightReadiness {
        guard let previous, previous.operatingProfileJSON != nil else { return next }
        if next.operatingProfileJSON == nil {
            var retained = next; retained.operatingProfileJSON = previous.operatingProfileJSON
            retained.operatingProfileChangesJSON = previous.operatingProfileChangesJSON
            return retained
        }
        var result = next
        var changes = (try? JSONSerialization.jsonObject(with: Data((previous.operatingProfileChangesJSON ?? "[]").utf8))) as? [[String: Any]] ?? []
        let old = active(previous), selected = object(next.operatingProfileJSON)
        if json(old["profile"] ?? [:]) != json(selected["profile"] ?? [:]) || json(old["checkedConditions"] ?? []) != json(selected["checkedConditions"] ?? []) {
            changes.append(["at": ISO8601DateFormatter().string(from: Date()), "before": old, "after": selected])
        }
        result.operatingProfileJSON = previous.operatingProfileJSON
        result.operatingProfileChangesJSON = json(changes)
        return result
    }
}

/// Explicit assignment session, scoped by organization and incident IDs, never persisted.
public struct OperatingProfileAssignment: Sendable {
    private var scope = ""
    public private(set) var assignmentID = ""
    public private(set) var profileJSON: String?
    public init() {}
    public mutating func setScope(organization: String, incident: String) {
        let next = OperatingProfiles.json([organization, incident])
        if scope != next { scope = next; end() }
    }
    public mutating func end() { assignmentID = ""; profileJSON = nil }
    public mutating func remember(_ profile: [String: Any]) {
        if assignmentID.isEmpty { assignmentID = UUID().uuidString }
        profileJSON = OperatingProfiles.json(profile)
    }
}
