import Foundation
import Testing
@testable import R2CCore

private final class TestClock: @unchecked Sendable {
    var nowMs: Int64 = 1_000_000
}

@Suite(.serialized)
struct LogThrottleTests {
    private func installClock() -> TestClock {
        LogThrottle.resetForTest()
        let clock = TestClock()
        LogThrottle.clockMs = { clock.nowMs }
        return clock
    }

    @Test
    func emitEmitsOnChange() {
        _ = installClock()
        #expect(LogThrottle.emit(key: "k", message: "sessions=1", onChangeOnly: true) == "sessions=1")
        #expect(LogThrottle.emit(key: "k", message: "sessions=1", onChangeOnly: true) == nil)
        #expect(LogThrottle.emit(key: "k", message: "sessions=2", onChangeOnly: true) == "sessions=2")
    }

    @Test
    func emitSuppressesDuplicates() {
        _ = installClock()
        #expect(LogThrottle.emit(key: "dup", message: "same", onChangeOnly: true) != nil)
        for _ in 0..<5 {
            #expect(LogThrottle.emit(key: "dup", message: "same", onChangeOnly: true) == nil)
        }
    }

    @Test
    func emitEmitsSummaryAfterInterval() {
        let clock = installClock()
        #expect(
            LogThrottle.emit(key: "hb", message: "hb ok", minIntervalMs: 1_000, onChangeOnly: true)
                == "hb ok"
        )
        #expect(
            LogThrottle.emit(key: "hb", message: "hb ok", minIntervalMs: 1_000, onChangeOnly: true)
                == nil
        )
        #expect(
            LogThrottle.emit(key: "hb", message: "hb ok", minIntervalMs: 1_000, onChangeOnly: true)
                == nil
        )
        clock.nowMs += 1_000
        #expect(
            LogThrottle.emit(key: "hb", message: "hb ok", minIntervalMs: 1_000, onChangeOnly: true)
                == "hb ok (repeated 2×)"
        )
    }

    @Test
    func emitIntervalGateWithoutOnChange() {
        let clock = installClock()
        #expect(LogThrottle.emit(key: "rate", message: "tick", minIntervalMs: 500) == "tick")
        #expect(LogThrottle.emit(key: "rate", message: "tick", minIntervalMs: 500) == nil)
        clock.nowMs += 500
        #expect(LogThrottle.emit(key: "rate", message: "tick", minIntervalMs: 500) == "tick (repeated 1×)")
    }

    @Test
    func shouldLogIntervalGate() {
        let clock = installClock()
        #expect(LogThrottle.shouldLog(key: "s", intervalMs: 1_000))
        #expect(!LogThrottle.shouldLog(key: "s", intervalMs: 1_000))
        clock.nowMs += 999
        #expect(!LogThrottle.shouldLog(key: "s", intervalMs: 1_000))
        clock.nowMs += 1
        #expect(LogThrottle.shouldLog(key: "s", intervalMs: 1_000))
    }
}
