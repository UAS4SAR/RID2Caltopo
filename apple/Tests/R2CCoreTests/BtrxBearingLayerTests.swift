import XCTest
@testable import R2CCore

/// The Swift boundary of the BTRX bearing layer against the real CBtrxBearing library
/// (docs/btrx/BEARING-LAYER-spec.md). Android's BtrxBearingLayerTest.kt covers the same rules
/// with a fake native layer; the C host tests (tools/btrx_bearing_test) cover the maths.
final class BtrxBearingLayerTests: XCTestCase {
    typealias P = BtrxProtocol
    static let wallBaseMs: Int64 = 1_790_000_000_000
    static let podOffsetMs: Int64 = 5_000 // pod millis = host ms - 5 s

    final class Clock: @unchecked Sendable { var us: Int64 = 100_000_000 }

    private func makeLayer(_ clock: Clock, lines: UnsafeMutablePointer<[String]>? = nil) -> BtrxBearingLayer {
        BtrxBearingLayer(monoUs: { clock.us }, wallMs: { Self.wallBaseMs + clock.us / 1000 },
                         log: { lines?.pointee.append($0) }, traceLog: { lines?.pointee.append($0) },
                         incidentKey: { [UInt8](repeating: 7, count: 16) })
    }

    private func meta(_ clock: Clock, backlog: Bool = false) -> BtrxRadioMeta {
        BtrxRadioMeta(fromNode: 2, toNode: 1, rxRssi: -60, rxSnr: 8, arrivalMs: Self.wallBaseMs + clock.us / 1000, backlog: backlog)
    }

    private func frame(_ type: Int, _ payload: [UInt8]) -> P.Frame {
        P.Frame(version: 1, type: type, seq: 1, podId: 0, flags: 0, payload: payload)
    }

    private func pong(seq: Int, t1: UInt32, podMs: UInt32) -> [UInt8] {
        var p = [UInt8](repeating: 0, count: 16)
        p[0] = UInt8(seq)
        P.putU32(&p, 2, t1); P.putU32(&p, 6, podMs); P.putU32(&p, 10, podMs)
        return p
    }

    private func health(role: Int, lost: UInt32?) -> [UInt8] {
        var p = [UInt8](repeating: 0, count: lost == nil ? 30 : 34)
        P.putU32(&p, 0, 500); p[6] = UInt8(role)
        if let lost { P.putU32(&p, 30, lost) }
        return p
    }

    // MARK: settings and text

    func testSettingsClampAndHalfStrength() {
        let s = BtrxBearingSettings(fadeTotalS: 5, fadeShapeP: 9).clamped()
        XCTAssertEqual(s.fadeTotalS, BtrxBearingSettings.minTotalS)
        XCTAssertEqual(s.fadeShapeP, BtrxBearingSettings.maxShapeP)
        let linear = BtrxBearingSettings(fadeTotalS: 600, fadeShapeP: 1).halfStrengthS
        let steep = BtrxBearingSettings(fadeTotalS: 600, fadeShapeP: 4).halfStrengthS
        XCTAssertGreaterThan(linear, 0); XCTAssertLessThan(linear, 600)
        XCTAssertGreaterThan(steep, linear, "a higher shape holds strength longer")
        XCTAssertEqual(BtrxBearingSettings().fadeMinutes, 10)
        XCTAssertEqual(BtrxBearingText.minutes(1), "1 min")
        XCTAssertEqual(BtrxBearingText.className(99), BtrxBearingText.className(99)) // total, no crash
    }

    // MARK: lifecycle and TIME_PING schedule

