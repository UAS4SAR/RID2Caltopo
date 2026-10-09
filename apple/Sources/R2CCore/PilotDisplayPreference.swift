import Foundation

public let defaultActiveTrackColor = "#1E88E5"
public let defaultArchiveTrackColor = "#FF00FF"

public struct PilotDisplayPreference: Codable, Sendable, Equatable {
    public var activeTrackColor: String
    public var archiveTrackColor: String
    public var bearingColor: String?
    public var resolvedBearingColor: String { Self.sanitizeTrackColor(bearingColor, fallback: activeTrackColor) }
    public var bearingEnabled: Bool

    public init(
        activeTrackColor: String = defaultActiveTrackColor,
        archiveTrackColor: String = defaultArchiveTrackColor,
        bearingEnabled: Bool = false,
        bearingColor: String? = nil
    ) {
        self.activeTrackColor = Self.sanitizeTrackColor(activeTrackColor, fallback: defaultActiveTrackColor)
        self.archiveTrackColor = Self.sanitizeTrackColor(archiveTrackColor, fallback: defaultArchiveTrackColor)
        self.bearingEnabled = bearingEnabled
        self.bearingColor = bearingColor.map { Self.sanitizeTrackColor($0, fallback: Self.sanitizeTrackColor(activeTrackColor, fallback: defaultActiveTrackColor)) }
    }

    public static func normalizePilotCallsign(_ value: String?) -> String? {
        let normalized = value?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
        return normalized.isEmpty ? nil : normalized
    }

    public static func preferredPilotCallsign(saved: String?, existing: String?) -> String {
        normalizePilotCallsign(saved)
            ?? existing?.trimmingCharacters(in: .whitespacesAndNewlines)
            ?? ""
    }

    public static func callsignsMatch(_ lhs: String?, _ rhs: String?) -> Bool {
        guard let left = normalizePilotCallsign(lhs),
              let right = normalizePilotCallsign(rhs) else { return false }
        return left == right
    }

    public static func activeAssignmentWarning(callsign: String, aircraftLabel: String) -> String {
        "Warning: pilot callsign \(callsign.trimmingCharacters(in: .whitespacesAndNewlines)) " +
            "is already assigned to active drone \(aircraftLabel). Confirm only if this is intentional."
    }

    public static func sanitizeTrackColor(_ value: String?, fallback: String) -> String {
        var candidate = value?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
        if candidate.count == 6 { candidate = "#" + candidate }
        guard candidate.count == 7, candidate.first == "#",
              candidate.dropFirst().allSatisfy({ $0.isHexDigit })
        else { return fallback }
        return candidate
    }
}

public struct MapScreenPoint: Sendable, Equatable {
    public let x: Double
    public let y: Double

    public init(x: Double, y: Double) {
        self.x = x
        self.y = y
    }
}

public struct CameraFovBoundaryBearings: Sendable, Equatable {
    public let leftDegrees: Double
    public let rightDegrees: Double

    public init(leftDegrees: Double, rightDegrees: Double) {
        self.leftDegrees = leftDegrees
        self.rightDegrees = rightDegrees
    }
}

public enum OperationalMapGeometry {
    public static let minimumTravelBearingDisplacementMeters = 2.0

    /// Uses the latest displacement an operator can also see in the marker
    /// track. Duplicate/stationary samples retain the last movement course.
    public static func travelBearingDegrees(
        points: [RidTrackPoint],
        minimumDisplacementMeters: Double = minimumTravelBearingDisplacementMeters
    ) -> Double? {
        guard minimumDisplacementMeters.isFinite,
              minimumDisplacementMeters > 0
        else { return nil }

        guard let latest = points.last,
              latest.latitude.isFinite,
              latest.longitude.isFinite
        else { return nil }
        for earlier in points.dropLast().reversed() {
            guard earlier.latitude.isFinite, earlier.longitude.isFinite else { continue }
            let distance = distanceMeters(
                fromLatitude: earlier.latitude,
                longitude: earlier.longitude,
                toLatitude: latest.latitude,
                longitude: latest.longitude
            )
            guard distance >= minimumDisplacementMeters else { continue }
            return initialBearingDegrees(
                fromLatitude: earlier.latitude,
                longitude: earlier.longitude,
                toLatitude: latest.latitude,
                longitude: latest.longitude
            )
        }
        return nil
    }

