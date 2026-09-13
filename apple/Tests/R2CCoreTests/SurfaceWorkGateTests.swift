import Foundation
import Testing
@testable import R2CCore

@Test func surfaceWorkGateDropsDisabledBusyAndFrequentRequests() {
    var gate = OperationalSurfaceWorkGate()
    let admitted0 = !gate.begin(aircraft: "a", enabled: false, now: 0)
    #expect(admitted0)
    let admitted1 = gate.begin(aircraft: "a", enabled: true, now: 0)
    #expect(admitted1)
    let admitted2 = !gate.begin(aircraft: "b", enabled: true, now: 5)
    #expect(admitted2)
    gate.finish()
    let admitted3 = !gate.begin(aircraft: "a", enabled: true, now: 0.999)
    #expect(admitted3)
    let admitted4 = gate.begin(aircraft: "b", enabled: true, now: 0.999)
    #expect(admitted4)
    gate.finish()
    let admitted5 = gate.begin(aircraft: "a", enabled: true, now: 1)
    #expect(admitted5)
    gate.finish()
    let admitted6 = !gate.begin(aircraft: "a", enabled: false, now: 5)
    #expect(admitted6)
    let admitted7 = gate.begin(aircraft: "a", enabled: true, now: 5)
    #expect(admitted7)
}

@Test func surfaceWorkGateForgetCannotOpenBusyWorker() {
    var gate = OperationalSurfaceWorkGate()
    let admitted8 = gate.begin(aircraft: "a", enabled: true, now: 0)
    #expect(admitted8)
    gate.forget(aircraft: "a")
    let admitted9 = !gate.begin(aircraft: "a", enabled: true, now: 0.001)
    #expect(admitted9)
    gate.finish()
    let admitted10 = gate.begin(aircraft: "a", enabled: true, now: 0.001)
    #expect(admitted10)
    #expect(gate.lastStartedAt(aircraft: "b") < gate.lastStartedAt(aircraft: "a"))
}
