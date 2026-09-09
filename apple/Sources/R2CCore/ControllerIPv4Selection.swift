import Foundation

public struct ControllerIPv4Candidate: Equatable, Sendable {
    public let interfaceName: String
    public let address: String

    public init(interfaceName: String, address: String) {
        self.interfaceName = interfaceName
        self.address = address
    }
}

public enum ControllerIPv4Selection {
    /// Selects an address using interface names already ordered by preference.
    /// Cellular interfaces are deliberately absent from both preference lists.
    public static func preferredAddress(
        candidates: [ControllerIPv4Candidate],
        wifiInterfaceNames: [String],
        wiredInterfaceNames: [String]
    ) -> String? {
        for interfaceName in wifiInterfaceNames + wiredInterfaceNames {
            if let candidate = candidates.first(where: { $0.interfaceName == interfaceName }) {
                return candidate.address
            }
        }
        return nil
    }
}
