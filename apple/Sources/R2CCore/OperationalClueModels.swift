import Foundation

public enum OperationalClueUploadState: String, Codable, Sendable, CaseIterable {
    case localOnly
    case pending
    case uploading
    case published
    case failed
}

public struct OperationalClueRecord: Codable, Sendable, Equatable, Identifiable {
    public let id: UUID
    public let capturedAt: Date
    public let aircraftID: String
    public let designator: String
    public let droneLatitude: Double
    public let droneLongitude: Double
    public let droneAltitudeMeters: Double?
    public let clueLatitude: Double
    public let clueLongitude: Double
    public let clueAltitudeMeters: Double?
    public let headingDegrees: Double?
    public let aglMeters: Double?
    public let atoMeters: Double?
    public let gimbalAngleDegrees: Double
    public var title: String
    public var clueDescription: String
    public let imageFilename: String
    public let thumbnailFilename: String
    public var uploadState: OperationalClueUploadState
    public var uploadAttempts: Int
    public var lastUploadError: String?
    public var caltopoMarkerID: String?
    public let caltopoMediaID: UUID

    public init(
        id: UUID = UUID(),
        capturedAt: Date,
        aircraftID: String,
        designator: String,
        droneLatitude: Double,
        droneLongitude: Double,
        droneAltitudeMeters: Double?,
        clueLatitude: Double,
        clueLongitude: Double,
        clueAltitudeMeters: Double?,
        headingDegrees: Double?,
        aglMeters: Double?,
        atoMeters: Double?,
        gimbalAngleDegrees: Double,
        title: String,
        clueDescription: String,
        imageFilename: String,
        thumbnailFilename: String,
        uploadState: OperationalClueUploadState,
        uploadAttempts: Int = 0,
        lastUploadError: String? = nil,
        caltopoMarkerID: String? = nil,
        caltopoMediaID: UUID = UUID()
    ) {
        self.id = id
        self.capturedAt = capturedAt
        self.aircraftID = aircraftID
        self.designator = designator
        self.droneLatitude = droneLatitude
        self.droneLongitude = droneLongitude
        self.droneAltitudeMeters = droneAltitudeMeters
        self.clueLatitude = clueLatitude
        self.clueLongitude = clueLongitude
        self.clueAltitudeMeters = clueAltitudeMeters
        self.headingDegrees = headingDegrees
        self.aglMeters = aglMeters
        self.atoMeters = atoMeters
        self.gimbalAngleDegrees = gimbalAngleDegrees
        self.title = title
        self.clueDescription = clueDescription
        self.imageFilename = imageFilename
        self.thumbnailFilename = thumbnailFilename
        self.uploadState = uploadState
        self.uploadAttempts = uploadAttempts
        self.lastUploadError = lastUploadError
        self.caltopoMarkerID = caltopoMarkerID
        self.caltopoMediaID = caltopoMediaID
    }
}

public struct OperationalClueProjection: Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public let terrainProjectionApplied: Bool
    public let demSource: String?
    public let demResolutionMeters: Double?
    public let demSampleStale: Bool

    public init(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double?,
        terrainProjectionApplied: Bool = false,
        demSource: String? = nil,
        demResolutionMeters: Double? = nil,
        demSampleStale: Bool = false
    ) {
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
        self.terrainProjectionApplied = terrainProjectionApplied
        self.demSource = demSource
        self.demResolutionMeters = demResolutionMeters
        self.demSampleStale = demSampleStale
    }
}

public struct OperationalClueHeadingSelection: Sendable, Equatable {
    public let degrees: Double?
    public let sourceLabel: String?

    public init(degrees: Double?, sourceLabel: String?) {
        self.degrees = degrees
        self.sourceLabel = sourceLabel
    }
}

public struct OperationalClueProjectionHeightSelection: Sendable, Equatable {
    public let meters: Double
    public let sourceLabel: String

    public init(meters: Double, sourceLabel: String) {
        self.meters = meters
        self.sourceLabel = sourceLabel
    }
}

public struct OperationalDJIVideoPosition: Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double

    public init(latitude: Double, longitude: Double, altitudeMeters: Double) {
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
    }
}

