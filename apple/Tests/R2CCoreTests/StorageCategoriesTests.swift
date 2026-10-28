import Foundation
import Testing
@testable import R2CCore

struct StorageCategoriesTests {
    @Test func retainedCategoriesAndHardLinksCountActualBytesOnce() async throws {
        try await Task.detached {
            let manager = FileManager.default
            let root = manager.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            defer { try? manager.removeItem(at: root) }
            let mission = root.appendingPathComponent("missions/one")
            for (name, count) in [("originals/photo.jpg", 10), ("results/map/tiles/one.png", 20), ("work/checkpoint.json", 30)] {
                let file = mission.appendingPathComponent(name)
                try manager.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
                try Data(repeating: 1, count: count).write(to: file)
            }
            let link = root.appendingPathComponent("shared.jpg")
            try manager.linkItem(at: mission.appendingPathComponent("originals/photo.jpg"), to: link)
            let report = try StorageCategoryReport.measure([(.projects, root.appendingPathComponent("missions")), (.other, root)])
            #expect(report.bytes[.projects] == 10)
            #expect(report.bytes[.layers] == 20)
            #expect(report.bytes[.checkpoints] == 30)
            #expect(report.total == 60)
            #expect(report.complete)
            let inventory = try MapStorageInventory.measure([[root]])
            #expect(inventory.bytes == [60])
        }.value
    }
}
