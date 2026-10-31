import Foundation

/// Capture-time values used by the Android-compatible published clue report.
public struct OperationalClueReportTelemetry: Sendable {
    public let observation: RidObservation
    public var headingDegrees: Double?
    public var aglMeters: Double?
    public var atoMeters: Double?
    public var verticalRateFeetPerMinute: Double?
    public var rawTiltDegrees: Double?
    public var calibratedTiltDegrees: Double?
    public var rawAzimuthDegrees: Double?
    public var horizontalFovDegrees: Double?
    public var verticalFovDegrees: Double?
    public var source: String?
    public var confidence: Double?
    public var timestampMicroseconds: Int64?
    public var seiLatitude: Double?
    public var seiLongitude: Double?
    public var relativeUpMeters: Double?
    public var referenceLatitude: Double?
    public var referenceLongitude: Double?
    public var referenceAltitudeMeters: Double?

    public init(observation: RidObservation, aglMeters: Double? = nil, atoMeters: Double? = nil) {
        self.observation = observation
        self.headingDegrees = observation.headingDegrees
        self.aglMeters = aglMeters
        self.atoMeters = atoMeters
    }
}

public enum OperationalClueDescription {
    private static let feetPerMeter = 3.28084
    private static func number(_ value: Double?, _ format: String) -> String {
        guard let value, value.isFinite else { return "N/A" }
        if format.hasSuffix("'") { return DisplayUnits.current.feet(value, decimals: format.contains(".1") ? 1 : 0) }
        return String(format: format, locale: Locale(identifier: "en_US_POSIX"), value)
    }
    private static func position(_ lat: Double, _ lon: Double, _ format: OperationalCoordinateDisplayFormat) -> String {
        OperationalCoordinateFormatter.format(latitude: lat, longitude: lon, as: format)
            .replacingOccurrences(of: "loc:", with: "")
    }
    private static func decimal(_ lat: Double, _ lon: Double) -> String {
        "\(number(lat, "%.6f")), \(number(lon, "%.6f"))"
    }

