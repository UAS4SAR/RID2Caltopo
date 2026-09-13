import Foundation
import CryptoKit

/// Offline DSM and its explicitly compatible ground reference. Never registered as an AGL DEM.
public struct OperationalSurfacePackage: Sendable {
    public struct WireObservation: Codable, Sendable {
        public let latitude, longitude: Double
        public let source, date, confidence: String
        public let heightMeters: Double?
    }
    public struct Metadata: Codable, Sendable {
        public let referenceGroup, sourceCRS: String?
        public let coreWest, coreSouth: Double?
        public let coreWidth, coreHeight: Int?
        public let wireObservations: [WireObservation]?
        public let schema: Int
        public let layer, id, version, processingVersion, sourceURL, surveyDate: String
        public let verticalReference, horizontalCRS, units, quality: String
        public let originLatitude, originLongitude, west, south, spacing: Double
        public let width, height: Int
        public let surfaceSHA256, groundSHA256: String
    }
    public struct Point: Codable, Sendable, Equatable {
        public let latitude, longitude: Double
        public init(latitude: Double, longitude: Double) { self.latitude=latitude; self.longitude=longitude }
    }
    public struct Peak: Codable, Sendable, Equatable {
        public let elevation, latitude, longitude, distance: Double
        public let ground: Double?
    }
    public struct Analysis: Codable, Sendable, Equatable {
        public let peak: Peak?
        public let complete: Bool
        public let checked, missing: Int
        public init(peak: Peak?, complete: Bool, checked: Int, missing: Int) {
            self.peak=peak;self.complete=complete;self.checked=checked;self.missing=missing
        }
    }
    public let metadata: Metadata
    private let surface, ground: [Float]
    public var id: String { metadata.id + "@" + metadata.version }
    public static let radius = 60.96
    public static let maximumBytes = 40*1024*1024
    private static let earthRadius = 6371008.8
    public init(data: Data) throws {
        guard data.count <= Self.maximumBytes else { throw OperationalZipError.sizeLimitExceeded }
        let entries = try OperationalZipArchive.decode(data, maximumEntries: 3, maximumExpandedBytes: Self.maximumBytes)
        guard Set(entries.map(\.path)) == Set(["manifest.json", "surface.f32", "ground.f32"]), entries.count == 3 else { throw OperationalZipError.invalidArchive }
        let files = Dictionary(uniqueKeysWithValues: entries.map { ($0.path,$0.data) })
        let m = try JSONDecoder().decode(Metadata.self, from: files["manifest.json"]!)
        guard m.schema == 1, m.layer == "top-surface", m.units == "metres",
              m.horizontalCRS == "R2C_LOCAL_EQUIRECTANGULAR_WGS84",
              [m.id,m.version,m.processingVersion,m.sourceURL,m.surveyDate,m.verticalReference,m.quality].allSatisfy({ !$0.isEmpty }),
              (1...4096).contains(m.width), (1...4096).contains(m.height), m.width*m.height <= 4_000_000,
              m.spacing.isFinite, (0.25...10).contains(m.spacing), Double(m.width)*m.spacing <= 10000, Double(m.height)*m.spacing <= 10000,
              abs(m.originLatitude)<70, abs(m.originLongitude)<=180, abs(m.west)<=10000, abs(m.south)<=10000
        else { throw OperationalZipError.invalidArchive }
        guard ["NAVD88 / GEOID12B / metres", "NAVD88 / GEOID18 / metres", "NAVD88 / same-survey paired reference / metres"].contains(m.verticalReference),
              (m.wireObservations?.count ?? 0)<=1000,
              (m.wireObservations ?? []).allSatisfy({ abs($0.latitude)<=90 && abs($0.longitude)<=180 && !$0.source.isEmpty && !$0.date.isEmpty && !$0.confidence.isEmpty && $0.heightMeters == nil })
        else { throw OperationalZipError.invalidArchive }
        if m.verticalReference.contains("same-survey") {
            guard m.referenceGroup?.isEmpty == false, m.sourceCRS?.contains("5703") == true else { throw OperationalZipError.invalidArchive }
        }
        func raster(_ name: String, _ checksum: String) throws -> [Float] {
            let bytes=files[name]!
            guard bytes.count == m.width*m.height*4,
                  SHA256.hash(data: bytes).map({String(format:"%02x",$0)}).joined() == checksum
            else { throw OperationalZipError.invalidArchive }
            let values = bytes.withUnsafeBytes { buffer in
                (0..<m.width*m.height).map { Float(bitPattern: UInt32(littleEndian: buffer.loadUnaligned(fromByteOffset: $0*4, as: UInt32.self))) }
            }
            guard !values.contains(where: { $0.isInfinite }) else { throw OperationalZipError.invalidArchive }
            return values
        }
        metadata=m
        surface=try raster("surface.f32",m.surfaceSHA256)
        ground=try raster("ground.f32",m.groundSHA256)
    }
    public var wireNotes:String {
        guard let wires=metadata.wireObservations,!wires.isEmpty else { return "No wire inventory supplied; coverage is not established." }
        return wires.map { "Wire crossing — height unknown at \($0.latitude), \($0.longitude); \($0.source); observed \($0.date); geometry \($0.confidence). No span inferred." }.joined(separator:"\n")
    }
    public func xy(_ p: Point) -> (Double,Double) {
        ((p.longitude-metadata.originLongitude)*Double.pi/180*Self.earthRadius*cos(metadata.originLatitude*Double.pi/180),
         (p.latitude-metadata.originLatitude)*Double.pi/180*Self.earthRadius)
    }
    public func coordinate(_ x:Double,_ y:Double) -> Point {
        Point(latitude:metadata.originLatitude+y/Self.earthRadius*180/Double.pi,
              longitude:metadata.originLongitude+x/(Self.earthRadius*cos(metadata.originLatitude*Double.pi/180))*180/Double.pi)
    }
    public func groundAt(_ p:Point) -> Double? {
        guard abs(p.latitude)<=90, abs(p.longitude)<=180 else { return nil }
        let (x,y)=xy(p); let c=Int(floor((x-metadata.west)/metadata.spacing)); let r=Int(floor((y-metadata.south)/metadata.spacing))
        guard (0..<metadata.width).contains(c), (0..<metadata.height).contains(r) else { return nil }
        let value=Double(ground[r*metadata.width+c]); return value.isFinite ? value : nil
    }
    public func disk(_ p:Point, radius:Double=Self.radius) -> Analysis {
        guard abs(p.latitude)<=90,abs(p.longitude)<=180,radius.isFinite,radius>0,radius<=5000 else { return Analysis(peak:nil,complete:false,checked:0,missing:0) }
        let (x,y)=xy(p)
        return region(x-radius,y-radius,x+radius,y+radius,x,y) { a,b in
            let dx=max(0,abs(a-x)-metadata.spacing/2), dy=max(0,abs(b-y)-metadata.spacing/2)
            return dx*dx+dy*dy <= radius*radius
        }
    }
    public func briefing(points:[Point], corridor:Double, polygon:Bool, coreOnly:Bool = false) -> Analysis {
        guard points.count >= (polygon ? 3 : 2), points.allSatisfy({abs($0.latitude)<=90 && abs($0.longitude)<=180}), corridor.isFinite,corridor>=0,corridor<=5000 else { return Analysis(peak:nil,complete:false,checked:0,missing:0) }
        let p=points.map(xy); let pad=corridor+metadata.spacing*sqrt(2)/2
        let x0=max(p.map{$0.0}.min()!-pad,coreOnly ? metadata.coreWest ?? metadata.west : -.infinity)
        let y0=max(p.map{$0.1}.min()!-pad,coreOnly ? metadata.coreSouth ?? metadata.south : -.infinity)
        let x1=min(p.map{$0.0}.max()!+pad,coreOnly ? (metadata.coreWest ?? metadata.west)+Double(metadata.coreWidth ?? metadata.width)-0.001 : .infinity)
        let y1=min(p.map{$0.1}.max()!+pad,coreOnly ? (metadata.coreSouth ?? metadata.south)+Double(metadata.coreHeight ?? metadata.height)-0.001 : .infinity)
        guard x1>=x0,y1>=y0 else { return Analysis(peak:nil,complete:true,checked:0,missing:0) }
        return region(x0,y0,x1,y1,p[0].0,p[0].1) { x,y in
            var inside=false
            if polygon {
                for i in p.indices {
                    let a=p[i],b=p[(i+1)%p.count]
                    if (a.1>y) != (b.1>y), x<(b.0-a.0)*(y-a.1)/(b.1-a.1)+a.0 { inside.toggle() }
                }
            }
            if inside { return true }
            for i in 0..<(polygon ? p.count : p.count-1) {
                let a=p[i],b=p[(i+1)%p.count],dx=b.0-a.0,dy=b.1-a.1
                let t = dx*dx+dy*dy == 0 ? 0 : min(1,max(0,((x-a.0)*dx+(y-a.1)*dy)/(dx*dx+dy*dy)))
                if hypot(x-a.0-t*dx,y-a.1-t*dy)<=pad { return true }
            }
            return false
        }
    }
    private func region(_ x0:Double,_ y0:Double,_ x1:Double,_ y1:Double,_ cx:Double,_ cy:Double,include:(Double,Double)->Bool) -> Analysis {
        let m=metadata
        let c0=Int(floor((x0-m.west)/m.spacing)),c1=Int(floor((x1-m.west)/m.spacing))
        let r0=Int(floor((y0-m.south)/m.spacing)),r1=Int(floor((y1-m.south)/m.spacing))
        guard c1>=c0,r1>=r0,Double(c1-c0+1)*Double(r1-r0+1)<=4_000_000 else { return Analysis(peak:nil,complete:false,checked:0,missing:0) }
        var peak:Peak?; var checked=0,missing=0
        for r in r0...r1 { for c in c0...c1 {
            let x=m.west+(Double(c)+0.5)*m.spacing,y=m.south+(Double(r)+0.5)*m.spacing
            guard include(x,y) else { continue }; checked+=1
            guard (0..<m.width).contains(c),(0..<m.height).contains(r),surface[r*m.width+c].isFinite else { missing+=1; continue }
            let z=Double(surface[r*m.width+c])
            if peak == nil || z>peak!.elevation {
                let ll=coordinate(x,y), g=Double(ground[r*m.width+c])
                peak=Peak(elevation:z,latitude:ll.latitude,longitude:ll.longitude,distance:hypot(x-cx,y-cy),ground:g.isFinite ? g : nil)
            }
        }}
        return Analysis(peak:peak,complete:checked>0 && missing==0,checked:checked,missing:missing)
    }
}

