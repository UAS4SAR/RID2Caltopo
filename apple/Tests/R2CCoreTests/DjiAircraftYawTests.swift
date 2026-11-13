import Testing
@testable import R2CCore

/// Values from the 2026-11-12 20:18 PT indoor M4TD yaw test (shared C decoder tests use the same raw words).
@Test func djiAircraftYawStartHeadingIsCameraYawMinus90() throws {
    let bearing = try #require(OperationalClueGeometry.djiAircraftYawTrueNorthDegrees(seiCameraAzimuthDegrees: 38.7905055))
    #expect(abs(bearing - 308.7905055) < 1e-7)
}

@Test func djiAircraftYawCrossingPlusMinus180StaysContinuous() throws {
    let before = try #require(OperationalClueGeometry.djiAircraftYawTrueNorthDegrees(seiCameraAzimuthDegrees: 178.2200987))
    let after = try #require(OperationalClueGeometry.djiAircraftYawTrueNorthDegrees(seiCameraAzimuthDegrees: 180.5339382))
    let signed = try #require(OperationalClueGeometry.djiAircraftYawTrueNorthDegrees(seiCameraAzimuthDegrees: -179.4660618))
    #expect(abs(before - 88.2200987) < 1e-7)
    #expect(abs(after - 90.5339382) < 1e-7)
    #expect(abs(signed - 90.5339382) < 1e-7)
    #expect(abs((after - before) - 2.3138395) < 1e-7)
}

@Test func djiAircraftYawWrapsAtTrueNorthAndMatchesTheExistingHelper() {
    #expect(OperationalClueGeometry.djiAircraftYawTrueNorthDegrees(seiCameraAzimuthDegrees: 90) == 0)
    for raw in [0.0, 38.79, 127.6, 213.4, 309.8, 397.5, -146.62] {
        #expect(OperationalClueGeometry.djiAircraftYawTrueNorthDegrees(seiCameraAzimuthDegrees: raw)
            == OperationalClueGeometry.djiControllerCameraAzimuthDegrees(seiCameraAzimuthDegrees: raw))
    }
    #expect(OperationalClueGeometry.djiAircraftYawTrueNorthDegrees(seiCameraAzimuthDegrees: nil) == nil)
    #expect(OperationalClueGeometry.djiAircraftYawTrueNorthDegrees(seiCameraAzimuthDegrees: .nan) == nil)
}
