import XCTest
@testable import R2CCore

/// Nov 16 watch test fixes at the Swift boundary, against the real CBtrxBearing library:
/// STEER bit 3 advisory with roll correction (0x0109), focus with strength trail and
/// strongest-here, the fine layer while low and slow, RSSI range on the ray, HEALTH 46 bytes,
/// the camera centre crosshair and the shared wording. Android: BtrxWatchFixesMapTest.kt.
final class BtrxWatchFixesTests: XCTestCase {
    typealias P = BtrxProtocol
    static let wallBaseMs: Int64 = 1_790_000_000_000
    final class Clock: @unchecked Sendable { var us: Int64 = 100_000_000 }

    private func meta(_ clock: Clock) -> BtrxRadioMeta {
        BtrxRadioMeta(fromNode: 2, toNode: 1, rxRssi: -60, rxSnr: 8, arrivalMs: Self.wallBaseMs + clock.us / 1000, backlog: false)
    }

    private func frame(_ type: Int, _ payload: [UInt8]) -> P.Frame {
        P.Frame(version: 1, type: type, seq: 1, podId: 0, flags: 0, payload: payload)
    }

    /// Hover 20 m up (low and slow), yawing; a watch 40 m east; every STEER carries bit 3
    /// (0x0109: roll > 10 deg) with roll 12 deg starboard down.
    func testTiltedLowHoverIsUsedFocusedAndFine() throws {
        let clock = Clock()
        let layer = BtrxBearingLayer(monoUs: { clock.us }, wallMs: { Self.wallBaseMs + clock.us / 1000 },
                                     incidentKey: { [UInt8](repeating: 7, count: 16) })
        var snaps: [BtrxBearingSnapshot] = []
        layer.onSnapshot = { if let s = $0 { snaps.append(s) } }
        layer.steeringDesignator = "Drone 1"
        XCTAssertTrue(layer.start(settings: BtrxBearingSettings(), trace: false, linkUp: true))
        var seq = 0
        var focused = false
        for step in 0..<900 { // 90 s
            let wall = Self.wallBaseMs + clock.us / 1000
            let yaw = (Double(step) * 0.6).truncatingRemainder(dividingBy: 360)
            layer.onYaw(designator: "Drone 1", yawTrueDeg: yaw, decodedAtWallMs: wall)
            if step % 10 == 0 {
                layer.noteAgl(designator: "Drone 1", aglM: 20)
                layer.onPosition(designator: "Drone 1", lat: 37.0, lon: -122.0, fixWallMs: wall)
            }
            if step % 2 == 0 { layer.tick { _, _ in false } }
            if step % 5 == 4 {
                let rel = (90.0 - yaw) * .pi / 180
                // Measured port-stbd: true -6 sin(rel) dB, minus the roll bias (k 0.10 x 12 deg).
                let delta = -6 * sin(rel) - 0.10 * 12
                let s = P.Steer(steerSeq: seq & 0xFF, flags: P.STEER_F_TARGET | P.STEER_F_IMU_OK | P.STEER_F_GATED,
                                windowEndMs: UInt32(truncatingIfNeeded: clock.us / 1000), ageMs: 0, srcId: 0x0202,
                                rssiPort: Int((-58 + delta / 2).rounded()), rssiStbd: Int((-58 - delta / 2).rounded()), peakRssi: -54,
                                hitsPort: 5, hitsStbd: 5, companyId: 0, devClass: 2, addrType: 0, macLsbFirst: [9, 8, 7, 6, 5, 4],
                                rollX2: 24, pitchX2: 0)
                seq += 1
                layer.onFrame(frame(P.STEER, P.steerPayload(s)), meta: meta(clock), fromPairedPod: true, fromDeskPod: false)
            }
            if step == 600, let key = snaps.last?.sources.first?.key {
                layer.setFocus(key); focused = true
            }
            clock.us += 100_000
        }
        XCTAssertTrue(focused)
        let last = try XCTUnwrap(snaps.last)
        XCTAssertGreaterThan(last.stats.used, 50, "bit 3 is advice on 0x0109: \(BtrxBearingText.countsLine(last.stats))")
        let src = try XCTUnwrap(last.sources.first)
        XCTAssertEqual(last.focusKey, src.key)
        XCTAssertFalse(last.trail.isEmpty, "strength trail of the focused source")
        XCTAssertNotNil(src.strongestLat)
        XCTAssertNotNil(src.maxRssiDbm)
        XCTAssertNotNil(src.lastRssiDbm)
        XCTAssertFalse(src.ownSuspect, "-58 dBm hovering is not own aircraft")
        let fine = try XCTUnwrap(last.fine, "fine layer while low and slow")
        XCTAssertEqual(fine.rgba.count, fine.cells * fine.cells * 4)
        XCTAssertGreaterThan(fine.north, fine.south)
        if let ray = src.latestRay {
            XCTAssertNotNil(ray.rangeM)
            XCTAssertLessThan(ray.lengthM, 400, "wedge from the RSSI range, not the fixed 400 m")
        }
        layer.clearOwnSuspect(src.key)
        layer.setFocus(0)
        for _ in 0..<5 { layer.tick { _, _ in false }; clock.us += 200_000 }
        XCTAssertEqual(snaps.last?.focusKey, 0)
        XCTAssertTrue(snaps.last?.trail.isEmpty ?? false)
        layer.stop()
    }

