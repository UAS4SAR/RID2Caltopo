import Foundation
import Testing
@testable import R2CCore

@Suite("Leftover recording recovery")
struct LeftoverRecordingRecoveryTests {
    private func box(_ type: String, payload: Int) -> Data {
        var data = Data()
        let size = UInt32(8 + payload)
        data.append(contentsOf: [UInt8(size >> 24 & 0xff), UInt8(size >> 16 & 0xff), UInt8(size >> 8 & 0xff), UInt8(size & 0xff)])
        data.append(contentsOf: Array(type.utf8))
        data.append(Data(repeating: 0, count: payload))
        return data
    }

    private func fmp4(fragments: Int = 2, truncateTail: Int = 0, moov: Bool = true) -> Data {
        var data = box("ftyp", payload: 16)
        if moov { data += box("moov", payload: 64) }
        for _ in 0..<fragments { data += box("moof", payload: 24) + box("mdat", payload: 256) }
        if truncateTail > 0 { data.removeLast(truncateTail) }
        return data
    }

    private func tempRoot() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("leftover-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    @discardableResult
    private func write(_ root: URL, _ relative: String, _ data: Data, modified: Date) throws -> URL {
        let url = root.appendingPathComponent(relative)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url)
        try FileManager.default.setAttributes([.modificationDate: modified], ofItemAtPath: url.path)
        return url
    }

    private func isolatedLedger() -> LeftoverRecordingFailureLedger {
        LeftoverRecordingFailureLedger(defaults: UserDefaults(suiteName: "leftover-\(UUID().uuidString)")!)
    }

    @Test func findsRawSegmentInUTCDateFolderLeftByEarlierRun() throws {
        let root = try tempRoot()
        let cutoff = Date()
        let raw = try write(root, "2026-11-13/1sar7djmtrc4td/1sar7djmtrc4td_2026-11-13_01-40-13-393436.mp4",
                            fmp4(), modified: cutoff.addingTimeInterval(-60))
        let found = LeftoverRecordingRecovery.candidates(root: root, notTouchedSince: cutoff, now: cutoff)
        #expect(found.map(\.url.lastPathComponent) == [raw.lastPathComponent])
        #expect(found.first?.streamPath == "1sar7djmtrc4td")
    }

    @Test func excludesSegmentsOfTheCurrentRun() throws {
        let root = try tempRoot()
        let cutoff = Date().addingTimeInterval(-120)
        // Written after this recorder started (mtime) — the live segment.
        try write(root, "2026-11-13/p/p_2026-11-13_01-43-28-695232.mp4", fmp4(), modified: Date())
        // Old mtime but named after the cutoff (clock oddity) — still this run.
        let future = ISO8601DateFormatter().string(from: cutoff.addingTimeInterval(30))
        let stamp = future.replacingOccurrences(of: "T", with: "_").replacingOccurrences(of: ":", with: "-").replacingOccurrences(of: "Z", with: "-000000")
        try write(root, "2026-11-13/p/p_\(stamp).mp4", fmp4(), modified: cutoff.addingTimeInterval(-300))
        // Explicitly excluded path.
        let open = try write(root, "2026-11-13/q/q_2026-11-13_01-00-00-000000.mp4", fmp4(), modified: cutoff.addingTimeInterval(-300))
        let found = LeftoverRecordingRecovery.candidates(
            root: root, notTouchedSince: cutoff, excludingPaths: [open.standardizedFileURL.path])
        #expect(found.isEmpty)
    }

    @Test func ignoresRecentlyModifiedFilesInsideQuietWindow() throws {
        let root = try tempRoot()
        let now = Date()
        try write(root, "2026-11-13/p/p_2026-11-13_01-00-00-000000.mp4", fmp4(), modified: now.addingTimeInterval(-3))
        #expect(LeftoverRecordingRecovery.candidates(root: root, notTouchedSince: now.addingTimeInterval(1), now: now).isEmpty)
    }

    @Test func ignoresCompletedTemporaryOriginalAndEmptyFiles() throws {
        let root = try tempRoot()
        let old = Date().addingTimeInterval(-600)
        try write(root, "2026-11-12/p/p_12Nov2026_174013_PST.mp4", fmp4(), modified: old)
        try write(root, "2026-11-13/p/.p_2026-11-13_01-40-13-393436-ios-compatible-X.mp4", fmp4(), modified: old)
        try write(root, "2026-11-13/p/p_2026-11-13_01-40-13-393436.sei-original.mp4", fmp4(), modified: old)
        try write(root, "2026-11-13/p/p_2026-11-13_01-50-00-000000.mp4", Data(), modified: old)
        try write(root, "2026-11-13/p/notes.txt", Data("x".utf8), modified: old)
        #expect(LeftoverRecordingRecovery.candidates(root: root, notTouchedSince: Date()).isEmpty)
    }

