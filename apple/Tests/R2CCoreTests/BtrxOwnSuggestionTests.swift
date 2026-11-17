import CBtrxBearing
import XCTest
@testable import R2CCore

/// Nov 17 watch test: the own-aircraft banner (which source, kept closed once answered, one
/// line) and the 0x010B HEALTH supply fields. Android: BtrxOwnSuggestionTest.kt and
/// BtrxDeskPodPowerTest.modelShowsTheDeskPodsHealthSupplyFields.
final class BtrxOwnSuggestionTests: XCTestCase {
    typealias P = BtrxProtocol
    let suspect = UInt32(BTRX_BEARING_SRC_OWN_SUSPECT)

    private func src(_ key: UInt64, _ id: String, srcId: Int = 0, flags: UInt32 = 0) -> BtrxBearingSource {
        BtrxBearingSource(index: 0, key: key, shortID: id, provisional: false, devClass: 0, rgb: 0x2196F3, flags: flags,
                          firstHeardUs: 0, lastHeardUs: 5_000_000, reportsIn: 40, reportsUsed: 30, reportsInWindow: 25,
                          peakAlpha: 0.6, estimate: nil, latestRay: nil, srcId: srcId)
    }

    func testPickSkipsIgnoredAnsweredAndPlain() {
        let plain = src(1, "Plain1")
        let ignored = src(2, "Ign222", flags: suspect | UInt32(BTRX_BEARING_SRC_IGNORED))
        let q = src(3, "QBXFvg", srcId: 50203, flags: suspect)
        XCTAssertEqual(BtrxOwnSuggestion.pick([plain, ignored, q], answers: BtrxOwnAnswers()), q)
        let a = BtrxOwnAnswers().plus(key: 3, srcId: 50203)
        XCTAssertNil(BtrxOwnSuggestion.pick([plain, ignored, q], answers: a))
        XCTAssertNil(BtrxOwnSuggestion.pick([src(30, "QBXFvg", srcId: 50203, flags: suspect)], answers: a), "same src_id, new address")
        let a0 = BtrxOwnAnswers().plus(key: 4, srcId: 0)
        XCTAssertTrue(a0.srcIds.isEmpty)
        let q0 = src(3, "QBXFvg", flags: suspect)
        XCTAssertEqual(BtrxOwnSuggestion.pick([q0], answers: a0), q0)
        XCTAssertNil(BtrxOwnSuggestion.pick([], answers: BtrxOwnAnswers()))
    }

    func testBannerIsOneShortLine() {
        let q = src(3, "QBXFvg", flags: suspect)
        XCTAssertEqual(BtrxBearingText.ownSuggestion(q), "QBXFvg (unknown) on the drone?")
        XCTAssertEqual(BtrxBearingText.ownSuggestionCollapsed(q), "QBXFvg on drone?")
        XCTAssertEqual(BtrxBearingText.ownSuggestSave, "Own aircraft")
        XCTAssertEqual(BtrxBearingText.ownSuggestKeep, "Not mine")
        XCTAssertLessThan(BtrxBearingText.ownSuggestion(q).count, 40)
    }

    /// Either answer closes the suggestion at once and it never comes back for that source,
    /// across a layer restart and an address change with the same src_id (real CBtrxBearing).
    func testAnswersCloseAndStay() {
        var logs: [String] = []
        let layer = BtrxBearingLayer(monoUs: { 100_000_000 }, wallMs: { 1_790_000_000_000 }, log: { logs.append($0) },
                                     incidentKey: { [UInt8](repeating: 7, count: 16) })
        XCTAssertTrue(layer.start(settings: BtrxBearingSettings(), trace: false, linkUp: true))
        let own = src(7, "QBXFvg", srcId: 50203, flags: suspect)
        let other = src(8, "Ab12Cd", flags: suspect)
        XCTAssertEqual(BtrxOwnSuggestion.pick([own, other], answers: layer.ownAnswers), own)
        layer.answerOwnSuggestion(own, own: true)
        XCTAssertEqual(logs.last, "BBOWN QBXFvg answered own aircraft (id 50203)")
        XCTAssertEqual(BtrxOwnSuggestion.pick([own, other], answers: layer.ownAnswers), other)
        layer.answerOwnSuggestion(other, own: false)
        XCTAssertEqual(logs.last, "BBOWN Ab12Cd answered not mine")
        XCTAssertNil(BtrxOwnSuggestion.pick([own, other], answers: layer.ownAnswers))
        layer.stop()
        XCTAssertTrue(layer.start(settings: BtrxBearingSettings(), trace: false, linkUp: true))
        XCTAssertNil(BtrxOwnSuggestion.pick([own, src(99, "QBXFvg", srcId: 50203, flags: suspect), other], answers: layer.ownAnswers))
        layer.stop()
    }

    private func health59(flags: UInt8 = 0x03, boot: UInt8 = 5, drops: UInt8 = 2) -> [UInt8] {
        var p = [UInt8](repeating: 0, count: 59)
        p[6] = 3
        for (o, v) in [(46, 3301), (48, 3290), (50, 4180), (52, 4012)] { p[o] = UInt8(v & 0xFF); p[o + 1] = UInt8(v >> 8) }
        p[56] = flags; p[57] = boot; p[58] = drops
        return p
    }

