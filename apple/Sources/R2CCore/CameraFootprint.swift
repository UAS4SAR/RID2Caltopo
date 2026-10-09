import Foundation

public struct CameraFootprintInput: Sendable, Equatable {
    public let latitude, longitude, launchLatitude, launchLongitude, height: Double
    public let azimuth, tilt, horizontalFov, verticalFov: Double
    public init(latitude: Double, longitude: Double, launchLatitude: Double, launchLongitude: Double,
                height: Double, azimuth: Double, tilt: Double, horizontalFov: Double, verticalFov: Double) {
        self.latitude=latitude; self.longitude=longitude; self.launchLatitude=launchLatitude
        self.launchLongitude=launchLongitude; self.height=height; self.azimuth=azimuth
        self.tilt=tilt; self.horizontalFov=horizontalFov; self.verticalFov=verticalFov
    }
}
public struct CameraFootprintVertex: Sendable, Equatable {
    public let latitude, longitude: Double
    public let clipped, corner: Bool
    public init(latitude: Double, longitude: Double, clipped: Bool, corner: Bool) {
        self.latitude=latitude; self.longitude=longitude; self.clipped=clipped; self.corner=corner
    }
}
public enum CameraFootprintGeometry {
    public static let maximumRange = 3000.0
    /// Launch-relative pinhole projection. Terrain closure must return nil outside downloaded S1M.
    public static func project(_ input: CameraFootprintInput,
                               elevation: ((Double, Double) -> Double?)? = nil,
                               cancelled: () -> Bool = { false },
                               edgeSubdivisions: Int? = nil) -> [CameraFootprintVertex] {
        let i = input
        guard [i.latitude,i.longitude,i.launchLatitude,i.launchLongitude,i.height,i.azimuth,i.tilt,
               i.horizontalFov,i.verticalFov].allSatisfy(\.isFinite), i.height > 0,
              (0.01...179).contains(i.horizontalFov), (0.01...179).contains(i.verticalFov),
              (-85...85).contains(i.latitude), (-180...180).contains(i.longitude) else { return [] }
        let launchGround = elevation?(i.launchLatitude,i.launchLongitude)
        if elevation != nil && launchGround == nil { return [] }
        let aircraftZ = (launchGround ?? 0) + i.height
        let rad = Double.pi / 180, earth = 6378137.0
        let yaw = i.azimuth*rad, pitch = i.tilt*rad
        let fx = sin(yaw)*cos(pitch), fy = cos(yaw)*cos(pitch), fz = sin(pitch)
        let rx = cos(yaw), ry = -sin(yaw)
        let ux = -sin(yaw)*sin(pitch), uy = -cos(yaw)*sin(pitch), uz = cos(pitch)
        let h = tan(i.horizontalFov*rad/2), v = tan(i.verticalFov*rad/2)
        let corners: [(Double,Double)] = [(-1,1),(1,1),(1,-1),(-1,-1)]
        let subdivisions = min(8, max(1, edgeSubdivisions ?? (elevation == nil ? 1 : 8)))
        var vertices: [CameraFootprintVertex] = []
        for edge in 0..<4 {
            for j in 0..<subdivisions {
                if cancelled() { return [] }
                let a = corners[edge], b = corners[(edge+1)%4], t = Double(j)/Double(subdivisions)
                let x = (a.0+(b.0-a.0)*t)*h, y = (a.1+(b.1-a.1)*t)*v
                let dx = fx+rx*x+ux*y, dy = fy+ry*x+uy*y, dz = fz+uz*y
                let norm = sqrt(dx*dx+dy*dy+dz*dz)
                func coordinate(_ distance: Double) -> (Double,Double) {
                    (i.latitude+dy/norm*distance/earth/rad,
                     i.longitude+dx/norm*distance/(earth*cos(i.latitude*rad))/rad)
                }
                func clearance(_ distance: Double) -> Double? {
                    let p = coordinate(distance)
                    let ground: Double
                    if let elevation { guard let z = elevation(p.0,p.1) else { return nil }; ground=z }
                    else { ground=0 }
                    return aircraftZ+dz/norm*distance-ground
                }
                var distance = maximumRange, clipped = true
                if elevation == nil {
                    let hit = dz < -1e-8 ? -i.height*norm/dz : Double.infinity
                    distance=min(hit,maximumRange); clipped=hit > maximumRange
                } else {
                    guard let initial = clearance(0), initial > 0 else { return [] }
                    var previous = 0.0, step = 5.0
                    while step <= maximumRange {
                        if cancelled() { return [] }
                        let c = clearance(step)
                        if c == nil || c! <= 0 {
                            var lo=previous, hi=step
                            for _ in 0..<10 {
                                let mid=(lo+hi)/2, value=clearance(mid)
                                if value == nil || value! <= 0 { hi=mid } else { lo=mid }
                            }
                            distance=(lo+hi)/2; clipped=c == nil
                            break
                        }
                        previous=step; step += 5
                    }
                }
                let p=coordinate(distance)
                vertices.append(.init(latitude:p.0,longitude:p.1,clipped:clipped,corner:j == 0))
            }
        }
        return vertices
    }
}

public struct CameraFootprintDrawing: Sendable {
    public let corners, boundary: [CameraFootprintVertex]
    public init(input: CameraFootprintInput, terrain: [CameraFootprintVertex]?) {
        boundary = terrain ?? []
        let terrainCorners = boundary.filter { $0.corner }
        corners = terrainCorners.count == 4 ? terrainCorners : CameraFootprintGeometry.project(input)
    }
}

public struct CameraFootprintCornerStroke: Sendable {
    public let start, end: MapScreenPoint
}
public extension CameraFootprintGeometry {
    static func cornerStrokes(points: [MapScreenPoint], length: Double) -> [CameraFootprintCornerStroke] {
        guard points.count == 4, length.isFinite, length > 0 else { return [] }
        var strokes: [CameraFootprintCornerStroke] = []
        for index in points.indices {
            let start = points[index]
            for adjacent in [(index + 3) % 4, (index + 1) % 4] {
                let dx = points[adjacent].x - start.x, dy = points[adjacent].y - start.y
                let distance = hypot(dx,dy)
                guard distance.isFinite, distance > 1e-6 else { continue }
                let scale = min(length,distance * 0.4) / distance
                strokes.append(.init(start:start,end:.init(x:start.x+dx*scale,y:start.y+dy*scale)))
            }
        }
        return strokes
    }
}
