import Foundation

public enum OperationalIncidentSelection {
    public static func name(mapID: String, mapTitle: String, standaloneName: String) -> String {
        let title = mapTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if !mapID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !title.isEmpty { return title }
        let name = standaloneName.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? "Training" : name
    }
}
