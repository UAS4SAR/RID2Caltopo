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

/// Footprint outline in ground coordinates.
/// Closed order is near-left, near-right, far-right, far-left.
/// Open (unbounded far edge): far-left → near-left → near-right → far-right, with no far edge.
public struct CameraFootprintOutline: Sendable, Equatable {
    public let vertices: [CameraFootprintVertex]
    public let closed: Bool
    public let nearGroundMeters: Double
    public let farGroundMeters: Double
    public let clamped: Bool
    public var isEmpty: Bool { vertices.isEmpty }
    public var count: Int { vertices.count }

    public init(vertices: [CameraFootprintVertex], closed: Bool,
                nearGroundMeters: Double = 0, farGroundMeters: Double = 0, clamped: Bool = false) {
        self.vertices = vertices
        self.closed = closed
        self.nearGroundMeters = nearGroundMeters
        self.farGroundMeters = farGroundMeters
        self.clamped = clamped
    }

    public static let empty = CameraFootprintOutline(vertices: [], closed: true)
}

public enum CameraFootprintGeometry {
    public static let maximumRange = 3000.0
    /// Launch-relative pinhole projection. Terrain closure must return nil outside downloaded S1M.
    /// Corner order: near-left, near-right, far-right, far-left. Unbounded far corners yield an open outline.
    public static func project(_ input: CameraFootprintInput,
                               elevation: ((Double, Double) -> Double?)? = nil,
                               cancelled: () -> Bool = { false },
                               edgeSubdivisions: Int? = nil) -> CameraFootprintOutline {
        let i = input
        guard [i.latitude,i.longitude,i.launchLatitude,i.launchLongitude,i.height,i.azimuth,i.tilt,
               i.horizontalFov,i.verticalFov].allSatisfy(\.isFinite), i.height > 0,
              (0.01...179).contains(i.horizontalFov), (0.01...179).contains(i.verticalFov),
              (-85...85).contains(i.latitude), (-180...180).contains(i.longitude) else { return .empty }
        let launchGround = elevation?(i.launchLatitude,i.launchLongitude)
        if elevation != nil && launchGround == nil { return .empty }
        let aircraftZ = (launchGround ?? 0) + i.height
        let rad = Double.pi / 180, earth = 6378137.0
        let yaw = i.azimuth*rad, pitch = i.tilt*rad
        let fx = sin(yaw)*cos(pitch), fy = cos(yaw)*cos(pitch), fz = sin(pitch)
        let rx = cos(yaw), ry = -sin(yaw)
        let ux = -sin(yaw)*sin(pitch), uy = -cos(yaw)*sin(pitch), uz = cos(pitch)
        let h = tan(i.horizontalFov*rad/2), v = tan(i.verticalFov*rad/2)
        // Near-left, near-right, far-right, far-left (image y=+1 is toward the far FOV edge).
        let corners: [(Double,Double)] = [(-1,-1),(1,-1),(1,1),(-1,1)]
        let subdivisions = min(8, max(1, edgeSubdivisions ?? (elevation == nil ? 1 : 8)))

        struct Hit {
            let latitude, longitude: Double
            let clipped: Bool
            let atMaxRange: Bool
            let groundMeters: Double
        }

        func rayDir(_ sx: Double, _ sy: Double) -> (Double, Double, Double) {
            let x = sx * h, y = sy * v
            let dx = fx + rx * x + ux * y, dy = fy + ry * x + uy * y, dz = fz + uz * y
            let norm = sqrt(dx * dx + dy * dy + dz * dz)
            return (dx / norm, dy / norm, dz / norm)
        }

        func coordinate(_ east: Double, _ north: Double, groundMeters: Double) -> (Double, Double) {
            (i.latitude + north * groundMeters / earth / rad,
             i.longitude + east * groundMeters / (earth * cos(i.latitude * rad)) / rad)
        }

        /// Place a tip at horizontal `maximumRange` along the ray's ground azimuth.
        func horizontalTip(_ dir: (Double, Double, Double)) -> Hit {
            let horiz = hypot(dir.0, dir.1)
            let east: Double
            let north: Double
            if horiz > 1e-8 {
                east = dir.0 / horiz; north = dir.1 / horiz
            } else {
                east = sin(yaw); north = cos(yaw)
            }
            let p = coordinate(east, north, groundMeters: maximumRange)
            return Hit(latitude: p.0, longitude: p.1, clipped: true, atMaxRange: true, groundMeters: maximumRange)
        }

        func hit(_ sx: Double, _ sy: Double) -> Hit? {
            if cancelled() { return nil }
            let dir = rayDir(sx, sy)
            let (ndx, ndy, ndz) = dir
            func slantCoordinate(_ distance: Double) -> (Double, Double) {
                (i.latitude + ndy * distance / earth / rad,
                 i.longitude + ndx * distance / (earth * cos(i.latitude * rad)) / rad)
            }
            func clearance(_ distance: Double) -> Double? {
                let p = slantCoordinate(distance)
                let ground: Double
                if let elevation { guard let z = elevation(p.0, p.1) else { return nil }; ground = z }
                else { ground = 0 }
                return aircraftZ + ndz * distance - ground
            }
            let limit = maximumRange
            if elevation == nil {
                let planeHit = ndz < -1e-8 ? -i.height / ndz : Double.infinity
                if planeHit > limit { return horizontalTip(dir) }
                let p = slantCoordinate(planeHit)
                return Hit(latitude: p.0, longitude: p.1, clipped: false, atMaxRange: false,
                           groundMeters: planeHit * hypot(ndx, ndy))
            }
            guard let initial = clearance(0), initial > 0 else { return nil }
            var previous = 0.0, step = 5.0
            while step <= limit {
                if cancelled() { return nil }
                let c = clearance(step)
                if c == nil || c! <= 0 {
                    var lo = previous, hi = step
                    for _ in 0..<10 {
                        let mid = (lo + hi) / 2, value = clearance(mid)
                        if value == nil || value! <= 0 { hi = mid } else { lo = mid }
                    }
                    let distance = (lo + hi) / 2
                    let p = slantCoordinate(distance)
                    return Hit(latitude: p.0, longitude: p.1, clipped: c == nil, atMaxRange: false,
                               groundMeters: distance * hypot(ndx, ndy))
                }
                previous = step; step += 5
            }
            return horizontalTip(dir)
        }

        func vertex(_ hit: Hit, corner: Bool) -> CameraFootprintVertex {
            .init(latitude: hit.latitude, longitude: hit.longitude, clipped: hit.clipped, corner: corner)
        }

        var cornerHits: [Hit] = []
        cornerHits.reserveCapacity(4)
        for c in corners {
            guard let sample = hit(c.0, c.1) else { return .empty }
            cornerHits.append(sample)
        }
        let closed = !cornerHits[2].atMaxRange && !cornerHits[3].atMaxRange
        let nearGround = (cornerHits[0].groundMeters + cornerHits[1].groundMeters) / 2
        let clamped = cornerHits[2].atMaxRange || cornerHits[3].atMaxRange
        let farLeftTip = cornerHits[3].atMaxRange ? horizontalTip(rayDir(corners[3].0, corners[3].1)) : cornerHits[3]
        let farRightTip = cornerHits[2].atMaxRange ? horizontalTip(rayDir(corners[2].0, corners[2].1)) : cornerHits[2]
        let farGround = (farLeftTip.groundMeters + farRightTip.groundMeters) / 2

        var vertices: [CameraFootprintVertex] = []
        if closed {
            for edge in 0..<4 {
                let a = corners[edge], b = corners[(edge + 1) % 4]
                for j in 0..<subdivisions {
                    if cancelled() { return .empty }
                    if j == 0 {
                        vertices.append(vertex(cornerHits[edge], corner: true))
                    } else {
                        let t = Double(j) / Double(subdivisions)
                        guard let sample = hit(a.0 + (b.0 - a.0) * t, a.1 + (b.1 - a.1) * t) else { return .empty }
                        vertices.append(vertex(sample, corner: false))
                    }
                }
            }
        } else {
            func appendGroundChord(start: Hit, end: Hit, startCorner: Bool, endCorner: Bool) {
                for j in 0..<subdivisions {
                    let t = Double(j) / Double(subdivisions)
                    let lat = start.latitude + (end.latitude - start.latitude) * t
                    let lon = start.longitude + (end.longitude - start.longitude) * t
                    vertices.append(.init(latitude: lat, longitude: lon,
                                          clipped: start.clipped || end.clipped,
                                          corner: j == 0 && startCorner))
                }
                if endCorner { vertices.append(vertex(end, corner: true)) }
            }
            appendGroundChord(start: farLeftTip, end: cornerHits[0], startCorner: true, endCorner: true)
            for j in 1..<subdivisions {
                let t = Double(j) / Double(subdivisions)
                let a = corners[0], b = corners[1]
                guard let sample = hit(a.0 + (b.0 - a.0) * t, a.1 + (b.1 - a.1) * t) else { return .empty }
                vertices.append(vertex(sample, corner: false))
            }
            vertices.append(vertex(cornerHits[1], corner: true))
            for j in 1..<subdivisions {
                let t = Double(j) / Double(subdivisions)
                let lat = cornerHits[1].latitude + (farRightTip.latitude - cornerHits[1].latitude) * t
                let lon = cornerHits[1].longitude + (farRightTip.longitude - cornerHits[1].longitude) * t
                vertices.append(.init(latitude: lat, longitude: lon, clipped: true, corner: false))
            }
            vertices.append(vertex(farRightTip, corner: true))
        }
        guard vertices.count >= 3 else { return .empty }
        return CameraFootprintOutline(
            vertices: vertices,
            closed: closed,
            nearGroundMeters: nearGround,
            farGroundMeters: farGround,
            clamped: clamped
        )
    }
}

