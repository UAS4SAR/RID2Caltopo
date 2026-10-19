import Foundation

/// Shared Android/iOS capture and bounded-processing contract. Saved missions retain their preset.
public enum MappingProcessingPolicy {
    public static let maximumPhotos = 10_000
    public static let corePhotos = 128
    public static let regionPhotos = 192
    public static let acre = 4046.8564224
    public static let maximumCoreAcres = 2.0
    public static let outputOverlapMeters = 5.0
    public static let meshSpacingMeters = 0.4
    public static let surfaceCellMeters = 0.2
    public static let storageWarningFraction = 0.8

    public struct Pass: Codable, Equatable, Sendable {
        public let gimbalPitchDegrees, directionDegrees, forwardOverlapPercent, sideOverlapPercent: Double
    }
    public struct CaptureProfile: Codable, Equatable, Sendable {
        public let id: String
        public let passes: [Pass]
    }
    public static let brushTrees = CaptureProfile(id: "brush-trees-crossed-70-v1", passes: [
        Pass(gimbalPitchDegrees: -90, directionDegrees: 0, forwardOverlapPercent: 85, sideOverlapPercent: 85),
        Pass(gimbalPitchDegrees: -70, directionDegrees: 90, forwardOverlapPercent: 85, sideOverlapPercent: 85)
    ])
    public static let openGround = CaptureProfile(id: "nadir", passes: [
        Pass(gimbalPitchDegrees: -90, directionDegrees: 0, forwardOverlapPercent: 80, sideOverlapPercent: 70)
    ])

    public static let nadir60Standard = CaptureProfile(id:"nadir-plus-60-80-70",passes:[Pass(gimbalPitchDegrees:-90,directionDegrees:0,forwardOverlapPercent:80,sideOverlapPercent:70),Pass(gimbalPitchDegrees:-60,directionDegrees:90,forwardOverlapPercent:80,sideOverlapPercent:70)])
    public static let nadir60 = CaptureProfile(id:"nadir-plus-60",passes:[Pass(gimbalPitchDegrees:-90,directionDegrees:0,forwardOverlapPercent:85,sideOverlapPercent:80),Pass(gimbalPitchDegrees:-60,directionDegrees:90,forwardOverlapPercent:85,sideOverlapPercent:80)])
    public static let nadir45 = CaptureProfile(id:"nadir-plus-45",passes:[Pass(gimbalPitchDegrees:-90,directionDegrees:0,forwardOverlapPercent:85,sideOverlapPercent:80),Pass(gimbalPitchDegrees:-45,directionDegrees:90,forwardOverlapPercent:85,sideOverlapPercent:80)])
    public static let captureProfiles = [brushTrees,openGround,nadir60Standard,nadir60,nadir45]

    public struct Bounds: Codable, Equatable, Sendable {
        public var west, south, east, north: Double
        public init(west: Double, south: Double, east: Double, north: Double) {
            self.west = west; self.south = south; self.east = east; self.north = north
        }
        public func area(scale: Double) -> Double { (east-west)*(north-south)*scale*scale }
        public func expanded(_ distance: Double) -> Bounds {
            Bounds(west: west-distance, south: south-distance, east: east+distance, north: north+distance)
        }
        var valid: Bool { [west,south,east,north].allSatisfy(\.isFinite) && east > west && north > south }
    }
    public struct Photo: Codable, Equatable, Sendable {
        public let digest: String
        public let x, y: Double
        public init(digest: String, x: Double, y: Double) { self.digest=digest; self.x=x; self.y=y }
    }
    public struct Region: Codable, Equatable, Sendable {
        public let core, images: [Photo]
        public let bounds: Bounds
    }
    public static func regions(photos: [Photo], bounds: Bounds, groundScale: Double) throws -> [Region] {
        guard (3...maximumPhotos).contains(photos.count), Set(photos.map(\.digest)).count == photos.count,
              photos.allSatisfy({ $0.x.isFinite && $0.y.isFinite }), bounds.valid,
              groundScale.isFinite, groundScale > 0 else { throw MappingMission.Failure("Invalid regional processing inputs.") }
        if photos.count <= regionPhotos && bounds.area(scale: groundScale) <= maximumCoreAcres*acre {
            return [Region(core: photos, images: photos, bounds: bounds)]
        }
        var cores: [([Photo], Bounds)] = []
        func split(_ items: [Photo], _ box: Bounds) throws {
            guard cores.count < 200 else { throw MappingMission.Failure("This assignment needs more than 200 processing regions.") }
            if items.count <= corePhotos && box.area(scale: groundScale) <= maximumCoreAcres*acre {
                cores.append((items,box)); return
            }
            let byX = items.count > corePhotos
                ? items.map(\.x).max()!-items.map(\.x).min()! >= items.map(\.y).max()!-items.map(\.y).min()!
                : box.east-box.west >= box.north-box.south
            let sorted = items.sorted { a,b in
                let aa=byX ? a.x:a.y, bb=byX ? b.x:b.y
                return aa == bb ? a.digest < b.digest : aa < bb
            }
            let median=sorted.count/2, low=byX ? box.west:box.south, high=byX ? box.east:box.north
            let middle=items.count > corePhotos ? median : sorted.filter { (byX ? $0.x:$0.y) < (low+high)/2 }.count
            let cut: Double
            if items.count > corePhotos {
                let a=sorted[median-1], b=sorted[median]
                cut=min(high-(high-low)*0.1,max(low+(high-low)*0.1,(byX ? a.x+b.x:a.y+b.y)/2))
            } else { cut=(low+high)/2 }
            var left=box, right=box
            if byX { left.east=cut; right.west=cut } else { left.north=cut; right.south=cut }
            try split(Array(sorted.prefix(middle)),left); try split(Array(sorted.dropFirst(middle)),right)
        }
        try split(photos,bounds)
        return cores.map { core,box in
            let ids=Set(core.map(\.digest))
            func distance(_ p: Photo) -> Double {
                if !core.isEmpty { return core.map { pow($0.x-p.x,2)+pow($0.y-p.y,2) }.min()! }
                return pow(p.x-min(box.east,max(box.west,p.x)),2)+pow(p.y-min(box.north,max(box.south,p.y)),2)
            }
            let candidates: [Photo] = photos.filter { !ids.contains($0.digest) }
            let distances: [(Photo,Double)] = candidates.map { ($0,distance($0)) }
            let ordered = distances.sorted { a,b in
                if a.1 == b.1 { return a.0.digest < b.0.digest }
                return a.1 < b.1
            }
            let halo: [Photo] = ordered.prefix(regionPhotos-core.count).map { $0.0 }
            return Region(core:core,images:(core+halo).sorted{$0.digest < $1.digest},bounds:box)
        }
    }

