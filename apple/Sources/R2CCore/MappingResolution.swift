import Foundation

/// Flat-ground nadir sampling estimate; wide timed capture uses observed 12 MP originals.
public enum MappingResolution {
    /// Camera and aircraft identity travel together with each saved mission.
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
        public var focalLength35mm: Double? {
            if id.hasSuffix(".visible.medium-tele") {return 70}
            if id.hasSuffix(".visible.tele") {return 168}
            return nil
        }
        public var captureResolutionMode: String { id=="m4td.visible.wide" ? "observed-timed-12mp":"planned-12mp" }
        public var isVisibleWide: Bool { mode == .visible && id.hasSuffix(".visible.wide") }
        public var aircraftSpec: Aircraft { MappingResolution.aircraftSpecs.first{$0.name==aircraft}! }
        public var lens: String { mode == .thermal ? "ir" : aircraftSpec.droneType==67 ? "wide":"visable" }
        // Conservative planning policy; M30 cadence needs controller/flight qualification.
        public var minimumCaptureInterval: Double { aircraftSpec.droneType==67 ? 2 : focalLength35mm != nil ? 1.5:1 }
        public var captureSettings: String { aircraftSpec.droneType==67 ? "Pilot 2 check: Wide camera, JPEG, 4000 × 3000 (12 MP).":"Pilot 2 check: Photo Resolution 4K and Image Ratio 4:3 (about 12 MP)." }

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
        case "m4td.visible.wide", "m4t.visible.wide", "m30.visible.wide", "m30t.visible.wide": return "Wide (1×), 12 MP"
        case "m4td.visible.medium-tele", "m4t.visible.medium-tele": return "Medium tele (3×), 12 MP"
        case "m4td.visible.tele", "m4t.visible.tele": return "Tele (7×), 12 MP"
        case "m4td.thermal", "m4t.thermal", "m30t.thermal": return "IR (thermal)"
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

    public struct Aircraft: Sendable, Equatable {
        public let name: String
        public let droneType, droneSubtype, payloadType: Int
        public var droneModelKey: String { "0-\(droneType)-\(droneSubtype)" }
        public var payloadModelKey: String { "1-\(payloadType)-0" }
    }
    public static let aircraftSpecs: [Aircraft] = [
        Aircraft(name:"DJI Matrice 4TD",droneType:100,droneSubtype:1,payloadType:99),
        Aircraft(name:"DJI Matrice 4T",droneType:99,droneSubtype:1,payloadType:89),
        Aircraft(name:"DJI Matrice 30",droneType:67,droneSubtype:0,payloadType:52),
        Aircraft(name:"DJI Matrice 30T",droneType:67,droneSubtype:1,payloadType:53)
    ]
    public static let aircraftModels = aircraftSpecs.map(\.name) + ["DJI Matrice 400"]
    public static func aircraftLabel(_ model: String) -> String {
        if model=="DJI Matrice 4TD" {return model+" · Pilot 2 lists M4D → M4TD"}
        return aircraftSpecs.contains{$0.name==model} ? model:model+" · camera profile needed"
    }
    /// Nominal geometry for planning; hardware flight qualification is recorded separately.
    public static let profiles: [Profile] = [
        Profile(id: "m4td.visible.wide", aircraft: "DJI Matrice 4TD", camera: "Wide camera", label: "Visible · wide (1×), timed 12 MP", mode: .visible,
                sensor: Camera(widthPixels: 4032, heightPixels: 3024, diagonalFOVDegrees: 82)),
        Profile(id: "m4td.visible.medium-tele", aircraft: "DJI Matrice 4TD", camera: "Medium tele camera", label: "Visible · medium tele (3×), 12 MP", mode: .visible,
                sensor: Camera(widthPixels: 4032, heightPixels: 3024, diagonalFOVDegrees: 35)),
        Profile(id: "m4td.visible.tele", aircraft: "DJI Matrice 4TD", camera: "Tele camera", label: "Visible · tele (7×), 12 MP", mode: .visible,
                sensor: Camera(widthPixels: 4096, heightPixels: 3072, diagonalFOVDegrees: 15)),
        Profile(id: "m4td.thermal", aircraft: "DJI Matrice 4TD", camera: "Thermal camera", label: "Thermal", mode: .thermal,
                sensor: thermal),
        Profile(id:"m4t.visible.wide",aircraft:"DJI Matrice 4T",camera:"Wide camera",label:"Visible · wide (1×), timed 12 MP",mode:.visible,sensor:daylightWide),
        Profile(id:"m4t.visible.medium-tele",aircraft:"DJI Matrice 4T",camera:"Medium tele camera",label:"Visible · medium tele (3×), 12 MP",mode:.visible,sensor:Camera(widthPixels:4032,heightPixels:3024,diagonalFOVDegrees:35)),
        Profile(id:"m4t.visible.tele",aircraft:"DJI Matrice 4T",camera:"Tele camera",label:"Visible · tele (7×), 12 MP",mode:.visible,sensor:Camera(widthPixels:4096,heightPixels:3072,diagonalFOVDegrees:15)),
        Profile(id:"m4t.thermal",aircraft:"DJI Matrice 4T",camera:"Thermal camera",label:"Thermal",mode:.thermal,sensor:thermal),
        Profile(id:"m30.visible.wide",aircraft:"DJI Matrice 30",camera:"Wide camera",label:"Visible · wide (1×), 12 MP",mode:.visible,sensor:Camera(widthPixels:4000,heightPixels:3000,diagonalFOVDegrees:84)),
        Profile(id:"m30t.visible.wide",aircraft:"DJI Matrice 30T",camera:"Wide camera",label:"Visible · wide (1×), 12 MP",mode:.visible,sensor:Camera(widthPixels:4000,heightPixels:3000,diagonalFOVDegrees:84)),
        Profile(id:"m30t.thermal",aircraft:"DJI Matrice 30T",camera:"Thermal camera",label:"Thermal",mode:.thermal,sensor:Camera(widthPixels:640,heightPixels:512,diagonalFOVDegrees:61)),
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