/// Non-adjacent edge crossings in a local ENU frame. Open outlines do not wrap.
public func cameraFootprintOutlineSelfIntersects(_ outline: CameraFootprintOutline) -> Bool {
    let vertices = outline.vertices
    guard vertices.count >= 4 else { return false }
    let originLat = vertices[0].latitude, originLon = vertices[0].longitude
    guard originLat.isFinite, originLon.isFinite else { return false }
    let cosLat = cos(originLat * .pi / 180)
    func xy(_ v: CameraFootprintVertex) -> (Double, Double) {
        ((v.longitude - originLon) * .pi / 180 * 6_378_137 * cosLat,
         (v.latitude - originLat) * .pi / 180 * 6_378_137)
    }
    func cross(_ ax: Double, _ ay: Double, _ bx: Double, _ by: Double) -> Double { ax * by - ay * bx }
    func segmentsIntersect(_ a: (Double, Double), _ b: (Double, Double),
                           _ c: (Double, Double), _ d: (Double, Double)) -> Bool {
        let abx = b.0 - a.0, aby = b.1 - a.1
        let d1 = cross(abx, aby, c.0 - a.0, c.1 - a.1)
        let d2 = cross(abx, aby, d.0 - a.0, d.1 - a.1)
        let cdx = d.0 - c.0, cdy = d.1 - c.1
        let d3 = cross(cdx, cdy, a.0 - c.0, a.1 - c.1)
        let d4 = cross(cdx, cdy, b.0 - c.0, b.1 - c.1)
        return d1 * d2 < 0 && d3 * d4 < 0
    }
    let points = vertices.map(xy)
    let n = points.count
    let segmentCount = outline.closed ? n : n - 1
    for i in 0..<segmentCount {
        let a = points[i], b = points[outline.closed ? (i + 1) % n : i + 1]
        let lastJ = outline.closed && i == 0 ? segmentCount - 1 : segmentCount
        if i + 2 < lastJ {
            for j in (i + 2)..<lastJ {
                if outline.closed && i == 0 && j == segmentCount - 1 { continue }
                let c = points[j], d = points[outline.closed ? (j + 1) % n : j + 1]
                if segmentsIntersect(a, b, c, d) { return true }
            }
        }
    }
    return false
}

