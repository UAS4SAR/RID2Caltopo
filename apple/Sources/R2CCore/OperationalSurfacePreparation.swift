import Foundation
import CryptoKit
import CAOL

public struct OperationalSurfaceSource: Sendable {
    public let url, metadataURL, published, survey: String
    public let bytes: Int64
}
public struct OperationalSurfacePreparationPlan: Sendable {
    public let bounds: OperationalMapBounds
    public let latitude, longitude: Double
    public let width, height: Int
    public var reused: Bool = false
    public var sources: [OperationalSurfaceSource]
    public var advertisedBytes: Int64 { sources.reduce(0) { $0 + $1.bytes } }
    public var tiles: Int { ((width+999)/1000)*((height+999)/1000) }
}
public enum OperationalSurfacePreparationError: Error, LocalizedError {
    case invalid(String)
    public var errorDescription: String? { if case .invalid(let text) = self { return text }; return nil }
}
public enum OperationalSurfacePreparation {
    public static func grid(_ bounds: OperationalMapBounds) throws -> OperationalSurfacePreparationPlan {
        let lat=(bounds.south+bounds.north)/2,lon=(bounds.west+bounds.east)/2
        guard [bounds.south,bounds.north,bounds.west,bounds.east].allSatisfy(\.isFinite),bounds.south<=bounds.north,bounds.west<=bounds.east,abs(bounds.south)<70,abs(bounds.north)<70,abs(bounds.west)<=180,abs(bounds.east)<=180 else { throw OperationalSurfacePreparationError.invalid("Invalid AOL region") }
        let w=ceil((bounds.east-bounds.west)*Double.pi/180*6371008.8*cos(lat*Double.pi/180)+124)
        let h=ceil((bounds.north-bounds.south)*Double.pi/180*6371008.8+124)
        guard w<=4000,h<=4000 else { throw OperationalSurfacePreparationError.invalid("AOL preparation supports regions up to about 4 km across; select a smaller map or assignment") }
        return .init(bounds:bounds,latitude:lat,longitude:lon,width:Int(w),height:Int(h),sources:[])
    }
    public static func sources(pages: [Data]) throws -> [OperationalSurfaceSource] {
        var found: [String:OperationalSurfaceSource]=[:]
        for page in pages {
            let object=try JSONSerialization.jsonObject(with:page) as? [String:Any]
            for item in object?["items"] as? [[String:Any]] ?? [] {
                guard let string=item["downloadURL"] as? String,let url=URL(string:string),url.scheme=="https",
                      ["rockyweb.usgs.gov","prd-tnm.s3.amazonaws.com"].contains(url.host ?? ""),url.path.lowercased().hasSuffix(".laz"),
                      url.path.contains("/Projects/"),let end=url.path.range(of:"/LAZ/"),let bytes=(item["sizeInBytes"] as? NSNumber)?.int64Value,bytes>0,bytes<=1_000_000_000 else { continue }
                found[string] = .init(url:string,metadataURL:item["metaUrl"] as? String ?? string,published:item["publicationDate"] as? String ?? "Unknown",survey:String(url.path[..<end.lowerBound]),bytes:bytes)
            }
        }
        let groups=Dictionary(grouping:Array(found.values),by: \.survey)
        guard let chosen=groups.values.max(by: { ($0.map(\.published).max() ?? "") + "|" + ($0.first?.survey ?? "") < ($1.map(\.published).max() ?? "") + "|" + ($1.first?.survey ?? "") }),!chosen.isEmpty else { throw OperationalSurfacePreparationError.invalid("No supported USGS lidar source files found for this area") }
        guard chosen.count<=64 else { throw OperationalSurfacePreparationError.invalid("Too many lidar files; select a smaller region") }
        return chosen.sorted { $0.url < $1.url }
    }
    /// Only called by explicit offline preparation, off the UI actor. Never from telemetry.
    public static func assemble(plan: OperationalSurfacePreparationPlan, files: [URL], hashes: [String:String], directory: URL, progress: @Sendable (String) async -> Void) async throws -> String {
        guard files.count==plan.sources.count,!files.isEmpty else { throw OperationalSurfacePreparationError.invalid("Incomplete lidar source set") }
        let reference=hex(SHA256.hash(data:try JSONSerialization.data(withJSONObject:hashes,options:.sortedKeys)))
        let regionID=directory.lastPathComponent
        var entries: [[String:Any]]=[];var tile=0;var missing:UInt64=0;var referenceCRS:String?
        for row in stride(from:0,to:plan.height,by:1000) { for col in stride(from:0,to:plan.width,by:1000) {
            try Task.checkCancellation();tile+=1
            let cw=min(1000,plan.width-col),ch=min(1000,plan.height-row)
            let coreWest = -Double(plan.width)/2+Double(col),coreSouth = -Double(plan.height)/2+Double(row)
            let left=min(62,col),bottom=min(62,row)
            let west=coreWest-Double(left),south=coreSouth-Double(bottom),w=cw+left+min(62,plan.width-col-cw),h=ch+bottom+min(62,plan.height-row-ch)
            guard let grid=aol_grid_create(plan.latitude,plan.longitude,west,south,Int32(w),Int32(h)) else { throw OperationalSurfacePreparationError.invalid("Unable to allocate AOL tile") }
            defer { aol_grid_free(grid) }
            func checked(_ status:Int32) throws { if status<0 { throw OperationalSurfacePreparationError.invalid(String(cString:aol_grid_error(grid))) } }
            for (index,file) in files.enumerated() {
                try checked(aol_grid_open(grid,file.path))
                let crs=String(cString:aol_grid_crs(grid))
                guard referenceCRS==nil || referenceCRS==crs else { throw OperationalSurfacePreparationError.invalid("Survey reference mismatch") };referenceCRS=crs
                var status:Int32=1;var batches=0
                repeat {
                    try Task.checkCancellation();status=aol_grid_step(grid,10000);try checked(status);batches+=1
                    if batches%20==0 { await progress("Assembling AOL tile \(tile)/\(plan.tiles); source \(index+1)/\(files.count): \(aol_grid_read(grid)*100/max(1,aol_grid_count(grid)))%") }
                } while status>0
            }
            let sf=directory.appendingPathComponent("surface.f32"),gf=directory.appendingPathComponent("ground.f32")
            defer { try? FileManager.default.removeItem(at:sf);try? FileManager.default.removeItem(at:gf) }
            try checked(aol_grid_write(grid,sf.path,gf.path));missing+=aol_grid_missing(grid)
            let surface=try Data(contentsOf:sf),ground=try Data(contentsOf:gf)
            let metadata: [String:Any] = [
                "schema":1,"layer":"top-surface","id":"\(regionID)-\(row)-\(col)","version":reference,
                "processingVersion":"r2c-native-lidar-max-1","sourceURL":plan.sources[0].metadataURL,
                "surveyDate":"Acquisition date not supplied by tile catalog; publication \(plan.sources.map(\.published).max() ?? "Unknown")",
                "verticalReference":referenceCRS!.uppercased().contains("GEOID18") ? "NAVD88 / GEOID18 / metres" : referenceCRS!.uppercased().contains("GEOID12B") ? "NAVD88 / GEOID12B / metres" : "NAVD88 / same-survey paired reference / metres",
                "horizontalCRS":"R2C_LOCAL_EQUIRECTANGULAR_WGS84","units":"metres",
                "quality":"Accepted LAS maxima; withheld and noise classes 7/18 excluded. Surface holes remain unknown. Ground uses nearest class 2 within 3 m. Same source reference for surface and ground; geoid realization is recorded in the source CRS when supplied. No wire completeness claim.",
                "originLatitude":plan.latitude,"originLongitude":plan.longitude,"west":west,"south":south,"spacing":1,"width":w,"height":h,
                "coreWest":coreWest,"coreSouth":coreSouth,"coreWidth":cw,"coreHeight":ch,"referenceGroup":reference,"sourceCRS":referenceCRS!,"sourceSHA256":hashes,
                "surfaceSHA256":hex(SHA256.hash(data:surface)),"groundSHA256":hex(SHA256.hash(data:ground))]
            let manifest=try JSONSerialization.data(withJSONObject:metadata,options:.sortedKeys)
            let bytes=try OperationalZipArchive.encode([.init(path:"manifest.json",data:manifest),.init(path:"surface.f32",data:surface),.init(path:"ground.f32",data:ground)],compress:true)
            _ = try OperationalSurfacePackage(data:bytes)
            let name="tile-\(row)-\(col).aol";try bytes.write(to:directory.appendingPathComponent(name),options:.atomic)
            entries.append(["file":name,"metadata":metadata])
        } }
        let index: [String:Any] = ["schema":1,"referenceGroup":reference,"originLatitude":plan.latitude,"originLongitude":plan.longitude,"width":plan.width,"height":plan.height,"entries":entries]
        try JSONSerialization.data(withJSONObject:index,options:.sortedKeys).write(to:directory.appendingPathComponent("index.json"),options:.atomic)
        return "Prepared \(plan.tiles) AOL tiles; \(missing) missing surface cells including overlapping margins. AOL stays unavailable wherever its disk has gaps."
    }
    private static func hex<S: Sequence>(_ data:S) -> String where S.Element == UInt8 { data.map { String(format:"%02x",$0) }.joined() }
}

