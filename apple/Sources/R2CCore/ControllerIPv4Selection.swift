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
        for interfaceName in wiredInterfaceNames + wifiInterfaceNames {
            if let candidate = candidates.first(where: {
                $0.interfaceName == interfaceName && isUsableAddress($0.address)
            }) {
                return candidate.address
            }
        }
        return nil
    }

    public static func isUsableAddress(_ address: String) -> Bool {
        let parts = address.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        let octets = parts.compactMap { UInt8($0) }
        guard octets.count == 4 else { return false }
        // Link-local addresses are valid for a directly connected controller.
        return octets[0] > 0 && octets[0] != 127 && octets[0] < 224
    }
}
