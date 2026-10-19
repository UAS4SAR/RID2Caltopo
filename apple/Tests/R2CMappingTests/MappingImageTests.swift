import XCTest
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
@testable import R2CMapping
import R2CCore

final class MappingImageTests:XCTestCase {
    func testRasterKeepsImageCoordinatesAndRGBAChannels() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true);defer{try? FileManager.default.removeItem(at:root)}
        let source=root.appendingPathComponent("quadrants.png"),output=root.appendingPathComponent("pixels.rgba")
        let width=40,height=30;var bytes=[UInt8](repeating:0,count:width*height*4)
        for y in 0..<height{for x in 0..<width{let i=(y*width+x)*4;bytes[i]=y<height/2 ? 255:0;bytes[i+2]=y<height/2 ? 0:255;bytes[i+3]=255}}
        let provider=try XCTUnwrap(CGDataProvider(data:Data(bytes) as CFData))
        let image=try XCTUnwrap(CGImage(width:width,height:height,bitsPerComponent:8,bitsPerPixel:32,bytesPerRow:width*4,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGBitmapInfo(rawValue:CGImageAlphaInfo.premultipliedLast.rawValue),provider:provider,decode:nil,shouldInterpolate:false,intent:.defaultIntent))
        let destination=try XCTUnwrap(CGImageDestinationCreateWithURL(source as CFURL,UTType.png.identifier as CFString,1,nil));CGImageDestinationAddImage(destination,image,nil);XCTAssertTrue(CGImageDestinationFinalize(destination))
        _ = try MappingOriginal.raster(source,output:output,sample:false)
        let actual=[UInt8](try Data(contentsOf:output));XCTAssertEqual(Array(actual.prefix(4)),[255,0,0,255]);XCTAssertEqual(Array(actual.suffix(4)),[0,0,255,255])
    }
    func testThreeRegionMergeUsesActualPixelOwner() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer{try? FileManager.default.removeItem(at:root)}
        let target=root.appendingPathComponent("result/23/4194304/4194303.png")
        // Region 2 is nearest but has no data. Region 3 must replace region 1.
        for (i,center,color) in [(0,100.0,UInt32(0xffff0000)),(1,0.0,UInt32(0)),(2,10.0,UInt32(0xff0000ff))] {
            let source=root.appendingPathComponent("region-\(i)/23/4194304/4194303.png")
            try MappingSurfaceProducts.png([UInt32](repeating:color,count:65536),source)
            let value:[String:Any] = ["core":[],"images":[],"bounds":["west":center-1,"east":center+1,"south":-1,"north":1]]
            let region=try JSONDecoder().decode(MappingProcessingPolicy.Region.self,from:JSONSerialization.data(withJSONObject:value))
            try MappingReconstruction.merge(source:source,destination:target,region:region)
        }
        let weights=try Data(contentsOf:target.appendingPathExtension("weights"))
        let winner=weights.withUnsafeBytes{Double(bitPattern:UInt64(littleEndian:$0.loadUnaligned(as:UInt64.self)))}
        XCTAssertLessThan(winner,200) // Region 3, not the red region 100 m away.
    }
    func testLibraryListsAllIncidentsAndPreservesOwnership() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer{try? FileManager.default.removeItem(at:root)}
        let store=try MappingMissionStore(root:root)
        for incident in ["FIRST","SECOND"] {
            let plan=try JSONSerialization.data(withJSONObject:["missionId":UUID().uuidString.lowercased(),"incidentId":incident,"title":incident])
            _ = try store.savePlan(plan,boundary:Data())
        }
        XCTAssertEqual(Set(try store.list(newestFirst:true).map(\.incidentID)),Set(["FIRST","SECOND"]))
        XCTAssertEqual(try store.list(incidentID:"FIRST",newestFirst:true).count,1)
        XCTAssertTrue(try store.list(incidentID:"",newestFirst:true).isEmpty)
    }
    func testPlanAndOriginalDeletionNeedExplicitConfirmation() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);defer{try? FileManager.default.removeItem(at:root)}
        let store=try MappingMissionStore(root:root),id=UUID().uuidString.lowercased()
        let plan=try JSONSerialization.data(withJSONObject:["missionId":id,"incidentId":"MAP","title":"Test"])
        let folder=try store.savePlan(plan,boundary:Data());let originals=folder.appendingPathComponent("originals")
        try FileManager.default.createDirectory(at:originals,withIntermediateDirectories:true);try Data([1,2,3]).write(to:originals.appendingPathComponent("retained.jpg"))
        try store.deleteResults(id);XCTAssertTrue(FileManager.default.fileExists(atPath:originals.path))
        XCTAssertThrowsError(try store.deleteMission(id,originalsConfirmed:false));XCTAssertTrue(FileManager.default.fileExists(atPath:folder.path))
        try store.deleteMission(id,originalsConfirmed:true);XCTAssertFalse(FileManager.default.fileExists(atPath:folder.path))
    }
}

