import Foundation

/// Combine overlapping accepted-track and live-stream histories without joining
/// the newest primary point back to the beginning of the supplementary history.
public enum OperationalTrackTimeline {
    public static func merged<Point>(
        primary: [Point],
        supplementary: [Point],
        receivedAt: (Point) -> Date
    ) -> [Point] {
        let start = primary.map(receivedAt).min()
        var byTime: [Date: Point] = [:]
        for point in supplementary {
            let time = receivedAt(point)
            if let start, time < start { continue }
            byTime[time] = point
        }
        // Accepted track data wins when both paths carry the same sample.
        for point in primary { byTime[receivedAt(point)] = point }
        return byTime.keys.sorted().compactMap { byTime[$0] }
    }
}
