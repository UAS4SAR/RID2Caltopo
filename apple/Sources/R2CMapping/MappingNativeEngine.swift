import Foundation
import R2CCore
import R2CMappingNative

public final class MappingCancellation: @unchecked Sendable {
    private let lock=NSLock()
    private var stopped=false
    public init() {}
    public func cancel() { lock.lock(); stopped=true; lock.unlock() }
    public func check() throws {lock.lock();let value=stopped;lock.unlock();if value{throw CancellationError()}}
}
private final class NativeProgress {
    let update: (String,Double)throws->Void
    var error: Error?
    init(_ update: @escaping (String,Double)throws->Void) { self.update=update }
}
public struct MappingGeometryResult: Codable, Sendable {
    public var originalWidth:Int?=nil
    public var originalHeight:Int?=nil
    public var rasterWidth:Int { originalWidth ?? 4032 }
    public var rasterHeight:Int { originalHeight ?? 3024 }
    public let adjustment: [Double]
    public var heights: [Float]
    public let width,height: Int
    public let x,y,cell,originX,originY,originAltitude,groundScale: Double
    public let sampleWidth,sampleHeight,selectedPhotos,registeredPhotos,featureTracks,matchedPairs,stereoPairs,measuredCells: Int
    public let digests: [String]
    public var approximate: Bool { (adjustment.last ?? 0)<0 }
    public var used: [Int] {digests.indices.filter{adjustment[12+$0*12].isFinite}}
    public var cameras: [Double] {used.flatMap{Array(adjustment[(12+$0*12)..<(24+$0*12)])}}
    public var fullIntrinsics: [Double] {
        let scale=Double(rasterWidth)/Double(sampleWidth)
        return [adjustment[2]*scale,(adjustment[3]+0.5)*scale-0.5,(adjustment[4]+0.5)*scale-0.5,adjustment[5],adjustment[6]]
    }
}

