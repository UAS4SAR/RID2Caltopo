import Foundation
import Testing
@testable import R2CCore

struct AlertCoordinatorScheduleTests {
    @Test func maintenanceRunsFirstThenEveryFifteenSeconds() {
        var schedule = AlertCoordinatorSchedule()
        let start = Date(timeIntervalSince1970: 1_000)
        let first = schedule.maintenanceDue(at: start)
        let early = schedule.maintenanceDue(at: start.addingTimeInterval(14))
        let due = schedule.maintenanceDue(at: start.addingTimeInterval(15))
        #expect(first)
        #expect(!early)
        #expect(due)
    }

    @Test func heartbeatOnlyInBackgroundAndRearmsOnForeground() {
        var schedule = AlertCoordinatorSchedule()
        let start = Date(timeIntervalSince1970: 2_000)
        let foreground = schedule.heartbeatDue(at: start, inBackground: false)
        let firstBackground = schedule.heartbeatDue(at: start.addingTimeInterval(1), inBackground: true)
        let tooSoon = schedule.heartbeatDue(at: start.addingTimeInterval(10), inBackground: true)
        let next = schedule.heartbeatDue(at: start.addingTimeInterval(16), inBackground: true)
        let backToForeground = schedule.heartbeatDue(at: start.addingTimeInterval(17), inBackground: false)
        let relocked = schedule.heartbeatDue(at: start.addingTimeInterval(18), inBackground: true)
        #expect(!foreground)
        #expect(firstBackground)
        #expect(!tooSoon)
        #expect(next)
        #expect(!backToForeground)
        #expect(relocked)
    }

    @Test func freshnessSummaryUsesProximityPositionAgeLimit() {
        let now = Date(timeIntervalSince1970: 3_000)
        let summary = AlertTrackFreshnessSummary(
            sampleDates: [now.addingTimeInterval(-1), now.addingTimeInterval(-5), now.addingTimeInterval(-9)],
            now: now
        )
        #expect(summary.trackCount == 3)
        #expect(summary.freshCount == 2)
        #expect(summary.logDescription == "tracks=3 fresh=2 newestPositionAge=1.0s")
        let empty = AlertTrackFreshnessSummary(sampleDates: [], now: now)
        #expect(empty.logDescription == "tracks=0 fresh=0 newestPositionAge=none")
    }
}
