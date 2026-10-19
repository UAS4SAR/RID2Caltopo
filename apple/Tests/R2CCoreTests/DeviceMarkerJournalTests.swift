import XCTest
@testable import R2CCore
final class DeviceMarkerJournalTests: XCTestCase {
    private func file() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("journal.json") }
    func testInterruptedPublishAndFailedDeleteSurviveRestart() throws {
        let url=file() // Independent instances model process death; no live device required.
        let entry=try DeviceMarkerJournal(file:url).arm(domain:"caltopo.com",map:"OLDMAP",marker:"device-1")
        XCTAssertEqual(try DeviceMarkerJournal(file:url).entries().first?.map,"OLDMAP")
        XCTAssertEqual(try DeviceMarkerJournal(file:url).entries().count,1)
        try DeviceMarkerJournal(file:url).acknowledge(entry)
        XCTAssertTrue(try DeviceMarkerJournal(file:url).entries().isEmpty)
    }
    func testLateAcknowledgementPreservesNewPublicationAndOtherMap() throws {
        let j=DeviceMarkerJournal(file:file())
        let old=try j.arm(domain:"caltopo.com",map:"OLD",marker:"device")
        try j.arm(domain:"caltopo.com",map:"NEW",marker:"device")
        try j.arm(domain:"caltopo.com",map:"OLD",marker:"device")
        try j.acknowledge(old)
        XCTAssertEqual(try j.entries().count,2)
    }
    func testCorruptJournalPreventsUnjournaledPublication() throws {
        let url=file();try FileManager.default.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
        try Data("broken".utf8).write(to:url)
        XCTAssertThrowsError(try DeviceMarkerJournal(file:url).arm(domain:"host",map:"MAP",marker:"id"))
        XCTAssertEqual(try String(contentsOf:url,encoding:.utf8),"broken")
    }
}
