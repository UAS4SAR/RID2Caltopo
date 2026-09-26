import Foundation

/// Platform-neutral Remote ID input consumed by the tracking and publishing layers.
///
/// Android scanners and Apple observation sources should normalize transport-specific
/// packets into this type before applying track or CalTopo publishing policy.
public struct RidObservation: Sendable, Equatable {
    public enum HeightReference: String, Sendable, Codable, CaseIterable {
        case takeoff
        case ground
    }

    public enum Source: String, Sendable, Codable, CaseIterable {
        case bluetoothLegacy
        case bluetoothExtended
        case wifiBeacon
        case wifiNan
        case trackerRelay
        case djiVideo
    }

    public let source: Source
    public let aircraftId: String
    public let receivedAt: Date
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public let heightMeters: Double?
    public let heightReference: HeightReference?
    /// Explicit RID ground status; nil is unknown, never assumed grounded.
    public let grounded: Bool?
    /// DJI tag-4 reference coordinate paired with this sample's relative height.
    /// Separate from aircraft position and from RID ground-status evidence.
    public let videoReferenceLatitude: Double?
    public let videoReferenceLongitude: Double?
    /// Raw F3411 NACp horizontal-accuracy code. Codes 10...12 declare <10 m containment.
    public let horizontalAccuracyCode: UInt8?
    public let proximityTelemetry: RidProximityTelemetry
    public let headingDegrees: Double?
    public let speedMetersPerSecond: Double?
    public let operatorLatitude: Double?
    public let operatorLongitude: Double?
    public let signalStrengthDbm: Int?
    public let droneScoutRelay: DroneScoutRelayMetadata?

    public init(
        source: Source,
        aircraftId: String,
        receivedAt: Date,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double? = nil,
        heightMeters: Double? = nil,
        heightReference: HeightReference? = nil,
        grounded: Bool? = nil,
        horizontalAccuracyCode: UInt8? = nil,
        headingDegrees: Double? = nil,
        speedMetersPerSecond: Double? = nil,
        operatorLatitude: Double? = nil,
        operatorLongitude: Double? = nil,
        signalStrengthDbm: Int? = nil,
        droneScoutRelay: DroneScoutRelayMetadata? = nil,
        videoReferenceLatitude: Double? = nil,
        videoReferenceLongitude: Double? = nil,
        proximityTelemetry: RidProximityTelemetry? = nil
    ) {
        self.source = source
        self.aircraftId = aircraftId
        self.receivedAt = receivedAt
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
        self.heightMeters = heightMeters
        self.heightReference = heightReference
        self.grounded = grounded
        self.videoReferenceLatitude = videoReferenceLatitude
        self.videoReferenceLongitude = videoReferenceLongitude
        self.horizontalAccuracyCode = horizontalAccuracyCode
        self.proximityTelemetry = proximityTelemetry ?? RidProximityTelemetry(
            horizontalAccuracyMeters: RidProximityTelemetry.horizontalAccuracyMeters(code: horizontalAccuracyCode))
        self.headingDegrees = RidHeading.normalized(headingDegrees)
        self.speedMetersPerSecond = speedMetersPerSecond
        self.operatorLatitude = operatorLatitude
        self.operatorLongitude = operatorLongitude
        self.signalStrengthDbm = signalStrengthDbm
        self.droneScoutRelay = droneScoutRelay
    }
}

/// A decoded RID packet that can be associated with an aircraft, whether or not the
/// packet contains a fresh Location message. Tracking uses this only for flight lifecycle
/// presence; position freshness and stale-location alerting remain observation-based.
public struct RidAircraftMessage: Sendable, Equatable {
    public let source: RidObservation.Source
    public let aircraftID: String
    public let receivedAt: Date

    public init(source: RidObservation.Source, aircraftID: String, receivedAt: Date) {
        self.source = source
        self.aircraftID = aircraftID
        self.receivedAt = receivedAt
    }
}
