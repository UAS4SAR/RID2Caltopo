import Foundation
import Testing
@testable import R2CCore

private actor Log {
    var entries: [String] = []
    func add(_ entry: String) { entries.append(entry) }
}

@Suite("Recording finalization chain (P6)")
struct SerialTaskChainTests {
    @Test func enqueueNeverWaitsForSlowWork() async {
        let chain = SerialTaskChain()
        let gate = AsyncStream<Void>.makeStream()
        let started = Date()
        chain.enqueue { for await _ in gate.stream { break } }  // a "normalize" that blocks
        chain.enqueue { }
        // The event loop gets control back at once while the first job is still blocked.
        #expect(Date().timeIntervalSince(started) < 0.1)
        gate.continuation.yield()
        gate.continuation.finish()
    }

    @Test func jobsRunInEnqueueOrder() async {
        let chain = SerialTaskChain()
        let log = Log()
        for index in 0..<20 {
            chain.enqueue {
                if index % 3 == 0 { try? await Task.sleep(nanoseconds: 2_000_000) }
                await log.add("\(index)")
            }
        }
        await chain.enqueue { }.value
        #expect(await log.entries == (0..<20).map(String.init))
    }

    @Test func lifecycleEventsAreDeliveredWhileFinalizationIsBlocked() async {
        // Models the MediaMTX event loop: recordFileCompleted goes to the chain, stream
        // lifecycle is delivered inline. streamStopped must not wait on normalize.
        let chain = SerialTaskChain()
        let log = Log()
        let gate = AsyncStream<Void>.makeStream()
        chain.enqueue {
            for await _ in gate.stream { break }
            await log.add("recordFileCompleted")
        }
        await log.add("streamStopped")
        await log.add("streamStarted")
        #expect(await log.entries == ["streamStopped", "streamStarted"])
        gate.continuation.yield()
        gate.continuation.finish()
        await chain.enqueue { }.value
        #expect(await log.entries.last == "recordFileCompleted")
    }
}
