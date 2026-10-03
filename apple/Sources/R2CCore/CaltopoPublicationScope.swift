import Foundation

/// Journal destination identity, shared by tracks and clues. Never a Teams API credential.
public enum CaltopoPublicationScope {
    public static func identifier(personalAccountID: String?, teamID: String) -> String {
        if let personalAccountID {
            let account = personalAccountID.trimmingCharacters(in: .whitespacesAndNewlines)
            return account.isEmpty ? "" : "personal:" + account
        }
        return teamID
    }
}
