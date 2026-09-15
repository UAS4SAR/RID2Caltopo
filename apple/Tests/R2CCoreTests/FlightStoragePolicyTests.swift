import Foundation
import Testing
@testable import R2CCore

struct FlightStoragePolicyTests {
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian); value.timeZone = TimeZone(secondsFromGMT: 0)!; return value
    }
    private func date(_ day: Int) -> Date { calendar.date(from: DateComponents(year: 2026, month: 9, day: day))! }
    @Test func ageThenSizeProtectsActiveFolders() {
        let folders: [FlightStoragePolicy.Folder] = [
            .init(name: "today", date: date(14), bytes: 50, protected: true),
            .init(name: "newer", date: date(13), bytes: 20, protected: false),
            .init(name: "old", date: date(1), bytes: 20, protected: false),
            .init(name: "active", date: date(2), bytes: 50, protected: true)
        ]
        #expect(FlightStoragePolicy.candidates(folders, used: 140, maximum: 100, maxDays: 30, now: date(14), calendar: calendar) == ["old", "newer"])
        #expect(FlightStoragePolicy.candidates(folders, used: 140, maximum: 500, maxDays: 7, now: date(14), calendar: calendar) == ["old"])
    }
    @Test func boundaryAndProtectedOverflow() {
        let folders: [FlightStoragePolicy.Folder] = [
            .init(name: "boundary", date: date(7), bytes: 20, protected: false),
            .init(name: "active", date: date(1), bytes: 200, protected: true)
        ]
        #expect(FlightStoragePolicy.candidates(folders, used: 220, maximum: 220, maxDays: 7, now: date(14), calendar: calendar).isEmpty)
        #expect(FlightStoragePolicy.candidates(folders, used: 220, maximum: 10, maxDays: 7, now: date(14), calendar: calendar) == ["boundary"])
    }
    @Test func recordingsUseDailyFoldersWithoutIndependentExpiry() throws {
        let data = try MediaMTXRuntimeConfiguration.build(base: Data("paths:\n  all_others:\n".utf8), captureStreams: true, recordingRoot: URL(fileURLWithPath: "/FlightStorage"))
        let text = String(decoding: data, as: UTF8.self)
        #expect(text.contains("/FlightStorage/%Y-%m-%d/%path/%path_%Y-%m-%d"))
        #expect(text.contains("recordDeleteAfter: 0s"))
    }
}

struct FlightStorageFilesystemTests {
    @Test func deletesWholeOldDayButKeepsTodayAndActiveDays() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        for day in ["2020-01-01", "2020-01-02", AppleFlightStorage.dayName(Date())] {
            let folder = root.appendingPathComponent(day)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            for file in ["track.json", "clue.jpg", "Log.txt", "video.mp4", "clues.json"] {
                try Data(repeating: 1, count: 100).write(to: folder.appendingPathComponent(file))
            }
        }
        AppleFlightStorage.protect("2020-01-02", owner: "filesystem-test")
        defer { AppleFlightStorage.release(owner: "filesystem-test") }
        let before = AppleFlightStorage.maintain(purge: false, at: root, maximum: 10_000, days: 30)
        #expect(before.used == 1500)
        let after = AppleFlightStorage.maintain(at: root, maximum: 10_000, days: 30)
        #expect(after.used == 1000)
        #expect(after.failures.isEmpty)
        #expect(!FileManager.default.fileExists(atPath: root.appendingPathComponent("2020-01-01").path))
        #expect(FileManager.default.fileExists(atPath: root.appendingPathComponent("2020-01-02/video.mp4").path))
    }
    @Test func ignoresNonDateDirectoriesAndDoesNotFollowSymlinks() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let outside = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root); try? FileManager.default.removeItem(at: outside) }
        try FileManager.default.createDirectory(at: root.appendingPathComponent("other"), withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        try Data([1,2,3]).write(to: outside.appendingPathComponent("preserve"))
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("2020-01-01"), withDestinationURL: outside)
        _ = AppleFlightStorage.maintain(at: root, maximum: 1, days: 1)
        #expect(FileManager.default.fileExists(atPath: outside.appendingPathComponent("preserve").path))
        #expect(FileManager.default.fileExists(atPath: root.appendingPathComponent("other").path))
    }
}