    func testStartStopAndPingBurstThenEvery30s() {
        let clock = Clock()
        let layer = makeLayer(clock)
        var sent: [(Int, UInt32, Int64)] = []
        layer.tick { _, _ in XCTFail("not running"); return true }
        XCTAssertTrue(layer.start(settings: BtrxBearingSettings(), trace: false, linkUp: true))
        XCTAssertTrue(layer.isRunning)
        XCTAssertTrue(layer.start(settings: BtrxBearingSettings(), trace: false, linkUp: true), "second start is a no-op")
        for _ in 0..<(40_000 / BtrxBearingLayer.tickMs) {
            layer.tick { seq, t1 in sent.append((seq, t1, clock.us)); return true }
            clock.us += Int64(BtrxBearingLayer.tickMs) * 1000
        }
        XCTAssertEqual(sent.count, 4, "burst of 3 then one 30 s later within 40 s")
        XCTAssertEqual(sent.map(\.0), [0, 1, 2, 3])
        XCTAssertEqual(sent[1].2 - sent[0].2, BtrxBearingLayer.pingBurstSpacingUs)
        XCTAssertEqual(sent[2].2 - sent[1].2, BtrxBearingLayer.pingBurstSpacingUs)
        XCTAssertEqual(sent[3].2 - sent[2].2, BtrxBearingLayer.pingIntervalUs)
        XCTAssertEqual(sent[0].1, UInt32(truncatingIfNeeded: sent[0].2 / 1000))
        // Reconnect: a new burst at once.
        layer.onConnected()
        sent.removeAll()
        for _ in 0..<15 { layer.tick { seq, t1 in sent.append((seq, t1, clock.us)); return true }; clock.us += 200_000 }
        XCTAssertEqual(sent.count, 3)
        layer.stop()
        XCTAssertFalse(layer.isRunning)
        layer.tick { _, _ in XCTFail("stopped"); return true }
    }

    func testPingThatCannotGoOutKeepsThePace() {
        let clock = Clock()
        let layer = makeLayer(clock)
        layer.start(settings: BtrxBearingSettings(), trace: false, linkUp: false)
        var tries = 0
        for _ in 0..<15 { layer.tick { _, _ in tries += 1; return false }; clock.us += 200_000 }
        XCTAssertEqual(tries, 3, "no link: the burst is not retried every tick")
    }

    // MARK: frames

    func testFramesFromOtherPodsAndWhileOffAreIgnored() {
        let clock = Clock()
        let layer = makeLayer(clock)
        var snaps: [BtrxBearingSnapshot?] = []
        layer.onSnapshot = { snaps.append($0) }
        layer.onFrame(frame(P.HEALTH, health(role: 3, lost: 9)), meta: meta(clock), fromPairedPod: false, fromDeskPod: true)
        layer.start(settings: BtrxBearingSettings(), trace: false, linkUp: true)
        layer.onFrame(frame(P.HEALTH, health(role: 3, lost: 9)), meta: meta(clock), fromPairedPod: false, fromDeskPod: false)
        layer.onFrame(frame(P.HEALTH, health(role: 3, lost: nil)), meta: meta(clock), fromPairedPod: false, fromDeskPod: true)
        for _ in 0..<10 { layer.tick { _, _ in true }; clock.us += 200_000 }
        if let last = snaps.compactMap({ $0 }).last {
            XCTAssertFalse(last.stats.deskLostKnown, "30-byte HEALTH: unknown, never 0")
            XCTAssertTrue(last.sources.isEmpty)
        }
    }

