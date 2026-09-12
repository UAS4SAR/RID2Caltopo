import Foundation
import Testing
@testable import R2CCore

private func s1mHeader() throws -> Data {
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return try Data(contentsOf: root.appendingPathComponent("test-fixtures/s1m-public-header.bin"))
}

@Test func s1mSubsetsPreservePublicCompressedRangesAndEdgeDimensions() throws {
    let header = try s1mHeader()
    let cog = try S1MCog(header: header)
    func double(_ at: Int) -> Double { var bits: UInt64 = 0; for i in 0..<8 { bits |= UInt64(header[at + i]) << (8 * i) }; return Double(bitPattern: bits) }
    let x = double(846), y = double(854)
    let pieces = try cog.pieces(minX: x + 4500, maxX: x + 4501, minY: y - 4501, maxY: y - 4500)
    let piece = try #require(pieces.first)
    #expect(pieces.count == 1 && piece.row == 4 && piece.col == 4)
    #expect(piece.ranges.count == 9)
    #expect(piece.ranges[0].offset == 255454748 && piece.ranges[0].length == 888806)
    #expect(piece.bytes == Int64(header.count + 72 + 7_930_305))
    let edge = try #require(cog.pieces(minX: x + 9998, maxX: x + 9999, minY: y - 9999, maxY: y - 9998).first)
    #expect(edge.ranges.count == 4)
    #expect(try cog.pieces(minX: x - 100, maxX: x - 90, minY: y + 90, maxY: y + 100).isEmpty)
}

@Test func s1mCroppedTerrainSamplesMatchOriginalCoordinateRamp() throws {
    var header = try s1mHeader()
    func put(_ at: Int, _ size: Int, _ bits: UInt64) { for i in 0..<size { header[at + i] = UInt8(truncatingIfNeeded: bits >> (8 * i)) } }
    func u16(_ at: Int) -> Int { Int(header[at]) | Int(header[at + 1]) << 8 }
    // Keep real S1M georeferencing/metadata, replacing compression with a known float raster.
    for i in 0..<19 { let e = 194 + i * 12; if [259, 317].contains(u16(e)) { put(e + 8, 4, 1) } }
    let model = GeoTiffElevationSource.latLonToConusAlbers(latitude: 39, longitude: -105)
    put(846, 8, (model.x - 2500).bitPattern); put(854, 8, (model.y + 2500).bitPattern)
    for i in 0..<400 { put(1932 + i * 4, 4, UInt64(65536 + i * 1048576)); put(3532 + i * 4, 4, 1048576) }
    let piece = try #require(S1MCog(header: header).pieces(minX: model.x, maxX: model.x, minY: model.y, maxY: model.y).first)
    var file = piece.header
    for range in piece.ranges {
        let id = (range.offset - 65536) / 1048576
        var block = Data(count: 1048576)
        for r in 0..<512 { for c in 0..<512 {
            let value = Float(id / 20 * 512 + r) + Float(id % 20 * 512 + c) * 0.125
            let at = (r * 512 + c) * 4
            for i in 0..<4 { block[at + i] = UInt8(truncatingIfNeeded: value.bitPattern >> (8 * i)) }
        } }
        file.append(block)
    }
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    try file.write(to: directory.appendingPathComponent("subset.tif"))
    let sample = try #require(GeoTiffElevationSource(directory: directory).sample(latitude: 39, longitude: -105))
    #expect(abs(sample.elevationMeters - 2812.5) < 0.01)
    #expect(sample.horizontalResolutionMeters == 1)
}

@Test func s1mRejectsTruncatedMetadataAndUnsupportedProjection() throws {
    #expect(throws: (any Error).self) { try S1MCog(header: Data([73, 73, 42, 0])) }
    var header = try s1mHeader()
    // EPSG:6350 key value from the public fixture.
    for at in stride(from: 878, to: 918, by: 8) where header[at] == 0 && header[at + 1] == 12 { header[at + 6] = 0; header[at + 7] = 0 }
    #expect(throws: (any Error).self) { try S1MCog(header: header) }
}
