import Foundation

public enum OperationalMainScreenPresentation {
    public static let receptionTransportHeaders = ["BT4:", "BT5:", "WiFi:", "NaN:"]

    public static let incidentMapLabel = "Incident map"

    public static func incidentMapValue(mapID: String, mapTitle: String) -> String {
        let title = mapTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if !title.isEmpty { return title }
        let identifier = mapID.trimmingCharacters(in: .whitespacesAndNewlines)
        return identifier.isEmpty ? "Standalone" : identifier
    }

    public static func showsAircraftHeader(activeTrackCount: Int) -> Bool {
        // Keep column meanings visible before the first aircraft arrives.
        true
    }

    public static func droneToBridgeRSSIText(_ rssiDbm: Int?) -> String? {
        guard let rssiDbm, (-127 ... -1).contains(rssiDbm) else { return nil }
        return "D→Bridge \(rssiDbm) dBm"
    }
}