/// Call from one serial worker. Native raster mappings are released at the end of each region.
public enum MappingNativeEngine {
    public static func reconstruct(photos: [MappingOriginal], originals: URL, work: URL, fov: Double,
        progress: @escaping (String,Double)throws->Void) throws -> MappingGeometryResult {
        guard (3...MappingProcessingPolicy.regionPhotos).contains(photos.count),fov.isFinite,fov>0,fov<180 else {throw MappingMission.Failure("Invalid region input.")}
        try FileManager.default.createDirectory(at:work,withIntermediateDirectories:true)
        let originX=photos.map(\.east).reduce(0,+)/Double(photos.count),originY=photos.map(\.north).reduce(0,+)/Double(photos.count)
        let groundScale=cos(photos.map(\.latitude).reduce(0,+)/Double(photos.count) * .pi/180)
        let altitude=photos.map{$0.absoluteAltitude-$0.heightMeters}.sorted()[photos.count/2]
        var cameras:[Double]=[],lasers:[Double]=[],paths:[String]=[]
        var sampleWidth=0,sampleHeight=0
        for (i,p) in photos.enumerated() {
            try progress("Preparing original \(i+1)/\(photos.count)",0.04*Double(i)/Double(photos.count))
            let path=work.appendingPathComponent(p.digest+".gray")
            let dimensions=try autoreleasepool {try MappingOriginal.raster(originals.appendingPathComponent(p.digest+".jpg"),output:path,sample:true)}
            if i>0 && (dimensions.0 != sampleWidth || dimensions.1 != sampleHeight) {throw MappingMission.Failure("Original sample dimensions differ.")}
            sampleWidth=dimensions.0;sampleHeight=dimensions.1;paths.append(path.path)
            let centre=[(p.east-originX)*groundScale,(p.north-originY)*groundScale,p.absoluteAltitude-altitude]
            cameras += centre+p.rotation+[p.sigmaEast,p.sigmaNorth,p.sigmaHeight]
            var target=[Double](repeating:.nan,count:3)
            if let laser=p.laser,laser.count==4,(-85...85).contains(laser[0]),(-180...180).contains(laser[1]),(5...500).contains(laser[3]) {
                let east=laser[1]*MappingOriginal.world/360,north=log(tan(.pi/4+laser[0] * .pi/360))*MappingOriginal.world/(2 * .pi)
                let candidate=[(east-originX)*groundScale,(north-originY)*groundScale,laser[2]-altitude]
                let delta=zip(candidate,centre).map(-),length=sqrt(delta.map{$0*$0}.reduce(0,+))
                let r=p.rotation,optical=(0..<3).map{row in (0..<3).map{r[row*3+$0]*delta[$0]}.reduce(0,+)}
                if abs(length-laser[3])<2 && optical[2]>0 && hypot(optical[0],optical[1])<=max(0.8,length*tan(2 * .pi/180)) {target=candidate}
            }
            lasers += target
        }
        let pitch=photos.map(\.pitch),focal=hypot(Double(sampleWidth),Double(sampleHeight))/(2*tan(fov * .pi/360))
        let callback=NativeProgress(progress);let context=Unmanaged.passUnretained(callback).toOpaque()
        let strings=paths.map{strdup($0)!};defer{strings.forEach{free($0)}}
        let cpaths:[UnsafePointer<CChar>?]=strings.map{UnsafePointer($0)}
        var output:UnsafeMutablePointer<R2CMappingResult>?,error=[CChar](repeating:0,count:2048)
        let success=cameras.withUnsafeBufferPointer { c in lasers.withUnsafeBufferPointer { l in pitch.withUnsafeBufferPointer { p in cpaths.withUnsafeBufferPointer { paths in
            var input=R2CMappingInput(count:Int32(photos.count),width:Int32(sampleWidth),height:Int32(sampleHeight),focal:focal,cameras:c.baseAddress,lasers:l.baseAddress,pitch:p.baseAddress,grayPaths:paths.baseAddress,connectivity:nil,connectivityContext:nil)
            return R2CMappingReconstruct(&input,{context,stage,fraction in
                guard let context,let stage else{return 0};let bridge=Unmanaged<NativeProgress>.fromOpaque(context).takeUnretainedValue()
                do{try bridge.update(String(cString:stage),fraction);return 1}catch{bridge.error=error;return 0}
            },context,&output,&error,error.count)
        }}}}
        if let error=callback.error {throw error}
        guard success==1,let output else{throw MappingMission.Failure(String(cString:error))}
        defer{R2CMappingFree(output)};let value=output.pointee
        return MappingGeometryResult(originalWidth:photos[0].width,originalHeight:photos[0].height,adjustment:Array(UnsafeBufferPointer(start:value.adjustment,count:value.adjustmentCount)),heights:Array(UnsafeBufferPointer(start:value.heights,count:Int(value.width*value.height))),
            width:Int(value.width),height:Int(value.height),x:value.x,y:value.y,cell:value.cell,originX:originX,originY:originY,originAltitude:altitude,groundScale:groundScale,
            sampleWidth:sampleWidth,sampleHeight:sampleHeight,selectedPhotos:Int(value.selectedPhotos),registeredPhotos:Int(value.registeredPhotos),featureTracks:Int(value.featureTracks),matchedPairs:Int(value.matchedPairs),stereoPairs:Int(value.stereoPairs),measuredCells:Int(value.measuredCells),digests:photos.map(\.digest))
    }
    public static func prepareProjection(_ g: MappingGeometryResult, rasters: URL, progress: (String,Double)throws->Void) throws {
        try withoutActuallyEscaping(progress) { progress in
            let bridge=NativeProgress {stage,fraction in try progress("\(stage) · \(Int(fraction*100))%",0.69+0.01*fraction)}
            let context=Unmanaged.passUnretained(bridge).toOpaque()
            let strings=g.used.map{strdup(rasters.appendingPathComponent(g.digests[$0]+".rgba").path)!}
            defer{strings.forEach{free($0)}}
            let paths:[UnsafePointer<CChar>?]=strings.map{UnsafePointer($0)},cameras=g.cameras,k=g.fullIntrinsics
            var error=[CChar](repeating:0,count:2048)
            let success=g.heights.withUnsafeBufferPointer { h in cameras.withUnsafeBufferPointer { c in k.withUnsafeBufferPointer { k in paths.withUnsafeBufferPointer { p in
                R2CMappingPrepareProjection(h.baseAddress,Int32(g.width),Int32(g.height),g.x,g.y,g.cell,c.baseAddress,g.used.count,k.baseAddress,Int32(g.rasterWidth),Int32(g.rasterHeight),p.baseAddress,{context,stage,fraction in
                    guard let context,let stage else{return 0}
                    let bridge=Unmanaged<NativeProgress>.fromOpaque(context).takeUnretainedValue()
                    do{try bridge.update(String(cString:stage),fraction);return 1}catch{bridge.error=error;return 0}
                },context,&error,error.count)
            }}}}
            if let error=bridge.error{throw error}
            guard success==1 else{throw MappingMission.Failure(String(cString:error))}
        }
    }
    public static func render(_ geometry: MappingGeometryResult, rasters: URL, x: Double, y: Double, step: Double) throws -> [UInt32] {
        let paths=geometry.used.map{rasters.appendingPathComponent(geometry.digests[$0]+".rgba").path}
        let strings=paths.map{strdup($0)!};defer{strings.forEach{free($0)}}
        let cpaths:[UnsafePointer<CChar>?]=strings.map{UnsafePointer($0)},cameras=geometry.cameras,k=geometry.fullIntrinsics
        var pixels=[Int32](repeating:0,count:65536),error=[CChar](repeating:0,count:2048)
        let result=geometry.heights.withUnsafeBufferPointer{height in cameras.withUnsafeBufferPointer{c in k.withUnsafeBufferPointer{intrinsics in cpaths.withUnsafeBufferPointer{paths in
            R2CMappingRender(height.baseAddress,Int32(geometry.width),Int32(geometry.height),geometry.x,geometry.y,geometry.cell,c.baseAddress,geometry.used.count,intrinsics.baseAddress,Int32(geometry.rasterWidth),Int32(geometry.rasterHeight),paths.baseAddress,x,y,step,&pixels,&error,error.count)
        }}}}
        guard result>=0 else{throw MappingMission.Failure(String(cString:error))};return pixels.map{UInt32(bitPattern:$0)}
    }
    public static func refine(_ geometry: MappingGeometryResult) throws -> MappingGeometryResult {
        var g=geometry,error=[CChar](repeating:0,count:2048)
        let ok=R2CMappingRefine(&g.heights,Int32(g.width),Int32(g.height),g.cell,&error,error.count)
        guard ok==1 else{throw MappingMission.Failure(String(cString:error))};return g
    }
    public static func faces(_ g: MappingGeometryResult,stride: Int,ids: [Int]) throws -> [Int32] {
        var output=[Int32](repeating:0,count:ids.count*6),error=[CChar](repeating:0,count:2048)
        let count=R2CMappingFaces(g.heights,Int32(g.width),Int32(g.height),Int32(stride),ids.map{Int32($0)},ids.count,&output,output.count,&error,error.count)
        guard count>=0 else{throw MappingMission.Failure(String(cString:error))};return Array(output.prefix(Int(count)))
    }
    public static func releaseRasters(){R2CMappingReleasePixels()}
}
