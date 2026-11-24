import XCTest
import CBtrxBearing
@testable import R2CCore

/// Swift copy of Android's BtrxStalenessTest.kt (Ken, Nov 23 Timberland flight): stale sources
/// fade and leave within 60 s, new ones come first, the Nearby Sources panel sorts by signal and
/// freshness at most every 12 s, and the bearing layer goes on with BTRX. Shared
/// test-fixtures/btrx/staleness.tsv.
final class BtrxStalenessTests: XCTestCase {
    private static let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()

    private func rows() throws -> [[String]] {
        try String(contentsOf: Self.root.appendingPathComponent("test-fixtures/btrx/staleness.tsv"), encoding: .utf8)
            .split(separator: "\n").map(String.init).filter { !$0.isEmpty && !$0.hasPrefix("#") }.map { $0.components(separatedBy: "\t") }
    }

    private func src(_ key: UInt64, rssi: Int?, firstUs: Int64, lastUs: Int64, est: Bool = false) -> BtrxBearingSource {
        let e = est ? BtrxBearingEstimate(lat: 39, lon: -121, axisMajor1sM: 9, axisMinor1sM: 4, axisMajorDeg: 30,
                                          ambiguous: false, secondLat: 0, secondLon: 0, secondMass: 0) : nil
        var s = BtrxBearingSource(index: 0, key: key, shortID: "S\(key)", provisional: false, devClass: 1, rgb: 0x2196F3, flags: 0,
                                  firstHeardUs: firstUs, lastHeardUs: lastUs, reportsIn: 10, reportsUsed: 10, reportsInWindow: 10,
                                  peakAlpha: 0.5, estimate: e, latestRay: nil)
        s.lastRssiDbm = rssi
        return s
    }

    private func us(_ s: String) -> Int64 { Int64((Double(s)! * 1e6).rounded()) }

    private func sources(_ spec: String, agesAt: Int64? = nil) -> [BtrxBearingSource] {
        spec.components(separatedBy: ",").map { e in
            let f = e.components(separatedBy: ":")
            let rssi = f[1] == "-" ? nil : Int(f[1])
            if let now = agesAt { return src(UInt64(f[0])!, rssi: rssi, firstUs: now - us(f[2]), lastUs: now - us(f[3])) }
            return src(UInt64(f[0])!, rssi: rssi, firstUs: us(f[2]), lastUs: us(f[3]))
        }
    }

    private func keys(_ s: [BtrxBearingSource]) -> String { s.map { String($0.key) }.joined(separator: ",") }

    func testConstantsMatchFixtureAndEngine() throws {
        let c = Dictionary(try rows().filter { $0[0] == "const" }.map { ($0[1], Float($0[2])!) }, uniquingKeysWith: { a, _ in a })
        XCTAssertEqual(c["fade_start_s"], BtrxStaleness.fadeStartS)
        XCTAssertEqual(c["hide_s"], BtrxStaleness.hideS)
        XCTAssertEqual(c["new_s"], BtrxStaleness.newS)
        XCTAssertEqual(c["panel_resort_s"], BtrxStaleness.panelResortS)
        XCTAssertEqual(c["age_penalty_db_per_s"], BtrxStaleness.agePenaltyDbPerS)
        let layer = try String(contentsOf: Self.root.appendingPathComponent("apple/Sources/R2CCore/Btrx/BtrxBearingLayer.swift"), encoding: .utf8)
        XCTAssertTrue(layer.contains("cfg.stale_fade_start_s = Float(BTRX_BEARING_STALE_FADE_START_S)"))
        XCTAssertTrue(layer.contains("cfg.stale_hide_s = Float(BTRX_BEARING_STALE_HIDE_S)"))
    }

    func testPresenceFromFixture() throws {
        let ps = try rows().filter { $0[0] == "presence" }
        XCTAssertEqual(ps.count, 7)
        for r in ps { XCTAssertEqual(BtrxStaleness.presence(ageS: Float(r[1])!), Float(r[2])!, accuracy: 1e-5, "age \(r[1])") }
    }

