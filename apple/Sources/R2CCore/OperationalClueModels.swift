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
    public var droneLatitude: Double
    public var droneLongitude: Double
    public let droneAltitudeMeters: Double?
    public var clueLatitude: Double
    public var clueLongitude: Double
    public var clueAltitudeMeters: Double?
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
    public var caltopoMediaID: UUID
    // Optional for compatibility with older indexes. Missing destinations require review.
    public var destinationMapID: String?
    public var destinationTeamID: String?
    /// Waypoint binding (nil for clues saved before binding existed).
    public var binding: ClueBinding?

    /// Capture time on the drone clock when bound; otherwise the stored capture time.
    public var ownershipTime: Date {
        binding.map { Date(timeIntervalSince1970: Double($0.captureTimeMs) / 1_000) } ?? capturedAt
    }

    /// Description sent to CalTopo and written to the KMZ (stored text plus the binding block).
    public var publishedDescription: String {
        ClueBindingText.publishedDescription(clueDescription, binding: binding)
    }

    public func canAutomaticallyPublish(mapID: String, teamID: String) -> Bool {
        uploadState != .localOnly && !mapID.isEmpty && !teamID.isEmpty && destinationMapID == mapID && destinationTeamID == teamID
    }

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
        caltopoMediaID: UUID = UUID(),
        destinationMapID: String? = nil,
        destinationTeamID: String? = nil,
        binding: ClueBinding? = nil
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
        self.destinationMapID = destinationMapID
        self.destinationTeamID = destinationTeamID
        self.binding = binding
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
    /// Set when returning a flat-surface estimate; nil when terrain was applied.
    public let flatFallbackReason: String?

    public init(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double?,
        terrainProjectionApplied: Bool = false,
        demSource: String? = nil,
        demResolutionMeters: Double? = nil,
        demSampleStale: Bool = false,
        flatFallbackReason: String? = nil
    ) {
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
        self.terrainProjectionApplied = terrainProjectionApplied
        self.demSource = demSource
        self.demResolutionMeters = demResolutionMeters
        self.demSampleStale = demSampleStale
        self.flatFallbackReason = flatFallbackReason
    }

    /// `terrain-raymarch`, `nadir-DEM`, or `flat` for per-clue projection logs.
    public func projectionMethod(gimbalPitchDegrees: Double) -> String {
        guard terrainProjectionApplied else { return "flat" }
        return gimbalPitchDegrees.isFinite && gimbalPitchDegrees <= -89.9 ? "nadir-DEM" : "terrain-raymarch"
    }
}

/// Per-walk timing for one S1M ray-march (logged as a single INFO line).
public final class OperationalClueRayMarchTiming: @unchecked Sendable {
    public var catalogMs: Double = 0
    public var tileOpenDecodeMs: Double = 0
    public var sampleCount: Int = 0
    public var stepMs: Double = 0
    public var totalMs: Double = 0
    public var maxRangeM: Double = 0
    public var coarseStepM: Double = 0
    public var hit: Bool = false
    public var startAltM: Double = .nan
    public var demUnderDroneM: Double = .nan
    public var aglM: Double = .nan
    public var startDatum: String = ""

    public init() {}

    public var infoLine: String {
        String(
            format: "Clue ray-march totalMs=%.1f catalogMs=%.1f tileOpenDecodeMs=%.1f stepMs=%.1f samples=%d maxRangeM=%.0f coarseStepM=%.0f hit=%@ startAltM=%.1f demUnderDroneM=%.1f aglM=%.1f datum=%@",
            locale: Locale(identifier: "en_US_POSIX"),
            totalMs,
            catalogMs,
            tileOpenDecodeMs,
            stepMs,
            sampleCount,
            maxRangeM,
            coarseStepM,
            hit ? "true" : "false",
            startAltM,
            demUnderDroneM,
            aglM,
            startDatum.isEmpty ? "none" : startDatum
        )
    }
}

