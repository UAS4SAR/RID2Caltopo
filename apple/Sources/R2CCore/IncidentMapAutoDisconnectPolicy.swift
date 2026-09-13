import Foundation

public struct IncidentMapOperationalState: Sendable, Equatable {
    public let connectedToIncidentMap: Bool
    public let activeFlightCount: Int
    public let lastRIDMessageAt: Date?
    public let mapConnectedAt: Date
    public let hasManagedVideoOrTransfer: Bool
    public let offlineMapPreparationActive: Bool
    public let offlineMapPreparationEndedAt: Date?

    public init(
        connectedToIncidentMap: Bool,
        activeFlightCount: Int,
        lastRIDMessageAt: Date?,
        mapConnectedAt: Date,
        hasManagedVideoOrTransfer: Bool,
        offlineMapPreparationActive: Bool,
        offlineMapPreparationEndedAt: Date? = nil
    ) {
        self.connectedToIncidentMap = connectedToIncidentMap
        self.activeFlightCount = activeFlightCount
        self.lastRIDMessageAt = lastRIDMessageAt
        self.mapConnectedAt = mapConnectedAt
        self.hasManagedVideoOrTransfer = hasManagedVideoOrTransfer
        self.offlineMapPreparationActive = offlineMapPreparationActive
        self.offlineMapPreparationEndedAt = offlineMapPreparationEndedAt
    }
}

public enum IncidentMapAutoDisconnectPolicy {
    public static let quietInterval: TimeInterval = 5 * 60
    public static let backgroundGraceInterval: TimeInterval = 5 * 60
    public static let relocationDistanceMeters = 50 * 0.3048
    public static let requiredHorizontalAccuracyMeters = 25 * 0.3048

    public static func isOperationallyIdle(
        _ state: IncidentMapOperationalState,
        now: Date
    ) -> Bool {
        guard state.connectedToIncidentMap,
              state.activeFlightCount == 0,
              !state.hasManagedVideoOrTransfer,
              !state.offlineMapPreparationActive
        else { return false }
        let baseline = max(state.mapConnectedAt, state.lastRIDMessageAt ?? state.mapConnectedAt,
                           state.offlineMapPreparationEndedAt ?? state.mapConnectedAt)
        return now.timeIntervalSince(baseline) >= quietInterval
    }
}

public struct IncidentMapRelocationGuard: Sendable, Equatable {
    private var anchorLatitude: Double?
    private var anchorLongitude: Double?

    public init() {}

    public mutating func arm(
        latitude: Double,
        longitude: Double,
        horizontalAccuracyMeters: Double
    ) {
        guard latitude.isFinite,
              longitude.isFinite,
              horizontalAccuracyMeters.isFinite,
              horizontalAccuracyMeters >= 0,
              horizontalAccuracyMeters < IncidentMapAutoDisconnectPolicy.requiredHorizontalAccuracyMeters
        else { return }
        anchorLatitude = latitude
        anchorLongitude = longitude
    }

    public mutating func evaluate(
        latitude: Double,
        longitude: Double,
        horizontalAccuracyMeters: Double,
        operationalState: IncidentMapOperationalState,
        now: Date
    ) -> Bool {
        guard latitude.isFinite,
              longitude.isFinite,
              horizontalAccuracyMeters.isFinite,
              horizontalAccuracyMeters >= 0,
              horizontalAccuracyMeters < IncidentMapAutoDisconnectPolicy.requiredHorizontalAccuracyMeters
        else {
            reset()
            return false
        }
        // A screen-off anchor survives an active flight. The operational gate
        // allows disconnect only after the flight is gone and RID is quiet.
        guard IncidentMapAutoDisconnectPolicy.isOperationallyIdle(
            operationalState,
            now: now
        ) else { return false }
        guard let anchorLatitude, let anchorLongitude else {
            self.anchorLatitude = latitude
            self.anchorLongitude = longitude
            return false
        }
        let distance = Self.distanceMeters(
            fromLatitude: anchorLatitude,
            longitude: anchorLongitude,
            toLatitude: latitude,
            longitude: longitude
        )
        guard distance >= IncidentMapAutoDisconnectPolicy.relocationDistanceMeters else {
            return false
        }
        reset()
        return true
    }

    public mutating func reset() {
        anchorLatitude = nil
        anchorLongitude = nil
    }

    private static func distanceMeters(
        fromLatitude: Double,
        longitude fromLongitude: Double,
        toLatitude: Double,
        longitude toLongitude: Double
    ) -> Double {
        let radius = 6_371_000.0
        let latitude1 = fromLatitude * .pi / 180
        let latitude2 = toLatitude * .pi / 180
        let deltaLatitude = (toLatitude - fromLatitude) * .pi / 180
        let deltaLongitude = (toLongitude - fromLongitude) * .pi / 180
        let a = sin(deltaLatitude / 2) * sin(deltaLatitude / 2)
            + cos(latitude1) * cos(latitude2)
            * sin(deltaLongitude / 2) * sin(deltaLongitude / 2)
        return radius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
