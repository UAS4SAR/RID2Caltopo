import Foundation

/// Keeps CalTopo clue Marker and MapMediaObject Point geometries aligned.
/// Local clue lat/lng is authoritative; media bytes are never re-uploaded here.
public enum CluePhotoGeometrySync: Sendable {
    /// Repair MapMediaObject when it is farther than this from the Marker.
    public static let driftRepairMeters: Double = 1.0

    public static func needsRepair(
        markerLatitude: Double,
        markerLongitude: Double,
        linkLatitude: Double,
        linkLongitude: Double,
        thresholdMeters: Double = driftRepairMeters
    ) -> Bool {
        guard markerLatitude.isFinite, markerLongitude.isFinite,
              linkLatitude.isFinite, linkLongitude.isFinite
        else { return false }
        if abs(markerLatitude - linkLatitude) <= 1e-7,
           abs(markerLongitude - linkLongitude) <= 1e-7 {
            return false
        }
        guard let relative = RidGeometry.relativePosition(
            fromLatitude: markerLatitude,
            longitude: markerLongitude,
            toLatitude: linkLatitude,
            longitude: linkLongitude
        ) else { return false }
        return relative.distanceMeters > thresholdMeters
    }

    /// Reads Point coordinates from a CalTopo Feature (or Feature nested under "feature").
    /// Returns (latitude, longitude); nil when geometry is absent or not a 2D Point.
    public static func pointLatLng(from featureOrResult: [String: Any]?) -> (latitude: Double, longitude: Double)? {
        guard let featureOrResult else { return nil }
        let feature: [String: Any]
        if featureOrResult["geometry"] != nil {
            feature = featureOrResult
        } else if let nested = featureOrResult["feature"] as? [String: Any], nested["geometry"] != nil {
            feature = nested
        } else {
            feature = featureOrResult
        }
        guard let geometry = feature["geometry"] as? [String: Any] else { return nil }
        if let type = geometry["type"] as? String, type.caseInsensitiveCompare("Point") != .orderedSame {
            return nil
        }
        guard let coordinates = geometry["coordinates"] as? [Any], coordinates.count >= 2,
              let lng = Self.doubleValue(coordinates[0]),
              let lat = Self.doubleValue(coordinates[1]),
              lat.isFinite, lng.isFinite
        else { return nil }
        return (latitude: lat, longitude: lng)
    }

    public static func pointGeometry(latitude: Double, longitude: Double) -> [String: Any] {
        [
            "type": "Point",
            "coordinates": [longitude, latitude],
        ]
    }

    /// Copy properties for a geometry-only MapMediaObject edit (never rewrite media bytes).
    public static func copyProperties(from feature: [String: Any]?) -> [String: Any] {
        guard let props = feature?["properties"] as? [String: Any] else { return [:] }
        return props
    }

    private static func doubleValue(_ value: Any) -> Double? {
        switch value {
        case let number as Double: return number
        case let number as NSNumber: return number.doubleValue
        case let number as Int: return Double(number)
        case let number as Float: return Double(number)
        default: return nil
        }
    }
}
