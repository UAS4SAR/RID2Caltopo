import Foundation

/// Compact encoding of per-point receive-time and drone-clock flags on archived flight GeoJSON.
///
/// Writers emit only the compact keys below. Readers still accept the bulky Oct-2026 arrays
/// (`r2c_point_received_ms`, `r2c_point_drone_clock`) so existing archives keep working.
///
/// Compact receive times:
/// - `r2c_point_recv_base_ms`: one absolute epoch-ms base (first non-null receive time)
/// - `r2c_point_recv_off_ms`: aligned integer ms offsets from that base (`null` where unknown)
///   Both keys are omitted when every receive time is null (same meaning as a pre-Oct-2026 file).
///
/// Compact drone-clock flags:
/// - `r2c_point_no_drone_clock`: sorted indices where the point used receive-time fallback
///   Omitted when every point is true (the common case).
public enum FlightArchivePointTiming {
    public static let legacyReceivedMs = "r2c_point_received_ms"
    public static let legacyDroneClock = "r2c_point_drone_clock"
    public static let recvBaseMs = "r2c_point_recv_base_ms"
    public static let recvOffMs = "r2c_point_recv_off_ms"
    public static let noDroneClock = "r2c_point_no_drone_clock"

    /// Writes compact keys only; never emits the legacy arrays.
    public static func write(into properties: inout [String: Any], receivedAtMs: [Int64?], droneClock: [Bool]) {
        let pointCount = max(receivedAtMs.count, droneClock.count)
        if let first = receivedAtMs.firstIndex(where: { $0 != nil }), let base = receivedAtMs[first] {
            let offsets: [Any] = (0..<pointCount).map { index in
                guard index < receivedAtMs.count, let value = receivedAtMs[index] else { return NSNull() }
                return value - base
            }
            properties[recvBaseMs] = base
            properties[recvOffMs] = offsets
        }
        let missing = (0..<pointCount).compactMap { index -> Int? in
            let isDrone = index >= droneClock.count || droneClock[index]
            return isDrone ? nil : index
        }
        if !missing.isEmpty { properties[noDroneClock] = missing }
    }

    public static func readReceivedAtMs(properties: [String: Any], pointCount: Int) -> [Int64?] {
        if let legacy = properties[legacyReceivedMs] as? [Any] {
            return (0..<pointCount).map { index in
                guard index < legacy.count else { return nil }
                return number(legacy[index]).map { Int64($0) }
            }
        }
        guard let baseValue = number(properties[recvBaseMs]),
              let offsets = properties[recvOffMs] as? [Any]
        else { return Array(repeating: nil, count: pointCount) }
        let base = Int64(baseValue)
        return (0..<pointCount).map { index in
            guard index < offsets.count, let offset = number(offsets[index]) else { return nil }
            return base + Int64(offset)
        }
    }

    public static func readDroneClock(properties: [String: Any], pointCount: Int) -> [Bool] {
        if let legacy = properties[legacyDroneClock] as? [Any] {
            return (0..<pointCount).map { index in
                guard index < legacy.count else { return true }
                return (legacy[index] as? Bool) ?? true
            }
        }
        var flags = Array(repeating: true, count: pointCount)
        guard let missing = properties[noDroneClock] as? [Any] else { return flags }
        for entry in missing {
            guard let index = number(entry).map({ Int($0) }), flags.indices.contains(index) else { continue }
            flags[index] = false
        }
        return flags
    }

    private static func number(_ value: Any?) -> Double? {
        if value is NSNull { return nil }
        if let value = value as? NSNumber { return value.doubleValue }
        if let value = value as? String { return Double(value) }
        return nil
    }
}
