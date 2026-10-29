import Foundation
import Testing
@testable import R2CCore

@Test func corruptClueIndexIsNotSilentlyAnEmptyDay() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let day = root.appendingPathComponent(AppleFlightStorage.dayName(Date()))
    try FileManager.default.createDirectory(at: day, withIntermediateDirectories: true)
    let index = day.appendingPathComponent("clues.json"), original = Data("[{torn index".utf8)
    try original.write(to: index)
    #expect(throws: (any Error).self) { try OperationalClueIndex.load(root: root) }
    #expect(try Data(contentsOf: index) == original)
}

@Test func missingClueRootIsEmptyButUnreadableRootIsAnError() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    #expect(try OperationalClueIndex.load(root: root).isEmpty)
    try Data([1]).write(to: root)
    #expect(throws: (any Error).self) { try OperationalClueIndex.load(root: root) }
}
