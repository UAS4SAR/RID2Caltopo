import Testing
@testable import R2CCore

@Test func networkChecksRecoverOncePerAvailableTransition() {
    var gate = NetworkCheckRecoveryGate()
    let inputs = [false, false, true] + Array(repeating: true, count: 10) + [false, true]
    let results = inputs.map { gate.update(available: $0) }
    #expect(results == [false, false, true] + Array(repeating: false, count: 10) + [false, true])
}

@Test func networkChecksRefreshOnceWhenStartingOnline() {
    var gate = NetworkCheckRecoveryGate()
    let first = gate.update(available: true)
    let second = gate.update(available: true)
    #expect(first)
    #expect(!second)
}
