import Foundation
import CryptoKit
import CoreGraphics
import ImageIO
import R2CCore

public enum MappingReconstruction {
    private struct SavedFile: Codable {let path,sha256:String;let bytes:Int64}
    private struct GeometryCheckpoint: Codable {let fingerprint,sha256:String}
    private struct Checkpoint: Codable {let fingerprint:String;let files:[SavedFile]}
    /// One region is decoded/solved/exported at a time. Checkpoints are verified before reuse.
    public static func run(store: MappingMissionStore, missionID: String, selectedDigests:Set<String>? = nil, cancellation: MappingCancellation,
        progress: @escaping @Sendable (MappingProgressSnapshot)->Void) throws -> URL {
        let fm=FileManager.default,root=try store.directory(missionID),planData=try Data(contentsOf:root.appendingPathComponent("mission.json"))
        guard let plan=try JSONSerialization.jsonObject(with:planData) as? [String:Any],let parameters=plan["parameters"] as? [String:Any],let camera=plan["cameraSpecifications"] as? [String:Any],let fov=camera["diagonalFOVDegrees"] as? Double,
              let feature=plan["feature"] as? [String:Any],let geometry=feature["geometry"] as? [String:Any],let ring=(geometry["coordinates"] as? [[[Double]]])?.first,ring.count>=3 else{throw MappingMission.Failure("Invalid mapping plan.")}
        let allPhotos=try store.originals(missionID)
        if let selectedDigests {guard !selectedDigests.isEmpty,selectedDigests.isSubset(of:Set(allPhotos.map(\.digest))) else{throw MappingMission.Failure("Selected originals are no longer available.")}}
        let photos=allPhotos.filter{selectedDigests?.contains($0.digest) ?? true},started=Date();var lastUpdate=Date.distantPast,lastBudgetCheck=Date.distantPast
        func update(_ stage: String,_ fraction: Double) throws {
            try cancellation.check();let now=Date()
            if now.timeIntervalSince(lastBudgetCheck)>30 {
                let attributes=try fm.attributesOfFileSystem(forPath:root.path)
                if let available=attributes[.systemFreeSize] as? NSNumber,available.int64Value<512*1048576 {throw MappingMission.Failure("Storage is full. Originals and completed regions are retained.")}
                lastBudgetCheck=now
            }
            if now.timeIntervalSince(lastUpdate)>=0.25 || fraction>=1 {lastUpdate=now;progress(MappingProgressSnapshot(stage:stage,fraction:fraction,startedAt:started,activityAt:now))}
        }
        guard (3...10000).contains(photos.count) else{throw MappingMission.Failure("Choose 3–10,000 original visible-wide photos.")}
        let scale=cos(ring.map{$0[1]}.reduce(0,+)/Double(ring.count) * .pi/180)
        let polygon=ring.map{[$0[0]*MappingOriginal.world/360,log(tan(.pi/4+$0[1] * .pi/360))*MappingOriginal.world/(2 * .pi)]}
        let bounds=MappingProcessingPolicy.Bounds(west:polygon.map{$0[0]}.min()!,south:polygon.map{$0[1]}.min()!,east:polygon.map{$0[0]}.max()!,north:polygon.map{$0[1]}.max()!)
        let byDigest=Dictionary(uniqueKeysWithValues:photos.map{($0.digest,$0)})
        let positions=photos.map { p in
            let offset=p.heightMeters*tan((90+p.pitch) * .pi/180),localScale=cos(p.latitude * .pi/180)
            return MappingProcessingPolicy.Photo(digest:p.digest,x:p.east+sin(p.yaw * .pi/180)*offset/localScale,y:p.north+cos(p.yaw * .pi/180)*offset/localScale)
        }
        let regions=try MappingProcessingPolicy.regions(photos:positions,bounds:bounds,groundScale:scale)
        let originals=root.appendingPathComponent("originals"),work=root.appendingPathComponent("work/rtk-regions-v2")
        try fm.createDirectory(at:work,withIntermediateDirectories:true)
        var fingerprintData=Data("apple-regions-v6-best-effort".utf8);fingerprintData.append(planData);fingerprintData.append(Data(photos.map(\.digest).sorted().joined().utf8))
        let fingerprint=SHA256.hash(data:fingerprintData).map{String(format:"%02x",$0)}.joined()
        for (i,p) in photos.enumerated(){let file=originals.appendingPathComponent(p.digest+".jpg"),size=Int64(try file.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0)
            guard try MappingFileIO.sha(file,progress:{bytes in try update("Checking original \(i+1)/\(photos.count) · \(MappingProgressSnapshot.bytes(bytes,total:size))",0.04*Double(i)/Double(photos.count))})==p.digest else{throw MappingMission.Failure("Original checksum changed. Re-import \(p.name).")}
        }
        let pixel=(parameters["desiredGroundResolutionCmPerPixel"] as? Double ?? 2)/100
        let zoom=min(23,max(17,Int(ceil(log2(scale*MappingOriginal.world/(256*pixel))))))
        var supported=Set<String>();var nominalCalibration=false;var approximateRegions=0;var regionReports:[[String:Any]]=[]
        for (index,region) in regions.enumerated(){
            let block=work.appendingPathComponent("regions/\(index)"),checkpoint=block.appendingPathComponent("checkpoint.json")
            try fm.createDirectory(at:block,withIntermediateDirectories:true)
            func regional(_ stage: String,_ fraction: Double) throws {try update("Region \(index+1)/\(regions.count) · \(stage)",0.04+0.88*(Double(index)+fraction)/Double(regions.count))}
            let old=try? JSONDecoder().decode(Checkpoint.self,from:Data(contentsOf:checkpoint))
            var verified=old?.fingerprint==fingerprint+"-refined-ray-coverage-photo-seams-v5" && !(old?.files.isEmpty ?? true)
            if verified,let old {for (fileIndex,record) in old.files.enumerated(){
                let file=block.appendingPathComponent(record.path).standardizedFileURL
                if !file.path.hasPrefix(block.path+"/") || !fm.fileExists(atPath:file.path){verified=false;break}
                let digest=try MappingFileIO.sha(file){bytes in try regional("Verifying saved file \(fileIndex+1)/\(old.files.count) · \(MappingProgressSnapshot.bytes(bytes,total:record.bytes))",0)}
                if digest != record.sha256 {verified=false;break}
            }}
            if !verified {
                // A different completed/all-received selection must not retain tiles
                // from the preceding result. Keep only the independently verified geometry.
                for child in try fm.contentsOfDirectory(at:block,includingPropertiesForKeys:nil) where !["geometry.plist","geometry-checkpoint.json"].contains(child.lastPathComponent){try fm.removeItem(at:child)}
                let raster=block.appendingPathComponent("rasters")
                try fm.createDirectory(at:raster,withIntermediateDirectories:true)
                defer{MappingNativeEngine.releaseRasters();try? fm.removeItem(at:raster)}
                let selected=region.images.compactMap{byDigest[$0.digest]}
                let geometryFile=block.appendingPathComponent("geometry.plist"),geometryMarker=block.appendingPathComponent("geometry-checkpoint.json")
                let savedGeometry=try? JSONDecoder().decode(GeometryCheckpoint.self,from:Data(contentsOf:geometryMarker))
                let g:MappingGeometryResult
                if savedGeometry?.fingerprint==fingerprint,FileManager.default.fileExists(atPath:geometryFile.path),
                   try MappingFileIO.sha(geometryFile,progress:{_ in try regional("Verifying saved camera and surface solution",0)})==savedGeometry?.sha256 {
                    g=try PropertyListDecoder().decode(MappingGeometryResult.self,from:Data(contentsOf:geometryFile))
                    guard g.digests==selected.map(\.digest) else{throw MappingMission.Failure("Saved region selection changed.")}
                }else{
                    g=try MappingNativeEngine.reconstruct(photos:selected,originals:originals,work:raster,fov:fov,progress:regional)
                    // Binary plists retain excluded cameras and unknown heights as NaN.
                    let encoder=PropertyListEncoder();encoder.outputFormat = .binary
                    try encoder.encode(g).write(to:geometryFile,options:.atomic)
                    try MappingFileIO.json(GeometryCheckpoint(fingerprint:fingerprint,sha256:MappingFileIO.sha(geometryFile,progress:{_ in try regional("Saving camera and surface solution",0.68)})),to:geometryMarker)
                }
                for (number,i) in g.used.enumerated(){try regional("Preparing full-resolution color \(number+1)/\(g.used.count)",0.685)
                    _=try autoreleasepool{try MappingOriginal.raster(originals.appendingPathComponent(g.digests[i]+".jpg"),output:raster.appendingPathComponent(g.digests[i]+".rgba"),sample:false)}
                }
                try MappingSurfaceProducts.export(g,rasters:raster,output:block,bounds:region.bounds,polygon:polygon,zoom:zoom,progress:regional)
                MappingNativeEngine.releaseRasters();try fm.removeItem(at:raster)
                let files=try regularFiles(block).filter{$0 != checkpoint};var saved:[SavedFile]=[]
                for (number,file) in files.enumerated(){let bytes=Int64(try file.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0)
                    let hash=try MappingFileIO.sha(file){done in try regional("Saving file \(number+1)/\(files.count) · \(MappingProgressSnapshot.bytes(done,total:bytes))",1)}
                    saved.append(SavedFile(path:try MappingFileIO.relative(file,to:block),sha256:hash,bytes:bytes))
                }
                try MappingFileIO.json(Checkpoint(fingerprint:fingerprint+"-refined-ray-coverage-photo-seams-v5",files:saved),to:checkpoint)
                if g.approximate {approximateRegions+=1}
                regionReports.append(["index":index,"reconstructionMode":g.approximate ? "approximate-plane":"measured-surface","registeredPhotoCount":g.used.count,"featureTracks":g.featureTracks,"measuredStereoCells":g.measuredCells,"knownProjectionCells":g.heights.filter{$0.isFinite}.count,"surfaceCells":g.heights.count,"reprojectionRmsSamplePixels":g.approximate ? NSNull():g.adjustment[7] as Any])
                supported.formUnion(g.used.map{g.digests[$0]});nominalCalibration = nominalCalibration || (g.adjustment.last ?? 0)<6
            } else {
                let g=try PropertyListDecoder().decode(MappingGeometryResult.self,from:Data(contentsOf:block.appendingPathComponent("geometry.plist")))
                if g.approximate {approximateRegions+=1}
                regionReports.append(["index":index,"reconstructionMode":g.approximate ? "approximate-plane":"measured-surface","registeredPhotoCount":g.used.count,"featureTracks":g.featureTracks,"measuredStereoCells":g.measuredCells,"knownProjectionCells":g.heights.filter{$0.isFinite}.count,"surfaceCells":g.heights.count,"reprojectionRmsSamplePixels":g.approximate ? NSNull():g.adjustment[7] as Any])
                supported.formUnion(g.used.map{g.digests[$0]});nominalCalibration = nominalCalibration || (g.adjustment.last ?? 0)<6
            }
        }
        let resultID=UUID().uuidString.lowercased(),result=work.appendingPathComponent("publishing")
        if fm.fileExists(atPath:result.path){try fm.removeItem(at:result)}
        try fm.createDirectory(at:result,withIntermediateDirectories:true)
        var collection:[[String:Any]]=[]
        for (i,region) in regions.enumerated(){let source=work.appendingPathComponent("regions/\(i)"),files=try regularFiles(source)
            for (number,file) in files.enumerated(){let relative=try MappingFileIO.relative(file,to:source);if relative=="checkpoint.json"||relative=="geometry.plist"||relative=="geometry-checkpoint.json"{continue}
                let target:URL
                if relative.hasPrefix("surface/"){target=result.appendingPathComponent("surface/regions/\(i)/"+String(relative.dropFirst(8)))}
                else if file.pathExtension=="png"{target=result.appendingPathComponent(relative)}else{continue}
                try fm.createDirectory(at:target.deletingLastPathComponent(),withIntermediateDirectories:true)
                if file.pathExtension=="png"{try merge(source:file,destination:target,region:region)}else{
                    let total=Int64(try file.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0)
                    try MappingFileIO.copy(file,to:target){done in try update("Assembling region \(i+1)/\(regions.count) · file \(number+1)/\(files.count) · \(MappingProgressSnapshot.bytes(done,total:total))",0.92+0.03*Double(i)/Double(regions.count))}
                }
            }
            collection.append(["index":i,"title":"Region \(i+1) of \(regions.count)","path":"regions/\(i)/surface.glb","meshSpacingMeters":0.4])
        }
        try MappingSurfaceProducts.json(["type":"r2c_surface_collection","version":1,"regions":collection,"meshSpacingMeters":0.4],result.appendingPathComponent("surface/collection.json"))
        for weight in try regularFiles(result).filter({$0.pathExtension=="weights"}) {try fm.removeItem(at:weight)}
        try parentTiles(result:result,zoom:zoom,progress:update)
        let boundsDegrees=[bounds.west*360/MappingOriginal.world,atan(sinh(bounds.south*2 * .pi/MappingOriginal.world))*180 / .pi,bounds.east*360/MappingOriginal.world,atan(sinh(bounds.north*2 * .pi/MappingOriginal.world))*180 / .pi]
        let lowerConfidence=photos.contains{$0.positioningMode=="gnss-assisted"}
        let positioningWarning=lowerConfidence ? " GNSS-assisted reconstruction: some photos lack a verified fixed-RTK position. Conservative position priors were used; absolute placement is not independently verified.":""
        let scaleWarning=nominalCalibration ? " Nominal camera calibration used because laser height anchors were insufficient. Surface height and scale are not independently verified.":""
        let recoveryWarning=approximateRegions>0 ? "\(approximateRegions) regions use approximate reference planes, not measured depth. The flat 3D preview is only a photo projection. Position and height accuracy are unknown. ":""
        let layer:[String:Any]=["regionReports":regionReports,"reconstructionMode":approximateRegions>0 ? "approximate-plane":"measured-surface","approximateRegionCount":approximateRegions,"photogrammetric3D":approximateRegions==0,"scaleMode":nominalCalibration ? "nominal-calibration":"laser-anchored","scaleWarning":scaleWarning,"positioningMode":lowerConfidence ? "gnss-assisted":"fixed-rtk","positioningWarning":positioningWarning,"type":"r2c_mapping_layer","version":1,"layerId":resultID,"missionId":missionID,"assignmentName":plan["title"] ?? "Map", "cameraProfileId":plan["cameraProfileId"] ?? "","incidentId":plan["incidentId"] ?? "","assignmentId":plan["assignmentId"] ?? "","title":plan["title"] ?? "Mission","profile":"daylight-rgb","tileScheme":"xyz","minZoom":14,"maxZoom":zoom,"bounds":boundsDegrees,"tileUrlTemplate":"http://127.0.0.1:8765/local-layer/\(resultID)/tiles/{Z}/{X}/{Y}.png","photoCount":supported.count,"selectedPhotoCount":photos.count,"excludedPhotoCount":photos.count-supported.count,"captureTimeLocal":photos.first?.capturedAt ?? "","captureEndTimeLocal":photos.last?.capturedAt ?? "","processedAt":ISO8601DateFormatter().string(from:Date()),"processingSeconds":Int(Date().timeIntervalSince(started)),"engine":"regions-v13-best-effort-mesh04","regionCount":regions.count,"surfaceModel":["type":"r2c_surface_collection","path":"surface/collection.json","regionCount":regions.count,"meshSpacingMeters":0.4],"assignmentBoundary":ring,"quality":recoveryWarning+"\(supported.count) of \(photos.count) originals support \(regions.count) overlapping reconstructed regions. Unknown depth remains transparent. Visible canopy surface; not bare earth."+positioningWarning+scaleWarning]
        try MappingSurfaceProducts.json(layer,result.appendingPathComponent("layer.json"));try cancellation.check()
        var tileEntries:[[String:Any]]=[],surfaceEntries:[[String:Any]]=[]
        for file in try regularFiles(result){let relative=try MappingFileIO.relative(file,to:result),path="mapping-layers/\(resultID)/"+relative
            if file.pathExtension=="png"{let parts=relative.split(separator:"/");if parts.count==3,let z=Int(parts[0]),let x=Int(parts[1]),let y=Int(parts[2].dropLast(4)){tileEntries.append(["source":"R2C-Mapping-\(resultID)","z":z,"x":x,"y":y,"path":path,"expires_at_epoch_ms":0])}}
            else if relative.hasPrefix("surface/"){let bytes=Int64(try file.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0);let hash=try MappingFileIO.sha(file){done in try update("Verifying 3D package · \(MappingProgressSnapshot.bytes(done,total:bytes))",0.99)};surfaceEntries.append(["layerId":resultID,"path":path,"bytes":bytes,"sha256":hash])}
        }
        let manifest=try JSONSerialization.data(withJSONObject:["format":"rid2caltopo_mutual_aid_package","version":1,"generated":ISO8601DateFormatter().string(from:Date()),"package_name":plan["title"] ?? "Mission","source_org":"","profile_enc":"","aol_entries":[],"dem_entries":[],"mapping_layers":[layer],"tile_entries":tileEntries,"surface_entries":surfaceEntries])
        let archive=result.appendingPathComponent("share.partial")
        try MappingShareArchive.write(root:result,layerID:resultID,manifest:manifest,destination:archive){try update($0,0.99)}
        try fm.moveItem(at:archive,to:result.appendingPathComponent("share.zip"))
        let destination=root.appendingPathComponent("results/\(resultID)");try fm.createDirectory(at:destination.deletingLastPathComponent(),withIntermediateDirectories:true);try fm.moveItem(at:result,to:destination)
        try update("Map ready",1);return destination
    }
    private static func pixels(_ url: URL) throws -> [UInt32] {
        guard let source=CGImageSourceCreateWithURL(url as CFURL,nil),let image=CGImageSourceCreateImageAtIndex(source,0,nil),image.width==256,image.height==256 else {throw MappingMission.Failure("Unreadable regional map tile.")}
        var bytes=[UInt8](repeating:0,count:65536*4)
        try bytes.withUnsafeMutableBytes{buffer in
            guard let context=CGContext(data:buffer.baseAddress,width:256,height:256,bitsPerComponent:8,bytesPerRow:1024,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else{throw MappingMission.Failure("Cannot read map tile.")}
            context.draw(image,in:CGRect(x:0,y:0,width:256,height:256))
        }
        return (0..<65536).map{let i=$0*4;return UInt32(bytes[i+3])<<24 | UInt32(bytes[i])<<16 | UInt32(bytes[i+1])<<8 | UInt32(bytes[i+2])}
    }
    // Store the actual winning pixel's distance on disk. An earlier region with
    // no data at this pixel must not prevent a later supported region filling it.
    static func merge(source: URL,destination: URL,region: MappingProcessingPolicy.Region) throws {
        let fresh=try pixels(source),fm=FileManager.default
        var old=fm.fileExists(atPath:destination.path) ? try pixels(destination):[UInt32](repeating:0,count:65536)
        let weightsURL=destination.appendingPathExtension("weights")
        var weights=[Double](repeating:.infinity,count:65536)
        if fm.fileExists(atPath:weightsURL.path) {
            let data=try Data(contentsOf:weightsURL)
            guard data.count==65536*8 else{throw MappingMission.Failure("Invalid regional tile checkpoint.")}
            weights=data.withUnsafeBytes{bytes in (0..<65536).map{Double(bitPattern:UInt64(littleEndian:bytes.loadUnaligned(fromByteOffset:$0*8,as:UInt64.self)))}}
        }
        let z=Int(source.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent)!,tx=Int(source.deletingLastPathComponent().lastPathComponent)!,ty=Int(source.deletingPathExtension().lastPathComponent)!
        let unit=MappingOriginal.world/(256*pow(2,Double(z))),cx=(region.bounds.west+region.bounds.east)/2,cy=(region.bounds.south+region.bounds.north)/2
        for row in 0..<256{for col in 0..<256{let n=row*256+col;if fresh[n]>>24==0{continue};let x=(Double(tx*256+col)+0.5)*unit-MappingOriginal.world/2,y=MappingOriginal.world/2-(Double(ty*256+row)+0.5)*unit
            let distance=pow(x-cx,2)+pow(y-cy,2)
            if old[n]>>24==0 || distance<weights[n]{old[n]=fresh[n];weights[n]=distance}
        }}
        try MappingSurfaceProducts.png(old,destination)
        var data=Data();data.reserveCapacity(65536*8)
        for weight in weights{var value=weight.bitPattern.littleEndian;withUnsafeBytes(of:&value){data.append(contentsOf:$0)}}
        try data.write(to:weightsURL,options:.atomic)
    }
    private static func parentTiles(result: URL,zoom: Int,progress: (String,Double)throws->Void) throws {
        if zoom<=14{return}
        for z in stride(from:zoom-1,through:14,by:-1){
            let root=result.appendingPathComponent(String(z+1));if !FileManager.default.fileExists(atPath:root.path){continue}
            let children=try regularFiles(root).filter{$0.pathExtension=="png"}
            var parents=Set<String>();for child in children {let x=Int(child.deletingLastPathComponent().lastPathComponent)!,y=Int(child.deletingPathExtension().lastPathComponent)!;parents.insert("\(x/2)/\(y/2)")}
            for (number,parent) in parents.sorted().enumerated(){try progress("Preparing zoom \(z) · tile \(number+1)/\(parents.count)",0.95+0.04*Double(zoom-z)/Double(zoom-14))
                let xy=parent.split(separator:"/").map{Int($0)!};var output=[UInt32](repeating:0,count:65536)
                for dy in 0..<2{for dx in 0..<2{let child=root.appendingPathComponent("\(xy[0]*2+dx)/\(xy[1]*2+dy).png");if !FileManager.default.fileExists(atPath:child.path){continue};let input=try pixels(child)
                    for row in 0..<128{for col in 0..<128{var r:UInt32=0,g:UInt32=0,b:UInt32=0,a:UInt32=0
                        for yy in 0..<2{for xx in 0..<2{let color=input[(row*2+yy)*256+col*2+xx];a+=color>>24;r+=(color>>16)&255;g+=(color>>8)&255;b+=color&255}}
                        output[(row+dy*128)*256+col+dx*128]=(a/4)<<24 | (r/4)<<16 | (g/4)<<8 | b/4
                    }}
                }}
                try MappingSurfaceProducts.png(output,result.appendingPathComponent("\(z)/\(parent).png"))
            }
        }
    }
    static func regularFiles(_ root: URL) throws -> [URL] {
        guard let iterator=FileManager.default.enumerator(at:root,includingPropertiesForKeys:[.isRegularFileKey],options:[]) else{throw MappingMission.Failure("Cannot enumerate mapping files.")}
        return try iterator.compactMap {item in guard let url=item as? URL else{return nil};return try url.resourceValues(forKeys:[.isRegularFileKey]).isRegularFile==true ? url:nil}
    }
}
