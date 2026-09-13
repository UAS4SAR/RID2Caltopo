import Foundation
import Testing
@testable import R2CCore

private struct TimelinePoint: Equatable {
    let name: String
    let time: Date
    init(_ name: String, _ seconds: Double) {
        self.name = name
        self.time = Date(timeIntervalSince1970: seconds)
    }
}

@Test func overlappingVideoTrackDoesNotReturnToLaunch() {
    let accepted = [TimelinePoint("launch", 0), .init("left-down", 2), .init("turn", 4), .init("straight", 6)]
    let streamed = [TimelinePoint("duplicate-launch", 0), .init("intermediate", 3), .init("duplicate-turn", 4), .init("newest", 7)]
    let merged = OperationalTrackTimeline.merged(primary: accepted, supplementary: streamed, receivedAt: { $0.time })
    #expect(merged.map(\.name) == ["launch", "left-down", "intermediate", "turn", "straight", "newest"])
    #expect(zip(merged, merged.dropFirst()).allSatisfy { $0.time < $1.time })
}

@Test func trackMergeExcludesPreviousFlightAndOrdersUnsortedInputs() {
    let merged = OperationalTrackTimeline.merged(
        primary: [TimelinePoint("later", 12), .init("new-flight", 10)],
        supplementary: [TimelinePoint("old-flight", 1), .init("middle", 11)],
        receivedAt: { $0.time })
    #expect(merged.map(\.name) == ["new-flight", "middle", "later"])
    let onlyStream = OperationalTrackTimeline.merged(primary: [TimelinePoint](),
        supplementary: [TimelinePoint("later", 2), .init("first", 1)], receivedAt: { $0.time })
    #expect(onlyStream.map(\.name) == ["first", "later"])
}

@Test func recordedIPadFlightDoesNotGainArtificialReturnSegment() throws {
    struct Point: Decodable, Equatable {
        let latitude: Double
        let longitude: Double
        let timestampMilliseconds: Double
        var date: Date { Date(timeIntervalSince1970: timestampMilliseconds / 1000) }
    }
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    let data = try Data(contentsOf: root.appendingPathComponent("test-fixtures/video-track/translated-flight.json"))
    let captured = try JSONDecoder().decode([Point].self, from: data)
    func largestStep(_ points: [Point]) -> Double {
        zip(points, points.dropFirst()).map { a,b in
            RidGeometry.relativePosition(fromLatitude: a.latitude, longitude: a.longitude,
                toLatitude: b.latitude, longitude: b.longitude)?.distanceMeters ?? 0
        }.max() ?? 0
    }
    #expect(largestStep(captured + captured) > 50)
    let merged = OperationalTrackTimeline.merged(primary: captured, supplementary: captured, receivedAt: { $0.date })
    #expect(merged == captured)
    #expect(largestStep(merged) < 10)
}
