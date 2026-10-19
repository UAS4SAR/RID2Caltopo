import Foundation
import CoreGraphics
import ImageIO
import zlib
import R2CCore

/// Worker-only streaming RGBA GeoTIFF in EPSG:3857. Sources are never modified.
public enum MappingGeoTiff {
    public static let caltopoMaxBytes: Int64 = 40_000_000
    public enum ExportFailure: LocalizedError {
        case noTiles, sizeLimit
        public var errorDescription: String? {
            switch self {
            case .noTiles: return "No saved map tiles are available."
            case .sizeLimit: return "Compressed GeoTIFF exceeds the selected size limit."
            }
        }
    }
    public struct Selection: Sendable {public let plan: Plan; public let fullZoom: Int}
    public struct Plan: Sendable {
        public let zoom, west, north, columns, rows: Int
        public var count: Int { columns * rows }
        public var pixelSize: Double { 2 * .pi * 6378137 / (256 * pow(2, Double(zoom))) }
    }
    public struct Result: Sendable { public let url: URL; public let zoom: Int; public let byteCount: Int64; public let reducedDetail: Bool }
    public static func plan(root: URL, minZoom: Int, maxZoom: Int) throws -> Plan {
        guard (0...23).contains(minZoom), (minZoom...23).contains(maxZoom) else { throw MappingMission.Failure("Invalid map zoom range.") }
        let fm = FileManager.default
        for z in stride(from: maxZoom, through: minZoom, by: -1) {
            try Task.checkCancellation()
            var west=Int.max, east = -1, north=Int.max, south = -1
            for column in (try? fm.contentsOfDirectory(at: root.appendingPathComponent("\(z)"), includingPropertiesForKeys: nil)) ?? [] {
                try Task.checkCancellation()
                guard let x=Int(column.lastPathComponent), (0..<(1<<z)).contains(x) else { continue }
                for tile in (try? fm.contentsOfDirectory(at: column, includingPropertiesForKeys: nil)) ?? [] {
                    try Task.checkCancellation()
                    guard tile.pathExtension == "png", let y=Int(tile.deletingPathExtension().lastPathComponent), (0..<(1<<z)).contains(y) else { continue }
                    west=min(west,x);east=max(east,x);north=min(north,y);south=max(south,y)
                }
            }
            if east >= 0 {
                guard Int64(east-west+1)*Int64(south-north+1) <= (Int64(UInt32.max)-4096)/8 else {throw MappingMission.Failure("Map extent exceeds classic TIFF’s tile index limit.")}
                return Plan(zoom:z,west:west,north:north,columns:east-west+1,rows:south-north+1)
            }
        }
        throw ExportFailure.noTiles
    }
    public static func export(root: URL, minZoom: Int, maxZoom: Int, assignmentName: String = "Map", limitForCalTopo: Bool = false) throws -> Result {
        let shares=FileManager.default.temporaryDirectory.appendingPathComponent("RID2Caltopo/GeoTIFF")
        try FileManager.default.createDirectory(at:shares,withIntermediateDirectories:true)
        for old in try FileManager.default.contentsOfDirectory(at:shares,includingPropertiesForKeys:[.contentModificationDateKey]) {
            try Task.checkCancellation()
            if let date=try? old.resourceValues(forKeys:[.contentModificationDateKey]).contentModificationDate, date.timeIntervalSinceNow < -86400 {try? FileManager.default.removeItem(at:old)}
        }
        let directory=shares.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        let url=directory.appendingPathComponent(MappingResultFiles.geoTiffName(assignment:assignmentName))
        do {
            let selection=try writeBest(root:root,destination:url,minZoom:minZoom,maxZoom:maxZoom,
                maxBytes:limitForCalTopo ? caltopoMaxBytes:nil) { plan,x,y in
                let tile=root.appendingPathComponent("\(plan.zoom)/\(x)/\(y).png")
                guard FileManager.default.fileExists(atPath:tile.path) else { return nil }
                return try pixels(tile)
            }
            return Result(url:url,zoom:selection.plan.zoom,byteCount:Int64(try url.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0),reducedDetail:selection.plan.zoom<selection.fullZoom)
        } catch { try? FileManager.default.removeItem(at:directory);throw error }
    }
    /// Tries actual compressed output before reducing the saved zoom level.
    public static func writeBest(root: URL, destination: URL, minZoom: Int, maxZoom: Int,
                                 maxBytes: Int64? = nil, read: (Plan,Int,Int)throws->Data?) throws -> Selection {
        var ceiling=maxZoom,fullZoom:Int?
        while ceiling>=minZoom {
            try Task.checkCancellation()
            let candidate:Plan
            do {candidate=try plan(root:root,minZoom:minZoom,maxZoom:ceiling)}
            catch ExportFailure.noTiles {if fullZoom==nil {throw ExportFailure.noTiles};break}
            if fullZoom==nil {fullZoom=candidate.zoom}
            do {
                try write(destination:destination,plan:candidate,maxBytes:maxBytes){x,y in try read(candidate,x,y)}
                return Selection(plan:candidate,fullZoom:fullZoom!)
            } catch ExportFailure.sizeLimit {ceiling=candidate.zoom-1}
        }
        throw MappingMission.Failure("No saved resolution fits the 40 MB CalTopo limit. Export with the limit unchecked.")
    }
    /// Reader provides straight RGBA in top-to-bottom order, or nil for a missing tile.
    public static func write(destination: URL, plan: Plan, maxBytes: Int64? = nil, read: (Int,Int)throws->Data?) throws {
        guard plan.columns>0, plan.rows>0, plan.count <= (Int(UInt32.max)-4096)/8 else { throw MappingMission.Failure("Map extent exceeds classic TIFF’s tile index limit.") }
        var tags:[Int:(Int,Data)]=[:]
        func shorts(_ v:[Int])->Data { var d=Data();for x in v { d.le(UInt16(x)) };return d }
        func longs(_ v:[Int])->Data { var d=Data();for x in v { d.le(UInt32(x)) };return d }
        func doubles(_ v:[Double])->Data { var d=Data();for x in v { d.le(x.bitPattern) };return d }
        tags[256]=(4,longs([plan.columns*256]));tags[257]=(4,longs([plan.rows*256]));tags[258]=(3,shorts([8,8,8,8]))
        tags[259]=(3,shorts([8]));tags[262]=(3,shorts([2]));tags[274]=(3,shorts([1]));tags[277]=(3,shorts([4]));tags[284]=(3,shorts([1]))
        tags[322]=(4,longs([256]));tags[323]=(4,longs([256]));tags[324]=(4,longs([0]))
        tags[325]=(4,longs([0]));tags[338]=(3,shorts([2]))
        tags[33550]=(12,doubles([plan.pixelSize,plan.pixelSize,0]))
        let half=Double.pi*6378137
        tags[33922]=(12,doubles([0,0,0,-half+Double(plan.west*256)*plan.pixelSize,half-Double(plan.north*256)*plan.pixelSize,0]))
        tags[34735]=(3,shorts([1,1,0,3,1024,0,1,1,1025,0,1,1,3072,0,1,3857]))
        let base=8+2+tags.count*12+4
        let fixedEnd=base+tags.values.reduce(0){$0+($1.1.count>4 ? $1.1.count:0)}
        let dataStart=fixedEnd+(plan.count>1 ? plan.count*8:0)
        if let maxBytes, dataStart>maxBytes {throw ExportFailure.sizeLimit}
        var header=Data([73,73]);header.le(UInt16(42));header.le(UInt32(8));header.le(UInt16(tags.count))
        var extra=Data(),offsets=0,counts=0
        for id in tags.keys.sorted() {
            let (type,data)=tags[id]!;header.le(UInt16(id));header.le(UInt16(type))
            if id==324 || id==325 {
                header.le(UInt32(plan.count))
                let location=plan.count==1 ? header.count:fixedEnd+(id==325 ? plan.count*4:0)
                if id==324 {offsets=location} else {counts=location}
                header.le(UInt32(plan.count==1 ? 0:location))
            } else {
                header.le(UInt32(data.count/(type==3 ? 2:type==12 ? 8:4)))
                if data.count<=4 {header.append(data);header.append(Data(count:4-data.count))}
                else {header.le(UInt32(base+extra.count));extra.append(data)}
            }
        }
        header.le(UInt32(0));header.append(extra)
        let partial=destination.appendingPathExtension("\(UUID().uuidString).partial"),fm=FileManager.default
        defer {try? fm.removeItem(at:partial)}
        guard fm.createFile(atPath:partial.path,contents:nil) else {throw MappingMission.Failure("Cannot create GeoTIFF.")}
        let output=try FileHandle(forWritingTo:partial)
        do {
            try output.write(contentsOf:header);try output.truncate(atOffset:UInt64(dataStart));try output.seek(toOffset:UInt64(dataStart))
            let empty=Data(count:262144);var index=0
            for y in 0..<plan.rows { for x in 0..<plan.columns {
                try Task.checkCancellation()
                let data=try read(plan.west+x,plan.north+y) ?? empty
                guard data.count==262144 else {throw MappingMission.Failure("Invalid map tile pixels.")}
                let compressed=try deflate(data)
                if try output.offset()%2 != 0 {try output.write(contentsOf:Data([0]))}
                let position=try output.offset()
                if let maxBytes, position+UInt64(compressed.count)>UInt64(maxBytes) {throw ExportFailure.sizeLimit}
                guard position+UInt64(compressed.count)<=UInt64(UInt32.max) else {throw MappingMission.Failure("Compressed map exceeds classic TIFF’s 4 GB limit. Export a smaller region.")}
                try output.write(contentsOf:compressed);let end=try output.offset()
                var entry=Data();entry.le(UInt32(position));try output.seek(toOffset:UInt64(offsets+index*4));try output.write(contentsOf:entry)
                entry=Data();entry.le(UInt32(compressed.count));try output.seek(toOffset:UInt64(counts+index*4));try output.write(contentsOf:entry)
                try output.seek(toOffset:end);index+=1
            } }
            try output.synchronize();try output.close();try Task.checkCancellation()
            try fm.moveItem(at:partial,to:destination)
        } catch { try? output.close();throw error }
    }
    private static func deflate(_ input:Data) throws -> Data {
        var length=compressBound(uLong(input.count)),output=Data(count:Int(compressBound(uLong(input.count))))
        let status=output.withUnsafeMutableBytes { destination in input.withUnsafeBytes { source in
            compress2(destination.bindMemory(to:UInt8.self).baseAddress!,&length,source.bindMemory(to:UInt8.self).baseAddress!,uLong(input.count),Z_DEFAULT_COMPRESSION)
        } }
        guard status==Z_OK else {throw MappingMission.Failure("Cannot compress map tile.")}
        output.count=Int(length);return output
    }
    private static func pixels(_ url: URL) throws -> Data {
        guard let source=CGImageSourceCreateWithURL(url as CFURL,nil),
              let properties=CGImageSourceCopyPropertiesAtIndex(source,0,nil) as? [CFString:Any],
              properties[kCGImagePropertyPixelWidth] as? Int == 256, properties[kCGImagePropertyPixelHeight] as? Int == 256,
              let image=CGImageSourceCreateImageAtIndex(source,0,nil) else {throw MappingMission.Failure("Unreadable map tile.")}
        var data=Data(count:262144)
        try data.withUnsafeMutableBytes { (buffer:UnsafeMutableRawBufferPointer) in
            guard let context=CGContext(data:buffer.baseAddress,width:256,height:256,bitsPerComponent:8,bytesPerRow:1024,space:CGColorSpace(name:CGColorSpace.sRGB)!,bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else {throw MappingMission.Failure("Cannot decode map tile.")}
            context.draw(image,in:CGRect(x:0,y:0,width:256,height:256))
            // TIFF ExtraSamples=2 stores straight alpha; CoreGraphics produces premultiplied RGB.
            let p=buffer.bindMemory(to:UInt8.self)
            for i in stride(from:0,to:p.count,by:4) {let a=Int(p[i+3]);if a>0 && a<255 {for c in 0..<3 {p[i+c]=UInt8(min(255,(Int(p[i+c])*255+a/2)/a))}}}
        }
        return data
    }
}
private extension Data {
    mutating func le<T:FixedWidthInteger>(_ value:T) {var v=value.littleEndian;Swift.withUnsafeBytes(of:&v){append(contentsOf:$0)}}
}
