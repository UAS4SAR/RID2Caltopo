import Foundation
import Testing
@testable import R2CCore

@Test func mapPackageEnumeratesAllCachedZoomsAndMoreThan128Tiles() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    func write(_ z: Int, _ x: Int, _ y: Int) throws {
        let dir = root.appendingPathComponent("\(z)/\(x)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data([1]).write(to: dir.appendingPathComponent("\(y).png"))
    }
    for z in [0, 5, 12, 19, 22] {
        let mid = z == 0 ? 0 : 1 << (z - 1)
        try write(z, mid, mid)
    }
    for x in 1...300 { try write(22, (1 << 21) + x, 1 << 21) }
    try write(22, 0, 0) // Outside visible region.
    let tiles = try MapPackageRegion.cachedTiles(root: root, fileExtension: "png",
        bounds: .init(north: 1, south: -1, west: -1, east: 1))
    #expect(tiles.count == 305)
    #expect(Set(tiles.map(\.zoom)) == [0, 5, 12, 19, 22])
    #expect(!tiles.contains(.init(zoom: 22, x: 0, y: 0)))
}

@Test func mapPackageIncludesOverlappingTilesAndExcludesTouchingEdges() {
    let bounds = OperationalMapBounds(north: 1, south: -1, west: -1, east: 1)
    #expect(MapPackageRegion.includes(.init(zoom: 0, x: 0, y: 0), in: bounds))
    #expect(!MapPackageRegion.overlaps(bounds, .init(north: 2, south: 1, west: 1, east: 2)))
    #expect(!MapPackageRegion.includes(.init(zoom: 22, x: -1, y: 0), in: bounds))
}