/// Along-look ground depth: distance between near-edge and far-edge midpoints.
public func cameraFootprintDepthMeters(_ outline: CameraFootprintOutline) -> Double {
    let corners = outline.vertices.filter(\.corner)
    guard corners.count >= 4 else {
        return max(0, outline.farGroundMeters - outline.nearGroundMeters)
    }
    let nearA: CameraFootprintVertex
    let nearB: CameraFootprintVertex
    let farA: CameraFootprintVertex
    let farB: CameraFootprintVertex
    if outline.closed {
        nearA = corners[0]; nearB = corners[1]; farA = corners[2]; farB = corners[3]
    } else {
        nearA = corners[1]; nearB = corners[2]; farA = corners[0]; farB = corners[3]
    }
    let nearLat = (nearA.latitude + nearB.latitude) / 2
    let nearLon = (nearA.longitude + nearB.longitude) / 2
    let farLat = (farA.latitude + farB.latitude) / 2
    let farLon = (farA.longitude + farB.longitude) / 2
    return cameraFootprintDistanceMeters(nearLat, nearLon, farLat, farLon)
}

public struct CameraFootprintDrawing: Sendable {
    public let corners, boundary: CameraFootprintOutline
    /// `liveCorners` keeps the flat preview on the current pose. Terrain vertices are the outline only.
    public init(input: CameraFootprintInput, terrain: CameraFootprintOutline?, liveCorners: Bool = false) {
        let boundary = (terrain?.isEmpty == false) ? terrain! : .empty
        let terrainCorners = boundary.vertices.filter(\.corner)
        if !liveCorners, boundary.closed, terrainCorners.count == 4 {
            corners = CameraFootprintOutline(
                vertices: terrainCorners, closed: boundary.closed,
                nearGroundMeters: boundary.nearGroundMeters, farGroundMeters: boundary.farGroundMeters,
                clamped: boundary.clamped
            )
        } else {
            let flat = CameraFootprintGeometry.project(input)
            if flat.isEmpty, boundary.closed, terrainCorners.count == 4 {
                corners = CameraFootprintOutline(
                    vertices: terrainCorners, closed: boundary.closed,
                    nearGroundMeters: boundary.nearGroundMeters, farGroundMeters: boundary.farGroundMeters,
                    clamped: boundary.clamped
                )
            } else {
                corners = flat
            }
        }
        self.boundary = boundary
    }
}

