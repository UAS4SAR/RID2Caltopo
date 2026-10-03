import Foundation

/// Offline suggestions shared with Android CtDroneSpec.GuessMakeModel; always editable.
public enum AircraftModelSuggestion {
    public static func from(remoteID: String) -> String {
        let rid = remoteID.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if rid.hasPrefix("1581F8HGX") { return "DJI Matrice 4TD" }
        if rid.hasPrefix("1581F9DEC") { return "DJI Mini 5 Pro" }
        if rid.hasPrefix("1581F67QE") { return "DJI Mavic 3 Pro" }
        if rid.hasPrefix("1581F6Z9C") { return "DJI Mini 4 Pro" }
        if rid.hasPrefix("1581F6W8") { return "DJI Avata 2" }
        if rid.hasPrefix("1581FBLKC") { return "DJI Avata 360" }
        if rid.hasPrefix("1865F10X") { return "DJI Neo" }
        if rid.hasPrefix("1748FEV3") { return "Autel Evo Max 4N" }
        if rid.hasPrefix("1748FEV2") { return "Autel EVO II V3" }
        if rid.hasPrefix("1910F916") { return "Potensic Atom LT" }
        return ""
    }
}