    public static func cameraFovBoundaryBearings(
        cameraAzimuthDegrees: Double?,
        horizontalFovDegrees: Double?
    ) -> CameraFovBoundaryBearings? {
        guard let cameraAzimuthDegrees, cameraAzimuthDegrees.isFinite,
              let horizontalFovDegrees, horizontalFovDegrees.isFinite,
              horizontalFovDegrees > 0, horizontalFovDegrees <= 180
        else { return nil }
        let halfFov = horizontalFovDegrees / 2
        return CameraFovBoundaryBearings(
            leftDegrees: normalizedDegrees(cameraAzimuthDegrees - halfFov),
            rightDegrees: normalizedDegrees(cameraAzimuthDegrees + halfFov)
        )
    }

    public static func bearingLineToViewportEdge(
        start: MapScreenPoint,
        headingDegrees: Double?,
        viewportWidth: Double,
        viewportHeight: Double
    ) -> MapScreenPoint? {
        guard let headingDegrees, headingDegrees.isFinite,
              viewportWidth > 0, viewportHeight > 0,
              start.x >= 0, start.x <= viewportWidth,
              start.y >= 0, start.y <= viewportHeight
        else { return nil }
        let radians = (headingDegrees - 90) * .pi / 180
        let dx = cos(radians)
        let dy = sin(radians)
        let epsilon = 1e-9
        var candidates: [Double] = []
        if dx > epsilon { candidates.append((viewportWidth - start.x) / dx) }
        else if dx < -epsilon { candidates.append((0 - start.x) / dx) }
        if dy > epsilon { candidates.append((viewportHeight - start.y) / dy) }
        else if dy < -epsilon { candidates.append((0 - start.y) / dy) }
        guard let distance = candidates.filter({ $0 >= 0 && $0.isFinite }).min() else { return nil }
        return MapScreenPoint(
            x: min(max(start.x + dx * distance, 0), viewportWidth),
            y: min(max(start.y + dy * distance, 0), viewportHeight)
        )
    }

    private static func distanceMeters(
        fromLatitude latitude1: Double,
        longitude longitude1: Double,
        toLatitude latitude2: Double,
        longitude longitude2: Double
    ) -> Double {
        let earthRadiusMeters = 6_371_008.8
        let lat1 = latitude1 * .pi / 180
        let lat2 = latitude2 * .pi / 180
        let deltaLat = (latitude2 - latitude1) * .pi / 180
        let deltaLongitude = (longitude2 - longitude1) * .pi / 180
        let a = sin(deltaLat / 2) * sin(deltaLat / 2)
            + cos(lat1) * cos(lat2)
            * sin(deltaLongitude / 2) * sin(deltaLongitude / 2)
        return earthRadiusMeters * 2 * atan2(sqrt(a), sqrt(max(0, 1 - a)))
    }

    private static func initialBearingDegrees(
        fromLatitude latitude1: Double,
        longitude longitude1: Double,
        toLatitude latitude2: Double,
        longitude longitude2: Double
    ) -> Double? {
        let lat1 = latitude1 * .pi / 180
        let lat2 = latitude2 * .pi / 180
        let deltaLongitude = (longitude2 - longitude1) * .pi / 180
        let y = sin(deltaLongitude) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLongitude)
        guard x != 0 || y != 0 else { return nil }
        let degrees = atan2(y, x) * 180 / .pi
        let remainder = degrees.truncatingRemainder(dividingBy: 360)
        return remainder >= 0 ? remainder : remainder + 360
    }

    private static func normalizedDegrees(_ degrees: Double) -> Double {
        let normalized = degrees.truncatingRemainder(dividingBy: 360)
        return normalized < 0 ? normalized + 360 : normalized
    }

}