    func testRankFromFixture() throws {
        let rs = try rows().filter { $0[0] == "rank" }
        XCTAssertEqual(rs.count, 2)
        let now: Int64 = 1_000_000_000
        for r in rs { XCTAssertEqual(keys(BtrxStaleness.rank(sources(r[2], agesAt: now), nowUs: now)), r[3], r[1]) }
    }

    func testPanelOrderFromFixture() throws {
        let steps = try rows().filter { $0[0] == "panel" }
        XCTAssertEqual(steps.count, 7)
        for (name, rs) in Dictionary(grouping: steps, by: { $0[1] }) {
            let order = BtrxPanelOrder()
            for r in rs {
                let got = order.arrange(sources(r[5]), nowUs: us(r[2]), holding: r[3] == "1", showOlder: r[4] == "1")
                XCTAssertEqual(keys(got), r[6], "\(name) t=\(r[2])")
            }
        }
    }

    func testMapDrawsFadesAndHidesStaleSources() {
        let now: Int64 = 100_000_000
        let fresh = src(1, rssi: -60, firstUs: 0, lastUs: now - 5_000_000, est: true)
        let fading = src(2, rssi: -60, firstUs: 0, lastUs: now - 40_000_000, est: true)
        let stale = src(3, rssi: -60, firstUs: 0, lastUs: now - 61_000_000)
        let fresh10 = src(4, rssi: -60, firstUs: now - 3_000_000, lastUs: now - 1_000_000)
        let stats = BtrxBearingStats(reportsIn: 0, entriesIn: 0, used: 0, gated: 0, noHeading: 0, noPosition: 0, lateDropped: 0, ringDropped: 0,
                                     bucketsExpiredEarly: 0, ignored: 0, backlogHistory: 0, steerTruncated: 0, extrasUnresolved: 0,
                                     provisionalMerges: 0, deskLostKnown: false, deskLost: 0, deskLostSinceConnect: 0, podALost: 0,
                                     tilesInUse: 0, tilesTotal: 0, fadeTotalS: 0, fadeShapeP: 0, podOffsetMs: 0, podOffsetErrMs: 0,
                                     offsetSource: 0, podSession: 0, displayLagMs: 0, lag: [])
        let snap = BtrxBearingSnapshot(seq: 1, tUs: now, width: 0, height: 0, north: 0, south: 0, east: 0, west: 0, rgba: Data(),
                                       sources: [fresh, fading, stale, fresh10], stats: stats)
        XCTAssertEqual(BtrxBearingView.drawMode(fresh, snap), .full)
        XCTAssertEqual(BtrxBearingView.drawMode(fading, snap), .full)
        XCTAssertEqual(BtrxBearingView.drawMode(stale, snap), .hidden)
        XCTAssertTrue((0.5...0.6).contains(BtrxStaleness.presence(fading, nowUs: now)))
        XCTAssertGreaterThan(BtrxBearingView.labelPriority(fresh10, snap), BtrxBearingView.labelPriority(fresh, snap))
        XCTAssertLessThan(BtrxBearingView.labelPriority(fading, snap), BtrxBearingView.labelPriority(fresh, snap))
        XCTAssertTrue(BtrxBearingView.mapLabel(fresh10, nowUs: now).hasPrefix("new · "))
        XCTAssertFalse(BtrxBearingView.mapLabel(fresh, nowUs: now).hasPrefix("new"))
    }

    func testBearingLayerGoesOnWithBtrx() throws {
        XCTAssertTrue(BtrxBearingAutoOn.shouldTurnOn(btrxEnabled: true, bearingOn: false))
        XCTAssertFalse(BtrxBearingAutoOn.shouldTurnOn(btrxEnabled: true, bearingOn: true))
        XCTAssertFalse(BtrxBearingAutoOn.shouldTurnOn(btrxEnabled: false, bearingOn: false))
        let c = try String(contentsOf: Self.root.appendingPathComponent("apple/Sources/R2CAppleRadios/BtrxBleCentral.swift"), encoding: .utf8)
        XCTAssertTrue(c.contains("if bluetoothEnabled { autoBearingOn(\"BTRX enabled at launch\") }"))
        XCTAssertTrue(c.contains("if on { autoBearingOn(\"Desk Pod Bluetooth link turned on\") }"))
        XCTAssertFalse(BtrxBearingText.layerSwitchHint.contains("Off at every launch"))
    }
}
