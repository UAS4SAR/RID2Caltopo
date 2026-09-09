import Foundation

public struct RidAircraftIdentity: Sendable, Equatable {
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
        mappedIDOverride: String? = nil
    ) {
        self.remoteID = remoteID.trimmingCharacters(in: .whitespacesAndNewlines)
        self.organization = organization.trimmingCharacters(in: .whitespacesAndNewlines)
        self.ownerName = ownerName.trimmingCharacters(in: .whitespacesAndNewlines)
        self.pilotCallsign = pilotCallsign.trimmingCharacters(in: .whitespacesAndNewlines)
        self.droneDescription = droneDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        self.mappedIDOverride = mappedIDOverride?.trimmingCharacters(in: .whitespacesAndNewlines)
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
        if !pilotCallsign.isEmpty { return pilotCallsign }
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
