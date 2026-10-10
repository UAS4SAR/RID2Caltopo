import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct TrackerDeviceReplacementCandidate: Identifiable, Equatable, Sendable {
    public let id: String
    public let deviceName: String
    public let deviceModel: String
}

/// Uses the currently authorized device token. Replacement is never automatic:
/// the operator must identify the earlier physical device first.
public enum TrackerDeviceAuthorizationError: Error, Equatable {
    case reauthenticationRequired(URL)
    case authorizationRejected
    case httpStatus(Int)
}

public enum TrackerDeviceReconciliation {
    public static func validateResponse(_ data: Data, statusCode: Int) throws {
        guard !(200..<300).contains(statusCode) else { return }
        if let url = TrackerReauthenticationChallenge.url(fromHTTPError: data, statusCode: statusCode) {
            throw TrackerDeviceAuthorizationError.reauthenticationRequired(url)
        }
        if statusCode == 401 || statusCode == 403 {
            throw TrackerDeviceAuthorizationError.authorizationRejected
        }
        throw TrackerDeviceAuthorizationError.httpStatus(statusCode)
    }

    public static func request(baseURL: String, token: String, replacementID: String? = nil, deviceName: String? = nil) throws -> URLRequest {
        guard var url = URLComponents(string: baseURL), url.scheme == "https",
              let host = url.host?.lowercased(),
              host == "r2c-tracker.com" || host.hasSuffix(".r2c-tracker.com"),
              url.user == nil, url.password == nil, !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { throw URLError(.badURL) }
        url.path = "/api/v1/device-authorization/" + (deviceName != nil ? "name" : replacementID == nil ? "replacement-candidates" : "replace")
        url.query = nil
        url.fragment = nil
        guard let endpoint = url.url else { throw URLError(.badURL) }
        var request = URLRequest(url: endpoint, timeoutInterval: 20)
        request.setValue(token, forHTTPHeaderField: "X-SAR-Token")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(String(TrackerCoordinationClient.trackerFunctionalityRelease), forHTTPHeaderField: "X-R2C-Functionality-Release")
        if let deviceName {
            let clean = deviceName.trimmingCharacters(in: .whitespacesAndNewlines)
            guard replacementID == nil, !clean.isEmpty, clean.count <= 160,
                  !clean.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
            else { throw URLError(.badURL) }
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: ["device_name": clean])
        }
        if let replacementID {
            guard !replacementID.isEmpty else { throw URLError(.badURL) }
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: ["replacement_credential_id": replacementID])
        }
        return request
    }

    public static func candidates(from data: Data) throws -> [TrackerDeviceReplacementCandidate] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              (object["supported_platforms"] as? [String])?.contains("ios") == true,
              let rows = object["candidates"] as? [[String: Any]] else { throw URLError(.cannotParseResponse) }
        var ids = Set<String>()
        return rows.compactMap { row in
            guard let id = (row["credential_id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  let name = (row["device_name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !id.isEmpty, !name.isEmpty, ids.insert(id).inserted else { return nil }
            return TrackerDeviceReplacementCandidate(id: id, deviceName: name, deviceModel: row["device_model"] as? String ?? "")
        }
    }

    public static func canonicalName(from data: Data) throws -> String {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let name = (object["canonical_device_name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !name.isEmpty else { throw URLError(.cannotParseResponse) }
        return name
    }
}
