import Foundation

public struct OperationalTerrainSample: Sendable, Equatable {
    public let elevationMeters: Double
    public let stale: Bool
    public let source: String?
    public let horizontalResolutionMeters: Double?

    public init(
        elevationMeters: Double,
        stale: Bool = false,
        source: String? = nil,
        horizontalResolutionMeters: Double? = nil
    ) {
        self.elevationMeters = elevationMeters
        self.stale = stale
        self.source = source
        self.horizontalResolutionMeters = horizontalResolutionMeters
    }
}

public struct OperationalAircraftAltitudeDisplay: Sendable, Equatable {
    public let positionStale: Bool
    public let atoStatus, aglStatus: OperationalMeasurementStatus
    public var atoLabel: String { (positionStale ? OperationalMeasurementStatus.stale : atoStatus).label(atoFeet,suffix:" ft") }
    public var aglLabel: String { (positionStale ? OperationalMeasurementStatus.stale : aglStatus).label(aglFeet,suffix:" ft") }
    public var aolLabel: String { (positionStale ? OperationalMeasurementStatus.stale : aol.status).label(aol.feet,suffix:" ft") }
    public var rangeLabel: String { (positionStale ? OperationalMeasurementStatus.stale : .available).label(rangeFeet,suffix:" ft") }
    public let aol: OperationalAOLState
    public let atoFeet: Double?
    public let aglFeet: Double?
    public let aglStale: Bool
    public let aglUsesTerrain: Bool
    public let rangeFeet: Double?

    public init(
        atoFeet: Double?,
        aglFeet: Double?,
        aglStale: Bool,
        aglUsesTerrain: Bool,
        rangeFeet: Double?,
        aol: OperationalAOLState = .init(),
        positionStale: Bool = false,
        atoStatus: OperationalMeasurementStatus = .available,
        aglStatus: OperationalMeasurementStatus = .available
    ) {
        self.positionStale=positionStale;self.atoStatus=atoStatus;self.aglStatus=aglStatus
        self.aol = aol
        self.atoFeet = atoFeet
        self.aglFeet = aglFeet
        self.aglStale = aglStale
        self.aglUsesTerrain = aglUsesTerrain
        self.rangeFeet = rangeFeet
    }
}

public struct OperationalPeerAltitudeReference: Sendable, Equatable {
    public let takeoffCoordinate: OperationalAltitudeCoordinator.Coordinate
    public let reportedGroundAltitudeMeters: Double

    public init(
        takeoffCoordinate: OperationalAltitudeCoordinator.Coordinate,
        reportedGroundAltitudeMeters: Double
    ) {
        self.takeoffCoordinate = takeoffCoordinate
        self.reportedGroundAltitudeMeters = reportedGroundAltitudeMeters
    }
}

/// Per-aircraft altitude state matching Android's takeoff-reference and DEM correction rules.
public struct OperationalAltitudeCoordinator: Sendable {
    public enum SeedSource: Sendable, Equatable {
        case automatic
        case automaticSealed
        case manual
    }

    public struct Coordinate: Sendable, Equatable {
        public let latitude: Double
        public let longitude: Double

        public init(latitude: Double, longitude: Double) {
            self.latitude = latitude
            self.longitude = longitude
        }
    }

    private struct Calibration: Sendable, Equatable {
        var takeoffTrackAltitudeMeters: Double
        var seedSource: SeedSource
    }

    public private(set) var takeoffCoordinate: Coordinate?
    public private(set) var currentCoordinate: Coordinate?
    private var currentAltitudeMeters: Double?
    private var lastObservationWasVideo = false
    private var relativeHeightMeters: Double?
    private var relativeHeightReference: RidObservation.HeightReference?
    private var calibration: Calibration?
    private var automaticSampleCount = 0
    private var takeoffTerrain: OperationalTerrainSample?
    private var currentTerrain: OperationalTerrainSample?
    private var currentTerrainKey: String?
    private var correctionMeters: Double?

