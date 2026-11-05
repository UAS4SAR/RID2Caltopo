import Foundation

public func decimalStorageGb(_ bytes: Int64) -> String {
    String(format: "%.1f GB", locale: Locale(identifier: "en_US_POSIX"), Double(bytes) / 1_000_000_000)
}

/// Used bytes already reduce filesystem free space. Limits do not reserve disk space.
public struct StorageAllocation: Sendable, Equatable {
    public let freeBytes: Int64?
    public let cacheUsed: Int64
    public let cacheMaximum: Int64
    public let flightUsed: Int64
    public let flightMaximum: Int64
    public init(freeBytes: Int64?, cacheUsed: Int64, cacheMaximum: Int64, flightUsed: Int64, flightMaximum: Int64) {
        self.freeBytes = freeBytes; self.cacheUsed = cacheUsed; self.cacheMaximum = cacheMaximum
        self.flightUsed = flightUsed; self.flightMaximum = flightMaximum
    }
    public var remainingCache: Int64 { max(0, cacheMaximum - cacheUsed) }
    public var remainingFlight: Int64 { max(0, flightMaximum - flightUsed) }
    public var freeAfterAllowances: Int64? { freeBytes.map { $0 - remainingCache - remainingFlight } }
}
