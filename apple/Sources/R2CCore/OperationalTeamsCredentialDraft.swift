import Foundation

public struct OperationalTeamsCredentialDraft: Equatable, Sendable {
    public var teamID: String
    public var credentialID: String
    public var secret: String
    public var connectKey: String
    public var domain: String

    public init(teamID: String = "", credentialID: String = "", secret: String = "", connectKey: String = "", domain: String = "caltopo.com") {
        self.teamID = teamID; self.credentialID = credentialID; self.secret = secret
        self.connectKey = connectKey; self.domain = domain
    }
    public var normalized: Self {
        func trim(_ value: String) -> String { value.trimmingCharacters(in: .whitespacesAndNewlines) }
        return .init(teamID: trim(teamID), credentialID: trim(credentialID), secret: trim(secret),
                     connectKey: trim(connectKey), domain: trim(domain).isEmpty ? "caltopo.com" : trim(domain))
    }
    public var validationMessage: String? {
        let value = normalized
        let present = [value.teamID, value.credentialID, value.secret].filter { !$0.isEmpty }.count
        return present == 0 || present == 3 ? nil : "Enter Team ID, Credential ID, and credential secret together, or clear all three."
    }
}