    public private(set) var aolTakeoffCoordinate: Coordinate?
    private var terrainPending = false
    public mutating func setTerrainPending(_ pending: Bool) { terrainPending=pending }
    private var aolState = OperationalAOLState()
    private var aolRefreshStartedAt: Date?
    private var receivedAt: Date?
    public func hasFreshTelemetry(at now: Date) -> Bool {
        receivedAt.map { now.timeIntervalSince($0) >= 0 && now.timeIntervalSince($0) < 5 } == true
    }
    public var hasFreshAOLTelemetry: Bool { hasFreshTelemetry(at: Date()) }
    public struct AOLInput: Sendable, Equatable {
        public let position: Coordinate?
        public let takeoff: Coordinate?
        public let height: Double?
        public let reference: String
    }
    public var aolInput: AOLInput {
        AOLInput(position: currentCoordinate, takeoff: aolTakeoffCoordinate, height: aolHeight,
                 reference: "\(calibration?.seedSource == .manual ? String(describing: calibration) : "automatic")|\(lastObservationWasVideo && calibration?.seedSource != .manual ? "video" : String(describing: aolTakeoffCoordinate))")
    }

    public var aolHeight: Double? {
        if calibration?.seedSource == .manual,let altitude=currentAltitudeMeters,let calibration { return altitude-calibration.takeoffTrackAltitudeMeters }
        return relativeHeightReference == .takeoff ? relativeHeightMeters : nil
    }
    public mutating func applyAOL(_ state: OperationalAOLState) { aolState = state; aolRefreshStartedAt = nil }
    /// Accept recent completed work while newer positions wait, without extending its age.
    @discardableResult
    public mutating func applyCompletedAOL(_ state: OperationalAOLState, input: AOLInput,
                                          startedAt: Date, now: Date) -> Bool {
        guard input.reference == aolInput.reference, hasFreshTelemetry(at: now),
              aolHeight != nil, aolTakeoffCoordinate != nil,
              now.timeIntervalSince(startedAt) >= 0, now.timeIntervalSince(startedAt) < 1.5 else { return false }
        aolState = state
        aolRefreshStartedAt = aolInput == input ? nil : startedAt
        return true
    }

    public init() {}

    public mutating func ingest(_ observation: RidObservation) {
        let previousInput = aolInput
        lastObservationWasVideo = observation.source == .djiVideo
        defer {
            if aolInput != previousInput {
                if aolInput.takeoff == nil {
                    aolState = .init(reason: "Takeoff ground reference not observed; receive ground status and near-zero height before flight")
                    aolRefreshStartedAt = nil
                } else if aolInput.height == nil {
                    aolState = .init(reason: "Takeoff-relative altitude unavailable")
                    aolRefreshStartedAt = nil
                } else if aolState.status == .available,
                    previousInput.takeoff == aolInput.takeoff ||
                        (observation.source == .djiVideo && calibration?.seedSource != .manual && previousInput.takeoff != nil),
                   aolInput.height != nil {
                    // Do not restart the grace window on each incoming observation.
                    if aolRefreshStartedAt == nil { aolRefreshStartedAt = observation.receivedAt }
                } else {
                    aolState = .init(reason: "Surface calculation pending for current position", status: .pending)
                    aolRefreshStartedAt = nil
                }
            }
        }
        receivedAt = observation.receivedAt
        let coordinate = Coordinate(latitude: observation.latitude, longitude: observation.longitude)
        if takeoffCoordinate == nil { takeoffCoordinate = coordinate }
        currentCoordinate = coordinate
        currentAltitudeMeters = observation.altitudeMeters.flatMap(Self.validAltitude)
        relativeHeightMeters = observation.heightMeters.flatMap(Self.validAltitude)
        relativeHeightReference = relativeHeightMeters == nil ? nil : observation.heightReference
        if observation.source == .djiVideo, calibration?.seedSource != .manual,
           relativeHeightReference == .takeoff, relativeHeightMeters != nil,
           hasFreshTelemetry(at: Date()),
           let latitude = observation.videoReferenceLatitude,
           let longitude = observation.videoReferenceLongitude,
           latitude.isFinite, longitude.isFinite,
           (-90...90).contains(latitude), (-180...180).contains(longitude),
           latitude != 0 || longitude != 0 {
            // Use the coordinate belonging to the streamed relative height, even if
            // reception starts airborne. Never substitute current aircraft position.
            aolTakeoffCoordinate = Coordinate(latitude: latitude, longitude: longitude)
        }
        // Some DJI aircraft report ground-relative zero until the airborne transition.
        // Both known zero-height references establish a ground launch; live AOL still requires ATO.
        if observation.grounded == true, relativeHeightReference != nil, let h=relativeHeightMeters, abs(h)<=1,
           Date().timeIntervalSince(observation.receivedAt)>=0, Date().timeIntervalSince(observation.receivedAt)<5 {
            aolTakeoffCoordinate=coordinate
        }

        guard calibration?.seedSource != .automaticSealed,
              calibration?.seedSource != .manual,
              let altitude = currentAltitudeMeters
        else { return }

        if relativeHeightReference == .takeoff, let height = relativeHeightMeters {
            if calibration == nil || height >= 2 {
                let sample = altitude - height
                if var existing = calibration {
                    let updated = existing.takeoffTrackAltitudeMeters * 0.75 + sample * 0.25
                    let delta = abs(updated - existing.takeoffTrackAltitudeMeters)
                    automaticSampleCount += 1
                    existing.takeoffTrackAltitudeMeters = updated
                    if automaticSampleCount >= 6, delta < 0.4 {
                        existing.seedSource = .automaticSealed
                    }
                    calibration = existing
                } else {
                    calibration = Calibration(takeoffTrackAltitudeMeters: sample, seedSource: .automatic)
                    automaticSampleCount = 1
                }
                refreshCorrection()
            }
        } else if calibration == nil {
            calibration = Calibration(takeoffTrackAltitudeMeters: altitude, seedSource: .automatic)
            automaticSampleCount = 1
            refreshCorrection()
        }
    }

