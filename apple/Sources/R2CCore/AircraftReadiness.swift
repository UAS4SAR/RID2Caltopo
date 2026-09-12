import Foundation

public struct AircraftAccessory: Codable, Sendable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var weightGrams: Double?
    public var required: Bool
    public var group: String

    public init(id: String = UUID().uuidString, name: String = "", weightGrams: Double? = nil,
                required: Bool = false, group: String = "") {
        self.id = id; self.name = name; self.weightGrams = weightGrams
        self.required = required; self.group = group
    }
}

public struct AircraftReadiness: Codable, Sendable, Equatable {
    public var recordId = ""
    public var serialNumber = ""
    public var registrationNumber = ""
    public var baseWeightGrams: Double?
    public var baseWeightIncludes = ""
    public var requiredEquipment = ""
    public var monitoringEquipment = ""
    public var accessories: [AircraftAccessory] = []

    public init() {}

    public func totalWeight(selected: Set<String>, payloadDescription: String, payloadGrams: Double?) -> Double? {
        guard selected.isSubset(of: Set(accessories.map(\.id))) else { return nil }
        let installed = accessories.filter { selected.contains($0.id) }
        let groups = Dictionary(grouping: installed.filter { !$0.group.isEmpty }, by: \.group)
        guard !groups.values.contains(where: { $0.count > 1 }),
              let baseWeightGrams, installed.allSatisfy({ $0.weightGrams != nil }),
              payloadDescription.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || payloadGrams != nil
        else { return nil }
        let weights = [baseWeightGrams, payloadGrams ?? 0] + installed.compactMap(\.weightGrams)
        guard weights.allSatisfy({ $0.isFinite && $0 >= 0 }) else { return nil }
        return weights.reduce(0, +)
    }

    public var jsonString: String {
        guard let data = try? JSONEncoder().encode(self) else { return "{}" }
        return String(decoding: data, as: UTF8.self)
    }

    public var dictionary: [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(jsonString.utf8))) as? [String: Any] ?? [:]
    }

    public static func decode(_ value: Any?) -> AircraftReadiness {
        let data: Data?
        if let text = value as? String { data = text.data(using: .utf8) }
        else if let object = value as? [String: Any] { data = try? JSONSerialization.data(withJSONObject: object) }
        else { data = nil }
        return data.flatMap { try? JSONDecoder().decode(Self.self, from: $0) } ?? Self()
    }
}