public let cameraFootprintTerrainPositionMeters = 30.0
public let cameraFootprintTerrainHeightMeters = 10.0
public let cameraFootprintTerrainAzimuthDegrees = 12.0
public let cameraFootprintTerrainTiltDegrees = 5.0
public let cameraFootprintTerrainFovDegrees = 1.5
public let cameraFootprintTerrainLaunchMeters = 1.0
public let cameraFootprintShallowTiltDegrees = -25.0
public let cameraFootprintShallowTerrainPositionMeters = 8.0
public let cameraFootprintShallowTerrainAzimuthDegrees = 4.0
public let cameraFootprintShallowTerrainTiltDegrees = 2.0
public let cameraFootprintDebugIntervalMs: Int64 = 5_000

public func cameraFootprintDistanceMeters(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
    guard [lat1, lon1, lat2, lon2].allSatisfy(\.isFinite) else { return .infinity }
    let earth = 6_378_137.0
    let meanLat = (lat1 + lat2) / 2 * .pi / 180
    var dLon = (lon2 - lon1) * .pi / 180
    if dLon > .pi { dLon -= 2 * .pi }
    if dLon < -.pi { dLon += 2 * .pi }
    return earth * hypot(dLon * cos(meanLat), (lat2 - lat1) * .pi / 180)
}

public func cameraFootprintAngleDeltaDegrees(_ a: Double, _ b: Double) -> Double {
    guard a.isFinite, b.isFinite else { return .infinity }
    let delta = abs(a - b).truncatingRemainder(dividingBy: 360)
    return min(delta, 360 - delta)
}

