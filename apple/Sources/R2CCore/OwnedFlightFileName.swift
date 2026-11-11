import Foundation

/// The archive file is named from the owned flight, not from points recorded while another tablet held the lease.
public enum OwnedFlightFileName {
    /// A grant that is later than the first stored point is the shadow start. The file uses the grant.
    public static func stamp(grant: Date?, firstStored: Date) -> Date {
        if let grant, firstStored < grant { return grant }
        return firstStored
    }

    /// Moves a file created at [shadowStart] onto the grant name. Does not overwrite a file already there.
    /// Returns the grant file name. A failed move leaves the source in place.
    @discardableResult
    public static func adopt(
        directory: URL,
        aircraftID: String,
        shadowStart: Date,
        grant: Date,
        timeZone: TimeZone,
        fileManager: FileManager = .default
    ) -> String {
        let grantName = RidTrackGeoJSON.suggestedFilenameBase(
            aircraftID: aircraftID, startDate: grant, timeZone: timeZone) + ".json"
        guard shadowStart != grant else { return grantName }
        let shadowName = RidTrackGeoJSON.suggestedFilenameBase(
            aircraftID: aircraftID, startDate: shadowStart, timeZone: timeZone) + ".json"
        movePair(directory: directory, from: shadowName, to: grantName, fileManager: fileManager)
        return grantName
    }

    private static func movePair(directory: URL, from: String, to: String, fileManager: FileManager) {
        moveOne(directory: directory, from: from, to: to, fileManager: fileManager)
        let fromKmz = URL(fileURLWithPath: from).deletingPathExtension().appendingPathExtension("kmz").lastPathComponent
        let toKmz = URL(fileURLWithPath: to).deletingPathExtension().appendingPathExtension("kmz").lastPathComponent
        moveOne(directory: directory, from: fromKmz, to: toKmz, fileManager: fileManager)
    }

    private static func moveOne(directory: URL, from: String, to: String, fileManager: FileManager) {
        let source = directory.appendingPathComponent(from)
        let destination = directory.appendingPathComponent(to)
        guard fileManager.fileExists(atPath: source.path), !fileManager.fileExists(atPath: destination.path) else { return }
        do {
            try fileManager.moveItem(at: source, to: destination)
        } catch {
            // The source stays. The grant-named write still has the owned flight.
        }
    }
}