    public mutating func applyTakeoffTerrain(_ sample: OperationalTerrainSample?) {
        takeoffTerrain = sample
        refreshCorrection()
    }

    public mutating func applyCurrentTerrain(
        _ sample: OperationalTerrainSample?,
        coordinate: Coordinate
    ) {
        guard coordinate == currentCoordinate else { return }
        currentTerrain = sample
        currentTerrainKey = Self.terrainKey(coordinate)
    }

    public mutating func markCurrentTerrainPending() {
        currentTerrain = currentTerrain.map {
            OperationalTerrainSample(elevationMeters: $0.elevationMeters, stale: true)
        }
    }

    public mutating func manualCalibrateAtFiftyFeet() {
        guard hasFreshAOLTelemetry,let altitude = currentAltitudeMeters,let coordinate=currentCoordinate else { return }
        aolTakeoffCoordinate=coordinate
        aolState = .init(reason:"Surface calculation pending for calibrated launch reference",status:.pending)
        aolRefreshStartedAt = nil
        takeoffCoordinate=coordinate
        takeoffTerrain=currentTerrainKey == Self.terrainKey(coordinate) && currentTerrain?.stale != true ? currentTerrain : nil
        correctionMeters=nil

        calibration = Calibration(
            takeoffTrackAltitudeMeters: altitude - 50 * Self.feetToMeters,
            seedSource: .manual
        )
        refreshCorrection()
    }

    public var canManualCalibrate: Bool { currentAltitudeMeters != nil && currentCoordinate != nil && hasFreshAOLTelemetry }
    public var seedSource: SeedSource? { calibration?.seedSource }

    public var peerTrafficReference: OperationalPeerAltitudeReference? {
        guard let takeoffCoordinate, let calibration else { return nil }
        return OperationalPeerAltitudeReference(
            takeoffCoordinate: takeoffCoordinate,
            reportedGroundAltitudeMeters: calibration.takeoffTrackAltitudeMeters
        )
    }

    public var display: OperationalAircraftAltitudeDisplay { display(at: Date()) }