public enum OperationalClueGeometry {
    public static func selectedProjectionHeight(
        freshAGLMeters: Double?,
        atoMeters: Double?,
        validatedDJIRelativeUpMeters: Double? = nil
    ) -> OperationalClueProjectionHeightSelection? {
        func validHeight(_ value: Double?) -> Double? {
            guard let value, value.isFinite, value > 0, value <= 10_000 else { return nil }
            return value
        }
        if let meters = validHeight(validatedDJIRelativeUpMeters) {
            return OperationalClueProjectionHeightSelection(
                meters: meters,
                sourceLabel: "DJI SEI relative altitude"
            )
        }
        if let meters = validHeight(freshAGLMeters) {
            return OperationalClueProjectionHeightSelection(
                meters: meters,
                sourceLabel: "fresh AGL"
            )
        }
        if let meters = validHeight(atoMeters) {
            return OperationalClueProjectionHeightSelection(
                meters: meters,
                sourceLabel: "ATO flat-ground fallback"
            )
        }
        return nil
    }

    /// Validates a complete DJI SEI position against RID without changing its epoch or coordinate.
    public static func djiValidatedHorizontalPosition(
        latitudeDegrees: Double?,
        longitudeDegrees: Double?,
        ridLatitudeDegrees: Double,
        ridLongitudeDegrees: Double,
        maximumResidualMeters: Double = 30
    ) -> (latitudeDegrees: Double, longitudeDegrees: Double)? {
        guard let latitudeDegrees, latitudeDegrees.isFinite,
              let longitudeDegrees, longitudeDegrees.isFinite,
              ridLatitudeDegrees.isFinite, ridLongitudeDegrees.isFinite,
              (-90 ... 90).contains(latitudeDegrees),
              (-180 ... 180).contains(longitudeDegrees),
              (-90 ... 90).contains(ridLatitudeDegrees),
              (-180 ... 180).contains(ridLongitudeDegrees),
              maximumResidualMeters.isFinite, maximumResidualMeters >= 0
        else { return nil }
        let earthRadiusMeters = 6_378_137.0
        let latitudeRadians = latitudeDegrees * .pi / 180
        let residualNorth = (ridLatitudeDegrees - latitudeDegrees) * .pi / 180 * earthRadiusMeters
        let residualEast = (ridLongitudeDegrees - longitudeDegrees) * .pi / 180 *
            earthRadiusMeters * cos(latitudeRadians)
        guard hypot(residualNorth, residualEast) <= maximumResidualMeters
        else { return nil }
        return (latitudeDegrees, longitudeDegrees)
    }

    /// Validates direct SEI relative-up against RID when RID altitude is available.
    public static func djiValidatedRelativeUpMeters(
        observedRelativeUpMeters: Double?,
        ridAltitudeMeters: Double?,
        takeoffMslMeters: Double?,
        maximumResidualMeters: Double = 20
    ) -> Double? {
        guard let observedRelativeUpMeters, observedRelativeUpMeters.isFinite,
              maximumResidualMeters.isFinite, maximumResidualMeters >= 0 else { return nil }
        guard let ridAltitudeMeters, ridAltitudeMeters.isFinite,
              let takeoffMslMeters, takeoffMslMeters.isFinite else { return observedRelativeUpMeters }
        let targetUp = ridAltitudeMeters - takeoffMslMeters
        return abs(observedRelativeUpMeters - targetUp) <= maximumResidualMeters
            ? observedRelativeUpMeters : nil
    }

    /// Clockwise true-north camera bearing derived from DJI's magnetic encoder.
    public static func djiControllerCameraAzimuthDegrees(
        seiCameraAzimuthDegrees: Double?,
        magneticDeclinationDegrees: Double? = nil
    ) -> Double? {
        guard let seiCameraAzimuthDegrees, seiCameraAzimuthDegrees.isFinite else { return nil }
        let declination = magneticDeclinationDegrees.flatMap { $0.isFinite ? $0 : nil } ?? 0
        return RidHeading.normalized(seiCameraAzimuthDegrees - 90 + declination)
    }