/// Exact pose equality never matches a moving SEI stream. One 500 ms terrain pass stays usable through a slow orbit.
public func cameraFootprintTerrainReusable(cached: CameraFootprintInput, current: CameraFootprintInput) -> Bool {
    guard cameraFootprintDistanceMeters(cached.launchLatitude, cached.launchLongitude, current.launchLatitude, current.launchLongitude) <= cameraFootprintTerrainLaunchMeters else { return false }
    let shallow = cached.tilt > cameraFootprintShallowTiltDegrees || current.tilt > cameraFootprintShallowTiltDegrees
    let positionLimit = shallow ? cameraFootprintShallowTerrainPositionMeters : cameraFootprintTerrainPositionMeters
    let azimuthLimit = shallow ? cameraFootprintShallowTerrainAzimuthDegrees : cameraFootprintTerrainAzimuthDegrees
    let tiltLimit = shallow ? cameraFootprintShallowTerrainTiltDegrees : cameraFootprintTerrainTiltDegrees
    guard cameraFootprintDistanceMeters(cached.latitude, cached.longitude, current.latitude, current.longitude) <= positionLimit,
          abs(cached.height - current.height) <= cameraFootprintTerrainHeightMeters,
          cameraFootprintAngleDeltaDegrees(cached.azimuth, current.azimuth) <= azimuthLimit,
          abs(cached.tilt - current.tilt) <= tiltLimit,
          abs(cached.horizontalFov - current.horizontalFov) <= cameraFootprintTerrainFovDegrees,
          abs(cached.verticalFov - current.verticalFov) <= cameraFootprintTerrainFovDegrees
    else { return false }
    return true
}

/// Exactly one footprint per aircraft. Terrain replaces the live outline in place when it does not self-intersect.
public func cameraFootprintDisplayOutline(_ drawing: CameraFootprintDrawing) -> CameraFootprintOutline {
    if drawing.boundary.count >= 3 && !cameraFootprintOutlineSelfIntersects(drawing.boundary) {
        return drawing.boundary
    }
    if drawing.corners.count >= 3 { return drawing.corners }
    return .empty
}

/// In-place footprint overlays keyed by aircraft. Toggle-off / stream-end clears an id.
public final class CameraFootprintOverlayBook: @unchecked Sendable {
    private var byDrone: [String: CameraFootprintOutline] = [:]

    public init() {}

    @discardableResult
    public func sync(
        inputs: [String: CameraFootprintInput],
        terrain: [String: (CameraFootprintInput, CameraFootprintOutline)] = [:]
    ) -> [String: CameraFootprintOutline] {
        var next: [String: CameraFootprintOutline] = [:]
        for (id, input) in inputs {
            let sampled: CameraFootprintOutline? = {
                guard let entry = terrain[id], !entry.1.isEmpty,
                      cameraFootprintTerrainReusable(cached: entry.0, current: input) else { return nil }
                return entry.1
            }()
            let outline = cameraFootprintDisplayOutline(CameraFootprintDrawing(input: input, terrain: sampled, liveCorners: true))
            if outline.count >= 3 { next[id] = outline }
        }
        byDrone = next
        return next
    }

    public func outlineCount(_ id: String) -> Int { byDrone[id] == nil ? 0 : 1 }
    public var size: Int { byDrone.count }
    public func clear() { byDrone.removeAll() }
}

public func cameraFootprintDebugLine(
    source: String,
    gimbalTiltDeg: Double,
    headingDeg: Double,
    aglMeters: Double,
    nearGroundMeters: Double,
    farGroundMeters: Double,
    clamped: Bool
) -> String {
    String(format: "CameraFootprint source=%@ gimbalTiltDeg=%.1f headingDeg=%.1f aglM=%.1f nearM=%.0f farM=%.0f clamped=%@",
           source, gimbalTiltDeg, headingDeg, aglMeters, nearGroundMeters, farGroundMeters, clamped ? "true" : "false")
}

/// At most one debug line per drone per `cameraFootprintDebugIntervalMs`.
public final class CameraFootprintDebugThrottle: @unchecked Sendable {
    private let intervalMs: Int64
    private var lastByDrone: [String: Int64] = [:]
    public init(intervalMs: Int64 = cameraFootprintDebugIntervalMs) { self.intervalMs = intervalMs }
    public func shouldLog(droneKey: String, nowMs: Int64) -> Bool {
        let last = lastByDrone[droneKey] ?? 0
        guard nowMs - last >= intervalMs else { return false }
        lastByDrone[droneKey] = nowMs
        return true
    }
    public func retain(activeKeys: Set<String>) {
        lastByDrone = lastByDrone.filter { activeKeys.contains($0.key) }
    }
}

public struct CameraFootprintFrameRect: Sendable, Equatable {
    public let left, top, right, bottom: Double
    public init(left: Double, top: Double, right: Double, bottom: Double) {
        self.left = left; self.top = top; self.right = right; self.bottom = bottom
    }
}