    func testHealth59PowerFieldsByLength() throws {
        let h = try P.parseHealth(health59())
        XCTAssertEqual(h.power, P.HealthPower(vddMv: 3301, vddMinMv: 3290, vddhMv: 4180, vddhMinMv: 4012, vbatMv: 0,
                                              powerFlags: 0x03, bootCause: 5, vbusDrops: 2))
        XCTAssertEqual(h.scanLostPerS, 0)
        XCTAssertNil(try P.parseHealth(Array(health59().prefix(58))).power)
        XCTAssertNil(try P.parseHealth(Array(health59().prefix(46))).power)
        XCTAssertNil(try P.parseHealth(Array(health59().prefix(44))).power)
        XCTAssertEqual(try P.parseHealth(health59() + [0, 0, 0, 0]).power, h.power)
        XCTAssertEqual(P.powerFlagNames(0x03), "vbus,usb_ready")
        XCTAssertEqual(P.powerFlagNames(0xFF), "vbus,usb_ready,battery,vdd_low,pof,vbus_lost,prev_pof,prev_vbus_lost")
        XCTAssertEqual(P.powerFlagNames(0), "none")
        XCTAssertEqual((0...7).map { P.bootCauseName($0) } + [P.bootCauseName(9)],
                       ["unknown", "power-on", "brown-out", "reset-pin", "watchdog", "soft-reset", "lockup", "wake", "9"])
    }

    func testHealth59InFrameLogAndDeskPodScreen() throws {
        let w = try XCTUnwrap(try P.parseHealth(health59()).power)
        XCTAssertEqual(BtrxFrameLog.powerFields(w), " vdd_mv=3301 vdd_min_mv=3290 vddh_mv=4180 vddh_min_mv=4012 vbat_mv=0 "
            + "power_flags=0x03(vbus,usb_ready) boot_cause=5(soft-reset) vbus_drops=2")
        XCTAssertEqual(BtrxDeskPodText.deskSupplyLines(w), [
            "Desk Pod supply: VDD 3301 mV (min 3290 mV), VDDH 4180 mV (min 4012 mV), VBAT none seen",
            "Desk Pod USB power: present, lost 2 times while running (flags vbus,usb_ready)",
            "Desk Pod started by: soft-reset",
        ])
        let bad = P.HealthPower(vddMv: 2950, vddMinMv: 2950, vddhMv: 4180, vddhMinMv: 4012, vbatMv: 0,
                                powerFlags: P.PWR_VBUS_LOST | P.PWR_VDD_LOW | P.PWR_POF | P.PWR_PREV_VBUS_LOST, bootCause: 5, vbusDrops: 1)
        let lines = BtrxDeskPodText.deskSupplyLines(bad)
        XCTAssertEqual(lines[1], "Desk Pod USB power: ABSENT, lost 1 time while running (flags vdd_low,pof,vbus_lost,prev_vbus_lost)")
        XCTAssertEqual(lines[3], "Desk Pod power warning: 3.3 V rail below 3.0 V; supply sagged (power-fail) since it started; USB power lost in the run before")
        XCTAssertTrue(BtrxDeskPodText.deskSupplyChange(nil, w)?.hasPrefix("desk pod power: vdd_mv=3301") ?? false)
        XCTAssertNil(BtrxDeskPodText.deskSupplyChange(w, P.HealthPower(vddMv: 3299, vddMinMv: 3290, vddhMv: 4180, vddhMinMv: 4012,
                                                                      vbatMv: 0, powerFlags: 0x03, bootCause: 5, vbusDrops: 2)))
    }

    final class FakeSender: BtrxFrameSender {
        var isReady = true
        func nextSeq() -> Int { 1 }
        func sendFrame(to: UInt32, frame: [UInt8]) {}
    }

    func testModelShowsTheDeskPodsHealthSupplyFields() throws {
        let desk: UInt32 = 0x66C8_42CA
        let log = BtrxTestLogger()
        let model = BtrxDeskPodModel(log: log, clock: { 1_000_000 })
        model.attach(FakeSender()); model.onConfigured(localNode: desk)
        func send(_ flags: UInt8, _ drops: UInt8) throws {
            model.onFrame(try P.decodeFrame(P.encodeFrame(P.HEALTH, payload: health59(flags: flags, boot: 1, drops: drops))),
                          meta: BtrxRadioMeta(fromNode: desk, toNode: desk, rxRssi: 0, rxSnr: 0, arrivalMs: 1_000_000, backlog: false))
        }
        try send(0x03, 0)
        let s = model.snapshot()
        XCTAssertEqual(s.deskHealthPower?.vddhMv, 4180)
        XCTAssertEqual(BtrxDeskPodText.deskPowerLines(s), [
            "Desk Pod supply: VDD 3301 mV (min 3290 mV), VDDH 4180 mV (min 4012 mV), VBAT none seen",
            "Desk Pod USB power: present, lost 0 times while running (flags vbus,usb_ready)",
            "Desk Pod started by: power-on",
        ])
        XCTAssertTrue(BtrxDeskPodText.healthLines(s).contains("Desk Pod started by: power-on"))
        XCTAssertEqual(log.debugs.filter { $0.hasPrefix("desk pod power:") }.count, 1)
        try send(0x03, 0)
        XCTAssertEqual(log.debugs.filter { $0.hasPrefix("desk pod power:") }.count, 1)
        try send(0x22, 1)
        XCTAssertTrue(log.debugs.last?.hasSuffix("power_flags=0x22(usb_ready,vbus_lost) boot_cause=1(power-on) vbus_drops=1") ?? false)
    }
}
