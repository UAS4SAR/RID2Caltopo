import Foundation

/// Presentation only; stored measurements and wire formats keep their original units.
public enum DisplayUnits: String, CaseIterable, Sendable {
    case imperial, metric
    public static let storageKey = "display.units"
    public static var current: Self { Self(rawValue: UserDefaults.standard.string(forKey: storageKey) ?? "") ?? .imperial }
    public var lengthUnit: String { self == .metric ? "m" : "ft" }
    public var distanceUnit: String { self == .metric ? "km" : "mi" }
    public var resolutionUnit: String { self == .metric ? "cm/px" : "in/px" }
    public var weightUnit: String { self == .metric ? "g" : "oz" }
    public func lengthValue(_ meters: Double) -> Double { self == .metric ? meters : meters / 0.3048 }
    public func lengthMeters(_ value: Double) -> Double { self == .metric ? value : value * 0.3048 }
    public func milesValue(_ miles: Double) -> Double { self == .metric ? miles * 1.609344 : miles }
    public func resolutionValue(_ cm: Double) -> Double { self == .metric ? cm : cm / 2.54 }
    public func resolutionCm(_ value: Double) -> Double { self == .metric ? value : value * 2.54 }
    public func gramsValue(_ grams: Double) -> Double { self == .metric ? grams : grams / 28.349523125 }
    public func grams(_ value: Double) -> Double { self == .metric ? value : value * 28.349523125 }
    public func number(_ value: Double, decimals: Int = 1) -> String { let text = String(format: "%.*f", locale: Locale(identifier: "en_US_POSIX"), decimals, value); return text.hasPrefix("-") && Double(text) == 0 ? String(text.dropFirst()) : text }
    public func length(_ meters: Double, decimals: Int = 0) -> String { "\(number(lengthValue(meters), decimals: decimals)) \(lengthUnit)" }
    public func feet(_ feet: Double, decimals: Int = 0) -> String { length(feet * 0.3048, decimals: decimals) }
    public func miles(_ miles: Double, decimals: Int = 1) -> String { "\(number(milesValue(miles), decimals: decimals)) \(distanceUnit)" }
    public func distance(_ meters: Double) -> String { abs(meters) >= (self == .metric ? 1000 : 1609.344) ? miles(meters / 1609.344, decimals: 2) : length(meters) }
    public func area(_ squareMiles: Double) -> String { "\(number(self == .metric ? squareMiles * 2.589988110336 : squareMiles, decimals: 2)) \(self == .metric ? "km²" : "sq mi")" }
    public func speed(_ metersPerSecond: Double) -> String { "\(number(self == .metric ? metersPerSecond : metersPerSecond * 2.2369362920544)) \(self == .metric ? "m/s" : "mph")" }
    public func climb(_ metersPerSecond: Double) -> String { "\(number(self == .metric ? metersPerSecond : metersPerSecond / 0.3048 * 60)) \(self == .metric ? "m/s" : "ft/min")" }
    public func resolution(_ cm: Double) -> String { "\(number(resolutionValue(cm), decimals: 2)) \(resolutionUnit)" }
}