    public struct StorageEstimate: Sendable, Equatable {
        public let originals, products, temporary: Int64
        public var required: Int64 { originals+products+temporary }
        public func approachesCapacity(total: Int64, available: Int64) -> Bool {
            guard total > 0, available >= 0 else { return true }
            return Double(total-available)+Double(required) >= Double(total)*MappingProcessingPolicy.storageWarningFraction
        }
    }
    public static func storage(photos: Int, width: Int, height: Int, areaSquareMeters: Double, pixelMeters: Double, regional: Bool) throws -> StorageEstimate {
        guard (3...maximumPhotos).contains(photos), (1...12000).contains(width), (1...12000).contains(height),
              areaSquareMeters.isFinite, areaSquareMeters > 0, pixelMeters.isFinite, pixelMeters > 0 else { throw MappingMission.Failure("Invalid storage estimate inputs.") }
        let raster=ceil(areaSquareMeters/(pixelMeters*pixelMeters)*4*4/3)
        let model=ceil(areaSquareMeters/(surfaceCellMeters*surfaceCellMeters)*64)
        let raw=Double(min(photos,regionPhotos))*Double(width)*Double(height)*4
        let products=raster*2+model, temporary=raw+(regional ? (raster+model)*3:model)
        guard products+temporary < Double(Int64.max)/2 else { throw MappingMission.Failure("Mapping storage estimate is too large.") }
        return StorageEstimate(originals:Int64(photos)*12*1048576*2,products:Int64(products),temporary:Int64(temporary))
    }
}

/// A wall-clock display never advances work: only real worker callbacks update activity.
public struct MappingProgressSnapshot: Sendable, Equatable {
    public let stage: String
    public let fraction: Double
    public let startedAt, activityAt: Date
    public init(stage: String, fraction: Double, startedAt: Date, activityAt: Date = Date()) {
        self.stage=stage; self.fraction=min(1,max(0,fraction.isFinite ? fraction:0)); self.startedAt=startedAt; self.activityAt=activityAt
    }
    public func elapsed(at now: Date) -> Int { max(0,Int(now.timeIntervalSince(startedAt))) }
    public func activity(at now: Date) -> String {
        let seconds=max(0,Int(now.timeIntervalSince(activityAt)))
        return seconds < 5 ? "Worker updated just now" : "Last worker update \(seconds) sec ago · waiting for the current operation"
    }
    public static let explanation = "Overall progress is approximate. Saving and packaging may take several minutes; file and byte counts show completed work."
    public static func bytes(_ done: Int64, total: Int64) -> String {
        String(format:"%.1f / %.1f MB",locale:Locale(identifier:"en_US_POSIX"),Double(done)/1048576,Double(total)/1048576)
    }
}

public extension MappingProcessingPolicy.CaptureProfile {
    var exportQualified:Bool {self != MappingProcessingPolicy.nadir45 && MappingProcessingPolicy.captureProfiles.contains(self)}
    var description:String {
        switch id {
        case "nadir":return "Single downward-looking grid for open ground and low brush. 80% forward / 70% side overlap."
        case "brush-trees-crossed-70-v1":return "Nadir anchors plus crossing −70° views for brush and trees. 85% overlap at the estimated canopy surface. Ground hidden in every view remains unseen."
        case "nadir-plus-60-80-70":return "Nadir plus crossing −60° views. 80% forward / 70% side overlap; comparison trial."
        case "nadir-plus-60":return "Nadir plus crossing −60° views. 85% forward / 80% side overlap; more photos than the 80% / 70% trial."
        case "nadir-plus-45":return "Nadir plus crossing −45° views. Preview only; route export is unavailable."
        default:return label
        }
    }
    var label:String {
        switch id {
        case "brush-trees-crossed-70-v1":return "Brush / trees · canopy-aware crossed survey"
        case "nadir":return "Open ground / low brush · nadir −90°"
        case "nadir-plus-60-80-70":return "Brush / trees · nadir + −60° crossing trial"
        case "nadir-plus-60":return "Nadir + −60° crossing · 85% / 80% trial"
        case "nadir-plus-45":return "Nadir + −45° crossing · later comparison preview"
        default:return id
        }
    }
}
