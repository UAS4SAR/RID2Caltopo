import Foundation
import Testing
@testable import R2CCore

private func report(heading: Double? = 273.2, telemetry: OperationalClueReportTelemetry? = nil,
                    format: OperationalCoordinateDisplayFormat = .decimal) -> String {
    OperationalClueDescription.build(description: "time: 09:17:27", designator: "1SAR7",
        projection: .init(latitude: 39, longitude: -75, altitudeMeters: 102,
                          terrainProjectionApplied: true, demSource: "usgs-geotiff-local-1m", demResolutionMeters: 1),
        format: format, heading: .init(degrees: heading, sourceLabel: "Camera yaw"),
        gimbalAngleDegrees: -45, aglMeters: 25, atoMeters: 40,
        projectionHeight: .init(meters: 25, sourceLabel: "fresh AGL"),
        aircraftPositionSource: "RID", distanceMeters: 0, telemetry: telemetry)
}

@Test func clueCaptureDescriptionMatchesSharedAndroidFixture() throws {
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    let expected = try String(contentsOf: root.appendingPathComponent("test-fixtures/clue-description/capture.txt"), encoding: .utf8)
        .trimmingCharacters(in: .newlines)
    #expect(report() == "time: 09:17:27\n\n" + expected)
    #expect(report(heading: 359.96).contains("Camera Azimuth: 0.0°"))
    #expect(report(heading: nil).contains("Camera Azimuth: N/A"))
}

@Test func clueTelemetryIncludesCapturedDronePositionSeparateFromClue() {
    let observation = RidObservation(source: .bluetoothLegacy, aircraftId: "RID123", receivedAt: .distantPast,
        latitude: 39.1, longitude: -75.1, altitudeMeters: 120, headingDegrees: 273.2, speedMetersPerSecond: 10)
    var telemetry = OperationalClueReportTelemetry(observation: observation, aglMeters: 25, atoMeters: 40)
    telemetry.rawTiltDegrees = -90
    telemetry.calibratedTiltDegrees = -45
    telemetry.rawAzimuthDegrees = 180
    telemetry.magneticDeclinationDegrees = 13.2
    telemetry.horizontalFovDegrees = 70
    telemetry.verticalFovDegrees = 40
    telemetry.source = "dji-sei-245"
    telemetry.confidence = 0.95
    telemetry.timestampMicroseconds = 123456
    telemetry.seiLatitude = 39.2
    telemetry.seiLongitude = -75.2
    telemetry.relativeUpMeters = 40
    telemetry.referenceLatitude = 39.3
    telemetry.referenceLongitude = -75.3
    telemetry.referenceAltitudeMeters = 80
    let expected = """
    Designator: 1SAR7
    Telemetry:
      Drone position (Decimal): 39.10000,-75.10000 alt 394'
      Heading: 273.2°
      AGL: 82'
      ATO: 131'
      Ground speed: 19.4 kt
      Track: 273.2°
      Camera tilt: -45.0° (raw -90.0°)
      DJI raw azimuth encoder: 180.0°
      Magnetic declination applied: +13.2°
      Horizontal FOV: 70.00°
      Vertical FOV: 40.00°
      Telemetry source: dji-sei-245 (confidence=0.95)
      Telemetry timestamp(us): 123456
      DJI SEI aircraft position: 39.2000000, -75.2000000 relative-up 131.2'
      Clue aircraft position source: DJI SEI local displacement
      DJI SEI home/reference: 39.3000000, -75.3000000 datum alt 262.5'
    """
    #expect(OperationalClueDescription.telemetrySummary(telemetry, designator: "1SAR7", format: .decimal) == expected)
    #expect(report(telemetry: telemetry) == report() + "\n\n" + expected)
    let usng = report(telemetry: telemetry, format: .usng)
    #expect(usng.contains("  Decimal: 39.000000, -75.000000"))
    #expect(usng.contains("  Drone position (Decimal): 39.100000, -75.100000"))
    #expect(usng.contains("  Drone position (USNG): 18S "))
}

@Test func clueDescriptionDoesNotInventMissingTelemetry() {
    let observation = RidObservation(source: .bluetoothLegacy, aircraftId: "RID123", receivedAt: .distantPast,
                                    latitude: 39, longitude: -75)
    let text = report(telemetry: .init(observation: observation))
    #expect(text.contains("Drone position (Decimal): 39.00000,-75.00000 alt N/A"))
    #expect(text.contains("  Heading: N/A\n  AGL: N/A\n  ATO: N/A"))
    #expect(!text.contains("Ground speed:"))
    #expect(!text.contains("DJI SEI aircraft position:"))
    #expect(!report().contains("Telemetry:"))
}