    /// Matrice 4TD calibration: raw -90 is down; controlled raw -14.5625 is horizontal.
    public static func djiCalibratedTiltDegrees(rawTiltDegrees: Double?) -> Double? {
        guard let rawTiltDegrees, rawTiltDegrees.isFinite else { return nil }
        let rawHorizontalReferenceDegrees = -14.5625
        let scale = 90.0 / (rawHorizontalReferenceDegrees - (-90.0))
        return min(90, max(-90, (rawTiltDegrees - rawHorizontalReferenceDegrees) * scale))
    }

    public static func djiVideoPosition(
        tag4AnglesDegrees: [Double]
    ) -> OperationalDJIVideoPosition? {
        guard tag4AnglesDegrees.count == 9 else { return nil }
        func signedAngle(_ value: Double) -> Double {
            value >= 180 ? value - 360 : value
        }
        let latitude = signedAngle(tag4AnglesDegrees[6]) / 2
        let longitude = signedAngle(tag4AnglesDegrees[7])
        let altitude = -(signedAngle(tag4AnglesDegrees[8]) / 360 * 4_294_967_296) / 1_000
        guard latitude.isFinite, longitude.isFinite, altitude.isFinite,
              (-90 ... 90).contains(latitude),
              (-180 ... 180).contains(longitude),
              (-1_000 ... 30_000).contains(altitude),
              abs(latitude) > 0.00000001 || abs(longitude) > 0.00000001
        else { return nil }
        return OperationalDJIVideoPosition(
            latitude: latitude,
            longitude: longitude,
            altitudeMeters: altitude
        )
    }

    public static func djiVideoAglMeters(
        mslAltitudeMeters: Double?,
        groundElevationMeters: Double?
    ) -> Double? {
        guard let mslAltitudeMeters, mslAltitudeMeters.isFinite,
              let groundElevationMeters, groundElevationMeters.isFinite
        else { return nil }
        let agl = mslAltitudeMeters - groundElevationMeters
        return agl.isFinite && (0 ... 10_000).contains(agl) ? agl : nil
    }

    public static func selectedGimbalAngleDegrees(
        streamPitchDegrees: Double?,
        fallbackDegrees: Double = -90
    ) -> Double {
        guard let streamPitchDegrees, streamPitchDegrees.isFinite else {
            return min(90, max(-90, fallbackDegrees))
        }
        return min(90, max(-90, streamPitchDegrees))
    }

    public static func selectedHeading(
        cameraAzimuthDegrees: Double? = nil,
        videoCourseDegrees: Double? = nil,
        cameraYawDegrees: Double?,
        streamHeadingDegrees: Double?,
        ridHeadingDegrees: Double?,
        derivedHeadingDegrees: Double? = nil
    ) -> OperationalClueHeadingSelection {
        if let cameraAzimuth = RidHeading.normalized(cameraAzimuthDegrees) {
            return OperationalClueHeadingSelection(
                degrees: cameraAzimuth,
                sourceLabel: "DJI camera azimuth"
            )
        }
        if let videoCourse = RidHeading.normalized(videoCourseDegrees) {
            return OperationalClueHeadingSelection(
                degrees: videoCourse,
                sourceLabel: "DJI video-derived course"
            )
        }
        if let derivedHeading = RidHeading.normalized(derivedHeadingDegrees) {
            return OperationalClueHeadingSelection(
                degrees: derivedHeading,
                sourceLabel: "Derived drone heading"
            )
        }
        if let cameraYaw = RidHeading.normalized(cameraYawDegrees) {
            return OperationalClueHeadingSelection(
                degrees: cameraYaw,
                sourceLabel: "Camera yaw"
            )
        }
        if let streamHeading = RidHeading.normalized(streamHeadingDegrees) {
            return OperationalClueHeadingSelection(
                degrees: streamHeading,
                sourceLabel: "Stream heading"
            )
        }
        if let ridHeading = RidHeading.normalized(ridHeadingDegrees) {
            return OperationalClueHeadingSelection(
                degrees: ridHeading,
                sourceLabel: "RID aircraft track"
            )
        }
        return OperationalClueHeadingSelection(degrees: nil, sourceLabel: nil)
    }

