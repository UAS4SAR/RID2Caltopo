import XCTest
import ImageIO
import CoreGraphics
import zlib
@testable import R2CMapping

final class MappingGeoTiffTests: XCTestCase {
    func testGeoReferencePixelsAndTransparentGap() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:root.appendingPathComponent("14/2600"),withIntermediateDirectories:true)
        defer {try? FileManager.default.removeItem(at:root)}
        // Plan spans three tiles; center is intentionally absent.
        for y in [6300,6302] {try Data().write(to:root.appendingPathComponent("14/2600/\(y).png"))}
        let plan=try MappingGeoTiff.plan(root:root,minZoom:14,maxZoom:14)
        XCTAssertEqual(plan.rows,3);XCTAssertEqual(plan.columns,1)
        let url=root.appendingPathComponent("map.tif")
        try MappingGeoTiff.write(destination:url,plan:plan) { _,y in
            if y==6301 {return nil}
            return Data((0..<65536).flatMap { i in i<256 ? [UInt8(255),0,0,255]:[0,0,255,128] })
        }
        let data=try Data(contentsOf:url)
        if let sample=ProcessInfo.processInfo.environment["R2C_GEOTIFF_SAMPLE"] {try data.write(to:URL(fileURLWithPath:sample))}
        func u16(_ i:Int)->Int {Int(data[i]) | Int(data[i+1])<<8}
        func u32(_ i:Int)->Int {u16(i) | u16(i+2)<<16}
        var tags:[Int:(Int,Int,Int)]=[:]
        for n in 0..<u16(8) {let i=10+n*12;tags[u16(i)]=(u16(i+2),u32(i+4),i+8)}
        func payload(_ id:Int)->Int {let t=tags[id]!;let size=t.0==3 ? 2:t.0==12 ? 8:4;return t.1*size<=4 ? t.2:u32(t.2)}
        func double(_ offset:Int)->Double {data.withUnsafeBytes{Double(bitPattern:UInt64(littleEndian:$0.loadUnaligned(fromByteOffset:offset,as:UInt64.self)))}}
        XCTAssertEqual(u32(payload(256)),256);XCTAssertEqual(u32(payload(257)),768)
        XCTAssertEqual(u16(payload(338)),2)
        XCTAssertEqual(u16(payload(34735)+30),3857)
        XCTAssertEqual(double(payload(33550)),plan.pixelSize,accuracy:1e-10)
        XCTAssertEqual(double(payload(33922)+24),-Double.pi*6378137+2600*256*plan.pixelSize,accuracy:1e-8)
        XCTAssertEqual(double(payload(33922)+32),Double.pi*6378137-6300*256*plan.pixelSize,accuracy:1e-8)
        XCTAssertEqual(u16(payload(259)),8)
        let first=try decodedTile(data,index:0),middle=try decodedTile(data,index:1)
        XCTAssertEqual(Array(first.prefix(4)),[255,0,0,255])
        XCTAssertEqual(Array(first[1024..<1028]),[0,0,255,128])
        XCTAssertEqual(middle,Data(count:262144))
        let source=try XCTUnwrap(CGImageSourceCreateWithURL(url as CFURL,nil))
        // ImageIO recognizes TIFF but cannot decode tiled Deflate on macOS.
        // Independent Pillow/libtiff decoding is part of the compatibility check.
        XCTAssertEqual(CGImageSourceGetType(source) as String?,"public.tiff")
    }
    func testNativeResolutionAndCancellationCleanup() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer {try? FileManager.default.removeItem(at:root)}
        for (z,x,y) in [(15,5200,12600),(15,5400,12800),(14,2600,6300)] {
            let file=root.appendingPathComponent("\(z)/\(x)/\(y).png")
            try FileManager.default.createDirectory(at:file.deletingLastPathComponent(),withIntermediateDirectories:true)
            try Data().write(to:file)
        }
        let plan=try MappingGeoTiff.plan(root:root,minZoom:14,maxZoom:15)
        XCTAssertEqual(plan.zoom,15)
        let url=root.appendingPathComponent("failure.tif")
        XCTAssertThrowsError(try MappingGeoTiff.write(destination:url,plan:plan){_,_ in throw CancellationError()})
        XCTAssertFalse(FileManager.default.fileExists(atPath:url.path))
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath:root.path).contains{$0.contains("partial")})
    }
    func testRealTileExportOrientationAndAlpha() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer {try? FileManager.default.removeItem(at:root)}
        let tile=root.appendingPathComponent("14/2600/6300.png")
        try FileManager.default.createDirectory(at:tile.deletingLastPathComponent(),withIntermediateDirectories:true)
        // ImageIO's top row is red; bottom is blue, including partial transparency.
        var bytes=[UInt8](repeating:0,count:262144)
        for y in 0..<256 { for x in 0..<256 {let i=(y*256+x)*4;bytes[i+(y<128 ? 0:2)]=128;bytes[i+3]=128} }
        let provider=try XCTUnwrap(CGDataProvider(data:Data(bytes) as CFData))
        let image=try XCTUnwrap(CGImage(width:256,height:256,bitsPerComponent:8,bitsPerPixel:32,bytesPerRow:1024,space:CGColorSpace(name:CGColorSpace.sRGB)!,bitmapInfo:CGBitmapInfo(rawValue:CGImageAlphaInfo.premultipliedLast.rawValue),provider:provider,decode:nil,shouldInterpolate:false,intent:.defaultIntent))
        let png=try XCTUnwrap(CGImageDestinationCreateWithURL(tile as CFURL,"public.png" as CFString,1,nil))
        CGImageDestinationAddImage(png,image,nil);XCTAssertTrue(CGImageDestinationFinalize(png))
        let result=try MappingGeoTiff.export(root:root,minZoom:14,maxZoom:14)
        defer {try? FileManager.default.removeItem(at:result.url.deletingLastPathComponent())}
        let data=try decodedTile(Data(contentsOf:result.url),index:0)
        XCTAssertEqual(Array(data.prefix(4)),[255,0,0,128])
        XCTAssertEqual(Array(data.suffix(4)),[0,0,255,128])
    }
    func testFullResolutionCanExceedFortyMB() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer {try? FileManager.default.removeItem(at:root)}
        try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
        var state:UInt32=42
        let pixels=Data((0..<262144).map{_ -> UInt8 in state ^= state<<13;state ^= state>>17;state ^= state<<5;return UInt8(truncatingIfNeeded:state)})
        let plan=MappingGeoTiff.Plan(zoom:23,west:2600,north:6300,columns:153,rows:1)
        let url=root.appendingPathComponent("full.tif")
        try MappingGeoTiff.write(destination:url,plan:plan){_,_ in pixels}
        XCTAssertGreaterThan(try url.resourceValues(forKeys:[.fileSizeKey]).fileSize!,40_000_000)
        func tile(_ x:Int,_ y:Int) throws {
            let url=root.appendingPathComponent("23/\(x)/\(y).png")
            try FileManager.default.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
            try Data().write(to:url)
        }
        try tile(2600,6300);try tile(2752,6300)
        XCTAssertEqual(try MappingGeoTiff.plan(root:root,minZoom:14,maxZoom:23).count,153)
        let lower=root.appendingPathComponent("22/1300/3150.png")
        try FileManager.default.createDirectory(at:lower.deletingLastPathComponent(),withIntermediateDirectories:true)
        try Data().write(to:lower)
        let limitedURL=root.appendingPathComponent("limited.tif")
        let limited=try MappingGeoTiff.writeBest(root:root,destination:limitedURL,minZoom:14,maxZoom:23,maxBytes:MappingGeoTiff.caltopoMaxBytes){plan,_,_ in
            plan.zoom==23 ? pixels:Data(count:262144)
        }
        XCTAssertEqual(limited.fullZoom,23);XCTAssertEqual(limited.plan.zoom,22)
        XCTAssertLessThanOrEqual(try limitedURL.resourceValues(forKeys:[.fileSizeKey]).fileSize!,40_000_000)
        try tile(0,0);try tile((1<<23)-1,(1<<23)-1)
        XCTAssertThrowsError(try MappingGeoTiff.plan(root:root,minZoom:14,maxZoom:23))
    }
    func testOptionalLimitUsesCompressedSizeAndPreservesPreviousExportOnFailure() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer {try? FileManager.default.removeItem(at:root)}
        for z in [16,14] {
            let url=root.appendingPathComponent("\(z)/2600/6300.png")
            try FileManager.default.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
            try Data().write(to:url)
        }
        let destination=root.appendingPathComponent("map.tif"),empty=Data(count:262144)
        var state:UInt32=42
        let noisy=Data((0..<262144).map{_ -> UInt8 in state ^= state<<13;state ^= state>>17;state ^= state<<5;return UInt8(truncatingIfNeeded:state)})
        let full=try MappingGeoTiff.writeBest(root:root,destination:destination,minZoom:14,maxZoom:16,maxBytes:1500){_,_,_ in empty}
        XCTAssertEqual(full.plan.zoom,16)
        // Separate destinations match the Apple sharing lifecycle (each export has a unique folder).
        let limited=try MappingGeoTiff.writeBest(root:root,destination:root.appendingPathComponent("limited.tif"),minZoom:14,maxZoom:16,maxBytes:1500){plan,_,_ in plan.zoom==16 ? noisy:empty}
        XCTAssertEqual(limited.fullZoom,16);XCTAssertEqual(limited.plan.zoom,14)
        let unlimitedURL=root.appendingPathComponent("unlimited.tif")
        let unlimited=try MappingGeoTiff.writeBest(root:root,destination:unlimitedURL,minZoom:14,maxZoom:16){_,_,_ in noisy}
        XCTAssertEqual(unlimited.plan.zoom,16)
        XCTAssertGreaterThan(try unlimitedURL.resourceValues(forKeys:[.fileSizeKey]).fileSize!,1500)
        let previous=try Data(contentsOf:destination)
        XCTAssertThrowsError(try MappingGeoTiff.writeBest(root:root,destination:destination,minZoom:14,maxZoom:16,maxBytes:500){_,_,_ in empty})
        XCTAssertEqual(try Data(contentsOf:destination),previous)
        var reads=0
        XCTAssertThrowsError(try MappingGeoTiff.writeBest(root:root,destination:root.appendingPathComponent("bad.tif"),minZoom:14,maxZoom:16,maxBytes:1500){_,_,_ in reads+=1;throw CancellationError()}) {error in
            XCTAssertTrue(error is CancellationError)
        }
        XCTAssertEqual(reads,1)
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath:root.path).contains{$0.contains("partial")})
    }
    private func decodedTile(_ data:Data,index:Int) throws -> Data {
        func u16(_ p:Int)->Int {Int(data[p]) | Int(data[p+1])<<8}
        func u32(_ p:Int)->Int {u16(p) | u16(p+2)<<16}
        var tags:[Int:(Int,Int)]=[:]
        for n in 0..<u16(8) {let p=10+n*12;tags[u16(p)]=(u32(p+4),p+8)}
        func payload(_ tag:Int)->Int {let(count,p)=tags[tag]!;return count==1 ? p:u32(p)}
        let start=u32(payload(324)+index*4),length=u32(payload(325)+index*4)
        var output=Data(count:262144),size=uLongf(262144)
        let status=output.withUnsafeMutableBytes{destination in data.withUnsafeBytes{source in
            uncompress(destination.bindMemory(to:UInt8.self).baseAddress!,&size,source.bindMemory(to:UInt8.self).baseAddress!.advanced(by:start),uLong(length))
        }}
        XCTAssertEqual(status,Z_OK);XCTAssertEqual(size,262144);return output
    }
}