    /// Hover with a turning drone near one source: the layer places it, the map tap query finds
    /// it, mute shows on the row, and a yaw from another stream changes nothing.
    func testHoverYawScenarioPlacesASource() throws {
        let clock = Clock()
        var lines: [String] = []
        let layer = withUnsafeMutablePointer(to: &lines) { makeLayer(clock, lines: $0) }
        var snaps: [BtrxBearingSnapshot] = []
        layer.onSnapshot = { if let s = $0 { snaps.append(s) } }
        layer.steeringDesignator = "Drone 1"
        layer.start(settings: BtrxBearingSettings(), trace: true, linkUp: true)
        let lat = 37.0, lon = -122.0
        let srcLat = 37.0, srcLon = -122.0 + 300 / (111_320 * cos(37.0 * .pi / 180)) // 300 m east
        var seq = 0
        var yaw = 0.0
        var pinged: (Int, UInt32)?
        let stepUs: Int64 = 100_000
        for step in 0..<1200 { // 120 s
            let wall = Self.wallBaseMs + clock.us / 1000
            yaw = (Double(step) * 0.6).truncatingRemainder(dividingBy: 360) // 6 deg/s
            layer.onYaw(designator: "drone 1 ", yawTrueDeg: yaw, decodedAtWallMs: wall)
            layer.onYaw(designator: "Drone 2", yawTrueDeg: 180, decodedAtWallMs: wall)
            if step % 10 == 0 {
                layer.noteAgl(designator: "Drone 1", aglM: 40)
                layer.onPosition(designator: "Drone 1", lat: lat, lon: lon, fixWallMs: wall)
            }
            if step % 2 == 0 {
                layer.tick { s, t1 in pinged = (s, t1); return true }
                if let (s, t1) = pinged {
                    let podMs = UInt32(truncatingIfNeeded: Int64(t1) + 10 - Self.podOffsetMs)
                    layer.onFrame(frame(P.TIME_PONG, pong(seq: s, t1: t1, podMs: podMs)), meta: meta(clock), fromPairedPod: true, fromDeskPod: false)
                    pinged = nil
                }
            }
            if step % 5 == 4 { // 500 ms window
                let rel = (90.0 - yaw) * .pi / 180
                let delta = -6 * sin(rel)
                let s = P.Steer(steerSeq: seq & 0xFF, flags: P.STEER_F_TARGET | P.STEER_F_IMU_OK,
                                windowEndMs: UInt32(truncatingIfNeeded: clock.us / 1000 - Self.podOffsetMs), ageMs: 0, srcId: 0x0101,
                                rssiPort: Int(-70 + delta / 2), rssiStbd: Int(-70 - delta / 2), peakRssi: -66, hitsPort: 5, hitsStbd: 5,
                                companyId: 0, devClass: 2, addrType: 0, macLsbFirst: [1, 2, 3, 4, 5, 6], rollX2: 0, pitchX2: 0)
                seq += 1
                layer.onFrame(frame(P.STEER, P.steerPayload(s)), meta: meta(clock), fromPairedPod: true, fromDeskPod: false)
                // Another pod's STEER never reaches the layer (the model filters it); here a
                // fromPairedPod=false STEER must be ignored.
                layer.onFrame(frame(P.STEER, P.steerPayload(s)), meta: meta(clock), fromPairedPod: false, fromDeskPod: true)
            }
            if step == 600 {
                layer.onFrame(frame(P.HEALTH, health(role: 3, lost: 42)), meta: meta(clock), fromPairedPod: false, fromDeskPod: true)
            }
            clock.us += stepUs
        }
        let last = try XCTUnwrap(snaps.last, "no snapshot")
        // TIME_PONG offset and the Desk Pod's lost count (34-byte HEALTH).
        XCTAssertNotEqual(last.stats.offsetSource, 0, "offset from TIME_PONG")
        XCTAssertEqual(Double(last.stats.podOffsetMs).magnitude, Double(Self.podOffsetMs), accuracy: 50)
        XCTAssertTrue(last.stats.deskLostKnown)
        XCTAssertEqual(last.stats.deskLost, 42)
        XCTAssertTrue(BtrxBearingText.lostLine(last.stats).contains("42"), BtrxBearingText.lostLine(last.stats))
        XCTAssertGreaterThan(last.stats.used, 20, "counts: \(BtrxBearingText.countsLine(last.stats))")
        XCTAssertEqual(last.sources.count, 1)
        let src = try XCTUnwrap(last.sources.first)
        XCTAssertGreaterThan(src.reportsUsed, 20)
        XCTAssertEqual(last.width * last.height * 4, last.rgba.count)
        XCTAssertTrue(lines.contains { $0.hasPrefix("BBv1 R ") }, "lag trace logs the reports")
        XCTAssertFalse(lines.contains { $0.contains("01:02:03") }, "no addresses in the log")
        // Tap query at the source: found; far away: nothing.
        XCTAssertEqual(layer.sourcesAt(lat: srcLat, lon: srcLon, radiusM: 150).map(\.key), [src.key])
        XCTAssertTrue(layer.sourcesAt(lat: 37.05, lon: -122.05, radiusM: 50).isEmpty)
        if let e = src.estimate {
            let dEast = (e.lon - lon) * 111_320 * cos(37.0 * .pi / 180), dNorth = (e.lat - lat) * 111_320
            XCTAssertGreaterThan(dEast, abs(dNorth), "estimate lies east: \(dEast) m E, \(dNorth) m N")
        }
        // Mute (hide) and unmute (show).
        layer.setMuted([src.key], muted: true)
        for _ in 0..<10 { layer.tick { _, _ in true }; clock.us += 200_000 }
        XCTAssertTrue(try XCTUnwrap(snaps.last?.sources.first).muted)
        layer.setMuted([src.key], muted: false)
        for _ in 0..<10 { layer.tick { _, _ in true }; clock.us += 200_000 }
        XCTAssertFalse(try XCTUnwrap(snaps.last?.sources.first).muted)
        // Fade change and reset.
        layer.setFade(BtrxBearingSettings(fadeTotalS: 60, fadeShapeP: 2))
        for _ in 0..<10 { layer.tick { _, _ in true }; clock.us += 200_000 }
        XCTAssertEqual(snaps.last?.stats.fadeTotalS, 60)
        layer.reset()
        for _ in 0..<10 { layer.tick { _, _ in true }; clock.us += 200_000 }
        XCTAssertEqual(snaps.last?.sources.count, 0)
        layer.stop()
    }

