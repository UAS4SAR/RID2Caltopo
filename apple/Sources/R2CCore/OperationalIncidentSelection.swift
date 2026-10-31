import Foundation

public enum OperationalIncidentSelection {
    public static func name(mapID: String, mapTitle: String, standaloneName: String) -> String {
        let title = mapTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if !mapID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !title.isEmpty { return title }
        let name = standaloneName.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? "Training" : name
    }

    /// Drops a stored "account: title" prefix so the field shows the catalog title.
    public static func displayMapTitle(_ title: String, personalName: String) -> String {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = personalName.trimmingCharacters(in: .whitespacesAndNewlines)
        let prefix = "\(name): "
        guard !name.isEmpty, trimmed.hasPrefix(prefix) else { return trimmed }
        let rest = String(trimmed.dropFirst(prefix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
        return rest.isEmpty ? trimmed : rest
    }

    public static func mapOptionsText(personal: Bool, accountName: String, mapTitle: String) -> String {
        let who = accountName.trimmingCharacters(in: .whitespacesAndNewlines)
        let shown = who.isEmpty ? "account" : who
        let credentials = personal ? "Credentials: Personal: \(shown)" : "Credentials: Team: \(shown)"
        let map = mapTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        return "\(credentials)\nMap: \(map.isEmpty ? "Map name unavailable" : map)"
    }
}