public enum OperationalClueProjectionLog {
    /// One line per clue: method, tile hit/miss/timeout, gimbal pitch/yaw, lat/lon delta from aircraft.
    public static func line(
        projection: OperationalClueProjection,
        aircraftLatitude: Double,
        aircraftLongitude: Double,
        gimbalPitchDegrees: Double,
        gimbalYawDegrees: Double?,
        tileHit: Bool? = nil
    ) -> String {
        let method = projection.projectionMethod(gimbalPitchDegrees: gimbalPitchDegrees)
        let hit: String = {
            if let reason = projection.flatFallbackReason, reason.localizedCaseInsensitiveContains("timed out") {
                return "timeout"
            }
            if let tileHit { return tileHit ? "hit" : "miss" }
            if projection.demSource?.hasPrefix("usgs-geotiff-local-") == true { return "hit" }
            if projection.terrainProjectionApplied { return "hit" }
            return "miss"
        }()
        let deltaLat = projection.latitude - aircraftLatitude
        let deltaLon = projection.longitude - aircraftLongitude
        let yaw = gimbalYawDegrees.map { String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), $0) } ?? "nil"
        return String(
            format: "Clue projection method=%@ tile=%@ gimbalPitch=%.1f gimbalYaw=%@ deltaLat=%.6f deltaLon=%.6f demSource=%@",
            locale: Locale(identifier: "en_US_POSIX"),
            method,
            hit,
            gimbalPitchDegrees,
            yaw,
            deltaLat,
            deltaLon,
            projection.demSource ?? "none"
        )
    }
}

/// Upload/local marker position must be the projected clue point; drone stays metadata only.
public enum ClueUploadPosition {
    /// `terrain-raymarch`, `nadir`, or `flat` for the upload position log (no absolute coords).
    public static func source(
        terrainProjectionApplied: Bool,
        gimbalPitchDegrees: Double
    ) -> String {
        if !terrainProjectionApplied { return "flat" }
        if gimbalPitchDegrees.isFinite && gimbalPitchDegrees <= -89.9 { return "nadir" }
        return "terrain-raymarch"
    }

    /// Great-circle offset from drone to clue in metres (nil when inputs are non-finite).
    public static func offsetMeters(
        clueLatitude: Double,
        clueLongitude: Double,
        droneLatitude: Double,
        droneLongitude: Double
    ) -> Double? {
        RidGeometry.relativePosition(
            fromLatitude: droneLatitude,
            longitude: droneLongitude,
            toLatitude: clueLatitude,
            longitude: clueLongitude
        )?.distanceMeters
    }