/// Catalog requests share the terrain retry policy, but retain actionable errors.
public enum OperationalSurfaceCatalog {
    public static func data(
        for request: URLRequest,
        transport: @Sendable (URLRequest) async throws -> (Data, URLResponse),
        sleep: @Sendable (TimeInterval) async throws -> Void = { seconds in
            try await Task.sleep(for: .seconds(seconds))
        }
    ) async throws -> Data {
        var request = request
        request.timeoutInterval = 25
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("RID2Caltopo/Apple (contact: kjt@uas4sar.com)", forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        var attempt = 0
        while true {
            try Task.checkCancellation()
            do {
                let (data, response) = try await transport(request)
                guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
                if (200..<300).contains(http.statusCode) {
                    guard data.count <= 4_000_000 else {
                        throw OperationalSurfacePreparationError.invalid("USGS lidar catalog response is too large; select a smaller region")
                    }
                    return data
                }
                if OperationalCacheRetryPolicy.shouldRetry(statusCode: http.statusCode),
                   let delay = OperationalCacheRetryPolicy.delaySeconds(attempt: attempt, retryAfterHeader: http.value(forHTTPHeaderField: "Retry-After")) {
                    attempt += 1
                    try await sleep(delay)
                    continue
                }
                throw OperationalSurfacePreparationError.invalid("USGS lidar catalog returned HTTP \(http.statusCode). Try the catalog again; if it persists, try another network or a smaller region.")
            } catch let error as URLError {
                if [.timedOut, .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed, .networkConnectionLost, .resourceUnavailable].contains(error.code),
                   let delay = OperationalCacheRetryPolicy.delaySeconds(attempt: attempt) {
                    attempt += 1
                    try await sleep(delay)
                    continue
                }
                if error.code == .cancelled { throw CancellationError() }
                throw OperationalSurfacePreparationError.invalid("USGS lidar catalog: \(error.localizedDescription) Check the connection and try the catalog again.")
            }
        }
    }
}

/// MA imports and cache hits must contain every core, with a consistent survey and geometry.
public enum OperationalPreparedSurfaceSet {
    public static func contains(width:Int,height:Int,west:Double,south:Double,east:Double,north:Double) -> Bool {
        west-62 >= -Double(width)/2-0.001 && east+62 <= Double(width)/2+0.001 && south-62 >= -Double(height)/2-0.001 && north+62 <= Double(height)/2+0.001
    }
    public static func fresh(prepared: Int64, now: Int64, maxAge: Int64) -> Bool {
        prepared>0 && prepared<=now && now-prepared<maxAge
    }
    public static func validate(_ data: Data, read: (String) throws -> Data) throws {
        struct Index: Decodable {
            struct Entry: Decodable { let file: String; let metadata: OperationalSurfacePackage.Metadata }
            let width,height:Int
            let originLatitude,originLongitude:Double
            let referenceGroup:String
            let entries:[Entry]
        }
        let index=try JSONDecoder().decode(Index.self,from:data)
        guard (1...4000).contains(index.width),(1...4000).contains(index.height) else { throw OperationalSurfacePreparationError.invalid("Invalid AOL grid") }
        var expected=Set<String>()
        for y in stride(from:0,to:index.height,by:1000) { for x in stride(from:0,to:index.width,by:1000) { expected.insert("tile-\(y)-\(x).aol") } }
        guard expected.count==index.entries.count else { throw OperationalSurfacePreparationError.invalid("Incomplete AOL grid") }
        for entry in index.entries {
            guard expected.remove(entry.file) != nil else { throw OperationalSurfacePreparationError.invalid("Unexpected or duplicate AOL tile") }
            let m=try OperationalSurfacePackage(data:read(entry.file)).metadata
            let parts=entry.file.replacingOccurrences(of:".aol",with:"").split(separator:"-")
            let y=Int(parts[1])!,x=Int(parts[2])!
            let encoder=JSONEncoder();encoder.outputFormatting=[.sortedKeys]
            guard try encoder.encode(m)==encoder.encode(entry.metadata),m.originLatitude==index.originLatitude,m.originLongitude==index.originLongitude,
                  m.referenceGroup==index.referenceGroup,m.spacing==1,m.coreWest == -Double(index.width)/2+Double(x),m.coreSouth == -Double(index.height)/2+Double(y),
                  m.coreWidth==min(1000,index.width-x),m.coreHeight==min(1000,index.height-y) else { throw OperationalSurfacePreparationError.invalid("AOL metadata mismatch") }
        }
    }
}