    public func display(at now: Date) -> OperationalAircraftAltitudeDisplay {
        let atoMeters: Double? = {
            guard let altitude = currentAltitudeMeters else { return nil }
            if calibration?.seedSource == .manual {
                return calibration.map { altitude - $0.takeoffTrackAltitudeMeters }
            }
            if relativeHeightReference == .takeoff { return relativeHeightMeters }
            return calibration.map { altitude - $0.takeoffTrackAltitudeMeters }
        }()

        let terrainMatchesPosition = currentCoordinate.map(Self.terrainKey) == currentTerrainKey
        let aglMeters: Double?
        let usesTerrain = correctionMeters != nil
        if let correctionMeters, let terrain = currentTerrain {
            if calibration?.seedSource != .manual, relativeHeightReference == .takeoff,
               let height = relativeHeightMeters,
               let calibration {
                let takeoffGround = calibration.takeoffTrackAltitudeMeters - correctionMeters
                aglMeters = height + takeoffGround - terrain.elevationMeters
            } else if let altitude = currentAltitudeMeters {
                aglMeters = altitude - terrain.elevationMeters - correctionMeters
            } else {
                aglMeters = nil
            }
        } else if calibration?.seedSource == .manual {
            aglMeters=atoMeters
        } else if relativeHeightReference == .ground {
            aglMeters = relativeHeightMeters
        } else if !usesTerrain, relativeHeightReference == .takeoff {
            aglMeters = relativeHeightMeters
        } else {
            aglMeters = nil
        }

        let rangeMeters: Double? = {
            guard let takeoffCoordinate, let currentCoordinate else { return nil }
            return RidGeometry.relativePosition(
                fromLatitude: takeoffCoordinate.latitude,
                longitude: takeoffCoordinate.longitude,
                toLatitude: currentCoordinate.latitude,
                longitude: currentCoordinate.longitude
            )?.distanceMeters
        }()
        let nonNegativeAGLMeters = aglMeters.map { max(0, $0) }
        let freshTelemetry = hasFreshTelemetry(at: now)
        let refreshingExpired = aolRefreshStartedAt.map { now.timeIntervalSince($0) >= 1.5 || now < $0 } ?? false
        let displayedAOL = refreshingExpired
            ? OperationalAOLState(reason: "Surface calculation pending for current position", status: .pending)
            : aolState
        return OperationalAircraftAltitudeDisplay(
            atoFeet: atoMeters.map { $0 * Self.metersToFeet },
            aglFeet: nonNegativeAGLMeters.map { $0 * Self.metersToFeet },
            aglStale: usesTerrain && (currentTerrain?.stale == true || !terrainMatchesPosition),
            aglUsesTerrain: usesTerrain,
            rangeFeet: rangeMeters.map { $0 * Self.metersToFeet },
            aol: freshTelemetry ? displayedAOL : .init(reason: "Aircraft position/altitude stale",status:.stale),
            positionStale: receivedAt != nil && !freshTelemetry,
            atoStatus: atoMeters == nil ? .unknown : .available,
            aglStatus: usesTerrain && (currentTerrain?.stale == true || !terrainMatchesPosition) || aglMeters == nil ? (terrainPending ? .pending : .unknown) : .available
        )
    }

    public static func terrainKey(_ coordinate: Coordinate) -> String {
        // Schedule local DEM sampling at roughly one-metre position changes. The GeoTIFF
        // source bilinearly interpolates its surrounding pixels. Approximately one-metre
        // scheduling exposes 1 m local tiles and best-available EPQS results without visible steps.
        "\(Int((coordinate.latitude * 100_000).rounded()))|\(Int((coordinate.longitude * 100_000).rounded()))"
    }

    public static func terrainCacheKey(_ coordinate: Coordinate) -> String {
        // EPQS is backed by the best available 3DEP source, including 1 m lidar DEMs.
        // Retain approximately one-metre spacing so a fine source is not flattened to 30 m.
        "\(Int((coordinate.latitude * 100_000).rounded()))|\(Int((coordinate.longitude * 100_000).rounded()))"
    }

    private mutating func refreshCorrection() {
        guard let calibration, let takeoffTerrain else { return }
        correctionMeters = calibration.takeoffTrackAltitudeMeters - takeoffTerrain.elevationMeters
    }

    private static func validAltitude(_ value: Double) -> Double? {
        value.isFinite && value > -999 ? value : nil
    }

    private static let feetToMeters = 0.3048
    private static let metersToFeet = 3.28084
}
