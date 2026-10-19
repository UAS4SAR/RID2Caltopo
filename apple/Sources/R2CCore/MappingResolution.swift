import Foundation

/// Flat-ground nadir sampling estimate; wide timed capture uses observed 12 MP originals.
public enum MappingResolution {
    /// Camera profiles are kept as mission metadata so a future aircraft/payload
    /// catalog can add systems such as M30T or M400 payloads without changing the
    /// mission editor's resolution math.
    public struct Profile: Sendable, Equatable, Identifiable {
        public let id: String
        public let aircraft: String
        public let camera: String
        public let label: String
        public let mode: Mode
        public let sensor: Camera
        public func matchesOriginal(sensor:String, focal35mm:Double?) -> Bool {
            if mode == .thermal {return sensor == "thermal"}
            guard let focal=focalLength35mm else {
                if sensor == "visible-wide" {return focal35mm == nil || !(focal35mm!.isFinite) || focal35mm!<=0 || abs(focal35mm!-24)<=2}
                return sensor == "visible-zoom" && focal35mm.map{$0.isFinite && abs($0-24)<=2} == true
            }
            guard ["visible-wide","visible-zoom"].contains(sensor),let actual=focal35mm,actual.isFinite else{return false}
            return abs(actual-focal)<=2
        }
        public var focalLength35mm: Double? { switch id {case "m4td.visible.medium-tele": return 70; case "m4td.visible.tele": return 168; default: return nil} }

        public init(id: String, aircraft: String, camera: String, label: String, mode: Mode, sensor: Camera) {
            self.id = id
            self.aircraft = aircraft
            self.camera = camera
            self.label = label
            self.mode = mode
            self.sensor = sensor
        }
    }

    public static func cameraLabel(profileID: String?, fallbackName: String? = nil) -> String {
        switch profileID {
        case "m4td.visible.wide": return "Wide (1×), 12 MP"
        case "m4td.visible.medium-tele": return "Medium tele (3×), 12 MP"
        case "m4td.visible.tele": return "Tele (7×), 12 MP"
        case "m4td.thermal": return "IR (thermal)"
        default: return fallbackName.flatMap { $0.isEmpty ? nil : $0 } ?? "Camera unavailable"
        }
    }

    public enum Mode: String, Sendable, Equatable { case visible, thermal }

    public struct Camera: Sendable, Equatable {
        public let widthPixels: Double
        public let heightPixels: Double
        public let diagonalFOVDegrees: Double
        public init(widthPixels: Double, heightPixels: Double, diagonalFOVDegrees: Double) {
            self.widthPixels = widthPixels
            self.heightPixels = heightPixels
            self.diagonalFOVDegrees = diagonalFOVDegrees
        }
    }

    public static let thermal = Camera(widthPixels: 640, heightPixels: 512, diagonalFOVDegrees: 45)
    public static let daylightWide = Camera(widthPixels: 4032, heightPixels: 3024, diagonalFOVDegrees: 82)

    /// Verified Matrice 4TD/4T camera choices. Other aircraft and payloads are
    /// intentionally added only after their exact camera configuration is known.
    public static let profiles: [Profile] = [
        Profile(id: "m4td.visible.wide", aircraft: "DJI Matrice 4TD", camera: "Wide camera", label: "Visible · wide (1×), timed 12 MP", mode: .visible,
                sensor: Camera(widthPixels: 4032, heightPixels: 3024, diagonalFOVDegrees: 82)),
        Profile(id: "m4td.visible.medium-tele", aircraft: "DJI Matrice 4TD", camera: "Medium tele camera", label: "Visible · medium tele (3×), 12 MP", mode: .visible,
                sensor: Camera(widthPixels: 4032, heightPixels: 3024, diagonalFOVDegrees: 35)),
        Profile(id: "m4td.visible.tele", aircraft: "DJI Matrice 4TD", camera: "Tele camera", label: "Visible · tele (7×), 12 MP", mode: .visible,
                sensor: Camera(widthPixels: 4096, heightPixels: 3072, diagonalFOVDegrees: 15)),
        Profile(id: "m4td.thermal", aircraft: "DJI Matrice 4TD", camera: "Thermal camera", label: "Thermal", mode: .thermal,
                sensor: thermal),
    ]

    public static func gsdCmPerPixel(heightMeters: Double, camera: Camera) -> Double? {
        guard heightMeters.isFinite, heightMeters > 0 else { return nil }
        let diagonal = hypot(camera.widthPixels, camera.heightPixels)
        let horizontalFOV = 2 * atan(tan(camera.diagonalFOVDegrees * .pi / 360) * camera.widthPixels / diagonal)
        let widthMeters = 2 * heightMeters * tan(horizontalFOV / 2)
        return widthMeters * 100 / camera.widthPixels
    }

    public static func heightMeters(gsdCmPerPixel: Double, camera: Camera) -> Double? {
        guard gsdCmPerPixel.isFinite, gsdCmPerPixel > 0,
              let oneMeter = Self.gsdCmPerPixel(heightMeters: 1, camera: camera) else { return nil }
        return gsdCmPerPixel / oneMeter
    }

    public static func pixelsPerFoot(gsdCmPerPixel: Double) -> Double? {
        guard gsdCmPerPixel.isFinite, gsdCmPerPixel > 0 else { return nil }
        return 30.48 / gsdCmPerPixel
    }
}
