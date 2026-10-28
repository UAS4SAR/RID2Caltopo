import Foundation
import Testing
@testable import R2CCore

struct MapStorageInventoryTests {
    @Test func aliasesAndOverlappingRootsCountOnce() async throws {
        try await Task.detached {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: root) }
            let project = root.appendingPathComponent("project")
            try FileManager.default.createDirectory(at: project, withIntermediateDirectories: true)
            try Data(repeating: 1, count: 17).write(to: project.appendingPathComponent("original.jpg"))
            let alias = root.appendingPathComponent("alias")
            try FileManager.default.createSymbolicLink(at: alias, withDestinationURL: project)
            let result = try MapStorageInventory.measure([[project], [alias, project]])
            #expect(result.bytes == [17, 0])
            #expect(result.complete)
        }.value
    }

    @Test func inventoryStopsOnCancellation() async throws {
        await Task.detached {
            #expect(throws: CancellationError.self) {
                try MapStorageInventory.measure([[FileManager.default.temporaryDirectory]], cancelled: { true })
            }
        }.value
    }
}
