import Foundation
import Testing
@testable import R2CCore

private actor CancellationGate {
    private var opened = false
    private var waiter: CheckedContinuation<Void, Never>?
    func wait() async {
        if !opened { await withCheckedContinuation { waiter = $0 } }
    }
    func open() { opened = true; waiter?.resume(); waiter = nil }
}

@Test func damagedJournalIsReportedAndCannotBeOverwrittenByAnyMutation() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: root) }
    let url = root.appendingPathComponent("history.json"), damaged = Data("[{interrupted".utf8)
    try damaged.write(to: url)
    let journal = await AwaitingMapFlightJournal.loaded(fileURL: url)
    #expect(await journal.isExecutingOnMainThread() == false)
    #expect(await journal.loadError != nil)
    let observation = RidObservation(source: .bluetoothLegacy, aircraftId: "test", receivedAt: Date(),
                                     latitude: 39, longitude: -121, altitudeMeters: 10)
    let operations: [@Sendable () async throws -> Void] = [
        { try await journal.record(remoteID: "test", label: "Test", observations: [observation], mapID: "", teamID: "", finished: false) },
        { try await journal.decide(id: "absent", mapID: nil, teamID: "") },
        { try await journal.notePublication(remoteID: "test", mapID: "map") },
        { try await journal.discard(remoteID: "test", startedAt: Date()) },
        { _ = try await journal.discard(id: "absent") },
        { try await journal.markQueued(id: "absent") }
    ]
    for operation in operations {
        await #expect(throws: AwaitingMapFlightJournalError.self) { try await operation() }
        #expect(try Data(contentsOf: url) == damaged)
    }
    try JSONEncoder().encode([AwaitingMapFlight]()).write(to: url, options: .atomic)
    await journal.retryLoading()
    #expect(await journal.loadError == nil)
    try await journal.record(remoteID: "test", label: "Test", observations: [observation], mapID: "", teamID: "", finished: false)
    #expect(await journal.entries.count == 1)
}

@Test func cancelledHistoryMutationDoesNotCommitOrEraseData() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let url = root.appendingPathComponent("history.json")
    let journal = await AwaitingMapFlightJournal.loaded(fileURL: url)
    let observation = RidObservation(source: .bluetoothLegacy, aircraftId: "test", receivedAt: Date(),
                                     latitude: 39, longitude: -121, altitudeMeters: 10)
    try await journal.record(remoteID: "test", label: "Saved", observations: [observation], mapID: "", teamID: "", finished: false)
    let before = try Data(contentsOf: url)
    let gate = CancellationGate()
    let task = Task {
        await gate.wait()
        try await journal.discard(remoteID: "test", startedAt: observation.receivedAt)
    }
    task.cancel()
    await gate.open()
    await #expect(throws: CancellationError.self) { try await task.value }
    #expect(try Data(contentsOf: url) == before)
}

@Test func unreadableJournalIsNotAnEmptySuccessfulHistory() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let url = root.appendingPathComponent("history.json")
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    let journal = await AwaitingMapFlightJournal.loaded(fileURL: url)
    #expect(await journal.loadError != nil)
    await #expect(throws: AwaitingMapFlightJournalError.self) { try await journal.markQueued(id: "absent") }
    #expect(FileManager.default.fileExists(atPath: url.path))
}

@Test func reviewGeometryAndOwnershipRunOutsideTheMainThread() async throws {
    let worker = AwaitingMapReviewWorker()
    #expect(await worker.isExecutingOnMainThread() == false)
    let now = Date()
    let points = (0..<20_000).map { index in
        RidObservation(source: .bluetoothLegacy, aircraftId: "test", receivedAt: now.addingTimeInterval(Double(index - 20_000)),
                       latitude: 39, longitude: -121, altitudeMeters: 10)
    }
    let flight = AwaitingMapFlight(id: "flight", remoteID: "test", label: "Long flight", observations: points, teamID: "")
    let result = try await worker.prepare(flights: [flight], allFlights: [flight], clues: [],
        icLocations: [.init(latitude: 39, longitude: -121)], latitude: 39, longitude: -121,
        hasMap: false, reminder: .init(), now: now)
    #expect(result.rows.first?.nearIC == true)
    #expect(result.rows.first?.proximity == .inside)
    #expect(result.shouldRemind)
    let gate = CancellationGate()
    let cancelled = Task {
        await gate.wait()
        return try await worker.prepare(flights: [flight], allFlights: [flight], clues: [], icLocations: [],
            latitude: nil, longitude: nil, hasMap: false, reminder: result.reminder)
    }
    cancelled.cancel()
    await gate.open()
    await #expect(throws: CancellationError.self) { try await cancelled.value }
}
