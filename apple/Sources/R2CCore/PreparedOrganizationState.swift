import Foundation

/// Immutable JSON parsed and bridged on a worker before UI access.
public struct PreparedOrganizationState: @unchecked Sendable {
    public let value: [String: Any]
    public static let empty = PreparedOrganizationState(value: [:])
    private init(value: [String: Any]) { self.value = value }

    public init(data: Data) throws {
        dispatchPrecondition(condition: .notOnQueue(.main))
        guard var value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CocoaError(.coderReadCorrupt)
        }
        // Do NSArray-to-Swift collection bridging before Main consumes the rows.
        for (key, item) in value {
            if let rows = item as? [[String: Any]] { value[key] = rows }
        }
        self.value = value
    }
}
