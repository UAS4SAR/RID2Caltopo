import XCTest
@testable import R2CCore

/// Swift copy of Android's BtrxMakersTest.kt.
final class BtrxMakersTests: XCTestCase {
    typealias P = BtrxProtocol

    func testClueNamesTheMakerAndKind() {
        XCTAssertEqual("Apple · wearable", BtrxMakers.clue(companyId: 0x004C, devClass: P.DEV_WEARABLE))
        XCTAssertEqual("Samsung · phone", BtrxMakers.clue(companyId: 0x0075, devClass: P.DEV_PHONE))
        XCTAssertEqual("GN Hearing · audio", BtrxMakers.clue(companyId: 0x0089, devClass: P.DEV_AUDIO))
        XCTAssertEqual("Tile · tracker", BtrxMakers.clue(companyId: 0x067C, devClass: P.DEV_TRACKER))
        XCTAssertEqual("Microsoft · computer", BtrxMakers.clue(companyId: 0x0006, devClass: P.DEV_COMPUTER))
    }

    func testUnknownMakerShowsHexAndUnknownPartsSayUnknown() {
        XCTAssertEqual("0x1234 · audio", BtrxMakers.clue(companyId: 0x1234, devClass: P.DEV_AUDIO))
        XCTAssertEqual("unknown · phone", BtrxMakers.clue(companyId: 0, devClass: P.DEV_PHONE))
        XCTAssertEqual("Garmin · unknown", BtrxMakers.clue(companyId: 0x0087, devClass: P.DEV_UNKNOWN))
        XCTAssertEqual("unknown device", BtrxMakers.clue(companyId: 0, devClass: P.DEV_UNKNOWN))
    }

    func testTableMatchesTheSigList() {
        let expected: [Int: String] = [0x004C: "Apple", 0x0075: "Samsung", 0x00E0: "Google", 0x0006: "Microsoft",
            0x0087: "Garmin", 0x009E: "Bose", 0x012D: "Sony", 0x0089: "GN Hearing", 0x0282: "Sonova",
            0x0107: "Demant", 0x00BA: "Starkey", 0x067C: "Tile"]
        for (id, name) in expected { XCTAssertEqual(name, BtrxMakers.names[id]) }
        XCTAssertNil(BtrxMakers.names[0x0224]) // Comarch SA, not Fitbit
    }

    func testSteeringViewCarriesTheTargetClue() {
        let st = BtrxSteering()
        let s = P.Steer(steerSeq: 1, flags: P.STEER_F_TARGET | P.STEER_F_IMU_OK, windowEndMs: 1000, ageMs: 50, srcId: 9,
                        rssiPort: -60, rssiStbd: -70, peakRssi: -58, hitsPort: 3, hitsStbd: 3, companyId: 0x004C,
                        devClass: P.DEV_WEARABLE, addrType: 1, macLsbFirst: [0, 0, 0, 0, 0, 0], rollX2: 0, pitchX2: 0)
        _ = st.onSteer(s, nowMs: 10_000)
        XCTAssertEqual("Apple · wearable", st.view(nowMs: 10_000)?.clue)
    }
}
