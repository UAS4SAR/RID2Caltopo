import Foundation

public struct FlightReadiness: Codable, Sendable, Equatable {
    public var aircraft = AircraftReadiness()
    public var selectedAccessories: Set<String> = []
    public var payloadDescription = ""
    public var payloadWeightGrams: Double?
    public var pilotJSON = "{}"
    public var serviceJSON = "{}"
    public var rosterFetchedAt = ""
    public var confirmedAt = ""
    public var operatingProfileJSON: String?
    public var operatingProfileChangesJSON: String?
    public var configurationVersion: Int64 = 0
    public init() {}

    // Remember equipment only; each flight gets fresh pilot, service and authority evidence.
    public var equipmentDictionary: [String: Any] {
        ["selectedAccessories": selectedAccessories.sorted(), "payloadDescription": payloadDescription,
         "payloadWeightGrams": payloadWeightGrams as Any? ?? NSNull()]
    }

    public func restoringEquipment(_ saved: [String: Any]) -> FlightReadiness {
        var copy = self
        copy.selectedAccessories = Set(saved["selectedAccessories"] as? [String] ?? [])
        copy.payloadDescription = saved["payloadDescription"] as? String ?? ""
        copy.payloadWeightGrams = (saved["payloadWeightGrams"] as? NSNumber)?.doubleValue
        return copy
    }

    public var pilotQualificationWarning: String {
        return "The saved roster does not verify current Part 107 qualifications for this callsign. Review the callsign or update the pilot’s qualifications in Tracker. Recording and publishing remain available."
    }

    public var equipmentSummary: String {
        var names = aircraft.accessories.filter { selectedAccessories.contains($0.id) }.map(\.name)
        if !payloadDescription.isEmpty { names.append(payloadDescription) }
        else if payloadWeightGrams != nil { names.append("Payload") }
        return names.isEmpty ? "No equipment or payload selected" : names.joined(separator: ", ")
    }

    public func resolvingPilot(callsign: String, roster: [[String: Any]]) -> FlightReadiness {
        let name = callsign.trimmingCharacters(in: .whitespacesAndNewlines)
        let matches = roster.filter { !name.isEmpty && ($0["callsign"] as? String ?? "").caseInsensitiveCompare(name) == .orderedSame }
        var copy = self
        copy.pilotJSON = matches.count == 1 ? OperatingProfiles.json(matches[0]) : "{}"
        return copy.withReportedPilot(callsign: name, matched: matches.count == 1)
    }

    public func withAircraft(local: AircraftReadiness?, published: AircraftReadiness?) -> FlightReadiness {
        var copy = self
        copy.aircraft = local ?? published ?? aircraft
        copy.selectedAccessories.formIntersection(copy.aircraft.accessories.map(\.id))
        return copy
    }

    public func withReportedPilot(callsign: String, matched: Bool) -> FlightReadiness {
        var copy = self
        var pilot = matched ? ((dictionary["pilot"] as? [String: Any]) ?? [:]) : [:]
        pilot["callsign"] = callsign.trimmingCharacters(in: .whitespacesAndNewlines)
        if let data = try? JSONSerialization.data(withJSONObject: pilot) { copy.pilotJSON = String(decoding: data, as: UTF8.self) }
        return copy
    }

    public var dictionary: [String: Any] {
        let pilot = (try? JSONSerialization.jsonObject(with: Data(pilotJSON.utf8))) as? [String: Any] ?? [:]
        let service = (try? JSONSerialization.jsonObject(with: Data(serviceJSON.utf8))) as? [String: Any] ?? [:]
        var result: [String: Any] = ["configurationVersion": configurationVersion, "aircraft": aircraft.dictionary, "selectedAccessories": selectedAccessories.sorted(),
                "payloadDescription": payloadDescription, "payloadWeightGrams": payloadWeightGrams as Any? ?? NSNull(),
                "pilot": pilot, "service": service, "rosterFetchedAt": rosterFetchedAt, "confirmedAt": confirmedAt,
                "pilotAttribution": pilot["memberId"] == nil ? "unresolved" : "operator_selected",
                "takeoffWeightGrams": aircraft.totalWeight(selected: selectedAccessories, payloadDescription: payloadDescription, payloadGrams: payloadWeightGrams) as Any? ?? NSNull()]
        if let operatingProfileJSON { result["operatingProfile"] = OperatingProfiles.object(operatingProfileJSON) }
        if let operatingProfileChangesJSON { result["operatingProfileChanges"] = (try? JSONSerialization.jsonObject(with: Data(operatingProfileChangesJSON.utf8))) ?? [] }
        return result
    }
}
