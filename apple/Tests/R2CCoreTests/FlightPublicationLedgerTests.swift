import Foundation
import Testing
@testable import R2CCore

@Suite("FlightPublicationLedger")
struct FlightPublicationLedgerTests {
    @Test func declinedBlocksPublicationAndAwaitingMap() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let ledger = FlightPublicationLedger(fileURL: root.appendingPathComponent("ledger.json"))
        try await ledger.remember(flightID: "flight-a", disposition: .declined)
        #expect(try await ledger.disposition(flightID: "flight-a") == .declined)
        #expect(try await ledger.blocksPublication(flightID: "flight-a"))
        #expect(try await ledger.blocksAwaitingMapPromotion(flightID: "flight-a"))
        #expect(try await ledger.isDeclinedOrSuppressed(flightID: "flight-a"))
    }

    @Test func publishedWinsOverSuppressed() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let ledger = FlightPublicationLedger(fileURL: root.appendingPathComponent("ledger.json"))
        try await ledger.remember(flightID: "flight-c", disposition: .suppressed)
        try await ledger.remember(flightID: "flight-c", disposition: .published)
        #expect(try await ledger.disposition(flightID: "flight-c") == .published)
        try await ledger.remember(flightID: "flight-c", disposition: .declined)
        #expect(try await ledger.disposition(flightID: "flight-c") == .published)
    }

    @Test func survivesReload() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let file = root.appendingPathComponent("ledger.json")
        let first = FlightPublicationLedger(fileURL: file)
        try await first.remember(flightID: "flight-d", disposition: .localArchived)
        let second = FlightPublicationLedger(fileURL: file)
        #expect(try await second.disposition(flightID: "flight-d") == .localArchived)
    }

    @Test func inboxHelperHonoursLedgerBlock() {
        #expect(!TrackArchiveConfirmation.showsInAwaitingPublicationInbox(
            decision: "review", finished: true, archiveConfirmed: false, ledgerBlocksPromotion: true))
        #expect(TrackArchiveConfirmation.showsInAwaitingPublicationInbox(
            decision: "review", finished: true, archiveConfirmed: false, ledgerBlocksPromotion: false))
    }
}