    public static func project(
        droneLatitude: Double,
        droneLongitude: Double,
        droneAltitudeMeters: Double?,
        headingDegrees: Double?,
        aglMeters: Double?,
        gimbalAngleDegrees: Double
    ) -> OperationalClueProjection {
        let groundAltitude = if let droneAltitudeMeters, let aglMeters {
            droneAltitudeMeters - aglMeters
        } else {
            droneAltitudeMeters
        }
        guard let headingDegrees, headingDegrees.isFinite,
              let aglMeters, aglMeters.isFinite, aglMeters > 0
        else {
            return OperationalClueProjection(
                latitude: droneLatitude,
                longitude: droneLongitude,
                altitudeMeters: groundAltitude
            )
        }
        let angle = min(90, max(-90, gimbalAngleDegrees))
        guard angle < -0.1 else {
            return OperationalClueProjection(
                latitude: droneLatitude,
                longitude: droneLongitude,
                altitudeMeters: groundAltitude
            )
        }
        let downFromHorizon = abs(angle)
        guard downFromHorizon < 89.9, downFromHorizon > 0.1 else {
            return OperationalClueProjection(
                latitude: droneLatitude,
                longitude: droneLongitude,
                altitudeMeters: groundAltitude
            )
        }
        let distance = aglMeters / tan(downFromHorizon * .pi / 180)
        guard distance.isFinite, distance > 0 else {
            return OperationalClueProjection(
                latitude: droneLatitude,
                longitude: droneLongitude,
                altitudeMeters: groundAltitude
            )
        }
        let destination = destinationPoint(
            latitude: droneLatitude,
            longitude: droneLongitude,
            bearingDegrees: headingDegrees,
            distanceMeters: min(distance, 2_500)
        )
        return OperationalClueProjection(
            latitude: destination.latitude,
            longitude: destination.longitude,
            altitudeMeters: groundAltitude
        )
    }

