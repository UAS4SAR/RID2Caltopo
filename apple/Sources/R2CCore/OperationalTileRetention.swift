import Foundation

/// How long a cached map tile may be kept.
///
/// Public OSM tiles and Esri ArcGIS Online imagery (ArcGIS-WorldImagery and the
/// same World Imagery service) may be held only as a short performance cache.
/// The OSM tile usage policy forbids treating the public tile servers as a bulk
/// or long-term store, and Esri's ArcGIS Online basemap terms likewise prohibit
/// a durable offline copy of World Imagery. One window covers both.
///
/// DEM tiles, including usgs-geotiff 1 m S1M terrain, and AOL / lidar products
/// have no retention limit. USGS Topo and USFS Topo are public-domain or
/// freely distributable and are also unrestricted. Anything that cannot be
/// positively classified is treated as unrestricted so a migration never
/// deletes data whose source is unknown.
public enum OperationalTileRetention {
    public static let restrictedRetentionDays = 3
    public static let restrictedRetentionSeconds: TimeInterval = TimeInterval(restrictedRetentionDays * 24 * 60 * 60)

    public enum Class: Equatable, Sendable {
        case restricted
        case unrestricted
        case standard
    }

    public enum Decision: Equatable, Sendable {
        case keep
        case delete
        case rewriteExpiry(Date)
    }

    private static let restricted: Set<String> = [
        "osm", "osmstandard", "mapnik", "openstreetmap",
        "arcgisworldimagery", "imagery", "esri", "worldimagery",
    ]
    private static let standard: Set<String> = ["usgscontours"]

    public static func classifyLocation(_ location: String) -> Class {
        if isProtectedTerrainOrLidar(location) { return .unrestricted }
        guard let token = sourceToken(location) else { return .unrestricted }
        return classifySource(token)
    }

    public static func classifySource(_ source: String) -> Class {
        let key = normalize(source)
        if restricted.contains(key) { return .restricted }
        if standard.contains(key) { return .standard }
        return .unrestricted
    }

    public static func decision(
        location: String?,
        createdAt: Date?,
        expiresAt: Date?,
        now: Date,
        standardMaxAgeDays: Int
    ) -> Decision {
        let kind = location.map(classifyLocation) ?? .unrestricted
        switch kind {
        case .unrestricted:
            if expiresAt == .distantFuture { return .keep }
            return .rewriteExpiry(.distantFuture)
        case .restricted:
            return restrictedDecision(createdAt: createdAt, expiresAt: expiresAt, now: now)
        case .standard:
            guard let createdAt, standardMaxAgeDays >= 0,
                  now >= createdAt.addingTimeInterval(TimeInterval(standardMaxAgeDays) * 86_400) else {
                return .keep
            }
            return .delete
        }
    }

    public static func shouldPurge(
        location: String?,
        createdAt: Date?,
        now: Date,
        standardMaxAgeDays: Int
    ) -> Bool {
        if case .delete = decision(
            location: location,
            createdAt: createdAt,
            expiresAt: .distantFuture,
            now: now,
            standardMaxAgeDays: standardMaxAgeDays
        ) { return true }
        return false
    }

    /// Expiry to store for a tile written now. Unrestricted tiles do not expire.
    public static func expiresAt(source: String, now: Date, standardMaxAgeDays: Int) -> Date {
        switch classifySource(source) {
        case .restricted:
            return now.addingTimeInterval(restrictedRetentionSeconds)
        case .unrestricted:
            return .distantFuture
        case .standard:
            return now.addingTimeInterval(TimeInterval(max(0, standardMaxAgeDays)) * 86_400)
        }
    }

    public static func freshnessCutoff(for location: String, now: Date, standardMaxAgeDays: Int) -> Date {
        switch classifyLocation(location) {
        case .restricted:
            return now.addingTimeInterval(-restrictedRetentionSeconds)
        case .standard:
            return now.addingTimeInterval(-TimeInterval(max(0, standardMaxAgeDays)) * 86_400)
        case .unrestricted:
            return .distantPast
        }
    }

    private static func restrictedDecision(createdAt: Date?, expiresAt: Date?, now: Date) -> Decision {
        guard let createdAt else {
            let cap = now.addingTimeInterval(restrictedRetentionSeconds)
            if let expiresAt, expiresAt <= cap { return .keep }
            return .rewriteExpiry(cap)
        }
        let cap = createdAt.addingTimeInterval(restrictedRetentionSeconds)
        if now >= cap { return .delete }
        if let expiresAt, expiresAt <= cap { return .keep }
        return .rewriteExpiry(cap)
    }

    private static func isProtectedTerrainOrLidar(_ location: String) -> Bool {
        let parts = location.replacingOccurrences(of: "\\", with: "/").split(separator: "/")
        return parts.contains(where: { part in
            let lower = part.lowercased()
            return lower == "dem" ||
                lower == "surface_v1" ||
                lower.hasSuffix(".tif") ||
                lower.hasSuffix(".tiff") ||
                lower.hasSuffix(".aol") ||
                lower.contains("s1m") ||
                lower.contains("geotiff") ||
                lower.contains("lidar")
        })
    }

    static func sourceToken(_ location: String) -> String? {
        let trimmed = location.trimmingCharacters(in: .whitespacesAndNewlines)
        if let match = cacheKey.firstMatch(in: trimmed, range: NSRange(trimmed.startIndex..., in: trimmed)),
           let range = Range(match.range(at: 1), in: trimmed) {
            return String(trimmed[range])
        }
        let normalized = location.replacingOccurrences(of: "\\", with: "/")
        let marker = "/MapTiles/"
        let relative: String
        if let range = normalized.range(of: marker) {
            relative = String(normalized[range.upperBound...])
        } else {
            relative = normalized
        }
        let parts = relative.split(separator: "/").map(String.init).filter { !$0.isEmpty }
        if parts.count >= 4, parts[1].allSatisfy(\.isNumber), parts[2].allSatisfy(\.isNumber) {
            return parts[0]
        }
        return nil
    }

    private static func normalize(_ source: String) -> String {
        String(source.lowercased().filter { $0.isLetter || $0.isNumber })
    }

    private static let cacheKey = try! NSRegularExpression(pattern: #"^v\d+\|([^|]+)\|\d+\|\d+\|\d+$"#)
}
