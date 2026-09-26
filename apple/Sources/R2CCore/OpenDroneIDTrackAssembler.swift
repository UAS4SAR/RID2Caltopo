import Foundation

public enum OpenDroneIDTrackAssemblyDisposition: String, Sendable, Equatable {
    case observation
    case noFreshLocation
    case missingIdentity
    case invalidLocation
}

public struct OpenDroneIDTrackAssemblyResult: Sendable, Equatable {
    public let observation: RidObservation?
    public let disposition: OpenDroneIDTrackAssemblyDisposition
    public let aircraftID: String?
    public let droneScoutRelay: DroneScoutRelayMetadata?

    public init(
        observation: RidObservation?,
        disposition: OpenDroneIDTrackAssemblyDisposition,
        aircraftID: String? = nil,
        droneScoutRelay: DroneScoutRelayMetadata? = nil
    ) {
        self.observation = observation
        self.disposition = disposition
        self.aircraftID = aircraftID
        self.droneScoutRelay = droneScoutRelay
    }
}

public actor OpenDroneIDTrackAssembler {
    private struct State: Sendable {
        var primaryID: OpenDroneIDBasicID?
        var serialID: OpenDroneIDBasicID?
        var location: OpenDroneIDLocation?
        var system: OpenDroneIDSystem?
        var droneScoutRelay: DroneScoutRelayMetadata?
    }

    private var transmitters: [UUID: State] = [:]

    public init() {}

    public func ingest(
        _ advertisement: OpenDroneIDAdvertisement,
        transmitterID: UUID,
        source: RidObservation.Source,
        receivedAt: Date,
        signalStrengthDbm: Int?
    ) -> RidObservation? {
        ingestWithResult(
            advertisement,
            transmitterID: transmitterID,
            source: source,
            receivedAt: receivedAt,
            signalStrengthDbm: signalStrengthDbm
        ).observation
    }

    public func ingestWithResult(
        _ advertisement: OpenDroneIDAdvertisement,
        transmitterID: UUID,
        source: RidObservation.Source,
        receivedAt: Date,
        signalStrengthDbm: Int?
    ) -> OpenDroneIDTrackAssemblyResult {
        var state = transmitters[transmitterID, default: State()]
        var receivedFreshLocation = false

        for message in advertisement.messages {
            switch message.payload {
            case let .basicID(basicID):
                if state.primaryID == nil {
                    state.primaryID = basicID
                }
                if basicID.isSerialNumber {
                    state.serialID = basicID
                }
            case let .location(location):
                state.location = location
                receivedFreshLocation = true
            case let .system(system):
                state.system = system
            case let .selfID(selfID):
                state.droneScoutRelay = DroneScoutRelayMetadata.parse(selfID.operationDescription)
            case .messagePack, .opaque:
                break
            }
        }

        transmitters[transmitterID] = state
        let aircraftID = (state.serialID ?? state.primaryID).map {
            RidTrackStore.canonicalAircraftID($0.uasID)
        }.flatMap { $0.isEmpty ? nil : $0 }
        guard receivedFreshLocation else {
            return OpenDroneIDTrackAssemblyResult(
                observation: nil,
                disposition: .noFreshLocation,
                aircraftID: aircraftID,
                droneScoutRelay: state.droneScoutRelay
            )
        }
        guard let aircraftID
        else {
            return OpenDroneIDTrackAssemblyResult(
                observation: nil,
                disposition: .missingIdentity,
                droneScoutRelay: state.droneScoutRelay
            )
        }
        guard let location = state.location,
              location.latitude != 0,
              location.longitude != 0
        else {
            return OpenDroneIDTrackAssemblyResult(
                observation: nil,
                disposition: .invalidLocation,
                aircraftID: aircraftID,
                droneScoutRelay: state.droneScoutRelay
            )
        }

        let observation = RidObservation(
            source: source,
            aircraftId: aircraftID,
            receivedAt: receivedAt,
            latitude: location.latitude,
            longitude: location.longitude,
            altitudeMeters: location.preferredAltitudeMeters,
            heightMeters: location.heightMeters > -999 ? location.heightMeters : nil,
            heightReference: location.heightMeters > -999
                ? (location.heightType == 0 ? .takeoff : .ground)
                : nil,
            grounded: location.status == 1 ? true : (location.status == 2 ? false : nil),
            horizontalAccuracyCode: location.horizontalAccuracyCode,
            headingDegrees: location.directionDegrees,
            speedMetersPerSecond: location.horizontalSpeedMetersPerSecond,
            operatorLatitude: state.system?.operatorLatitude,
            operatorLongitude: state.system?.operatorLongitude,
            signalStrengthDbm: signalStrengthDbm,
            droneScoutRelay: state.droneScoutRelay,
            proximityTelemetry: .fromRID(horizontalCode: location.horizontalAccuracyCode,
                geodetic: location.geodeticAltitudeMeters, pressure: location.pressureAltitudeMeters,
                verticalCode: location.verticalAccuracyCode, barometerCode: location.barometerAccuracyCode)
        )
        return OpenDroneIDTrackAssemblyResult(
            observation: observation,
            disposition: .observation,
            aircraftID: aircraftID,
            droneScoutRelay: state.droneScoutRelay
        )
    }

    public func removeState(for transmitterID: UUID) {
        transmitters.removeValue(forKey: transmitterID)
    }

    public func removeAllState() {
        transmitters.removeAll()
    }
}