    func testHealth46ScanLostFromBuild010A() throws {
        var p = [UInt8](repeating: 0, count: 46)
        P.putU32(&p, 30, 7)
        p[43] = UInt8(P.EXCL_OWN)
        p[44] = 0x2A; p[45] = 0x01
        let h = try P.parseHealth(p)
        XCTAssertEqual(h.scanLostPerS, 298)
        XCTAssertEqual(h.phoneLost, 7)
        XCTAssertEqual(h.ext?.excludeFlags, P.EXCL_OWN)
        XCTAssertNil(try P.parseHealth(Array(p.prefix(44))).scanLostPerS)
        XCTAssertEqual(try P.parseHealth(Array(p.prefix(44))).ext, h.ext)
        XCTAssertNil(try P.parseHealth(Array(p.prefix(45))).scanLostPerS)
        XCTAssertEqual(BtrxDeskPodText.scanLostLine(298), "Drone Pod scan reports lost: 298 per second")
    }

    func testCrosshairIsTheOpticalAxisGroundPoint() throws {
        let m = 6378137.0
        func east(_ lon: Double) -> Double { (lon + 121) * .pi / 180 * m * cos(39 * .pi / 180) }
        func north(_ lat: Double) -> Double { (lat - 39) * .pi / 180 * m }
        let nadir = CameraFootprintGeometry.project(.init(latitude: 39, longitude: -121, launchLatitude: 39, launchLongitude: -121,
                                                          height: 100, azimuth: 0, tilt: -90, horizontalFov: 90, verticalFov: 60))
        let c0 = try XCTUnwrap(nadir.center)
        XCTAssertEqual(north(c0.latitude), 0, accuracy: 0.01)
        XCTAssertEqual(east(c0.longitude), 0, accuracy: 0.01)
        let oblique = CameraFootprintInput(latitude: 39, longitude: -121, launchLatitude: 39, launchLongitude: -121,
                                           height: 100, azimuth: 90, tilt: -45, horizontalFov: 60, verticalFov: 40)
        XCTAssertEqual(east(try XCTUnwrap(CameraFootprintGeometry.project(oblique).center).longitude), 100, accuracy: 0.05)
        let terrain = CameraFootprintGeometry.project(oblique, elevation: { _, _ in 900 })
        XCTAssertEqual(east(try XCTUnwrap(terrain.center).longitude), 100, accuracy: 1)
        let up = CameraFootprintGeometry.project(.init(latitude: 39, longitude: -121, launchLatitude: 39, launchLongitude: -121,
                                                       height: 100, azimuth: 0, tilt: 5, horizontalFov: 60, verticalFov: 40))
        XCTAssertNil(up.center)
        XCTAssertNotNil(cameraFootprintDisplayOutline(CameraFootprintDrawing(input: oblique, terrain: nil, liveCorners: true)).center)
        let c = CameraFootprintVertex(latitude: 39, longitude: -121, clipped: false, corner: false)
        let r = cameraCrosshairToTarget(c, 39 - 50 / m * 180 / .pi, -121)
        XCTAssertEqual(r.distanceM, 50, accuracy: 0.2)
        XCTAssertEqual(r.bearingDeg, 180, accuracy: 0.01)
        XCTAssertEqual(BtrxBearingText.crosshairToTarget(distanceM: 50.2, bearingDeg: 180), "50 m · 180°")
        XCTAssertEqual(BtrxBearingText.crosshairToTarget(distanceM: 12, bearingDeg: 359.7), "12 m · 0°")
    }

    func testInspectCardLinesMatchAndroid() {
        let est = BtrxBearingEstimate(lat: 39.0012345, lon: -121.0006789, axisMajor1sM: 6, axisMinor1sM: 4, axisMajorDeg: 30,
                                      ambiguous: false, secondLat: 0, secondLon: 0, secondMass: 0)
        let s = BtrxBearingSource(index: 0, key: 1, shortID: "Q3x9Kd", provisional: false, devClass: 2, rgb: 0x2196F3, flags: 0,
                                  firstHeardUs: 0, lastHeardUs: 5_000_000, reportsIn: 40, reportsUsed: 30, reportsInWindow: 25,
                                  peakAlpha: 0.6, estimate: est, latestRay: nil, srcId: 50703, lastRssiDbm: -61, maxRssiDbm: -52, pinned: true)
        XCTAssertEqual(BtrxBearingText.inspectLines(s, nowUs: 65_000_000), [
            "Source Q3x9Kd (id 50703) · watch",
            "Estimate 39.001235, -121.000679",
            "Uncertainty ±12 m (2σ)",
            "Reports 25 in the fade window (30 used of 40)",
            "Last heard 1 min ago",
            "Peak RSSI -52 dBm (latest -61 dBm)",
        ])
    }
}
