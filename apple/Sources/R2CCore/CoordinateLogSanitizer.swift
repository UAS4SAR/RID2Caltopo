import Foundation

/// Strips precise coordinates from diagnostic lines before they are written.
public enum CoordinateLogSanitizer {
    private static let labeled = try! NSRegularExpression(
        pattern: #"(?i)\b([a-z0-9_]*(?:latitude|longitude|lat|lng|lon))\s*[:=]\s*-?\d+\.\d{4,}"#
    )
    private static let pair = try! NSRegularExpression(
        pattern: #"(?<![A-Za-z0-9.])-?\d{1,3}\.\d{5,}\s*,\s*-?\d{1,3}\.\d{5,}"#
    )

    public static func redact(_ message: String) -> String {
        guard message.contains(where: \.isNumber) else { return message }
        let labeledGone = replace(labeled, in: message) { match, source in
            let label = source.substring(with: match.range(at: 1))
            return "\(label)=[redacted]"
        }
        return replace(pair, in: labeledGone) { _, _ in "[redacted]" }
    }

    private static func replace(
        _ expression: NSRegularExpression,
        in message: String,
        _ transform: (NSTextCheckingResult, NSString) -> String
    ) -> String {
        let source = message as NSString
        let matches = expression.matches(in: message, range: NSRange(location: 0, length: source.length))
        guard !matches.isEmpty else { return message }
        let result = NSMutableString(string: message)
        for match in matches.reversed() {
            result.replaceCharacters(in: match.range, with: transform(match, source))
        }
        return result as String
    }
}

/// Confirmed device-marker deletes, and which CalTopo class/id to call.
public enum DeviceMarkerDeletePlan {
    public struct Target: Equatable, Sendable {
        public let featureClass: String
        public let id: String
        public init(featureClass: String, id: String) {
            self.featureClass = featureClass
            self.id = id
        }
        /// Marker ids stay bare. Shape ids are `Shape/<id>` so the DELETE path matches the feature.
        public var wire: String { featureClass == "Shape" ? "Shape/\(id)" : id }
    }

    public struct Feature: Equatable, Sendable {
        public let id: String
        public let className: String
        public let guid: String
        public init(id: String, className: String, guid: String) {
            self.id = id
            self.className = className
            self.guid = guid
        }
    }

    public static func resolve(markerID: String, deviceGUID: String, features: [Feature]) -> Target {
        let wanted = markerID.trimmingCharacters(in: .whitespacesAndNewlines)
        let guid = deviceGUID.trimmingCharacters(in: .whitespacesAndNewlines)
        var fallback: Target?
        for feature in features {
            if feature.className == "Folder" { continue }
            let idMatch = feature.id.caseInsensitiveCompare(wanted) == .orderedSame
            let guidMatch = !guid.isEmpty &&
                (feature.guid.caseInsensitiveCompare(guid) == .orderedSame
                    || feature.guid.caseInsensitiveCompare(wanted) == .orderedSame)
            if !idMatch && !guidMatch { continue }
            let featureClass = feature.className == "Shape" ? "Shape" : "Marker"
            let target = Target(featureClass: featureClass, id: feature.id.isEmpty ? wanted : feature.id)
            if idMatch && featureClass == "Marker" { return target }
            if fallback == nil { fallback = target }
        }
        return fallback ?? Target(featureClass: "Marker", id: wanted)
    }

    public static func parseWire(_ spec: String) -> Target {
        if spec.hasPrefix("Shape/") { return Target(featureClass: "Shape", id: String(spec.dropFirst(6))) }
        if spec.hasPrefix("Marker/") { return Target(featureClass: "Marker", id: String(spec.dropFirst(7))) }
        return Target(featureClass: "Marker", id: spec)
    }
}

public enum MarkerCleanupRetry {
    private static let delaysMs: [Int64] = [30_000, 120_000, 600_000, 3_600_000, 21_600_000]
    nonisolated(unsafe) public static var clockMs: Int64 = .min

    public static func now() -> Int64 {
        if clockMs == .min { return Int64(Date().timeIntervalSince1970 * 1000) }
        return clockMs
    }

    public static func delayMs(attempts: Int) -> Int64 {
        let index = min(max(attempts - 1, 0), delaysMs.count - 1)
        return delaysMs[index]
    }

    /// Journal clears only after a confirmed delete.
    public static func clearJournal(httpStatus: Int, verifiedSuccess: Bool) -> Bool {
        if httpStatus == 404 { return true }
        return (200..<300).contains(httpStatus) && verifiedSuccess
    }

    /// A 400 for a marker that is not on the map is already gone. A 400 for a marker
    /// that is still listed is a rejected delete and must be retried.
    public static func alreadyRemoved(httpStatus: Int, listedOnMap: Bool) -> Bool {
        if httpStatus == 404 { return true }
        return httpStatus == 400 && !listedOnMap
    }

    public static func idPrefix(_ id: String) -> String { String(id.prefix(8)) }

    public static func resetClockForTest() { clockMs = .min }
}