    @Test func idempotentOnceLocalized() throws {
        let root = try tempRoot()
        let old = Date().addingTimeInterval(-600)
        let raw = try write(root, "2026-11-13/p/p_2026-11-13_01-40-13-393436.mp4", fmp4(), modified: old)
        #expect(LeftoverRecordingRecovery.candidates(root: root, notTouchedSince: Date()).count == 1)
        let localized = try #require(ManagedVideoRecordingIdentity.localizedMediaMTXRecordingURL(
            for: raw, timeZone: TimeZone(identifier: "America/Los_Angeles")!))
        let dayFolder = root.appendingPathComponent("2026-11-12/p")
        try FileManager.default.createDirectory(at: dayFolder, withIntermediateDirectories: true)
        try FileManager.default.moveItem(at: raw, to: dayFolder.appendingPathComponent(localized.lastPathComponent))
        #expect(localized.lastPathComponent == "p_12Nov2026_174013_PST.mp4")
        #expect(LeftoverRecordingRecovery.candidates(root: root, notTouchedSince: Date()).isEmpty)
    }

    @Test func knownFailureSkippedUntilFileChanges() throws {
        let root = try tempRoot()
        let ledger = isolatedLedger()
        let old = Date().addingTimeInterval(-600)
        let raw = try write(root, "2026-11-13/p/p_2026-11-13_01-40-13-393436.mp4", fmp4(moov: false), modified: old)
        let first = try #require(LeftoverRecordingRecovery.candidates(root: root, notTouchedSince: Date(), failures: ledger).first)
        ledger.record(first, reason: "no moov box")
        #expect(LeftoverRecordingRecovery.candidates(root: root, notTouchedSince: Date(), failures: ledger).isEmpty)
        #expect(ledger.reason(forPath: first.url.path) == "no moov box")
        try fmp4().write(to: raw)
        try FileManager.default.setAttributes([.modificationDate: old.addingTimeInterval(5)], ofItemAtPath: raw.path)
        #expect(LeftoverRecordingRecovery.candidates(root: root, notTouchedSince: Date(), failures: ledger).count == 1)
        ledger.clear(path: first.url.path)
        #expect(ledger.reason(forPath: first.url.path) == nil)
    }

    @Test func headerCheckAcceptsCutOffFragmentedSegment() throws {
        let root = try tempRoot()
        let old = Date()
        #expect(LeftoverRecordingRecovery.checkHeader(try write(root, "a.mp4", fmp4(), modified: old)) == .playable)
        // Killed mid-fragment: last mdat truncated, earlier fragments complete.
        #expect(LeftoverRecordingRecovery.checkHeader(try write(root, "b.mp4", fmp4(truncateTail: 100), modified: old)) == .playable)
    }

    @Test func headerCheckRejectsUnconvertibleFiles() throws {
        let root = try tempRoot()
        let old = Date()
        #expect(LeftoverRecordingRecovery.checkHeader(try write(root, "nomoov.mp4", fmp4(moov: false), modified: old)) == .noMovieHeader)
        #expect(LeftoverRecordingRecovery.checkHeader(try write(root, "nomedia.mp4", fmp4(fragments: 0), modified: old)) == .noMediaData)
        // moov itself cut off.
        let cutMoov = box("ftyp", payload: 16) + box("moov", payload: 64).prefix(40)
        #expect(LeftoverRecordingRecovery.checkHeader(try write(root, "cutmoov.mp4", cutMoov, modified: old)) == .noMovieHeader)
        if case .unreadable = LeftoverRecordingRecovery.checkHeader(try write(root, "tiny.mp4", Data([0, 0, 0]), modified: old)) {} else {
            Issue.record("tiny file should be unreadable")
        }
    }

    @Test func recoveredFileKeepsItsRecordedTime() throws {
        let root = try tempRoot()
        let ended = Date(timeIntervalSince1970: 1_794_534_170)  // 17:42:50 PST, Nov 12 2026
        let raw = try write(root, "2026-11-13/p/p_2026-11-13_01-40-13-393436.mp4", fmp4(), modified: ended)
        let candidate = try #require(LeftoverRecordingRecovery.candidates(root: root, notTouchedSince: Date()).first)
        // Conversion replaces the file (new mtime).
        try fmp4(fragments: 3).write(to: raw)
        #expect(LeftoverRecordingRecovery.restoreRecordedTime(of: raw, to: candidate))
        let modified = try raw.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate
        #expect(modified == ended)
    }

    @Test func ordersOldestSegmentFirst() throws {
        let root = try tempRoot()
        let old = Date().addingTimeInterval(-600)
        try write(root, "2026-11-13/p/p_2026-11-13_01-43-28-695232.mp4", fmp4(), modified: old)
        try write(root, "2026-11-13/p/p_2026-11-13_01-40-13-393436.mp4", fmp4(), modified: old)
        let names = LeftoverRecordingRecovery.candidates(root: root, notTouchedSince: Date()).map(\.url.lastPathComponent)
        #expect(names == ["p_2026-11-13_01-40-13-393436.mp4", "p_2026-11-13_01-43-28-695232.mp4"])
    }

    // Field 2026-11-23 (Android): a copy cut off at 44 MB of 1.1 GB kept the final name.
    private func faststart(truncateTail: Int = 0) -> Data {
        var data = box("ftyp", payload: 16) + box("moov", payload: 64) + box("mdat", payload: 4096)
        if truncateTail > 0 { data.removeLast(truncateTail) }
        return data
    }

    @Test func completenessRequiresBoxesToEndAtTheEndOfTheFile() throws {
        let root = try tempRoot()
        let now = Date()
        #expect(LeftoverRecordingRecovery.isComplete(try write(root, "a.mp4", faststart(), modified: now)))
        #expect(LeftoverRecordingRecovery.isComplete(try write(root, "b.mp4", fmp4(), modified: now)))
        // moov first but the media cut off: playable header, yet not a complete copy.
        let cut = try write(root, "c.mp4", faststart(truncateTail: 1000), modified: now)
        #expect(LeftoverRecordingRecovery.checkHeader(cut) == .playable)
        #expect(!LeftoverRecordingRecovery.isComplete(cut))
        #expect(!LeftoverRecordingRecovery.isComplete(try write(root, "d.mp4", fmp4(truncateTail: 10), modified: now)))
        #expect(!LeftoverRecordingRecovery.isComplete(try write(root, "e.mp4", fmp4(moov: false), modified: now)))
    }

    @Test func shorterIncompleteFileIsReplacedInsteadOfAddingADashTwoName() throws {
        let root = try tempRoot()
        let now = Date()
        let preferred = try write(root, "day/x/x_23Nov2026_131404_PST.mp4", faststart(truncateTail: 2000), modified: now)
        let destination = LeftoverRecordingRecovery.destination(
            preferred: preferred,
            sourceSize: Int64(faststart().count),
            fileExists: FileManager.default.fileExists(atPath:),
            size: { url in (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize.map(Int64.init) },
            isComplete: LeftoverRecordingRecovery.isComplete
        )
        #expect(destination == .replaceIncomplete(preferred))
    }

    @Test func completeOrLongerFileKeepsItsNameAndTheNewOneGetsADashName() throws {
        let root = try tempRoot()
        let now = Date()
        let size: (URL) -> Int64? = { url in (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize.map(Int64.init) }
        // Complete but smaller (normalized output is often smaller than the raw fMP4).
        let complete = try write(root, "day/x/x_1.mp4", faststart(), modified: now)
        #expect(LeftoverRecordingRecovery.destination(
            preferred: complete, sourceSize: 1_000_000,
            fileExists: FileManager.default.fileExists(atPath:), size: size,
            isComplete: LeftoverRecordingRecovery.isComplete
        ) == .new(root.appendingPathComponent("day/x/x_1-2.mp4")))
        // Incomplete but not shorter: another recording, never replaced.
        let longer = try write(root, "day/x/x_2.mp4", faststart(truncateTail: 10), modified: now)
        #expect(LeftoverRecordingRecovery.destination(
            preferred: longer, sourceSize: 100,
            fileExists: FileManager.default.fileExists(atPath:), size: size,
            isComplete: LeftoverRecordingRecovery.isComplete
        ) == .new(root.appendingPathComponent("day/x/x_2-2.mp4")))
        let fresh = root.appendingPathComponent("day/x/x_3.mp4")
        #expect(LeftoverRecordingRecovery.destination(
            preferred: fresh, sourceSize: 100,
            fileExists: FileManager.default.fileExists(atPath:), size: size,
            isComplete: LeftoverRecordingRecovery.isComplete
        ) == .new(fresh))
    }
}
