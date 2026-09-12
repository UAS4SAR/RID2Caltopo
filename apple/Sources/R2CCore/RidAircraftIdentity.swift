import Foundation

public struct RidAircraftIdentity: Sendable, Equatable {
    public let flightReadiness: FlightReadiness?
    public let readiness: AircraftReadiness
    public let remoteID: String
    public let organization: String
    public let ownerName: String
    public let pilotCallsign: String
    public let droneDescription: String
    public let mappedIDOverride: String?

    public init(
        remoteID: String,
        organization: String,
        ownerName: String = "",
        pilotCallsign: String,
        droneDescription: String,
        mappedIDOverride: String? = nil,
        readiness: AircraftReadiness = AircraftReadiness(),
        flightReadiness: FlightReadiness? = nil
    ) {
        self.flightReadiness = flightReadiness
        self.readiness = readiness
        self.remoteID = remoteID.trimmingCharacters(in: .whitespacesAndNewlines)
        self.organization = organization.trimmingCharacters(in: .whitespacesAndNewlines)
        self.ownerName = ownerName.trimmingCharacters(in: .whitespacesAndNewlines)
        self.pilotCallsign = pilotCallsign.trimmingCharacters(in: .whitespacesAndNewlines)
        self.droneDescription = droneDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        self.mappedIDOverride = mappedIDOverride?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Older peer confirmations carry presentation identity but no flight snapshot.
    public func preservingLocalFlightReadiness(from local: RidAircraftIdentity?) -> RidAircraftIdentity {
        guard let local, local.remoteID == remoteID, local.flightReadiness != nil else { return self }
        return RidAircraftIdentity(remoteID: remoteID, organization: organization, ownerName: ownerName,
            pilotCallsign: pilotCallsign, droneDescription: droneDescription, mappedIDOverride: mappedIDOverride,
            readiness: local.readiness, flightReadiness: local.flightReadiness)
    }

    /// Repair the two known legacy import fallbacks when exporting against the
    /// published source. Real owner names and all equipment edits are retained.
    public func preservingBlankPublishedOwnerFields(_ published: [String: Any]) -> RidAircraftIdentity {
        let legacyOwner = published["owner"] as? String ?? ""
        let sourceName = published["ownerName"] as? String ?? ""
        let sourceCallsign = published["ownerCallsign"] as? String ?? legacyOwner
        let legacyIsCallsign = legacyOwner.range(of: #"^[0-9]+[A-Za-z]+[0-9]+(?:-[0-9]+)?$"#, options: .regularExpression) != nil
        let repairedName = sourceName.isEmpty && legacyIsCallsign && ownerName == legacyOwner ? "" : ownerName
        let repairedCallsign = sourceCallsign.isEmpty && pilotCallsign == published["mappedId"] as? String ? "" : pilotCallsign
        return RidAircraftIdentity(remoteID: remoteID, organization: organization, ownerName: repairedName,
            pilotCallsign: repairedCallsign, droneDescription: droneDescription, mappedIDOverride: mappedIDOverride,
            readiness: readiness, flightReadiness: flightReadiness)
    }

    public var isComplete: Bool {
        !remoteID.isEmpty && !organization.isEmpty && !pilotCallsign.isEmpty && !droneDescription.isEmpty
    }

    public var mappedID: String {
        if let mappedIDOverride, !mappedIDOverride.isEmpty { return mappedIDOverride }
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_") )
        let callsign = pilotCallsign.unicodeScalars
            .filter { allowed.contains($0) }
            .map(String.init)
            .joined()
        guard !callsign.isEmpty else { return remoteID }
        return callsign + Self.modelAbbreviation(droneDescription)
    }

    /// Operator-visible aircraft label. `mappedID` remains the stable stream routing key.
    public var displayLabel: String {
        if !mappedID.isEmpty { return mappedID }
        return remoteID
    }

    /// Mirrors Android CtDroneSpec.GuessPilotCallsign so imported rid_map
    /// owner names are not mistaken for operational pilot callsigns.
    public static func guessPilotCallsign(mappedID: String, model: String, remoteID: String) -> String {
        let mappedID = mappedID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !mappedID.isEmpty, mappedID != remoteID else { return "" }

        let modelAbbreviation = modelAbbreviation(model)
        if !modelAbbreviation.isEmpty,
           mappedID.lowercased().hasSuffix(modelAbbreviation.lowercased()) {
            return String(mappedID.dropLast(modelAbbreviation.count)).trimmingCharacters(in: .whitespacesAndNewlines)
        }

        if !modelAbbreviation.isEmpty,
           let suffixRange = mappedID.range(of: #"-\d+$"#, options: .regularExpression) {
            let prefix = String(mappedID[..<suffixRange.lowerBound])
            if prefix.lowercased().hasSuffix(modelAbbreviation.lowercased()) {
                let callsign = String(prefix.dropLast(modelAbbreviation.count))
                return (callsign + String(mappedID[suffixRange])).trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }

        if modelAbbreviation.isEmpty,
           let match = mappedID.range(of: #"^[0-9]+[A-Za-z]+[0-9]+"#, options: .regularExpression) {
            return String(mappedID[match])
        }
        return mappedID
    }

    public static func modelAbbreviation(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        if trimmed.caseInsensitiveCompare("Potensic Atom LT") == .orderedSame {
            return "PtnscAtm2lt"
        }

        let vowels = CharacterSet(charactersIn: "AEIOUaeiou")
        var result = ""
        for word in trimmed.split(whereSeparator: { $0.isWhitespace }) {
            for (index, scalar) in word.unicodeScalars.enumerated() {
                if index == 0 {
                    result.append(contentsOf: String(scalar).uppercased())
                } else if !vowels.contains(scalar) {
                    result.append(contentsOf: String(scalar).lowercased())
                }
            }
        }
        return String(result.suffix(10))
    }
}

/// Validate one aircraft while using the remaining entries only for uniqueness.
public enum RidMappingEditValidation {
    public static func errors(_ entry: RidAircraftIdentity, others: [RidAircraftIdentity]) -> [String] {
        var errors: [String] = []
        let rid = entry.remoteID.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let callsign = entry.pilotCallsign.trimmingCharacters(in: .whitespacesAndNewlines)
        let model = entry.droneDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        if entry.organization.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { errors.append("Organization is required.") }
        if rid.range(of: #"^[A-Z0-9]+$"#, options: .regularExpression) == nil { errors.append("Remote ID must contain only A-Z and 0-9.") }
        if callsign.range(of: #"^[0-9]+[A-Za-z]+[0-9]+(?:-[0-9]+)?$"#, options: .regularExpression) == nil { errors.append("Owner callsign must look like 1SAR7 or 1SAR7-2.") }
        if model.isEmpty { errors.append("Model is required.") }
        if others.contains(where: { $0.remoteID.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == rid }) { errors.append("Remote ID is already listed.") }
        if others.contains(where: { $0.pilotCallsign.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == callsign.lowercased() && $0.droneDescription.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == model.lowercased() }) { errors.append("Model must be unique for this owner callsign.") }
        let weights = [entry.readiness.baseWeightGrams] + entry.readiness.accessories.map(\.weightGrams)
        if weights.compactMap({ $0 }).contains(where: { !$0.isFinite || $0 < 0 || $0 > 100000 }) { errors.append("Invalid weight in grams.") }
        if entry.readiness.accessories.contains(where: { $0.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) { errors.append("Give each accessory or battery a name.") }
        return errors
    }
}
