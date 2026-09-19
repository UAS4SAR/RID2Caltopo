import Foundation

public enum OperationalStreamSetupPresentation {
    public static func instruction(
        ingestAddress: String,
        networkSSID: String?
    ) -> String {
        let address = ingestAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        let streamURL = address.hasSuffix("/")
            ? address + "droneDesig"
            : address + "/droneDesig"
        let trimmedSSID = networkSSID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let displaySSID = trimmedSSID.isEmpty ? "Wi-Fi name unavailable" : trimmedSSID
        return "Stream video to: \(streamURL) on \(displaySSID) network"
    }
}
