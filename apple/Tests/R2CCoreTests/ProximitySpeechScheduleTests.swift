import Foundation
import Testing
@testable import R2CCore

private let start = Date(timeIntervalSince1970: 1_800_000_000)

private func announce(_ schedule: inout RidProximitySpeechSchedule, _ id: Int64?, suspended: Bool = false,
                      enabled: Bool = true, at seconds: TimeInterval) -> Bool {
    schedule.shouldAnnounce(activeAlertInstanceID: id, suspended: suspended, enabled: enabled,
                            now: start.addingTimeInterval(seconds))
}

@Test func proximitySpeechRepeatsEveryThirtySecondsWhileActive() {
    #expect(RidProximitySpeechSchedule.repeatInterval == 30)
    var schedule = RidProximitySpeechSchedule()
    var announcedAt: [Int] = []
    for second in 0...95 where announce(&schedule, 7, at: TimeInterval(second)) {
        announcedAt.append(second)
    }
    #expect(announcedAt == [0, 30, 60, 90])
}

@Test func proximitySpeechDoesNotRepeatWhileSuspendedAndRestartsOnResume() {
    var schedule = RidProximitySpeechSchedule()
    let results = [
        announce(&schedule, 7, at: 0),
        announce(&schedule, nil, suspended: true, at: 5),
        announce(&schedule, nil, suspended: true, at: 40),
        announce(&schedule, 7, at: 41), // resumed: treated as newly active
        announce(&schedule, 7, at: 70),
        announce(&schedule, 7, at: 71),
    ]
    #expect(results == [true, false, false, true, false, true])
}

@Test func proximitySpeechStopsWhenClearedOrDisabledAndAnnouncesNewInstances() {
    var schedule = RidProximitySpeechSchedule()
    let results = [
        announce(&schedule, 1, at: 0),
        announce(&schedule, 2, at: 3),
        announce(&schedule, nil, at: 40),
        announce(&schedule, 3, enabled: false, at: 41),
        announce(&schedule, 3, at: 42),
    ]
    #expect(results == [true, true, false, false, true])
}