    // MARK: field test Nov 13: status without a pose, then a drone on the table

    func testStatusWithoutPoseThenGroundedDrone() throws {
        let clock = Clock()
        let layer = makeLayer(clock)
        var statuses: [BtrxBearingStatus] = []
        var snaps: [BtrxBearingSnapshot] = []
        layer.onStatus = { if let s = $0 { statuses.append(s) } }
        layer.onSnapshot = { if let s = $0 { snaps.append(s) } }
        layer.steeringDesignator = nil
        layer.liveStreamCount = 1
        XCTAssertTrue(layer.start(settings: BtrxBearingSettings(), trace: false, linkUp: true))
        var seq = 0
        func steer() {
            let s = P.Steer(steerSeq: seq & 0xFF, flags: P.STEER_F_TARGET | P.STEER_F_IMU_OK,
                            windowEndMs: UInt32(truncatingIfNeeded: clock.us / 1000 - Self.podOffsetMs), ageMs: 0, srcId: 0x0202,
                            rssiPort: -60, rssiStbd: -68, peakRssi: -58, hitsPort: 5, hitsStbd: 5,
                            companyId: 0, devClass: 1, addrType: 0, macLsbFirst: [6, 5, 4, 3, 2, 1], rollX2: 0, pitchX2: 0)
            seq += 1
            layer.onFrame(frame(P.STEER, P.steerPayload(s)), meta: meta(clock), fromPairedPod: true, fromDeskPod: false)
        }
        // 10 s of reports, no pose at all: nothing published, the status says why.
        for step in 0..<50 {
            if step % 5 == 0 { steer() }
            layer.tick { _, _ in false }
            clock.us += 200_000
        }
        XCTAssertTrue(snaps.isEmpty, "no pose: no snapshot")
        let s1 = try XCTUnwrap(statuses.last)
        XCTAssertTrue(s1.line?.hasPrefix("Bearing layer: on the stream of the drone carrying the Drone Pod") == true, s1.line ?? "nil")
        XCTAssertGreaterThan(try XCTUnwrap(s1.stats).reportsIn, 5)
        XCTAssertEqual(s1.stats?.used, 0)
        // The drone sits on the table (AGL 0) streaming SEI yaw and RID position.
        layer.steeringDesignator = "1sar7djmtrc4td"
        for step in 0..<150 {
            let wall = Self.wallBaseMs + clock.us / 1000
            layer.onYaw(designator: "1sar7DjMtrc4td", yawTrueDeg: 90, decodedAtWallMs: wall)
            if step % 5 == 0 {
                layer.noteAgl(designator: "1sar7DjMtrc4td", aglM: 0)
                layer.onPosition(designator: "1sar7DjMtrc4td", lat: 39.153047, lon: -121.132901, fixWallMs: wall)
                steer()
            }
            layer.tick { _, _ in false }
            clock.us += 200_000
        }
        var last = try XCTUnwrap(snaps.last, "grounded drone: snapshot")
        XCTAssertGreaterThan(last.stats.used, 10, BtrxBearingText.countsLine(last.stats))
        XCTAssertGreaterThan(try XCTUnwrap(last.sources.first).peakAlpha, 0)
        // Nov 20: -64 dBm while the drone sits at its first position: muted as a launch-site
        // source (launch auto mute, on by default), so nothing is shown until "Show".
        let src = try XCTUnwrap(last.sources.first)
        XCTAssertTrue(src.launch && src.muted)
        XCTAssertTrue(try XCTUnwrap(statuses.last).line?.hasPrefix("Bearing layer: every source with shading is hidden") == true)
        XCTAssertEqual(layer.unmuteLaunch(), 1)
        for _ in 0..<10 { layer.tick { _, _ in false }; clock.us += 200_000 }
        last = try XCTUnwrap(snaps.last)
        XCTAssertTrue(try XCTUnwrap(last.sources.first).launch)
        XCTAssertFalse(try XCTUnwrap(last.sources.first).muted)
        XCTAssertNil(try XCTUnwrap(statuses.last).line, "shading on screen: no status line")
        layer.stop()
    }

