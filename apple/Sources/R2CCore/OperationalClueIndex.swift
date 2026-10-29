import Foundation

/// Worker-only index loading. Missing indexes are empty; unreadable indexes are errors.
public enum OperationalClueIndex {
    public static func load(root: URL) throws -> [OperationalClueRecord] {
        let directories: [URL]
        do { directories = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) }
        catch let error as CocoaError where error.code == .fileReadNoSuchFile { return [] }
        var records: [OperationalClueRecord] = []
        for directory in directories where AppleFlightStorage.date(directory.lastPathComponent) != nil {
            let data: Data
            do { data = try Data(contentsOf: directory.appendingPathComponent("clues.json")) }
            catch let error as CocoaError where error.code == .fileReadNoSuchFile { continue }
            records += try JSONDecoder().decode([OperationalClueRecord].self, from: data)
        }
        // A missing/unavailable image is not permission to discard its durable metadata.
        return records.sorted { $0.capturedAt > $1.capturedAt }
    }
}