    public static func projectWithTerrain(
        droneLatitude: Double,
        droneLongitude: Double,
        droneAltitudeMeters: Double?,
        headingDegrees: Double?,
        aglMeters: Double?,
        gimbalAngleDegrees: Double,
        terrainReferenceLatitude: Double? = nil,
        terrainReferenceLongitude: Double? = nil,
        relativeUpMeters: Double? = nil,
        sampleElevationMeters: @Sendable (Double, Double) async -> OperationalTerrainSample?
    ) async -> OperationalClueProjection {
        let referenceDEMSample: OperationalTerrainSample? = if let latitude = terrainReferenceLatitude,
                                                               let longitude = terrainReferenceLongitude,
                                                               latitude.isFinite,
                                                               longitude.isFinite,
                                                               (-90 ... 90).contains(latitude),
                                                               (-180 ... 180).contains(longitude) {
            await sampleElevationMeters(latitude, longitude)
        } else {
            nil
        }
        let anchoredRelativeUp = relativeUpMeters.flatMap {
            $0.isFinite && $0 > 0 ? $0 : nil
        }
        let referenceGround = referenceDEMSample.flatMap { sample in
            sample.elevationMeters.isFinite ? sample.elevationMeters : nil
        }
        let effectiveAltitude = if let referenceGround, let anchoredRelativeUp {
            referenceGround + anchoredRelativeUp
        } else {
            droneAltitudeMeters
        }
        let effectiveAGL = anchoredRelativeUp ?? aglMeters
        let flatProjection = project(
            droneLatitude: droneLatitude,
            droneLongitude: droneLongitude,
            droneAltitudeMeters: effectiveAltitude,
            headingDegrees: headingDegrees,
            aglMeters: effectiveAGL,
            gimbalAngleDegrees: gimbalAngleDegrees
        )
        if anchoredRelativeUp != nil,
           terrainReferenceLatitude != nil,
           terrainReferenceLongitude != nil,
           referenceGround == nil {
            return flatProjection
        }
        guard let droneAltitudeMeters = effectiveAltitude, droneAltitudeMeters.isFinite,
              let headingDegrees, headingDegrees.isFinite,
              let aglMeters = effectiveAGL, aglMeters.isFinite, aglMeters > 0
        else { return flatProjection }

        let angle = min(90, max(-90, gimbalAngleDegrees))
        guard angle < -0.1 else { return flatProjection }
        let tiltFromHorizon = max(0.1, min(90, abs(angle)))
        guard tiltFromHorizon < 89.9 else { return flatProjection }
        let slopeDown = tan(tiltFromHorizon * .pi / 180)
        guard slopeDown.isFinite, slopeDown > 0 else { return flatProjection }
        let flatDistance = aglMeters / slopeDown
        guard flatDistance.isFinite, flatDistance > 0 else { return flatProjection }

        let flatGround = referenceGround ?? (droneAltitudeMeters - aglMeters)
        let droneDEMSample: OperationalTerrainSample? = if let referenceDEMSample {
            referenceDEMSample
        } else {
            await sampleElevationMeters(droneLatitude, droneLongitude)
        }
        let droneDEM = referenceGround ?? droneDEMSample?.elevationMeters
        let scale: Double = if referenceGround == nil {
            inferDEMScaleToMeters(
                droneAltitudeMeters: droneAltitudeMeters,
                knownGroundMeters: flatGround,
                droneDEM: droneDEM
            )
        } else {
            1
        }
        // A shallow sightline over falling terrain may stay above the ground for kilometres.
        // Do not derive the terrain-search limit from the short flat-ground estimate.
        let usingLocalDEM = droneDEMSample?.source?.hasPrefix("usgs-geotiff-local-") == true
        let maximumDistance = usingLocalDEM ? 10_000.0 : 2_500.0
        let step = usingLocalDEM ? 50.0 : 100.0

        var previousDistance = 0.0
        var previousGround = flatGround
        var distance = step
        while distance <= maximumDistance + 0.001 {
            let candidate = destinationPoint(
                latitude: droneLatitude,
                longitude: droneLongitude,
                bearingDegrees: headingDegrees,
                distanceMeters: distance
            )
            let candidateDEMSample = await sampleElevationMeters(candidate.latitude, candidate.longitude)
            if usingLocalDEM, candidateDEMSample?.source?.hasPrefix("usgs-geotiff-local-") != true {
                break
            }
            let candidateDEM = candidateDEMSample?.elevationMeters
            let ground = normalizedDEMGroundMeters(
                candidateDEM: candidateDEM,
                droneDEM: droneDEM,
                flatGroundMeters: flatGround,
                scaleToMeters: scale
            )
            guard let ground, let candidateDEMSample else {
                distance += step
                continue
            }
            let rayAltitude = droneAltitudeMeters - slopeDown * distance
            if rayAltitude <= ground {
                var lowDistance = previousDistance
                var lowGround = previousGround
                var highDistance = distance
                var highPoint = candidate
                var highGround = ground
                for _ in 0 ..< 6 {
                    let midDistance = (lowDistance + highDistance) / 2
                    let midPoint = destinationPoint(
                        latitude: droneLatitude,
                        longitude: droneLongitude,
                        bearingDegrees: headingDegrees,
                        distanceMeters: midDistance
                    )
                    let midDEM = await sampleElevationMeters(midPoint.latitude, midPoint.longitude)?.elevationMeters
                    let midGround = normalizedDEMGroundMeters(
                        candidateDEM: midDEM,
                        droneDEM: droneDEM,
                        flatGroundMeters: flatGround,
                        scaleToMeters: scale
                    ) ?? ((lowGround + highGround) / 2)
                    if droneAltitudeMeters - slopeDown * midDistance <= midGround {
                        highDistance = midDistance
                        highPoint = midPoint
                        highGround = midGround
                    } else {
                        lowDistance = midDistance
                        lowGround = midGround
                    }
                }
                return OperationalClueProjection(
                    latitude: highPoint.latitude,
                    longitude: highPoint.longitude,
                    altitudeMeters: highGround,
                    terrainProjectionApplied: true,
                    demSource: candidateDEMSample.source,
                    demResolutionMeters: candidateDEMSample.horizontalResolutionMeters,
                    demSampleStale: candidateDEMSample.stale
                )
            }
            previousDistance = distance
            previousGround = ground
            distance += step
        }
        return flatProjection
    }

