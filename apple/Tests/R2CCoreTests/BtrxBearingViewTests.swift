import XCTest
import CBtrxBearing
@testable import R2CCore

/// Swift copy of Android's BtrxBearingViewTest.kt (Nov 20 back-deck test): focus by class or
/// maker, display thresholds, label layout (shared test-fixtures/btrx/label-layout.tsv), maker
/// in labels, the "computer" class.
final class BtrxBearingViewTests: XCTestCase {
    private func src(_ key: UInt64, devClass: Int = 2, companyId: Int = 0, flags: UInt32 = 0, reports: Int = 25,
                     pm: Float? = 18, pinned: Bool = false, focusMatch: Bool = false) -> BtrxBearingSource {
        let est = pm.map { BtrxBearingEstimate(lat: 39, lon: -121, axisMajor1sM: $0 / 2, axisMinor1sM: $0 / 4, axisMajorDeg: 30,
                                               ambiguous: false, secondLat: 0, secondLon: 0, secondMass: 0) }
        return BtrxBearingSource(index: 0, key: key, shortID: "Q3x9Kd", provisional: false, devClass: devClass, rgb: 0x2196F3, flags: flags,
                                 firstHeardUs: 0, lastHeardUs: 5_000_000, reportsIn: 40, reportsUsed: 30, reportsInWindow: reports,
                                 peakAlpha: 0.6, estimate: est, latestRay: nil, pinned: pinned, companyId: companyId, focusMatch: focusMatch)
    }

    private let muted = UInt32(BTRX_BEARING_SRC_MUTED), ignored = UInt32(BTRX_BEARING_SRC_IGNORED), launch = UInt32(BTRX_BEARING_SRC_LAUNCH)

