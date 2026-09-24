import Foundation

public struct TrackerArchiveUploadConfiguration: Sendable, Equatable {
    public let urlPrefix: String
    public let apiKey: String
    public let organization: String

    public init(urlPrefix: String, apiKey: String, organization: String) {
        self.urlPrefix = TrackerCoordinationEndpoint.organizationScopedPrefix(
            from: urlPrefix,
            organization: organization
        )
        self.apiKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        self.organization = organization.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    public var isConfigured: Bool {
        !urlPrefix.isEmpty && !apiKey.isEmpty && !organization.isEmpty
    }
}

public enum TrackerArchiveEligibility: Sendable, Equatable {
    case eligible
    case malformedArchive
    case localArchiveOnly
    case missingOrganization
    case organizationMismatch
    case unknownTeamAircraft
}

public enum TrackerArchiveUploadContract {
    public static func eligibility(
        geoJSON: Data,
        configuration: TrackerArchiveUploadConfiguration,
        knownRemoteIDs: Set<String>
    ) -> TrackerArchiveEligibility {
        guard let root = try? JSONSerialization.jsonObject(with: geoJSON) as? [String: Any],
              let feature = (root["features"] as? [[String: Any]])?.first,
              let properties = feature["properties"] as? [String: Any],
              let metadata = properties["r2c_prop"] as? [String: Any]
        else { return .malformedArchive }
        if (metadata["local_archive_only"] as? Bool) == true { return .localArchiveOnly }
        let organization = normalize(metadata["org"] as? String)
        let trackerOrganization = normalize(configuration.organization)
        guard !organization.isEmpty, !trackerOrganization.isEmpty else { return .missingOrganization }
        guard organization == trackerOrganization else { return .organizationMismatch }
        let remoteID = (metadata["rid"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !remoteID.isEmpty, knownRemoteIDs.contains(remoteID) else { return .unknownTeamAircraft }
        return .eligible
    }

    public static func makeRequest(
        geoJSON: Data,
        configuration: TrackerArchiveUploadConfiguration
    ) throws -> URLRequest {
        guard configuration.isConfigured,
              var components = URLComponents(string: configuration.urlPrefix)
        else { throw URLError(.badURL) }
        var path = components.path
        while path.hasSuffix("/") { path.removeLast() }
        components.path = path + "/upload"
        guard let url = components.url else { throw URLError(.badURL) }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = "PUT"
        request.httpBody = geoJSON
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("RID2Caltopo/0.1", forHTTPHeaderField: "User-Agent")
        request.setValue(configuration.apiKey, forHTTPHeaderField: "X-SAR-Token")
        return request
    }

    public static func isTransient(statusCode: Int) -> Bool {
        statusCode == 408 || statusCode == 429 || statusCode >= 500
    }

    public static func shouldMarkReported(statusCode: Int) -> Bool {
        // Keep the archive pending until authorization or the app is repaired.
        ![401, 403, 426, 499].contains(statusCode) && !isTransient(statusCode: statusCode)
    }

    private static func normalize(_ value: String?) -> String {
        (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }
}
