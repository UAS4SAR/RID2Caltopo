import Foundation

/// Display-only outer boundary fade in projected metres. Original tiles stay unchanged.
public struct MappingBoundaryFade: Sendable {
    public enum Coverage: Equatable, Sendable { case inside, outside, edge }
    private struct Point: Sendable { let x, y: Double }
    private let points: [Point]
    private let scale: Double
    public static let world = 2 * Double.pi * 6_378_137

    public init?(ring: [[Double]]) {
        guard (3...2000).contains(ring.count), ring.allSatisfy({
            $0.count >= 2 && $0[0].isFinite && $0[1].isFinite && abs($0[1]) < 85
        }) else { return nil }
        points = ring.map { Point(x: $0[0] * .pi / 180 * 6_378_137,
                                 y: log(tan(.pi / 4 + $0[1] * .pi / 360)) * 6_378_137) }
        scale = cos(ring.map { $0[1] }.reduce(0, +) / Double(ring.count) * .pi / 180)
    }

    private func contains(x: Double, y: Double) -> Bool {
        var inside = false
        var previous = points.last!
        for point in points {
            if (point.y > y) != (previous.y > y),
               x < (previous.x - point.x) * (y - point.y) / (previous.y - point.y) + point.x {
                inside.toggle()
            }
            previous = point
        }
        return inside
    }

    /// An exact segment/rectangle intersection avoids classifying interior tiles
    /// as edge tiles merely because a diagonal edge's bounding box overlaps them.
    private func boundaryIntersects(west: Double, south: Double, east: Double, north: Double) -> Bool {
        var a = points.last!
        for b in points {
            let dx = b.x - a.x, dy = b.y - a.y
            var low = 0.0, high = 1.0
            var intersects = true
            for (p, q) in [(-dx, a.x - west), (dx, east - a.x), (-dy, a.y - south), (dy, north - a.y)] {
                if p == 0 {
                    if q < 0 { intersects = false; break }
                } else if p < 0 {
                    low = max(low, q / p)
                } else {
                    high = min(high, q / p)
                }
                if low > high { intersects = false; break }
            }
            if intersects { return true }
            a = b
        }
        return false
    }

    public func coverage(zoom: Int, x: Int, y: Int) -> Coverage {
        let unit = Self.world / pow(2, Double(zoom))
        let west = Double(x) * unit - Self.world / 2, east = west + unit
        let north = Self.world / 2 - Double(y) * unit, south = north - unit
        if boundaryIntersects(west: west, south: south, east: east, north: north) { return .edge }
        if contains(x: (west + east) / 2, y: (south + north) / 2) { return .inside }
        let fade = 5 / scale
        return boundaryIntersects(west: west - fade, south: south - fade, east: east + fade, north: north + fade)
            ? .edge : .outside
    }

    public func opacity(projectedX x: Double, projectedY y: Double) -> Double {
        if contains(x: x, y: y) { return 1 }
        var distance = Double.infinity
        var a = points.last!
        for b in points {
            let dx = b.x - a.x, dy = b.y - a.y, squared = dx * dx + dy * dy
            let t = squared > 0 ? min(1, max(0, ((x - a.x) * dx + (y - a.y) * dy) / squared)) : 0
            distance = min(distance, hypot(x - a.x - t * dx, y - a.y - t * dy))
            a = b
        }
        let t = min(1, max(0, distance * scale / 5))
        return 1 - t * t * (3 - 2 * t)
    }
}
