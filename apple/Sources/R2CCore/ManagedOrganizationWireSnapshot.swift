import Foundation

/// Organization credentials are independent of a currently selected personal map.
public enum ManagedOrganizationWireSnapshot {
    public static func encode(
        teams: OperationalTeamsCredentialDraft, trackFolder: String,
        mutualAid: MutualAidTemplateCredentials?, identities: [RidAircraftIdentity],
        published: PreparedOrganizationState, appVersion: String, appBuild: Int
    ) throws -> Data {
        dispatchPrecondition(condition: .notOnQueue(.main))
        let credentials = try OrgConfigTokenCodec.canonicalCredentialPayload([
            "type": "ct_credentials", "file_version": "1.0", "team_id": teams.teamID,
            "credential_id": teams.credentialID, "credential_secret": teams.secret,
            "domain_and_port": teams.domain, "connect_key": teams.connectKey, "track_folder": trackFolder])
        let mutualAidEncoded: String
        if let template = mutualAid {
            let clear = try OrgConfigTokenCodec.canonicalCredentialPayload([
                "type": "ct_mutual_aid_credentials", "file_version": "1.0",
                "team_id": template.teamID, "credential_id": template.credentialID,
                "credential_secret": template.credentialSecret, "domain_and_port": template.domainAndPort,
                "source_label": template.sourceLabel, "target_folder_hint": template.targetFolderHint,
                "connect_key": template.connectKey])
            mutualAidEncoded = OrgConfigTokenCodec.encryptPayload(clear)
        } else { mutualAidEncoded = "" }
        var sources: [String: [String: Any]] = [:]
        for entry in published.value["aircraft"] as? [[String: Any]] ?? [] {
            if let id = entry["remoteId"] as? String, sources[id.lowercased()] == nil { sources[id.lowercased()] = entry }
        }
        let result: [String: Any] = [
            "configSchemaVersion": 1, "aircraftSchemaVersion": 1, "sourcePlatform": "ios",
            "sourceAppVersion": appVersion, "sourceAppBuild": appBuild,
            "organizationCaltopoEnc": OrgConfigTokenCodec.encryptPayload(credentials),
            "mutualAidCaltopoEnc": mutualAidEncoded,
            "droneSpecs": identities.map { identity in
                let clean = sources[identity.remoteID.lowercased()].map { identity.preservingBlankPublishedOwnerFields($0) } ?? identity
                return ["remoteId": clean.remoteID, "mappedId": clean.mappedID, "org": clean.organization,
                        "model": clean.droneDescription, "owner": clean.pilotCallsign, "ownerName": clean.ownerName,
                        "ownerCallsign": clean.pilotCallsign, "readiness": clean.readiness.dictionary] as [String: Any]
            }]
        return try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
    }
}