    public static func build(description: String, designator: String,
                             projection: OperationalClueProjection,
                             format: OperationalCoordinateDisplayFormat,
                             heading: OperationalClueHeadingSelection,
                             gimbalAngleDegrees: Double,
                             aglMeters: Double?, atoMeters: Double?,
                             projectionHeight: OperationalClueProjectionHeightSelection,
                             aircraftPositionSource: String,
                             distanceMeters: Double?,
                             telemetry: OperationalClueReportTelemetry?) -> String {
        var lines = ["Projected clue location:",
                     "  Position (\(format.label)): \(position(projection.latitude, projection.longitude, format)) alt \(number(projection.altitudeMeters.map { $0 * feetPerMeter }, "%.0f'"))"]
        if format != .decimal { lines.append("  Decimal: \(decimal(projection.latitude, projection.longitude))") }
        let azimuth = RidHeading.normalized(heading.degrees).map {
            number(($0 * 10).rounded(.toNearestOrEven).truncatingRemainder(dividingBy: 3600) / 10, "%.1f°")
        } ?? "N/A"
        lines += ["  Camera Azimuth: \(azimuth)",
                  "  Heading source: \(heading.sourceLabel ?? "N/A")",
                  "  Gimbal angle at capture: \(number(gimbalAngleDegrees, "%.1f°"))",
                  "  AGL: \(number(aglMeters.map { $0 * feetPerMeter }, "%.0f'"))",
                  "  Projection height: \(number(projectionHeight.meters * feetPerMeter, "%.0f'")) (\(projectionHeight.sourceLabel))",
                  "  Aircraft position source: \(aircraftPositionSource)",
                  demSummary(projection),
                  "  ATO: \(number(atoMeters.map { $0 * feetPerMeter }, "%.0f'"))",
                  "  Distance to clue: \(number(distanceMeters.map { $0 * feetPerMeter }, "%.0f'"))"]
        if let telemetry {
            lines += ["", telemetrySummary(telemetry, designator: designator, format: format)]
        }
        let notes = description.trimmingCharacters(in: .whitespacesAndNewlines)
        let body = (notes.isEmpty ? "" : notes + "\n\n") + lines.joined(separator: "\n")
        return appendFlatSurfaceNote(body, terrainApplied: projection.terrainProjectionApplied, gimbalAngleDegrees: gimbalAngleDegrees)
    }

    public static let flatSurfaceLocationNote =
        "Location approximated from a flat-surface estimate (terrain data not yet loaded)."

    /// Straight down. Matches the projection cutoff so a nadir sightline is not called an estimate.
    public static func isNadirGimbal(_ gimbalAngleDegrees: Double) -> Bool {
        gimbalAngleDegrees.isFinite && gimbalAngleDegrees <= -89.9
    }

    /// Published point that was not DEM-refined. Nadir stays unmarked.
    public static func appendFlatSurfaceNote(_ description: String, terrainApplied: Bool, gimbalAngleDegrees: Double) -> String {
        if terrainApplied || isNadirGimbal(gimbalAngleDegrees) { return description }
        if description.contains(flatSurfaceLocationNote) { return description }
        let trimmed = description.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? flatSurfaceLocationNote : trimmed + "\n\n" + flatSurfaceLocationNote
    }

    /// After a late DEM hit, drop the flat-surface note and refresh DEM / decimal position lines.
    public static func applyingTerrainProjection(
        _ description: String,
        projection: OperationalClueProjection,
        format: OperationalCoordinateDisplayFormat = .decimal
    ) -> String {
        var text = description.replacingOccurrences(of: "\n\n" + flatSurfaceLocationNote, with: "")
        text = text.replacingOccurrences(of: flatSurfaceLocationNote, with: "")
        let demLine = demSummary(projection)
        if let range = text.range(of: #"  DEM used:.*$"#, options: .regularExpression) {
            text.replaceSubrange(range, with: demLine)
        } else if !text.contains(demLine) {
            text = text.trimmingCharacters(in: .whitespacesAndNewlines) + "\n" + demLine
        }
        let decimal = "  Decimal: \(decimal(projection.latitude, projection.longitude))"
        if let range = text.range(of: #"  Decimal: .+$"#, options: .regularExpression) {
            text.replaceSubrange(range, with: decimal)
        }
        let position = "  Position (\(format.label)): \(self.position(projection.latitude, projection.longitude, format)) alt \(number(projection.altitudeMeters.map { $0 * feetPerMeter }, "%.0f'"))"
        if let range = text.range(of: #"  Position \([^)]+\): .+$"#, options: .regularExpression) {
            text.replaceSubrange(range, with: position)
        }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func demSummary(_ projection: OperationalClueProjection) -> String {
        guard projection.terrainProjectionApplied else { return "  DEM used: none (flat-ground estimate)" }
        let source: String
        if projection.demSource?.hasPrefix("usgs-geotiff-local-") == true { source = "local USGS GeoTIFF" }
        else if projection.demSource == "usgs-epqs" { source = "USGS elevation service" }
        else { source = projection.demSource.flatMap { $0.isEmpty ? nil : $0 } ?? "USGS elevation data" }
        let resolution = projection.demResolutionMeters.flatMap { $0.isFinite && $0 > 0 ? " (\(DisplayUnits.current.length($0)) grid)" : nil } ?? " (resolution not reported)"
        return "  DEM used: \(source)\(resolution)\(projection.demSampleStale ? ", cached" : "")"
    }

    public static func telemetrySummary(_ data: OperationalClueReportTelemetry, designator: String,
                                        format: OperationalCoordinateDisplayFormat) -> String {
        let drone = data.observation
        var lines = ["Designator: \(designator)", "Telemetry:",
                     "  Drone position (\(format.label)): \(position(drone.latitude, drone.longitude, format)) alt \(number(drone.altitudeMeters.map { $0 * feetPerMeter }, "%.0f'"))"]
        if format != .decimal { lines.append("  Drone position (Decimal): \(decimal(drone.latitude, drone.longitude))") }
        lines += ["  Heading: \(number(data.headingDegrees, "%.1f°"))",
                  "  AGL: \(number(data.aglMeters.map { $0 * feetPerMeter }, "%.0f'"))",
                  "  ATO: \(number(data.atoMeters.map { $0 * feetPerMeter }, "%.0f'"))"]
        if let rate = data.verticalRateFeetPerMinute, rate.isFinite { lines.append("  Vertical rate: \(DisplayUnits.current.climb(rate * 0.3048 / 60))") }
        if let speed = drone.speedMetersPerSecond, speed.isFinite { lines.append("  Ground speed: \(DisplayUnits.current.speed(speed))") }
        if let track = drone.headingDegrees, track.isFinite { lines.append("  Track: \(number(track, "%.1f°"))") }
        if let raw = data.rawTiltDegrees, raw.isFinite {
            if let calibrated = data.calibratedTiltDegrees, calibrated.isFinite {
                lines.append("  Camera tilt: \(number(calibrated, "%.1f°")) (raw \(number(raw, "%.1f°")))")
            } else { lines.append("  Gimbal pitch: \(number(raw, "%.1f°"))") }
        }
        if let raw = data.rawAzimuthDegrees, raw.isFinite { lines.append("  DJI raw azimuth encoder: \(number(raw, "%.1f°"))") }
        if let fov = data.horizontalFovDegrees, fov.isFinite { lines.append("  Horizontal FOV: \(number(fov, "%.2f°"))") }
        if let fov = data.verticalFovDegrees, fov.isFinite { lines.append("  Vertical FOV: \(number(fov, "%.2f°"))") }
        if let source = data.source { lines.append("  Telemetry source: \(source) (confidence=\(data.confidence.map { number($0, "%.2f") } ?? "n/a"))") }
        if let timestamp = data.timestampMicroseconds { lines.append("  Telemetry timestamp(us): \(timestamp)") }
        if let lat = data.seiLatitude, let lon = data.seiLongitude {
            lines.append("  DJI SEI aircraft position: \(number(lat, "%.7f")), \(number(lon, "%.7f")) relative-up \(number(data.relativeUpMeters.map { $0 * feetPerMeter }, "%.1f'"))")
            lines.append("  Clue aircraft position source: DJI SEI local displacement")
        }
        if let lat = data.referenceLatitude, let lon = data.referenceLongitude {
            lines.append("  DJI SEI home/reference: \(number(lat, "%.7f")), \(number(lon, "%.7f")) datum alt \(number(data.referenceAltitudeMeters.map { $0 * feetPerMeter }, "%.1f'"))")
        }
        return lines.joined(separator: "\n")
    }
}