struct FlightStorageCapacityTests {
    @Test func protectedDayOverLimitPausesCaptureWithoutDeletingFiles() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let folder = root.appendingPathComponent(AppleFlightStorage.dayName(Date()))
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let video = folder.appendingPathComponent("video.mp4")
        try Data(repeating: 1, count: 1000).write(to: video)
        let snapshot = AppleFlightStorage.maintain(at: root, maximum: 1000, days: 30)
        #expect(snapshot.used == 1000)
        #expect(snapshot.blocked)
        #expect(FileManager.default.fileExists(atPath: video.path))
    }
}

struct FlightStorageDemandTests {
    @Test func ninetyPercentThresholdIncludesPlannedWrites() {
        let limit: Int64 = 10_000_000_000
        #expect(!FlightStoragePolicy.needsCleanup(used: 8_999_999_999, maximum: limit))
        #expect(FlightStoragePolicy.needsCleanup(used: 9_000_000_000, maximum: limit))
        #expect(FlightStoragePolicy.needsCleanup(used: 8_800_000_000, incoming: 300_000_000, maximum: limit))
        #expect(!FlightStoragePolicy.needsCleanup(used: 9_100_000_000, maximum: 11_000_000_000))
        #expect(FlightStoragePolicy.defaultGB == 10)
    }
    @Test func plannedWriteReclaimsBeforeAllocationAndReportsProtectedShortfall() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let today = root.appendingPathComponent(AppleFlightStorage.dayName(Date()))
        let old = root.appendingPathComponent("2020-01-01")
        for dir in [today, old] { try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true) }
        try Data(repeating: 1, count: 850).write(to: today.appendingPathComponent("active.mp4"))
        try Data(repeating: 1, count: 50).write(to: old.appendingPathComponent("old.mp4"))
        let result = AppleFlightStorage.maintain(at: root, maximum: 1000, days: 3650, reserving: 100)
        #expect(result.used == 850)
        #expect(result.allowanceInsufficient)
        #expect(result.message.contains("increase the allowance"))
        #expect(!FileManager.default.fileExists(atPath: old.path))
        let increased = AppleFlightStorage.maintain(at: root, maximum: 1100, days: 3650, reserving: 100)
        #expect(!increased.allowanceInsufficient)
    }
}

#if canImport(Darwin)
private final class FlightFileProbe: @unchecked Sendable {
    private let lock = NSLock()
    private var sizes: [String: Int64] = [:]
    private var count = 0
    func record(_ url: URL, _ size: Int64) { lock.lock(); defer { lock.unlock() }; sizes[url.resolvingSymlinksInPath().path] = size; count += 1 }
    func size(_ url: URL) -> Int64 { lock.lock(); defer { lock.unlock() }; return sizes[url.resolvingSymlinksInPath().path] ?? 0 }
    func diagnostic() -> String { lock.lock(); defer { lock.unlock() }; return String(describing: sizes) }
    func events() -> Int { lock.lock(); defer { lock.unlock() }; return count }
}
struct FlightStorageFileActivityTests {
    @Test func nativeRecordingGrowthIsObservedWithoutIdlePolling() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let probe = FlightFileProbe()
        let monitor = FlightStorageFileMonitor(roots: [root]) { url, size in probe.record(url, size) }
        defer { monitor.stop(); try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("drone_2026-09-14_10-00-00-123456.mp4")
        try Data(repeating: 1, count: 1024).write(to: file)
        for _ in 0..<100 where probe.size(file) != 1024 { try await Task.sleep(for: .milliseconds(20)) }
        #expect(probe.size(file) == 1024, "file=\(file.path) observed=\(probe.diagnostic())")
        let handle = try FileHandle(forWritingTo: file)
        try handle.seekToEnd(); try handle.write(contentsOf: Data(repeating: 2, count: 512)); try handle.close()
        for _ in 0..<100 where probe.size(file) != 1536 { try await Task.sleep(for: .milliseconds(20)) }
        #expect(probe.size(file) == 1536, "events=\(probe.events()) observed=\(probe.diagnostic())")
        try await Task.sleep(for: .milliseconds(400))
        let count = probe.events()
        try await Task.sleep(for: .milliseconds(500))
        #expect(probe.events() == count)
    }
}
#endif
