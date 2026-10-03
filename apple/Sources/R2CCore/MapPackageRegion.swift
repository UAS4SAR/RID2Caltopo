import Foundation

/// Geographic overlap for cache export, independent of preparation presets.
public enum MapPackageRegion {
    public static func overlaps(_ a: OperationalMapBounds, _ b: OperationalMapBounds) -> Bool {
        guard a.north > b.south, a.south < b.north else { return false }
        func segments(_ v: OperationalMapBounds) -> [(Double, Double)] {
            v.west <= v.east ? [(v.west, v.east)] : [(v.west, 180), (-180, v.east)]
        }
        return segments(a).contains { x in segments(b).contains { y in x.0 < y.1 && x.1 > y.0 } }
    }

    public static func includes(_ tile: OperationalOfflineTile, in bounds: OperationalMapBounds) -> Bool {
        guard (0...29).contains(tile.zoom) else { return false }
        let n = pow(2.0, Double(tile.zoom))
        guard tile.x >= 0, tile.y >= 0, Double(tile.x) < n, Double(tile.y) < n else { return false }
        func latitude(_ y: Int) -> Double { atan(sinh(.pi * (1 - 2 * Double(y) / n))) * 180 / .pi }
        return overlaps(bounds, .init(north: latitude(tile.y), south: latitude(tile.y + 1),
                                     west: Double(tile.x) / n * 360 - 180, east: Double(tile.x + 1) / n * 360 - 180))
    }

    public static func cachedTiles(root: URL, fileExtension: String, bounds: OperationalMapBounds) throws -> [OperationalOfflineTile] {
        let root = root.resolvingSymlinksInPath()
        guard FileManager.default.fileExists(atPath: root.path) else { return [] }
        var failure: Error?
        guard let files = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles], errorHandler: { _, error in failure = error; return false }) else { return [] }
        var result: [OperationalOfflineTile] = []
        for case let file as URL in files {
            guard file.pathExtension == fileExtension,
                  try file.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true else { continue }
            let path = Array(file.pathComponents.suffix(3))
            guard files.level == 3, let z = Int(path[0]), let x = Int(path[1]),
                  let y = Int(file.deletingPathExtension().lastPathComponent) else { continue }
            let tile = OperationalOfflineTile(zoom: z, x: x, y: y)
            if includes(tile, in: bounds) { result.append(tile) }
        }
        if let failure { throw failure }
        return result
    }
}
