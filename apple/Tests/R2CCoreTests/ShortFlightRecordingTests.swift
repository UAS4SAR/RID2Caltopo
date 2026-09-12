import Foundation
import Testing
@testable import R2CCore

@Test func shortFlightRequiresBothStrictLimits() {
    #expect(ShortFlightRecordingPolicy.qualifies(seconds: 59.999, meters: 160.9343))
    #expect(ShortFlightRecordingPolicy.qualifies(seconds: 0, meters: 0))
    #expect(!ShortFlightRecordingPolicy.qualifies(seconds: 60, meters: 0))
    #expect(!ShortFlightRecordingPolicy.qualifies(seconds: 1, meters: 160.9344))
    #expect(!ShortFlightRecordingPolicy.qualifies(seconds: .nan, meters: 0))
    #expect(!ShortFlightRecordingPolicy.qualifies(seconds: -1, meters: 0))
}

@Test @MainActor func shortFlightTimeoutWinsOverLateNoExactlyOnce() {
    var now = 0.0; var saved = 0
    let gate = ShortFlightRecordingGate(now: { now })
    gate.setActive(true)
    #expect(gate.request(aircraft: "A", seconds: 10, meters: 1) { saved += 1 })
    let id = gate.prompts[0].id
    now = 9.999; gate.expire(); #expect(saved == 0)
    now = 10; gate.decide(id, record: false); gate.expire()
    #expect(saved == 1)
    #expect(gate.prompts.isEmpty)
}

@Test @MainActor func shortFlightChoicesAreIndependentAndBackgroundKeepsPending() {
    let gate = ShortFlightRecordingGate(now: { 0 })
    var savedA = 0; var savedB = 0
    gate.setActive(true)
    #expect(gate.request(aircraft: "A", seconds: 10, meters: 1) { savedA += 1 })
    #expect(gate.request(aircraft: "B", seconds: 10, meters: 1) { savedB += 1 })
    gate.decide(gate.prompts[0].id, record: false)
    gate.setActive(false)
    #expect(savedA == 0 && savedB == 1)
    #expect(!gate.request(aircraft: "C", seconds: 10, meters: 1) { savedA += 1 })
}

@Test @MainActor func shortFlightYesRecordsImmediatelyWithoutDuplicateTimeout() {
    var now = 0.0; var saved = 0
    let gate = ShortFlightRecordingGate(now: { now })
    gate.setActive(true)
    #expect(gate.request(aircraft: "A", seconds: 10, meters: 1) { saved += 1 })
    gate.decide(gate.prompts[0].id, record: true)
    now = 20; gate.expire()
    #expect(saved == 1)
}

@Test @MainActor func shortFlightNoResponseAutomaticallyRecordsAtTenSeconds() {
    var now = 0.0; var saved = 0
    let gate = ShortFlightRecordingGate(now: { now })
    gate.setActive(true)
    #expect(gate.request(aircraft: "A", seconds: 10, meters: 1) { saved += 1 })
    now = 10; gate.expire(); gate.expire()
    #expect(saved == 1)
    #expect(gate.prompts.isEmpty)
}