    func testStatusReasonsInOrderAndEffectiveStream() {
        func line(steer: Float? = 1, d: String? = "A", live: Int = 1, yaw: Float? = 0.1, pos: Float? = 0.5, drawing: Bool = false, hidden: Bool = false) -> String? {
            BtrxBearingText.statusLine(BtrxBearingStatusInput(stats: nil, designator: d, liveStreams: live, steerAgeS: steer,
                                                               yawAgeS: yaw, positionAgeS: pos, drawing: drawing, allHidden: hidden))
        }
        XCTAssertNil(line(drawing: true))
        XCTAssertEqual(line(steer: nil), "Bearing layer: no reports from the paired Drone Pod")
        XCTAssertEqual(line(steer: 11), "Bearing layer: no reports from the paired Drone Pod")
        XCTAssertTrue(line(d: nil, live: 0)?.hasPrefix("Bearing layer: no drone video.") == true)
        XCTAssertEqual(line(yaw: 3.5), "Bearing layer: no heading from A video (DJI SEI)")
        XCTAssertEqual(line(pos: nil), "Bearing layer: no Remote ID position for A")
        XCTAssertEqual(line(hidden: true), "Bearing layer: every source with shading is hidden")
        XCTAssertEqual(line(), "Bearing layer: no report placed yet")
        XCTAssertEqual(BtrxSteeringTarget.effective(nil, live: ["A"]), "A")
        XCTAssertNil(BtrxSteeringTarget.effective(nil, live: ["A", "B"]))
        XCTAssertEqual(BtrxSteeringTarget.effective("B", live: ["A", "B"]), "B")
        XCTAssertEqual(BtrxSteeringTarget.effective("C", live: ["A"]), "A")
        XCTAssertNil(BtrxSteeringTarget.effective("C", live: []))
    }

    // MARK: protocol extensions (same vectors as Android BtrxProtocolTest and btrx_bearing)

    private func steer40(_ count: Int, format: Int = 1, len: Int = 40, target: Bool = true) -> [UInt8] {
        var p = [UInt8](repeating: 0, count: len)
        p[0] = 9; p[1] = target ? UInt8(P.STEER_F_TARGET) : 0
        P.putU32(&p, 2, 123456)
        p[8] = 0x34; p[9] = 0x12
        if len > 27 { p[27] = UInt8((format << 4) | count) }
        if len >= 34 { p[28] = 5; p[29] = 0; p[30] = UInt8(bitPattern: -70); p[31] = UInt8(bitPattern: -128); p[32] = 0xF1; p[33] = UInt8(0x01 | 0x02 | (2 << 2)) }
        if len >= 40 { p[34] = 6; p[35] = 0; p[36] = UInt8(bitPattern: -80); p[37] = UInt8(bitPattern: -75); p[38] = 0x0F; p[39] = UInt8(4 << 2) }
        return p
    }

    func testHealthPhoneLostOptional() throws {
        XCTAssertNil(try P.parseHealth(health(role: 3, lost: nil)).phoneLost)
        let h = try P.parseHealth(health(role: 3, lost: 0xFFFF_FFFF))
        XCTAssertEqual(h.phoneLost, 0xFFFF_FFFF)
        XCTAssertEqual(h.uptimeS, 500)
        XCTAssertEqual(h.role, 3)
    }