public struct OperationalAOLState: Sendable, Equatable {
    public let feet: Double?
    public let status: OperationalMeasurementStatus
    public let reason, details: String
    public init(feet:Double?=nil,reason:String="Surface package not prepared",details:String?=nil,status:OperationalMeasurementStatus?=nil) { self.status=status ?? (feet == nil ? .unknown : .available); self.feet=feet; self.reason=reason; self.details=details ?? reason }
    public var label:String { status.label(feet) }
    public static let explanation="AOL · 200 ft radius: height above the highest mapped surface nearby. Negative is not a collision prediction. Positive does not exclude wires or unmapped obstacles. Wires may be absent; no wire clearance is inferred from AOL. Reference assumes a ground launch at the observed takeoff location; elevated launches are unsupported. Aircraft and survey uncertainty are not bounded by pixel size."
    public static func calculate(package p:OperationalSurfacePackage,position:OperationalSurfacePackage.Point,takeoff:OperationalSurfacePackage.Point,height:Double?,compatibleGround:Double? = nil) -> Self {
        guard let height,height.isFinite else { return Self(reason:"Takeoff-relative altitude unavailable") }
        guard let ground=compatibleGround ?? p.groundAt(takeoff) else { return Self(reason:"Compatible takeoff ground unavailable") }
        let a=p.disk(position)
        guard a.complete else { return Self(reason:"Incomplete surface coverage (\(a.missing) cells)") }
        guard let peak=a.peak else { return Self(reason:"Surface coverage unavailable") }
        let m=p.metadata
        return Self(feet:(ground+height-peak.elevation)/0.3048,reason:"Available",details:"\(explanation)\nMapped high point: \(peak.latitude), \(peak.longitude); \(String(format:"%.0f",peak.distance/0.3048)) ft away; surface \(String(format:"%.0f",peak.elevation/0.3048)) ft; ground \(peak.ground.map{String(format:"%.0f",$0/0.3048)} ?? "—") ft.\n\(p.id); survey \(m.surveyDate); \(m.spacing) m cells; \(m.verticalReference). Full disk covered. Survey age is independent of telemetry age.\n\(m.quality)\n\(p.wireNotes)")
    }
}

public enum OperationalMeasurementStatus: Sendable, Equatable {
    case available, unknown, pending, stale
    public func label(_ value: Double?, suffix: String = "", maxAbs: Double = .infinity, showPendingValue: Bool = false) -> String {
        switch self {
        case .stale: return "POS?"
        case .pending:
            if showPendingValue, let value, value.isFinite, abs(value) <= maxAbs {
                return OperationalMeasurementStatus.available.label(value, suffix: suffix, maxAbs: maxAbs) + "?"
            }
            return "--"
        case .unknown: return "Unk"
        case .available:
            guard let value,value.isFinite,abs(value)<=maxAbs else { return "Unk" }
            let rounded = String(format:"%.0f",value)
            return (rounded == "-0" ? "0" : rounded)+suffix
        }
    }
}