/// AD's scan zone is a centered fraction of the video frame, clamped to 0.5...1 like the detector.
/// Nil means AD is off. A centered crop scales tan(FOV/2) so the frame aspect is unchanged.
public func cameraFootprintAdScanRect(_ zoneFraction: Double) -> CameraFootprintFrameRect? {
    guard zoneFraction.isFinite else { return nil }
    let zone = min(max(zoneFraction, 0.5), 1)
    let margin = (1 - zone) / 2
    return CameraFootprintFrameRect(left: margin, top: margin, right: 1 - margin, bottom: 1 - margin)
}

/// Camera-plane offsets of a video rectangle inside the full FOV. Origin is top-left, y down;
/// camera x is right and y is up. Order matches image top-left rect corners.
public func cameraFootprintSubframeRayOffsets(
    horizontalFovDeg: Double,
    verticalFovDeg: Double,
    rect: CameraFootprintFrameRect
) -> [(Double, Double)]? {
    let values = [horizontalFovDeg, verticalFovDeg, rect.left, rect.top, rect.right, rect.bottom]
    guard values.allSatisfy(\.isFinite),
          (0.01 ... 179).contains(horizontalFovDeg),
          (0.01 ... 179).contains(verticalFovDeg),
          rect.left >= 0, rect.top >= 0, rect.right <= 1, rect.bottom <= 1,
          rect.right - rect.left >= 1e-6, rect.bottom - rect.top >= 1e-6
    else { return nil }
    let h = tan(horizontalFovDeg * .pi / 360)
    let v = tan(verticalFovDeg * .pi / 360)
    func offset(_ u: Double, _ imageV: Double) -> (Double, Double) {
        ((u - 0.5) * 2 * h, (0.5 - imageV) * 2 * v)
    }
    return [
        offset(rect.left, rect.top),
        offset(rect.right, rect.top),
        offset(rect.right, rect.bottom),
        offset(rect.left, rect.bottom),
    ]
}

public func cameraFootprintApplyingAdScanZone(
    _ input: CameraFootprintInput,
    scanZoneFraction: Double?
) -> CameraFootprintInput {
    guard let zone = scanZoneFraction, let rect = cameraFootprintAdScanRect(zone), rect.right - rect.left < 1 - 1e-9,
          let offsets = cameraFootprintSubframeRayOffsets(
            horizontalFovDeg: input.horizontalFov, verticalFovDeg: input.verticalFov, rect: rect
          )
    else { return input }
    let horizontal = atan(abs(offsets[1].0)) * 360 / .pi
    let vertical = atan(abs(offsets[0].1)) * 360 / .pi
    guard (0.01 ... 179).contains(horizontal), (0.01 ... 179).contains(vertical) else { return input }
    return CameraFootprintInput(
        latitude: input.latitude, longitude: input.longitude,
        launchLatitude: input.launchLatitude, launchLongitude: input.launchLongitude,
        height: input.height, azimuth: input.azimuth, tilt: input.tilt,
        horizontalFov: horizontal, verticalFov: vertical
    )
}

/// Stable per-aircraft key. A blank id is not stored, and a missing key stays off.
public func cameraFootprintPreferenceKey(_ remoteID: String) -> String? {
    let normalized = remoteID.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    return normalized.isEmpty ? nil : normalized
}

public func mergeCameraFootprintTerrain(
    previous: [String: (CameraFootprintInput, CameraFootprintOutline)],
    computed: [String: (CameraFootprintInput, CameraFootprintOutline)],
    current: [String: CameraFootprintInput]
) -> [String: (CameraFootprintInput, CameraFootprintOutline)] {
    guard !current.isEmpty else { return [:] }
    var merged: [String: (CameraFootprintInput, CameraFootprintOutline)] = [:]
    for (id, input) in current {
        if let fresh = computed[id], !fresh.1.isEmpty {
            merged[id] = fresh
        } else if let prior = previous[id], !prior.1.isEmpty, cameraFootprintTerrainReusable(cached: prior.0, current: input) {
            merged[id] = prior
        }
    }
    return merged
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
