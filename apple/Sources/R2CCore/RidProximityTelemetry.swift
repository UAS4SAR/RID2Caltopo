import Foundation

/// Per-position quality. Unverified DJI SEI and legacy relays retain unknown altitude.
public struct RidProximityTelemetry: Sendable, Equatable {
    public enum Reference: String, Sendable { case unknown, geodetic, pressure }
    public static let unknownHorizontalMeters = 15.24 // Provisional 50 ft allowance, not measured SEI accuracy.
    public static let maximumPositionAgeSeconds = 5.0
    public static let maximumAltitudeAgeSeconds = 5.0
    public let horizontalAccuracyMeters: Double
    public let absoluteAltitudeMeters: Double?
    public let altitudeReference: Reference
    public let verticalAccuracyMeters: Double?

    public init(horizontalAccuracyMeters: Double = unknownHorizontalMeters,
                absoluteAltitudeMeters: Double? = nil, altitudeReference: Reference = .unknown,
                verticalAccuracyMeters: Double? = nil) {
        self.horizontalAccuracyMeters = horizontalAccuracyMeters
        self.absoluteAltitudeMeters = absoluteAltitudeMeters
        self.altitudeReference = altitudeReference
        self.verticalAccuracyMeters = verticalAccuracyMeters
    }
    public var hasUsableAltitude: Bool {
        guard altitudeReference != .unknown, let altitude = absoluteAltitudeMeters,
              altitude.isFinite, altitude > -999,
              let error = verticalAccuracyMeters, error.isFinite, error > 0 else { return false }
        return true
    }
    public static func horizontalAccuracyMeters(code: UInt8?) -> Double {
        switch code {
        case 1: 18520; case 2: 7408; case 3: 3704; case 4: 1852
        case 5: 926; case 6: 555.6; case 7: 185.2; case 8: 92.6
        case 9: 30; case 10: 10; case 11: 3; case 12: 1
        default: unknownHorizontalMeters
        }
    }
    public static func verticalAccuracyMeters(code: UInt8) -> Double? {
        switch code {
        case 1: 150; case 2: 45; case 3: 25; case 4: 10; case 5: 3; case 6: 1
        default: nil
        }
    }
    public static func fromRID(horizontalCode: UInt8, geodetic: Double, pressure: Double,
                               verticalCode: UInt8, barometerCode: UInt8) -> Self {
        let horizontal = horizontalAccuracyMeters(code: horizontalCode)
        if geodetic.isFinite, geodetic > -999, let error = verticalAccuracyMeters(code: verticalCode) {
            return Self(horizontalAccuracyMeters: horizontal, absoluteAltitudeMeters: geodetic,
                        altitudeReference: .geodetic, verticalAccuracyMeters: error)
        }
        if pressure.isFinite, pressure > -999, let error = verticalAccuracyMeters(code: barometerCode) {
            return Self(horizontalAccuracyMeters: horizontal, absoluteAltitudeMeters: pressure,
                        altitudeReference: .pressure, verticalAccuracyMeters: error)
        }
        return Self(horizontalAccuracyMeters: horizontal)
    }
}