    func testSteerExtras() throws {
        XCTAssertTrue(try P.parseSteer(steer40(0, len: 27)).extras.isEmpty)
        XCTAssertTrue(try P.parseSteer(steer40(0, len: 28)).extras.isEmpty)
        let one = try P.parseSteer(steer40(1, len: 34))
        XCTAssertEqual(one.extras.count, 1)
        let x = one.extras[0]
        XCTAssertEqual(x.srcId, 5); XCTAssertEqual(x.rssiPort, -70); XCTAssertEqual(x.rssiStbd, -128)
        XCTAssertEqual(x.hitsPort, 1); XCTAssertEqual(x.hitsStbd, 15)
        XCTAssertTrue(x.newSource); XCTAssertTrue(x.linked); XCTAssertEqual(x.devClass, 2)
        let two = try P.parseSteer(steer40(2))
        XCTAssertEqual(two.extras.map(\.srcId), [5, 6])
        XCTAssertEqual(two.extras[1].hitsPort, 15); XCTAssertEqual(two.extras[1].hitsStbd, 0)
        XCTAssertFalse(two.extras[1].newSource); XCTAssertEqual(two.extras[1].devClass, 4)
        XCTAssertEqual(two.extFormat, 1)
        XCTAssertEqual(two.srcId, 0x1234)
        let three = try P.parseSteer(steer40(3))
        XCTAssertTrue(three.extTruncated && three.extras.isEmpty)
        let short = try P.parseSteer(steer40(1, len: 30))
        XCTAssertTrue(short.extTruncated && short.extras.isEmpty)
        XCTAssertTrue(try P.parseSteer(steer40(1, format: 0, len: 34)).extras.isEmpty)
        XCTAssertTrue(try P.parseSteer(steer40(1, len: 34, target: false)).extras.isEmpty)
        var f2 = steer40(2, format: 2, len: 44)
        f2[28] = 7; f2[36] = 8; f2[29] = 0; f2[37] = 0
        XCTAssertEqual(try P.parseSteer(f2).extras.map(\.srcId), [7, 8])
        XCTAssertNil(try P.parseSteer(f2).yaw)
    }

    func testTimePingPayloadMatchesPackTime() throws {
        let p = P.timePingPayload(pingSeq: 0x2A, t1Ms: 0x0000_0102)
        XCTAssertEqual(p.map { String(format: "%02x", $0) }.joined(), "2a000201000000000000000000000000")
        let f = try P.decodeFrame(P.timePingFrame(seq: 3, podId: 0x1122_3344, pingSeq: 1, t1Ms: 1000))
        XCTAssertEqual(f.type, P.TIME_PING)
        XCTAssertEqual(f.podId, 0x1122_3344)
        XCTAssertEqual(try P.parseTime(f.payload).t1, 1000)
    }

    /// The paired Drone Pod's HEALTH build (offset 4) switches the C layer to stable src ids at 0x0106. Same as Android.
    func testStableSrcIdsFollowThePairedPodBuild() {
        let clock = Clock()
        var lines: [String] = []
        let layer = withUnsafeMutablePointer(to: &lines) { makeLayer(clock, lines: $0) }
        _ = layer.start(settings: BtrxBearingSettings(), trace: false, linkUp: true)
        func h(_ build: Int, _ size: Int = 44) -> [UInt8] {
            var p = [UInt8](repeating: 0, count: size); P.putU32(&p, 0, 500); p[4] = UInt8(build & 0xFF); p[5] = UInt8(build >> 8); p[6] = 2
            return p
        }
        layer.onFrame(frame(P.HEALTH, h(0x0105, 34)), meta: meta(clock), fromPairedPod: true, fromDeskPod: false)
        XCTAssertFalse(layer.stableSrcIds)
        layer.onFrame(frame(P.HEALTH, h(0x0106)), meta: meta(clock), fromPairedPod: false, fromDeskPod: true)
        XCTAssertFalse(layer.stableSrcIds, "the Desk Pod's build does not decide")
        layer.onFrame(frame(P.HEALTH, h(0x0106)), meta: meta(clock), fromPairedPod: true, fromDeskPod: false)
        XCTAssertTrue(layer.stableSrcIds)
        layer.onFrame(frame(P.HEALTH, h(0x0105, 34)), meta: meta(clock), fromPairedPod: true, fromDeskPod: false)
        XCTAssertFalse(layer.stableSrcIds, "an older pod paired later goes back")
        layer.stop()
    }
}
