import Foundation
import Testing
@testable import R2CCore

private actor UploadAttempts {
    var count = 0
    func upload() async -> Int {
        count += 1
        let attempt = count
        try? await Task.sleep(for: .milliseconds(100))
        return attempt
    }
}

@Test func concurrentArchiveAndReplayShareUploadAndLaterRetryIsAllowed() async {
    let work = TrackerArchiveUploadWork<Int>()
    let attempts = UploadAttempts()
    let results = await withTaskGroup(of: Int.self, returning: [Int].self) { group in
        for _ in 0..<20 {
            group.addTask { await work.run(key: "flight.json") { await attempts.upload() } }
        }
        var values: [Int] = []
        for await value in group { values.append(value) }
        return values
    }
    #expect(results.count == 20)
    #expect(results.allSatisfy { $0 == 1 })
    #expect(await attempts.count == 1)
    #expect(await work.run(key: "flight.json") { await attempts.upload() } == 2)
}