extension MappingImageTests {
    func testTelephotoSurfaceProductsUseActualRasterSizeAndArchiveIsReadable() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString),rasters=root.appendingPathComponent("rasters"),output=root.appendingPathComponent("result")
        try FileManager.default.createDirectory(at:rasters,withIntermediateDirectories:true);defer{MappingNativeEngine.releaseRasters();try? FileManager.default.removeItem(at:root)}
        let original=rasters.appendingPathComponent("a.rgba");FileManager.default.createFile(atPath:original.path,contents:nil)
        let handle=try FileHandle(forWritingTo:original);let block=Data(repeating:255,count:4096*4);for _ in 0..<3072{try handle.write(contentsOf:block)};try handle.close()
        try FileManager.default.linkItem(at:original,to:rasters.appendingPathComponent("b.rgba"));try FileManager.default.linkItem(at:original,to:rasters.appendingPathComponent("c.rgba"))
        var adjustment:[Double]=[3,0,700,511.5,383.5,0,0,0,0,0,0,0]
        for x in [-8.0,0,8]{adjustment += [1,0,0,0,-1,0,0,0,-1,x,0,60]};adjustment.append(0)
        let g=MappingGeometryResult(originalWidth:4096,originalHeight:3072,adjustment:adjustment,heights:[Float](repeating:0,count:64*64),width:64,height:64,x:0,y:0,cell:0.2,originX:0,originY:0,originAltitude:0,groundScale:1,sampleWidth:1024,sampleHeight:768,selectedPhotos:3,registeredPhotos:3,featureTracks:0,matchedPairs:0,stereoPairs:0,measuredCells:4096,digests:["a","b","c"])
        let plist=PropertyListEncoder();plist.outputFormat = .binary
        XCTAssertEqual(try PropertyListDecoder().decode(MappingGeometryResult.self,from:plist.encode(g)).heights,g.heights)
        try MappingSurfaceProducts.export(g,rasters:rasters,output:output,bounds:.init(west:0,south:0,east:13,north:13),polygon:[[0,0],[13,0],[13,13],[0,13]],zoom:21){_,_ in}
        let glb=try Data(contentsOf:output.appendingPathComponent("surface/surface.glb"));XCTAssertEqual(Array(glb.prefix(4)),[0x67,0x6c,0x54,0x46])
        XCTAssertTrue(try MappingReconstruction.regularFiles(output).contains{$0.pathExtension=="png"})
        if let fixture=ProcessInfo.processInfo.environment["R2C_MAPPING_SIMULATOR_FIXTURE"] {
            let destination=URL(fileURLWithPath:fixture,isDirectory:true)
            try FileManager.default.createDirectory(at:destination,withIntermediateDirectories:true)
            for region in 0..<2 {let target=destination.appendingPathComponent("surface/regions/\(region)");try FileManager.default.createDirectory(at:target.deletingLastPathComponent(),withIntermediateDirectories:true);if FileManager.default.fileExists(atPath:target.path){try FileManager.default.removeItem(at:target)};try FileManager.default.copyItem(at:output.appendingPathComponent("surface"),to:target);if region==1{var distinct=glb;let length=distinct.withUnsafeBytes{Int(UInt32(littleEndian:$0.loadUnaligned(fromByteOffset:12,as:UInt32.self)))};var object=try JSONSerialization.jsonObject(with:distinct.subdata(in:20..<20+length)) as! [String:Any];var accessors=object["accessors"] as! [[String:Any]];accessors[3]["count"]=3000;object["accessors"]=accessors;var json=try JSONSerialization.data(withJSONObject:object,options:[.sortedKeys]);XCTAssertLessThanOrEqual(json.count,length);while json.count<length{json.append(32)};distinct.replaceSubrange(20..<20+length,with:json);try distinct.write(to:target.appendingPathComponent("surface.glb"))}}
            try MappingSurfaceProducts.json(["regions":[["path":"regions/0/surface.glb"],["path":"regions/1/surface.glb"]]],destination.appendingPathComponent("surface/collection.json"))
        }
        let archive=root.appendingPathComponent("share.zip")
        try MappingShareArchive.write(root:output,layerID:UUID().uuidString.lowercased(),manifest:Data("{}".utf8),destination:archive){_ in}
        let decoded=try OperationalZipArchive.decode(Data(contentsOf:archive))
        XCTAssertTrue(decoded.contains{$0.path.hasSuffix("surface/surface.glb") && $0.data==glb})
        #if os(macOS)
        let process=Process();process.executableURL=URL(fileURLWithPath:"/usr/bin/unzip");process.arguments=["-t",archive.path];process.standardOutput=FileHandle.nullDevice;process.standardError=FileHandle.nullDevice;try process.run();process.waitUntilExit();XCTAssertEqual(process.terminationStatus,0)
        #endif
    }
}