    public static func logLine(
        id: String,
        source: String,
        offsetMeters: Double?
    ) -> String {
        let offset = offsetMeters.map {
            String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), $0)
        } ?? "nil"
        return "Clue upload position id=\(id) source=\(source) offsetM=\(offset)"
    }

    /// Privacy-safe post-save refine confirmation (no absolute coordinates).
    public static func terrainRefineAppliedLogLine(
        id: String,
        offsetMeters: Double?
    ) -> String {
        let offset = offsetMeters.map {
            String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), $0)
        } ?? "nil"
        return "Clue terrain refine applied id=\(id) offsetM=\(offset)"
    }

    /// True when the stored clue description already reflects a DEM hit (not flat-ground).
    public static func descriptionIndicatesTerrainApplied(_ description: String) -> Bool {
        description.localizedCaseInsensitiveContains("DEM used:")
            && !description.localizedCaseInsensitiveContains("DEM used: none")
    }

    /// Non-nadir clues with a usable height must not store/upload the drone as the clue.
    public static func isDistinctFromDrone(
        clueLatitude: Double,
        clueLongitude: Double,
        droneLatitude: Double,
        droneLongitude: Double,
        gimbalPitchDegrees: Double,
        minimumOffsetMeters: Double = 1.0
    ) -> Bool {
        if gimbalPitchDegrees.isFinite && gimbalPitchDegrees <= -89.9 { return true }
        guard let offset = offsetMeters(
            clueLatitude: clueLatitude,
            clueLongitude: clueLongitude,
            droneLatitude: droneLatitude,
            droneLongitude: droneLongitude
        ) else { return false }
        return offset >= minimumOffsetMeters
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

/// Immutable datum correction captured with a RID/waypoint frame; never consults later aircraft state.
public struct ClueDemAltitudeReference: Sendable, Equatable, Hashable {
    public let correctionMeters: Double
    public let scaleToMeters: Double

    public init(correctionMeters: Double, scaleToMeters: Double = 1.0) {
        self.correctionMeters = correctionMeters
        self.scaleToMeters = scaleToMeters
    }

    public func heightMeters(droneAltitudeMeters: Double, groundRaw: Double) -> Double? {
        guard droneAltitudeMeters.isFinite, droneAltitudeMeters > -1000,
              groundRaw.isFinite,
              correctionMeters.isFinite,
              scaleToMeters.isFinite, scaleToMeters > 0
        else { return nil }
        let height = droneAltitudeMeters - groundRaw * scaleToMeters - correctionMeters
        return height.isFinite && height > 0 && height <= 10_000 ? height : nil
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

    /// Clockwise true-north camera bearing from DJI's type-245 SEI azimuth.
    /// The SEI azimuth already references true north (2026-10-06 circular flights:
    /// adding magnetic declination put bearings ~12.9° off), so none is applied.
    public static func djiControllerCameraAzimuthDegrees(seiCameraAzimuthDegrees: Double?) -> Double? {
        guard let seiCameraAzimuthDegrees, seiCameraAzimuthDegrees.isFinite else { return nil }
        return RidHeading.normalized(seiCameraAzimuthDegrees - 90)
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
        gimbalAngleDegrees: Double,
        knownGroundElevationMeters: Double? = nil
    ) -> OperationalClueProjection {
        let demGround = knownGroundElevationMeters.flatMap { $0.isFinite ? $0 : nil }
        let groundAltitude = demGround ?? {
            if let droneAltitudeMeters, let aglMeters {
                return droneAltitudeMeters - aglMeters
            }
            return droneAltitudeMeters
        }()
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
        sampleElevationMeters: @escaping @Sendable (Double, Double) async -> OperationalTerrainSample?,
        sampleLocalElevationMeters: (@Sendable (Double, Double) async -> OperationalTerrainSample?)? = nil,
        timing: OperationalClueRayMarchTiming? = nil
    ) async -> OperationalClueProjection {
        let walkStarted = ContinuousClock.now
        defer { timing?.totalMs = elapsedMilliseconds(since: walkStarted) }
        let networkSample = sampleElevationMeters
        let localSample: @Sendable (Double, Double) async -> OperationalTerrainSample? = { latitude, longitude in
            if let sampleLocalElevationMeters {
                return await sampleLocalElevationMeters(latitude, longitude)
            }
            return await networkSample(latitude, longitude)
        }
        let referenceDEMSample: OperationalTerrainSample? = if let latitude = terrainReferenceLatitude,
                                                               let longitude = terrainReferenceLongitude,
                                                               latitude.isFinite,
                                                               longitude.isFinite,
                                                               (-90 ... 90).contains(latitude),
                                                               (-180 ... 180).contains(longitude) {
            await networkSample(latitude, longitude)
        } else {
            nil
        }
        let anchoredRelativeUp = relativeUpMeters.flatMap {
            $0.isFinite && $0 > 0 ? $0 : nil
        }
        let referenceGround = referenceDEMSample.flatMap { sample in
            sample.elevationMeters.isFinite ? sample.elevationMeters : nil
        }
        // SEI relative-up only anchors when the home/reference DEM sample hits. A miss must not
        // abort ray-march: fall through with AGL/ATO so local S1M at the aircraft can still run.
        let useRelativeUpAnchor = anchoredRelativeUp != nil && referenceGround != nil
        let effectiveAltitude = if useRelativeUpAnchor, let referenceGround, let anchoredRelativeUp {
            referenceGround + anchoredRelativeUp
        } else {
            droneAltitudeMeters
        }
        let effectiveAGL = useRelativeUpAnchor ? anchoredRelativeUp : aglMeters
        func flatEstimate(
            knownGround: Double?,
            reason: String,
            dem: OperationalTerrainSample? = nil
        ) -> OperationalClueProjection {
            var projection = project(
                droneLatitude: droneLatitude,
                droneLongitude: droneLongitude,
                droneAltitudeMeters: effectiveAltitude,
                headingDegrees: headingDegrees,
                aglMeters: effectiveAGL,
                gimbalAngleDegrees: gimbalAngleDegrees,
                knownGroundElevationMeters: knownGround
            )
            projection = OperationalClueProjection(
                latitude: projection.latitude,
                longitude: projection.longitude,
                altitudeMeters: projection.altitudeMeters,
                demSource: dem?.source,
                demResolutionMeters: dem?.horizontalResolutionMeters,
                demSampleStale: dem?.stale ?? false,
                flatFallbackReason: reason
            )
            return projection
        }
        // A vertical sightline can use DEM directly without heading or an AGL estimate.
        if gimbalAngleDegrees.isFinite, gimbalAngleDegrees <= -89.9,
           droneLatitude.isFinite, droneLongitude.isFinite,
           (-90 ... 90).contains(droneLatitude), (-180 ... 180).contains(droneLongitude) {
            if let ground = await networkSample(droneLatitude, droneLongitude), ground.elevationMeters.isFinite {
                return OperationalClueProjection(
                    latitude: droneLatitude, longitude: droneLongitude, altitudeMeters: ground.elevationMeters,
                    terrainProjectionApplied: true, demSource: ground.source,
                    demResolutionMeters: ground.horizontalResolutionMeters, demSampleStale: ground.stale
                )
            }
            return flatEstimate(
                knownGround: referenceGround,
                reason: "nadir DEM sample unavailable",
                dem: referenceDEMSample
            )
        }
        guard let droneAltitudeMeters = effectiveAltitude, droneAltitudeMeters.isFinite,
              let headingDegrees, headingDegrees.isFinite,
              let aglMeters = effectiveAGL, aglMeters.isFinite, aglMeters > 0
        else {
            return flatEstimate(
                knownGround: referenceGround,
                reason: "heading or AGL unavailable",
                dem: referenceDEMSample
            )
        }

        let angle = min(90, max(-90, gimbalAngleDegrees))
        guard angle < -0.1 else {
            return flatEstimate(
                knownGround: referenceGround,
                reason: "gimbal at or above horizon",
                dem: referenceDEMSample
            )
        }
        let tiltFromHorizon = max(0.1, min(90, abs(angle)))
        guard tiltFromHorizon < 89.9 else {
            return flatEstimate(
                knownGround: referenceGround,
                reason: "gimbal treated as nadir without DEM",
                dem: referenceDEMSample
            )
        }
        let slopeDown = tan(tiltFromHorizon * .pi / 180)
        guard slopeDown.isFinite, slopeDown > 0 else {
            return flatEstimate(
                knownGround: referenceGround,
                reason: "invalid sightline slope",
                dem: referenceDEMSample
            )
        }
        let flatDistance = aglMeters / slopeDown
        guard flatDistance.isFinite, flatDistance > 0 else {
            return flatEstimate(
                knownGround: referenceGround,
                reason: "non-positive flat distance",
                dem: referenceDEMSample
            )
        }

        // Only reuse the home/reference sample when the SEI altitude anchor is active.
        let droneDEMSample: OperationalTerrainSample? = if useRelativeUpAnchor, let referenceDEMSample {
            referenceDEMSample
        } else {
            await networkSample(droneLatitude, droneLongitude)
        }
        let droneDEM = (useRelativeUpAnchor ? referenceGround : nil) ?? droneDEMSample?.elevationMeters
        // ATO/AGL flat ground anchors foot-vs-metre DEM scale detection.
        let atoFlatGround = (useRelativeUpAnchor ? referenceGround : nil) ?? (droneAltitudeMeters - aglMeters)
        let scale: Double = if useRelativeUpAnchor {
            1
        } else {
            inferDEMScaleToMeters(
                droneAltitudeMeters: droneAltitudeMeters,
                knownGroundMeters: atoFlatGround,
                droneDEM: droneDEM
            )
        }
        let knownDemGround = (useRelativeUpAnchor ? referenceGround : nil) ?? droneDEM.map { $0 * scale }
        let flatGround = knownDemGround ?? atoFlatGround
        if droneDEMSample == nil, knownDemGround == nil {
            return flatEstimate(knownGround: knownDemGround, reason: "DEM sample unavailable at aircraft")
        }
        // DEM under the aircraft + AGL puts the ray in the DEM vertical datum by construction.
        // Telemetry/RID altitudes may be ellipsoidal or takeoff-relative (geoid offset ~tens of m).
        let demUnderDroneM = knownDemGround
        let startAltitudeMeters: Double
        let startDatum: String
        if let demUnderDroneM {
            startAltitudeMeters = demUnderDroneM + aglMeters
            startDatum = "dem+agl"
        } else if useRelativeUpAnchor, let referenceGround, let anchoredRelativeUp {
            startAltitudeMeters = referenceGround + anchoredRelativeUp
            startDatum = "sei-dem+relative-up"
        } else {
            startAltitudeMeters = droneAltitudeMeters
            startDatum = "telemetry-altitude"
        }
        timing?.startAltM = startAltitudeMeters
        timing?.demUnderDroneM = demUnderDroneM ?? .nan
        timing?.aglM = aglMeters
        timing?.startDatum = startDatum
        if let demUnderDroneM, startAltitudeMeters - demUnderDroneM < 1.0 {
            return flatEstimate(
                knownGround: knownDemGround,
                reason: "implausible-terrain-hit",
                dem: droneDEMSample
            )
        }
        // Fine steps near the aircraft, then grow to a coarse step. Cap range from the
        // flat-ground estimate so shallow sightlines still reach distant rising terrain.
        let usingLocalDEM = droneDEMSample?.source?.hasPrefix("usgs-geotiff-local-") == true
        let absoluteCap = usingLocalDEM ? 5_000.0 : 2_500.0
        let minimumSearch = usingLocalDEM ? 1_500.0 : 800.0
        let maximumDistance = min(absoluteCap, max(flatDistance * 12.0, minimumSearch))
        let coarseStep = usingLocalDEM ? 200.0 : 100.0
        let fineStep = max(2.0, aglMeters / 4.0)
        let fineZone = max(fineStep * 4.0, aglMeters * 4.0)
        timing?.maxRangeM = maximumDistance
        timing?.coarseStepM = coarseStep
        let stepStarted = ContinuousClock.now

        var previousDistance = 0.0
        var previousGround = knownDemGround ?? flatGround
        var coverageEnded = false
        var currentStep = fineStep
        var distance = fineStep
        var sampleIndex = 0
        while distance <= maximumDistance + 0.001 {
            if Task.isCancelled {
                timing?.stepMs = elapsedMilliseconds(since: stepStarted)
                return flatEstimate(
                    knownGround: knownDemGround,
                    reason: "terrain refine cancelled",
                    dem: droneDEMSample
                )
            }
            let candidate = destinationPoint(
                latitude: droneLatitude,
                longitude: droneLongitude,
                bearingDegrees: headingDegrees,
                distanceMeters: distance
            )
            let candidateDEMSample: OperationalTerrainSample?
            if usingLocalDEM {
                candidateDEMSample = await localSample(candidate.latitude, candidate.longitude)
            } else {
                candidateDEMSample = await networkSample(candidate.latitude, candidate.longitude)
            }
            timing?.sampleCount += 1
            sampleIndex += 1
            if usingLocalDEM, candidateDEMSample?.source?.hasPrefix("usgs-geotiff-local-") != true {
                coverageEnded = true
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
                distance += currentStep
                if distance < fineZone {
                    currentStep = min(coarseStep, max(fineStep, currentStep * 1.5))
                } else {
                    currentStep = coarseStep
                }
                continue
            }
            let rayAltitude = startAltitudeMeters - slopeDown * distance
            if rayAltitude <= ground {
                // First sample already underground: datum/start error, not a real near-field hit.
                if sampleIndex <= 1 || previousDistance <= 0.001 {
                    timing?.stepMs = elapsedMilliseconds(since: stepStarted)
                    return flatEstimate(
                        knownGround: knownDemGround,
                        reason: "implausible-terrain-hit",
                        dem: droneDEMSample
                    )
                }
                var lowDistance = previousDistance
                var lowGround = previousGround
                var highDistance = distance
                var highPoint = candidate
                var highGround = ground
                // Bisect between last-above and first-below (~0.8 m with an 200 m bracket).
                for _ in 0 ..< 8 {
                    if Task.isCancelled {
                        timing?.stepMs = elapsedMilliseconds(since: stepStarted)
                        return flatEstimate(
                            knownGround: knownDemGround,
                            reason: "terrain refine cancelled",
                            dem: droneDEMSample
                        )
                    }
                    let midDistance = (lowDistance + highDistance) / 2
                    let midPoint = destinationPoint(
                        latitude: droneLatitude,
                        longitude: droneLongitude,
                        bearingDegrees: headingDegrees,
                        distanceMeters: midDistance
                    )
                    let midDEM: Double?
                    if usingLocalDEM {
                        midDEM = await localSample(midPoint.latitude, midPoint.longitude)?.elevationMeters
                    } else {
                        midDEM = await networkSample(midPoint.latitude, midPoint.longitude)?.elevationMeters
                    }
                    timing?.sampleCount += 1
                    let midGround = normalizedDEMGroundMeters(
                        candidateDEM: midDEM,
                        droneDEM: droneDEM,
                        flatGroundMeters: flatGround,
                        scaleToMeters: scale
                    ) ?? ((lowGround + highGround) / 2)
                    if startAltitudeMeters - slopeDown * midDistance <= midGround {
                        highDistance = midDistance
                        highPoint = midPoint
                        highGround = midGround
                    } else {
                        lowDistance = midDistance
                        lowGround = midGround
                    }
                }
                timing?.stepMs = elapsedMilliseconds(since: stepStarted)
                // Reject near-drone hits that flat geometry cannot explain (slope can shorten,
                // but not to a few metres when flat range is tens of metres).
                if highDistance < flatDistance * 0.3 {
                    return flatEstimate(
                        knownGround: knownDemGround,
                        reason: "implausible-terrain-hit",
                        dem: droneDEMSample
                    )
                }
                timing?.hit = true
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
            if distance < fineZone {
                currentStep = min(coarseStep, max(fineStep, currentStep * 1.5))
            } else {
                currentStep = coarseStep
            }
            distance += currentStep
        }
        timing?.stepMs = elapsedMilliseconds(since: stepStarted)
        return flatEstimate(
            knownGround: knownDemGround,
            reason: coverageEnded
                ? "local DEM coverage ended before ray intersection"
                : "ray did not intersect DEM within search distance",
            dem: droneDEMSample
        )
    }

    private static func elapsedMilliseconds(since start: ContinuousClock.Instant) -> Double {
        let duration = start.duration(to: .now)
        return Double(duration.components.seconds) * 1_000
            + Double(duration.components.attoseconds) / 1e15
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
        case crosshairOnly
        case msl
        case reference
    }

    public static func nextMode(_ mode: DisplayMode) -> DisplayMode {
        switch mode {
        case .crosshairOnly: .msl
        case .msl: .reference
        case .reference: .crosshairOnly
        }
    }

    public struct Sample: Sendable, Equatable {
        public let latitude: Double?
        public let longitude: Double?
        public let elevationFeet: Int
        public let demResolutionMeters: Int?

        public let assumedNadir: Bool
        public let pointAolFeet: Int?
        public init(elevationFeet: Int, demResolutionMeters: Int?, assumedNadir: Bool = false, pointAolFeet: Int? = nil, latitude: Double? = nil, longitude: Double? = nil) {
            self.latitude = latitude
            self.longitude = longitude
            self.assumedNadir = assumedNadir
            self.pointAolFeet = pointAolFeet
            self.elevationFeet = elevationFeet
            self.demResolutionMeters = demResolutionMeters
        }
    }

    public static func displayText(
        _ sample: Sample?,
        referenceElevationFeet: Int? = nil,
        mode: DisplayMode = .msl
    ) -> String {
        guard let sample else { return mode == .reference ? "--' REF" : "--' MSL" }
        if mode == .reference && referenceElevationFeet == nil { return "--' REF" }
        let extras = (sample.pointAolFeet.map { " · AOL \($0)'" } ?? "") + (sample.assumedNadir ? " · Assumed ↓90°" : "")
        let suffix = sample.demResolutionMeters.map { "\($0)m DEM" } ?? "USGS DEM"
        if mode == .reference, let referenceElevationFeet {
            let delta = sample.elevationFeet - referenceElevationFeet
            let signedDelta = delta >= 0 ? "+\(delta)" : "\(delta)"
            return "\(signedDelta)' REF · \(suffix)\(extras)"
        }
        return "\(sample.elevationFeet)' MSL · \(suffix)\(extras)"
    }

    public static func readout(
        _ sample: Sample?, referenceElevationFeet: Int? = nil,
        mode: DisplayMode, coordinateFormat: OperationalCoordinateDisplayFormat,
        showCoordinates: Bool
    ) -> String? {
        guard mode != .crosshairOnly, let sample else { return nil }
        let elevation = displayText(sample, referenceElevationFeet: referenceElevationFeet, mode: mode)
        if showCoordinates, !sample.assumedNadir, let latitude = sample.latitude, let longitude = sample.longitude {
            return elevation + "\n" + OperationalCoordinateFormatter.format(latitude: latitude, longitude: longitude, as: coordinateFormat)
        }
        return elevation
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

public extension OperationalClueRecord {
    func shareReport(format: OperationalCoordinateDisplayFormat) -> String {
        let location = OperationalCoordinateFormatter.format(latitude: clueLatitude, longitude: clueLongitude, as: format)
        return "\(title)\nAircraft: \(designator)\nCaptured: \(capturedAt.formatted(date: .abbreviated, time: .standard))\nLocation: \(location) (\(format.label))\n\n\(publishedDescription)"
    }
}