    private static func inferDEMScaleToMeters(
        droneAltitudeMeters: Double,
        knownGroundMeters: Double,
        droneDEM: Double?
    ) -> Double {
        guard let droneDEM, droneDEM.isFinite else { return 1 }
        let directError = abs(droneDEM - knownGroundMeters) + abs(droneDEM - droneAltitudeMeters)
        let converted = droneDEM * 0.3048
        let feetError = abs(converted - knownGroundMeters) + abs(converted - droneAltitudeMeters)
        return feetError < directError ? 0.3048 : 1
    }

    private static func normalizedDEMGroundMeters(
        candidateDEM: Double?,
        droneDEM: Double?,
        flatGroundMeters: Double,
        scaleToMeters: Double
    ) -> Double? {
        guard let candidateDEM, candidateDEM.isFinite else { return nil }
        guard let droneDEM, droneDEM.isFinite else { return candidateDEM * scaleToMeters }
        return flatGroundMeters + (candidateDEM - droneDEM) * scaleToMeters
    }

    private static func destinationPoint(
        latitude: Double,
        longitude: Double,
        bearingDegrees: Double,
        distanceMeters: Double
    ) -> (latitude: Double, longitude: Double) {
        let angularDistance = distanceMeters / 6_371_008.8
        let bearing = bearingDegrees * .pi / 180
        let latitude1 = latitude * .pi / 180
        let longitude1 = longitude * .pi / 180
        let latitude2 = asin(
            sin(latitude1) * cos(angularDistance)
                + cos(latitude1) * sin(angularDistance) * cos(bearing)
        )
        let longitude2 = longitude1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude1),
            cos(angularDistance) - sin(latitude1) * sin(latitude2)
        )
        return (latitude2 * 180 / .pi, longitude2 * 180 / .pi)
    }
}

public enum OperationalCenterpointElevation {
    public enum DisplayMode: Sendable, Equatable {
        case msl
        case reference
    }

    public struct Sample: Sendable, Equatable {
        public let elevationFeet: Int
        public let demResolutionMeters: Int?

        public init(elevationFeet: Int, demResolutionMeters: Int?) {
            self.elevationFeet = elevationFeet
            self.demResolutionMeters = demResolutionMeters
        }
    }

    public static func displayText(
        _ sample: Sample?,
        referenceElevationFeet: Int? = nil,
        mode: DisplayMode = .msl
    ) -> String {
        guard let sample else { return "--' MSL" }
        let suffix = sample.demResolutionMeters.map { "\($0)m DEM" } ?? "USGS DEM"
        if mode == .reference, let referenceElevationFeet {
            let delta = sample.elevationFeet - referenceElevationFeet
            let signedDelta = delta >= 0 ? "+\(delta)" : "\(delta)"
            return "\(signedDelta)' REF · \(suffix)"
        }
        return "\(sample.elevationFeet)' MSL · \(suffix)"
    }

    public static func shouldSetReference(
        focused: Bool,
        elevationEnabled: Bool,
        pressNearCenter: Bool
    ) -> Bool {
        focused && elevationEnabled && pressNearCenter
    }

    public static func isNearCenter(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        radius: Double
    ) -> Bool {
        guard x.isFinite, y.isFinite, width > 0, height > 0, radius > 0 else { return false }
        let dx = x - width / 2
        let dy = y - height / 2
        return dx * dx + dy * dy <= radius * radius
    }
}

public struct CaltopoPhotoClue: Sendable, Equatable {
    public let markerID: UUID
    public let mediaID: UUID
    public let latitude: Double
    public let longitude: Double
    public let title: String
    public let description: String
    public let createdMilliseconds: Int64
    public let jpegData: Data
    public let teamID: String
    public let folderID: String?

    public init(
        markerID: UUID = UUID(),
        mediaID: UUID = UUID(),
        latitude: Double,
        longitude: Double,
        title: String,
        description: String,
        createdMilliseconds: Int64,
        jpegData: Data,
        teamID: String,
        folderID: String? = nil
    ) {
        self.markerID = markerID
        self.mediaID = mediaID
        self.latitude = latitude
        self.longitude = longitude
        self.title = title
        self.description = description
        self.createdMilliseconds = createdMilliseconds
        self.jpegData = jpegData
        self.teamID = teamID
        self.folderID = folderID
    }
}
