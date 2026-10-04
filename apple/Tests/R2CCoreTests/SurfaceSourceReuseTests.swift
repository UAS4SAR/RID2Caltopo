import Testing
import Foundation
@testable import R2CCore

// Mirrors SurfaceSourceReuseTest on Android.
private let sha = String(repeating: "a", count: 64)
private let one = OperationalSurfaceSource(url: "https://rockyweb.usgs.gov/Projects/A/LAZ/one.laz", metadataURL: "meta", published: "2020-01-01", survey: "A", bytes: 40_000_000)
private let two = OperationalSurfaceSource(url: "https://rockyweb.usgs.gov/Projects/A/LAZ/two.laz", metadataURL: "meta", published: "2020-01-01", survey: "A", bytes: 30_000_000)
private let record = OperationalRetainedSourceRecord(url: one.url, bytes: 40_100_000, sha256: sha)
private func plan(west: Double = -122.01, _ sources: [OperationalSurfaceSource]) throws -> OperationalSurfacePreparationPlan {
    var plan = try OperationalSurfacePreparation.grid(OperationalMapBounds(north: 37.01, south: 37.0, west: west, east: -122.0))
    plan.sources = sources
    return plan
}

@Test func keptLidarFileReusedOnlyWhenCompleteAndVerified() {
    typealias R = OperationalSurfaceSourceReuse
    #expect(R.reusable(source: one, record: record, fileLength: 40_100_000, computedSHA256: sha))
    #expect(!R.reusable(source: one, record: record, fileLength: 20_000_000, computedSHA256: sha))      // partial
    #expect(!R.reusable(source: one, record: record, fileLength: 40_100_001, computedSHA256: sha))      // wrong size
    #expect(!R.reusable(source: one, record: record, fileLength: nil, computedSHA256: nil))             // missing
    #expect(!R.reusable(source: one, record: nil, fileLength: 40_100_000, computedSHA256: sha))         // no record
    #expect(!R.reusable(source: one, record: record, fileLength: 40_100_000, computedSHA256: String(repeating: "b", count: 64)))
    #expect(!R.reusable(source: one, record: record, fileLength: 40_100_000, computedSHA256: nil))
    #expect(!R.reusable(source: two, record: record, fileLength: 40_100_000, computedSHA256: sha))      // different source
    #expect(!R.reusable(source: one, record: .init(url: one.url, bytes: 0, sha256: sha), fileLength: 0, computedSHA256: sha))
}

@Test func keptLidarWorkFolderKeyedBySelection() throws {
    typealias R = OperationalSurfaceSourceReuse
    let base = try plan([one, two])
    #expect(R.workKey(base) == R.workKey(try plan([two, one])))
    #expect(R.workKey(base) != R.workKey(try plan(west: -122.02, [one, two])))
    #expect(R.workKey(base) != R.workKey(try plan([one])))
    let resized = OperationalSurfaceSource(url: one.url, metadataURL: "meta", published: "2020-01-01", survey: "A", bytes: 41_000_000)
    #expect(R.workKey(base) != R.workKey(try plan([resized, two])))
    #expect(R.workDirectoryName(base).hasPrefix("aol-prep-"))
    let keep = R.workDirectoryName(base)
    let names = [keep, "aol-prep-old", "aol-prep-1234-uuid", "com.apple.dyld"]
    #expect(R.staleWorkDirectories(names, keepName: keep) == ["aol-prep-old", "aol-prep-1234-uuid"])
    #expect(R.staleWorkDirectories(names, keepName: nil) == [keep, "aol-prep-old", "aol-prep-1234-uuid"])
}

@Test func keptLidarRecordRoundTripsAndRejectsDamage() {
    #expect(OperationalRetainedSourceRecord.decode(record.encode()) == record)
    #expect(OperationalRetainedSourceRecord.decode(nil) == nil)
    #expect(OperationalRetainedSourceRecord.decode("v1\n\(one.url)\n40100000\n") == nil)
    #expect(OperationalRetainedSourceRecord.decode("v1\n\(one.url)\nlots\n\(sha)\n") == nil)
    #expect(OperationalRetainedSourceRecord.decode("v1\n\(one.url)\n40100000\nnot-a-hash\n") == nil)
}

@Test func closeAndLaunchCleanupDeletesOnlyAOLWorkFolders() throws {
    let fm = FileManager.default
    let root = fm.temporaryDirectory.appendingPathComponent("aol-cleanup-test-\(UUID().uuidString)")
    defer { try? fm.removeItem(at: root) }
    let keptName = OperationalSurfaceSourceReuse.workDirectoryName(try plan([one, two]))
    let kept = root.appendingPathComponent(keptName)
    try fm.createDirectory(at: kept, withIntermediateDirectories: true)
    try Data("lidar".utf8).write(to: kept.appendingPathComponent("source-0.laz"))
    try Data(record.encode().utf8).write(to: kept.appendingPathComponent("source-0.record"))
    try Data("partial".utf8).write(to: kept.appendingPathComponent("source-1.laz.part"))
    try fm.createDirectory(at: root.appendingPathComponent("aol-prep-1234-uuid/prepared"), withIntermediateDirectories: true)
    let other = root.appendingPathComponent("com.apple.dyld")
    try fm.createDirectory(at: other, withIntermediateDirectories: true)
    try Data("x".utf8).write(to: other.appendingPathComponent("cache"))
    try Data("not a folder".utf8).write(to: root.appendingPathComponent("aol-prep-note.txt"))

    #expect(OperationalSurfaceSourceReuse.deleteWorkDirectories(in: root) == 2)
    #expect(Set(try fm.contentsOfDirectory(atPath: root.path)) == ["com.apple.dyld", "aol-prep-note.txt"])
    #expect(fm.fileExists(atPath: other.appendingPathComponent("cache").path))
    #expect(OperationalSurfaceSourceReuse.deleteWorkDirectories(in: root) == 0)
    #expect(OperationalSurfaceSourceReuse.deleteWorkDirectories(in: root.appendingPathComponent("missing")) == 0)

    try fm.createDirectory(at: kept, withIntermediateDirectories: true)
    try fm.createDirectory(at: root.appendingPathComponent("aol-prep-other"), withIntermediateDirectories: true)
    #expect(OperationalSurfaceSourceReuse.deleteWorkDirectories(in: root, keepName: keptName) == 1)
    #expect(fm.fileExists(atPath: kept.path))
}
