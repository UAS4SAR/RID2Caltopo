import Foundation

public enum CaltopoMarkerVerification {
    /// An empty delta is inconclusive; only a tombstone or authoritative Marker ID list proves absence.
    public static func confirmsAbsent(data: Data, markerID: String) -> Bool {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              root["status"] as? String == "ok",
              let result = root["result"] as? [String: Any],
              let state = result["state"] as? [String: Any],
              let features = state["features"] as? [[String: Any]] else { return false }
        var deleted = false
        for feature in features where (feature["id"] as? String)?.lowercased() == markerID.lowercased() {
            let properties = feature["properties"] as? [String: Any]
            let action = (properties?["action"] as? String)?.lowercased()
            let tombstone = feature["deleted"] as? Bool == true || properties?["deleted"] as? Bool == true ||
                action == "delete" || action == "removed" || (feature["geometry"] == nil && feature["properties"] == nil)
            guard tombstone else { return false }
            deleted = true
        }
        let ids = result["ids"] as? [String: Any] ?? state["ids"] as? [String: Any]
        if ids?["Marker"] == nil { return deleted }
        guard let markers = ids?["Marker"] as? [String] else { return false }
        return !markers.contains { $0.lowercased() == markerID.lowercased() }
    }
    public static func confirmsPresent(data: Data, markerID: String) -> Bool {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let result = root["result"] as? [String: Any], let state = result["state"] as? [String: Any]
        else { return false }
        let ids = result["ids"] as? [String: Any] ?? state["ids"] as? [String: Any]
        if let markers = ids?["Marker"] as? [String], markers.contains(where: { $0.lowercased() == markerID.lowercased() }) { return true }
        return ((state["features"] as? [[String: Any]]) ?? []).contains {
            guard ($0["id"] as? String)?.lowercased() == markerID.lowercased() else { return false }
            let properties = $0["properties"] as? [String: Any]
            let action = (properties?["action"] as? String)?.lowercased()
            return ($0["geometry"] != nil || properties != nil) && $0["deleted"] as? Bool != true &&
                properties?["deleted"] as? Bool != true && action != "delete" && action != "removed"
        }
    }

}
