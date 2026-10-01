import Foundation

/// Narrow write qualification: only the user's designated disposable test map.
public enum CaltopoPersonalMarkerTest {
    public static let mapID = "G00CPSS"
    public struct Reply: Sendable {
        public let code: Int
        public let data: Data
        public init(code: Int, data: Data) { self.code = code; self.data = data }
    }
    public enum Presence { case absent, own, conflict }
    public struct Failure: Error, LocalizedError {
        public let message: String
        public init(message: String) { self.message = message }
        public var errorDescription: String? { message }
    }
    public static func title(_ id: UUID) -> String { "RID2Caltopo session test \(id.uuidString.lowercased())" }
    public static func markerPath(_ id: UUID) -> String { "/api/v1/map/\(mapID)/Marker/\(id.uuidString.lowercased())" }
    public static let readPath = "/api/v1/map/\(mapID)/since/0"
    public static func payload(_ id: UUID) throws -> String {
        let object: [String: Any] = ["id": id.uuidString.lowercased(), "type": "Feature",
            "geometry": ["type": "Point", "coordinates": [0, 0]],
            "properties": ["class": "Marker", "title": title(id),
                "description": "Temporary personal-session test at 0,0; safe to remove."]]
        return String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self)
    }
    public static func presence(_ reply: Reply, id: UUID) throws -> Presence {
        guard CaltopoPersonalProbe.result(code: reply.code, data: reply.data).readable,
              let json = try JSONSerialization.jsonObject(with: reply.data) as? [String: Any],
              let result = json["result"] as? [String: Any], let state = result["state"] as? [String: Any],
              let features = state["features"] as? [[String: Any]] else {
            throw Failure(message: "Map read failed (HTTP \(reply.code)).")
        }
        for feature in features where feature["id"] as? String == id.uuidString.lowercased() {
            let properties = feature["properties"] as? [String: Any]
            return properties?["class"] as? String == "Marker" && properties?["title"] as? String == title(id) ? .own : .conflict
        }
        return .absent
    }
    @MainActor
    public static func run(id: UUID, cleanupOnly: Bool,
                           send: (String, String, String?) async throws -> Reply,
                           savePending: (UUID) throws -> Void, clearPending: () throws -> Void) async throws -> String {
        let path = markerPath(id)
        let before = try presence(await send("GET", readPath, nil), id: id)
        guard before != .conflict else { throw Failure(message: "Marker identity does not match. Nothing was removed.") }
        if !cleanupOnly {
            guard before == .absent else { throw Failure(message: "Test marker already exists. Use cleanup.") }
            try savePending(id)
            let created = try await send("POST", path, payload(id))
            guard (200...299).contains(created.code) else {
                throw Failure(message: "Create returned HTTP \(created.code). Use cleanup to check for a possible leftover.")
            }
            guard try presence(await send("GET", readPath, nil), id: id) == .own else {
                throw Failure(message: "Created marker was not verified. Use cleanup.")
            }
        } else if before == .absent {
            try clearPending()
            return "Cleanup verified: the pending test marker is absent."
        }
        let deleted = try await send("DELETE", path, nil)
        guard (200...299).contains(deleted.code) else {
            throw Failure(message: "Remove returned HTTP \(deleted.code). Test marker cleanup is still pending.")
        }
        guard try presence(await send("GET", readPath, nil), id: id) == .absent else {
            throw Failure(message: "Test marker is still present. Cleanup remains pending.")
        }
        try clearPending()
        return cleanupOnly ? "Cleanup verified: test marker removed." : "Publishing succeeded: created, read back, removed, and verified absent on \(mapID)."
    }
}