    func testSharedLabelLayoutFixtureMatchesAndroid() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("test-fixtures/btrx/label-layout.tsv")
        let rows = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }.map { $0.components(separatedBy: "\t") }
        let cases = Dictionary(grouping: rows, by: { $0[0] })
        XCTAssertEqual(cases.count, 8)
        for (name, rs) in cases {
            let obstacles = rs.filter { $0[1] == "obstacle" }.map {
                BtrxLabelLayout.Box(left: Float($0[3])!, top: Float($0[4])!, right: Float($0[5])!, bottom: Float($0[6])!)
            }
            let itemRows = rs.filter { $0[1] == "item" }
            let items = itemRows.map {
                BtrxLabelLayout.Item(id: Int64($0[2])!, anchorX: Float($0[3])!, anchorY: Float($0[4])!, width: Float($0[5])!,
                                     height: Float($0[6])!, priority: Int($0[7])!)
            }
            let placed = BtrxLabelLayout.place(items, obstacles: obstacles, gap: 8, margin: 2)
            for r in itemRows {
                XCTAssertEqual(placed[Int64(r[2])!]?.spot ?? -1, Int(r[8])!, "\(name) item \(r[2])")
            }
            let boxes = placed.values.map(\.box)
            for i in boxes.indices {
                for j in boxes.indices where j > i { XCTAssertFalse(boxes[i].overlaps(boxes[j], margin: 2), name) }
                for o in obstacles { XCTAssertFalse(boxes[i].overlaps(o, margin: 2), name) }
            }
        }
    }

    func testDrawModeFollowsFocus() {
        let watch = src(1, focusMatch: true), phone = src(2, devClass: 1)
        XCTAssertEqual(BtrxBearingView.drawMode(phone, focusKey: 0, focusActive: false, hideOthers: false), .full)
        XCTAssertEqual(BtrxBearingView.drawMode(src(3, flags: muted), focusKey: 0, focusActive: false, hideOthers: false), .hidden)
        XCTAssertEqual(BtrxBearingView.drawMode(src(4, flags: launch), focusKey: 0, focusActive: false, hideOthers: false), .full)
        XCTAssertEqual(BtrxBearingView.drawMode(watch, focusKey: 0, focusActive: true, hideOthers: false), .full)
        XCTAssertEqual(BtrxBearingView.drawMode(phone, focusKey: 0, focusActive: true, hideOthers: false), .dim)
        XCTAssertEqual(BtrxBearingView.drawMode(phone, focusKey: 0, focusActive: true, hideOthers: true), .hidden)
        XCTAssertEqual(BtrxBearingView.drawMode(phone, focusKey: 2, focusActive: true, hideOthers: true), .full)
    }

    func testThresholdsHideWideEstimatesAndThinLabels() {
        let d = BtrxBearingDisplay()
        XCTAssertEqual(d.maxPlusMinusM, 50); XCTAssertEqual(d.minReports, 5); XCTAssertTrue(d.launchAutoMute)
        XCTAssertTrue(BtrxBearingView.estimateShown(src(1, pm: 50), d, focusKey: 0))
        XCTAssertFalse(BtrxBearingView.estimateShown(src(1, pm: 51), d, focusKey: 0))
        XCTAssertTrue(BtrxBearingView.estimateShown(src(1, pm: 90), d, focusKey: 1))
        XCTAssertTrue(BtrxBearingView.estimateShown(src(1, pm: 90), BtrxBearingDisplay(maxPlusMinusM: 0), focusKey: 0))
        XCTAssertFalse(BtrxBearingView.estimateShown(src(1, pm: nil), d, focusKey: 0))
        XCTAssertTrue(BtrxBearingView.labelShown(src(1, reports: 5), d, focusKey: 0))
        XCTAssertFalse(BtrxBearingView.labelShown(src(1, reports: 4), d, focusKey: 0))
        XCTAssertTrue(BtrxBearingView.labelShown(src(1, reports: 1, pinned: true), d, focusKey: 0))
        XCTAssertTrue(BtrxBearingView.labelShown(src(1, reports: 1), d, focusKey: 1))
        XCTAssertEqual(BtrxBearingDisplay(maxPlusMinusM: -3, minReports: 0, launchAutoMute: false).clamped(),
                       BtrxBearingDisplay(maxPlusMinusM: 0, minReports: 1, launchAutoMute: false))
        XCTAssertEqual(BtrxBearingDisplay(maxPlusMinusM: 400, minReports: 99).clamped(), BtrxBearingDisplay(maxPlusMinusM: 100, minReports: 30))
        XCTAssertEqual(BtrxBearingDisplay(maxPlusMinusM: 4, minReports: 5).clamped(), BtrxBearingDisplay(maxPlusMinusM: 10, minReports: 5))
        XCTAssertEqual(BtrxBearingView.maxPmFromSlider(110), 0)
        XCTAssertEqual(BtrxBearingView.maxPmFromSlider(52), 50)
        XCTAssertEqual(BtrxBearingView.sliderFromMaxPm(0), 110)
        XCTAssertEqual(BtrxBearingView.maxPlusMinusText(0), "no limit")
        XCTAssertEqual(BtrxBearingView.maxPlusMinusText(50), "±50 m")
    }

    func testLabelPriorityOrdersFocusPinnedEstimateReports() {
        let p = { (s: BtrxBearingSource) in BtrxBearingView.labelPriority(s, focusKey: 1, focusActive: true) }
        let focused = p(src(1, reports: 3)), match = p(src(2, reports: 3, focusMatch: true)), pinned = p(src(3, reports: 3, pinned: true))
        let est = p(src(4, reports: 90)), none = p(src(5, reports: 900, pm: nil))
        XCTAssertTrue(focused > match && match > pinned && pinned > est && est > none)
        XCTAssertGreaterThan(BtrxBearingView.labelPriority(src(6, reports: 91), focusKey: 0, focusActive: false), est)
    }

    func testFocusOptionsFilterTextAndLaunchChip() {
        let srcs = [src(1, devClass: 2, companyId: 0x0087), src(2, devClass: 1, companyId: 0x004C), src(3, devClass: 1, companyId: 0x004C),
                    src(4, devClass: 6, companyId: 0x0006), src(5, devClass: 1, flags: ignored)]
        XCTAssertEqual(BtrxBearingView.focusOptions(srcs), [
            BtrxBearingFocusOption(isClass: true, value: 1, label: "phone", count: 2),
            BtrxBearingFocusOption(isClass: true, value: 6, label: "computer", count: 1),
            BtrxBearingFocusOption(isClass: true, value: 2, label: "watch", count: 1),
            BtrxBearingFocusOption(isClass: false, value: 0x004C, label: "Apple", count: 2),
            BtrxBearingFocusOption(isClass: false, value: 0x0087, label: "Garmin", count: 1),
            BtrxBearingFocusOption(isClass: false, value: 0x0006, label: "Microsoft", count: 1),
        ])
        let f = BtrxBearingFocusFilter.none.withClass(2).withCompany(0x0087)
        XCTAssertTrue(f.active)
        XCTAssertEqual(f.classMask, 1 << 2)
        XCTAssertEqual(BtrxBearingView.filterText(f), "watch, Garmin")
        XCTAssertEqual(BtrxBearingView.filterText(f.withHideOthers(true)), "watch, Garmin · others hidden")
        XCTAssertEqual(f.withClass(2).withCompany(0x0087), BtrxBearingFocusFilter(classMask: 0, companyId: -1))
        XCTAssertNil(BtrxBearingView.filterText(.none))
        XCTAssertEqual(f.withHideOthers(true).logText, "classes 0x4, company 0x0087, others hidden")
        XCTAssertEqual(BtrxBearingView.launchChipText(mutedCount: 3, flaggedCount: 5), "3 muted at takeoff")
        XCTAssertEqual(BtrxBearingView.launchChipText(mutedCount: 0, flaggedCount: 5), "5 at takeoff shown")
        XCTAssertNil(BtrxBearingView.launchChipText(mutedCount: 0, flaggedCount: 0))
    }

    func testLabelsNameTheMakerAndComputerClass() {
        XCTAssertEqual(BtrxBearingText.label(src(1, companyId: 0x0087)), "watch · Garmin · Q3x9Kd · 25 rpt · ±18 m")
        XCTAssertEqual(BtrxBearingText.label(src(1, devClass: 6, companyId: 0x0006)), "computer · Microsoft · Q3x9Kd · 25 rpt · ±18 m")
        XCTAssertEqual(BtrxBearingText.label(src(1)), "watch · Q3x9Kd · 25 rpt · ±18 m")
        XCTAssertEqual(BtrxBearingText.className(6), "computer")
        let l = src(1, companyId: 0x0087, flags: launch | muted)
        let lines = BtrxBearingText.inspectLines(l, nowUs: 5_000_000)
        XCTAssertEqual(lines[0], "Source Q3x9Kd · watch · Garmin")
        XCTAssertEqual(lines[1], "Heard strongly near the takeoff spot (muted)")
        XCTAssertTrue(l.launch)
    }

    func testPopupDismissWordingMatchesAndroid() {
        // Nov 23 watch test: × at the top right of the map popups hides them; it is not "Close".
        XCTAssertEqual(BtrxBearingText.dismiss, "Dismiss")
        XCTAssertEqual(BtrxBearingText.done, "Done")
        XCTAssertEqual(BtrxBearingText.close, "Close")
    }
}
