import Foundation

/// Worker-only index loading. Missing indexes are empty; unreadable indexes are errors.
///
/// Reads `<day>/<flightId>/clues.json` (per-flight layout) and any not-yet-migrated day-level
/// `<day>/clues.json`. A clue that appears in both (interrupted migration) keeps the confirmed or
/// flight-nested copy.
public enum OperationalClueIndex {
    public static func load(root: URL) throws -> [OperationalClueRecord] {
        var byID: [UUID: OperationalClueRecord] = [:]
        var order: [UUID] = []
        for directory in try AppleClueFlightFolder.indexDirectories(root: root) {
            let data: Data
            do { data = try Data(contentsOf: directory.appendingPathComponent(AppleClueFlightFolder.indexFilename)) }
            catch let error as CocoaError where error.code == .fileReadNoSuchFile { continue }
            for record in try JSONDecoder().decode([OperationalClueRecord].self, from: data) {
                if let prior = byID[record.id] {
                    if prefers(record, over: prior) { byID[record.id] = record }
                } else {
                    byID[record.id] = record
                    order.append(record.id)
                }
            }
        }
        // A missing/unavailable image is not permission to discard its durable metadata.
        return order.compactMap { byID[$0] }.sorted { $0.capturedAt > $1.capturedAt }
    }

    private static func prefers(_ candidate: OperationalClueRecord, over prior: OperationalClueRecord) -> Bool {
        if candidate.uploadConfirmed != prior.uploadConfirmed { return candidate.uploadConfirmed }
        let candidateNested = AppleClueFlightFolder.flightFolder(ofRelativePath: candidate.imageFilename) != nil
        let priorNested = AppleClueFlightFolder.flightFolder(ofRelativePath: prior.imageFilename) != nil
        return candidateNested && !priorNested
    }
}
