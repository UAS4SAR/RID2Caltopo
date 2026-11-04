import Foundation
@testable import R2CCore

/// Test builders for the per-flight clue layout `<day>/<flightId>/…`.
enum ClueFlightFolderTestSupport {
    static func binding(flightID: String?, aircraftID: String = "RID-1", captureMs: Int64 = 1_700_000_000_000) -> ClueBinding {
        ClueBinding(aircraftID: aircraftID, flightID: flightID, flightStartMs: nil, captureTimeMs: captureMs,
                    captureTimeSource: "app-receive", captureReceivedAtMs: captureMs, waypoint: nil, offsetMs: nil,
                    nearestOffsetMs: nil, quality: .unbound, hovering: false, originIsWaypoint: false, framePosition: nil)
    }

    static func record(
        id: UUID = UUID(),
        image: String,
        state: OperationalClueUploadState = .pending,
        flightID: String? = nil,
        aircraftID: String = "RID-1",
        capturedAt: Date = Date(timeIntervalSince1970: 1_700_000_000)
    ) -> OperationalClueRecord {
        OperationalClueRecord(
            id: id, capturedAt: capturedAt, aircraftID: aircraftID, designator: "ALPHA",
            droneLatitude: 39.1, droneLongitude: -121.1, droneAltitudeMeters: 100,
            clueLatitude: 39.11, clueLongitude: -121.11, clueAltitudeMeters: nil,
            headingDegrees: nil, aglMeters: nil, atoMeters: nil, gimbalAngleDegrees: -45,
            title: "Clue", clueDescription: "", imageFilename: image,
            thumbnailFilename: image.replacingOccurrences(of: ".jpg", with: "-thumb.jpg"),
            uploadState: state,
            binding: flightID.map {
                binding(flightID: $0, aircraftID: aircraftID,
                        captureMs: Int64(capturedAt.timeIntervalSince1970 * 1_000))
            }
        )
    }

    static func tempRoot() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("clue-flight-\(UUID().uuidString)", isDirectory: true)
    }

    static func write(_ records: [OperationalClueRecord], to directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try JSONEncoder().encode(records).write(to: directory.appendingPathComponent("clues.json"))
    }

    static func photo(_ name: String, in directory: URL, bytes: [UInt8] = [0xFF, 0xD8, 0xFF, 0xD9]) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data(bytes).write(to: directory.appendingPathComponent(name))
    }

    static func exists(_ url: URL) -> Bool { FileManager.default.fileExists(atPath: url.path) }
}
