import Foundation
import CryptoKit
import CoreGraphics
import ImageIO
import os
import R2CCore

public enum MappingReconstruction {
    private static let processingLog = Logger(subsystem: "org.ncssar.RID2Caltopo", category: "MappingProcessing")
    private static let resumeLock = NSLock()
    // Guarded by resumeLock; marked unsafe for Swift 6 global-mutable checking.
    nonisolated(unsafe) private static var resumeScheduled = false
    private struct SavedFile: Codable {let path,sha256:String;let bytes:Int64}
    private struct GeometryCheckpoint: Codable {let fingerprint,sha256:String}
    private struct Checkpoint: Codable {let fingerprint:String;let files:[SavedFile]}

    /// Interrupted job discovered after process death from processing-status.json.
    public struct InterruptedJob: Sendable {
        public let missionID: String
        public let title: String
        public let incidentID: String
        public let selectedDigests: Set<String>?
    }

    /// Persist stop state so cancel/failure does not look like process death on next launch.
    public static func markStopped(root: URL, message: String, cancelled: Bool) {
        var body: [String: Any] = [
            "active": false,
            "stage": message,
            "cancelled": cancelled,
            "updatedAt": ISO8601DateFormatter().string(from: Date()),
        ]
        if !cancelled { body["error"] = message }
        if let encoded = try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys]) {
            try? encoded.write(to: root.appendingPathComponent("processing-status.json"), options: .atomic)
        }
    }

    /// Scan missions for active processing-status left by process death. IO worker only.
    public static func findInterruptedJobs(missionsRoot: URL) -> [InterruptedJob] {
        precondition(!Thread.isMainThread)
        let fm = FileManager.default
        guard let missions = try? fm.contentsOfDirectory(at: missionsRoot, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) else { return [] }
        var jobs: [InterruptedJob] = []
        for mission in missions {
            guard UUID(uuidString: mission.lastPathComponent) != nil else { continue }
            let statusURL = mission.appendingPathComponent("processing-status.json")
            guard let data = try? Data(contentsOf: statusURL),
                  let status = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  (status["active"] as? Bool) == true else { continue }
            let digests: Set<String>? = {
                if let selected = status["selectedDigests"] as? [String], !selected.isEmpty { return Set(selected) }
                if let input = try? Data(contentsOf: mission.appendingPathComponent("processing-input.json")),
                   let object = try? JSONSerialization.jsonObject(with: input) as? [String: Any],
                   let sha = object["sha256"] as? [String], !sha.isEmpty { return Set(sha) }
                return nil
            }()
            let plan = (try? JSONSerialization.jsonObject(with: Data(contentsOf: mission.appendingPathComponent("mission.json"))) as? [String: Any]) ?? [:]
            jobs.append(InterruptedJob(
                missionID: mission.lastPathComponent,
                title: plan["title"] as? String ?? mission.lastPathComponent.prefix(8).description,
                incidentID: plan["incidentId"] as? String ?? "",
                selectedDigests: digests
            ))
        }
        return jobs
    }

    /// Once per process: return interrupted jobs so the UI can re-queue. Off MainActor.
    public static func consumeInterruptedJobs(missionsRoot: URL) -> [InterruptedJob] {
        resumeLock.lock(); defer { resumeLock.unlock() }
        guard !resumeScheduled else { return [] }
        resumeScheduled = true
        let jobs = findInterruptedJobs(missionsRoot: missionsRoot)
        if let first = jobs.first {
            processingLog.debug("resume job id=\(first.missionID.prefix(8), privacy: .public) photos=\(first.selectedDigests?.count ?? 0, privacy: .public)")
        }
        return jobs
    }

    /// One region is decoded/solved/exported at a time. Checkpoints are verified before reuse.
    public static func run(store: MappingMissionStore, missionID: String, selectedDigests:Set<String>? = nil, cancellation: MappingCancellation,
        progress: @escaping @Sendable (MappingProgressSnapshot)->Void) throws -> URL {
        precondition(!Thread.isMainThread)
        let fm=FileManager.default,root=try store.directory(missionID),planData=try Data(contentsOf:root.appendingPathComponent("mission.json"))
        var finishedCleanly = false
        defer {
            if !finishedCleanly {
                let cancelled = cancellation.isCancelled
                let message = cancelled
                    ? "Processing cancelled; originals and completed regions retained."
                    : "Processing interrupted; originals retained"
                markStopped(root: root, message: message, cancelled: cancelled)
            }
        }
        guard let plan=try JSONSerialization.jsonObject(with:planData) as? [String:Any],let parameters=plan["parameters"] as? [String:Any],let camera=plan["cameraSpecifications"] as? [String:Any],let fov=camera["diagonalFOVDegrees"] as? Double,
              let feature=plan["feature"] as? [String:Any],let geometry=feature["geometry"] as? [String:Any],let ring=(geometry["coordinates"] as? [[[Double]]])?.first,ring.count>=3 else{throw MappingMission.Failure("Invalid mapping plan.")}
        let allPhotos=try store.originals(missionID)
        if let selectedDigests {guard !selectedDigests.isEmpty,selectedDigests.isSubset(of:Set(allPhotos.map(\.digest))) else{throw MappingMission.Failure("Selected originals are no longer available.")}}
        let photos=allPhotos.filter{selectedDigests?.contains($0.digest) ?? true},started=Date()
        var lastUpdate=Date.distantPast,lastBudgetCheck=Date.distantPast,lastProgressLog=Date.distantPast
        var lastPhase:String?=nil,phaseStarted=started
        var budgetRoot=root.appendingPathComponent("work/rtk-regions-v2")
        var budgetEnabled=true
        var estimateSummary:String?=nil
        let photoCount=photos.count
        let supportRoot=store.root.deletingLastPathComponent()
        var phases=MappingProgressPhases.tracker(engine:"rtk-surface", photoCount:photoCount, measuredMsPerPhoto:MappingProgressPhases.Costs.load(supportRoot:supportRoot))
        func ensureCancel() throws { try cancellation.check() }
        func checkBudget() throws {
            try ensureCancel()
            guard budgetEnabled else { return }
            let now=Date()
            if now.timeIntervalSince(lastBudgetCheck)>30 {
                try MappingStorageBudget.checkExisting(budgetRoot, reserveBytes: MappingStorageBudget.reconstructReserveBytes, bucket: "Mapping work space")
                lastBudgetCheck=now
            }
        }
        func reportPhase(_ phase:String, local:Double?, unitIndex:Int?=nil, unitTotal:Int?=nil, stageOverride:String?=nil) throws {
            try checkBudget()
            let now=Date()
            let phaseElapsedMs=now.timeIntervalSince(phaseStarted)*1000
            let defaults=MappingProgressPhases.defaultCostsMs(photoCount:photoCount, engine:"rtk-surface")
            let estimated=defaults[phase] ?? 1
            let timeHint: Double? = local == nil && estimated > 1 ? min(0.95, max(0, phaseElapsedMs / estimated)) : nil
            let fraction=phases.overall(phase, local: local, timeHint: timeHint)
            let stage=stageOverride ?? phases.stage(phase, unitIndex: unitIndex, unitTotal: unitTotal)
            if phase != lastPhase {
                if let lastPhase {
                    let endedMs=Int64(now.timeIntervalSince(phaseStarted)*1000)
                    processingLog.debug("phase end id=\(lastPhase, privacy: .public) elapsedMs=\(endedMs, privacy: .public) photos=\(photoCount, privacy: .public)")
                    MappingProgressPhases.Costs.record(supportRoot:supportRoot, phaseId:lastPhase, elapsedMs:endedMs, photoCount:photoCount)
                }
                processingLog.debug("phase start id=\(phase, privacy: .public) photos=\(photoCount, privacy: .public)")
                lastPhase=phase; phaseStarted=now; lastProgressLog=now
            } else if now.timeIntervalSince(lastProgressLog) > 60 {
                processingLog.debug("phase progress id=\(phase, privacy: .public) fraction=\(String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), fraction), privacy: .public) elapsedMs=\(Int(now.timeIntervalSince(started)*1000), privacy: .public) photos=\(photoCount, privacy: .public)")
                lastProgressLog=now
            }
            if now.timeIntervalSince(lastUpdate)>=0.25 || fraction>=1 || local==nil {
                lastUpdate=now
                let elapsed=Int64(max(0, now.timeIntervalSince(started)))
                let remaining=phases.etaSeconds(overall:fraction, elapsedSeconds:elapsed)
                progress(MappingProgressSnapshot(stage:stage,fraction:fraction,startedAt:started,activityAt:now,estimateSummary:estimateSummary,remainingSeconds:remaining))
            }
        }
        func update(_ stage: String,_ fraction: Double) throws {
            // Stitcher absolute fractions are remapped into phase bands; optimizer cost never drives %.
            let body: String = {
                if let range = stage.range(of: " · ") { return String(stage[range.upperBound...]) }
                return stage
            }()
            func counts(in text: String) -> (Int, Int)? {
                guard let match = text.range(of: #"(\d+)/(\d+)"#, options: .regularExpression) else { return nil }
                let parts = String(text[match]).split(separator: "/").compactMap { Int($0) }
                guard parts.count == 2 else { return nil }
                return (parts[0], parts[1])
            }
            if body.hasPrefix("Checking original") {
                if let (i, n) = counts(in: body) { try reportPhase(MappingProgressPhases.checkOriginals, local: Double(max(0, i - 1)) / Double(n), unitIndex: i, unitTotal: n, stageOverride: stage) }
                else { try reportPhase(MappingProgressPhases.checkOriginals, local: fraction, stageOverride: stage) }
            } else if body.hasPrefix("Preparing full-resolution color") || body.hasPrefix("Preparing original") || body.hasPrefix("Coloring 3D") {
                if let (i, n) = counts(in: body) { try reportPhase(MappingProgressPhases.prepareColors, local: Double(max(0, i - 1)) / Double(n), unitIndex: i, unitTotal: n, stageOverride: stage) }
                else { try reportPhase(MappingProgressPhases.prepareColors, local: nil, stageOverride: stage) }
            } else if body.hasPrefix("Rendering 2D tiles") || body.hasPrefix("Projecting") {
                if let (i, n) = counts(in: body) { try reportPhase(MappingProgressPhases.projectTiles, local: Double(i) / Double(n), unitIndex: i, unitTotal: n, stageOverride: stage) }
                else { try reportPhase(MappingProgressPhases.projectTiles, local: nil, stageOverride: stage) }
            } else if body.hasPrefix("Preparing zoom") || body.hasPrefix("Building zoom") {
                if let (i, n) = counts(in: body) { try reportPhase(MappingProgressPhases.zoomLevels, local: Double(i) / Double(n), unitIndex: i, unitTotal: n, stageOverride: stage) }
                else { try reportPhase(MappingProgressPhases.zoomLevels, local: nil, stageOverride: stage) }
            } else if body.hasPrefix("Assembling") || body.hasPrefix("Packaging") || body.hasPrefix("Verifying 3D") || body.hasPrefix("Saving file") || body.hasPrefix("Verifying saved file") || body.hasPrefix("Saving camera") {
                if let (i, n) = counts(in: body) { try reportPhase(MappingProgressPhases.package, local: Double(i) / Double(n), unitIndex: i, unitTotal: n, stageOverride: stage) }
                else { try reportPhase(MappingProgressPhases.package, local: nil, stageOverride: stage) }
            } else if body.hasPrefix("Matching") || body.hasPrefix("Refining local surface") || body.contains("Matching photo seams") {
                try reportPhase(MappingProgressPhases.matchSeams, local: nil, stageOverride: phases.title(MappingProgressPhases.matchSeams))
            } else if body == "Map ready" || body.hasPrefix("Publishing") {
                try reportPhase(MappingProgressPhases.publish, local: body == "Map ready" ? 1 : 0, stageOverride: stage)
            } else {
                // Native optimizer cost is never interpreted as local progress.
                try reportPhase(MappingProgressPhases.reconstruct, local: nil, stageOverride: stage)
            }
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
        budgetRoot=work
        let width=camera["widthPixels"] as? Int ?? 4032, height=camera["heightPixels"] as? Int ?? 3024
        let gsdCm=parameters["desiredGroundResolutionCmPerPixel"] as? Double ?? 2
        let pixelMeters=gsdCm/100
        let area=bounds.area(scale:scale)
        let surfaceCell=MappingMissionDefaults.cell(parameters)
        let fillUnknown2D=MappingMissionDefaults.fillUnknown2D(parameters)
        let estimate=try MappingProcessingPolicy.storage(photos:photos.count,width:width,height:height,areaSquareMeters:area,pixelMeters:pixelMeters,regional:regions.count>1,surfaceCellMeters:surfaceCell)
        let resultsRoot=root.appendingPathComponent("results",isDirectory:true)
        try fm.createDirectory(at:resultsRoot,withIntermediateDirectories:true)
        let assessment=try estimate.assess(originalsRoot:root,scratchRoot:work,layersRoot:resultsRoot,reserveBytes:MappingStorageBudget.reconstructReserveBytes)
        estimateSummary=assessment.summary()
        var statusBody: [String: Any] = [
            "active": true,
            "stage": "Preparing local map",
            "progress": 0.0,
            "engine": "rtk-surface",
            "estimate": assessment.json(),
            "updatedAt": ISO8601DateFormatter().string(from: Date()),
        ]
        if let selectedDigests {
            statusBody["selectedDigests"] = selectedDigests.sorted()
            if let input = try? JSONSerialization.data(withJSONObject: ["sha256": selectedDigests.sorted()], options: [.sortedKeys]) {
                try? input.write(to: root.appendingPathComponent("processing-input.json"), options: .atomic)
            }
        }
        if let encoded = try? JSONSerialization.data(withJSONObject: statusBody, options: [.sortedKeys]) {
            try? encoded.write(to: root.appendingPathComponent("processing-status.json"), options: .atomic)
        }
        processingLog.debug("start missionId=\(missionID.prefix(8), privacy: .public) photos=\(photos.count, privacy: .public) engine=rtk-surface \(assessment.summary(), privacy: .public)")
        if !assessment.shortages.isEmpty { throw MappingMission.Failure(assessment.summary()) }
        try reportPhase(MappingProgressPhases.checkOriginals, local:0, stageOverride:phases.title(MappingProgressPhases.checkOriginals))
        var fingerprintData=Data("apple-regions-v6-best-effort".utf8);fingerprintData.append(planData);fingerprintData.append(Data(photos.map(\.digest).sorted().joined().utf8))
        let fingerprint=SHA256.hash(data:fingerprintData).map{String(format:"%02x",$0)}.joined()
        for (i,p) in photos.enumerated(){let file=originals.appendingPathComponent(p.digest+".jpg"),size=Int64(try file.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0)
            guard try MappingFileIO.sha(file,progress:{bytes in try update("Checking original \(i+1)/\(photos.count) · \(MappingProgressSnapshot.bytes(bytes,total:size))",Double(i)/Double(photos.count))})==p.digest else{throw MappingMission.Failure("Original checksum changed. Re-import \(p.name).")}
        }
        let pixel=(parameters["desiredGroundResolutionCmPerPixel"] as? Double ?? 2)/100
        let zoom=min(23,max(17,Int(ceil(log2(scale*MappingOriginal.world/(256*pixel))))))
        var supported=Set<String>();var nominalCalibration=false;var approximateRegions=0;var regionReports:[[String:Any]]=[]
        for (index,region) in regions.enumerated(){
            let block=work.appendingPathComponent("regions/\(index)"),checkpoint=block.appendingPathComponent("checkpoint.json")
            try fm.createDirectory(at:block,withIntermediateDirectories:true)
            func regional(_ stage: String,_ fraction: Double) throws {
                // Region index is carried in the stage label; overall % comes from phase bands only.
                _ = fraction
                try update("Region \(index+1)/\(regions.count) · \(stage)", 0)
            }
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
                    g=try MappingNativeEngine.reconstruct(photos:selected,originals:originals,work:raster,fov:fov,surfaceCellMeters:surfaceCell,progress:regional)
                    // Binary plists retain excluded cameras and unknown heights as NaN.
                    let encoder=PropertyListEncoder();encoder.outputFormat = .binary
                    try encoder.encode(g).write(to:geometryFile,options:.atomic)
                    try MappingFileIO.json(GeometryCheckpoint(fingerprint:fingerprint,sha256:MappingFileIO.sha(geometryFile,progress:{_ in try regional("Saving camera and surface solution",0.68)})),to:geometryMarker)
                }
                for (number,i) in g.used.enumerated(){try regional("Preparing full-resolution color \(number+1)/\(g.used.count)",0.685)
                    _=try autoreleasepool{try MappingOriginal.raster(originals.appendingPathComponent(g.digests[i]+".jpg"),output:raster.appendingPathComponent(g.digests[i]+".rgba"),sample:false)}
                }
                try MappingSurfaceProducts.export(g,rasters:raster,output:block,bounds:region.bounds,polygon:polygon,zoom:zoom,fillUnknown2D:fillUnknown2D,progress:regional)
                MappingNativeEngine.releaseRasters();try fm.removeItem(at:raster)
                let files=try regularFiles(block).filter{$0 != checkpoint};var saved:[SavedFile]=[]
                for (number,file) in files.enumerated(){let bytes=Int64(try file.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0)
                    let hash=try MappingFileIO.sha(file){done in try regional("Saving file \(number+1)/\(files.count) · \(MappingProgressSnapshot.bytes(done,total:bytes))",1)}
                    saved.append(SavedFile(path:try MappingFileIO.relative(file,to:block),sha256:hash,bytes:bytes))
                }
                try MappingFileIO.json(Checkpoint(fingerprint:fingerprint+"-refined-ray-coverage-photo-seams-v5",files:saved),to:checkpoint)
                if g.approximate {approximateRegions+=1}
                regionReports.append(Self.regionReport(index:index,g:g,surface:block.appendingPathComponent("surface/surface.json")))
                supported.formUnion(g.used.map{g.digests[$0]});nominalCalibration = nominalCalibration || (g.adjustment.last ?? 0)<6
            } else {
                let g=try PropertyListDecoder().decode(MappingGeometryResult.self,from:Data(contentsOf:block.appendingPathComponent("geometry.plist")))
                if g.approximate {approximateRegions+=1}
                regionReports.append(Self.regionReport(index:index,g:g,surface:block.appendingPathComponent("surface/surface.json")))
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
        let filled=regionReports.filter{($0["fillUnknown2DAreas"] as? Bool)==true}
        let estimatedCells=filled.reduce(0){$0+(($1["estimatedFillCells"] as? Int) ?? 0)}
        let estimatedAssignment=filled.reduce(0){$0+(($1["estimatedFillAssignmentCells"] as? Int) ?? 0)}
        let assignmentCells=filled.reduce(0){$0+(($1["assignmentSurfaceCells"] as? Int) ?? 0)}
        let fillFraction=assignmentCells>0 ? Double(estimatedAssignment)/Double(assignmentCells):0
        let fillRadius=filled.compactMap{$0["fillRadiusMeters"] as? Double}.max()
        let fillNote=filled.isEmpty ? "Unknown depth remains transparent. " : "Cells without measured depth inside photo coverage use estimated heights and are marked as estimated (2D only; 3D stays measured). Areas outside photo coverage remain transparent. "
        var layer:[String:Any]=["regionReports":regionReports,"reconstructionMode":approximateRegions>0 ? "approximate-plane":"measured-surface","approximateRegionCount":approximateRegions,"photogrammetric3D":approximateRegions==0,"scaleMode":nominalCalibration ? "nominal-calibration":"laser-anchored","scaleWarning":scaleWarning,"positioningMode":lowerConfidence ? "gnss-assisted":"fixed-rtk","positioningWarning":positioningWarning,"type":"r2c_mapping_layer","version":1,"layerId":resultID,"missionId":missionID,"assignmentName":plan["title"] ?? "Map", "cameraProfileId":plan["cameraProfileId"] ?? "","incidentId":plan["incidentId"] ?? "","assignmentId":plan["assignmentId"] ?? "","title":plan["title"] ?? "Mission","profile":"daylight-rgb","tileScheme":"xyz","minZoom":14,"maxZoom":zoom,"bounds":boundsDegrees,"tileUrlTemplate":"http://127.0.0.1:8765/local-layer/\(resultID)/tiles/{Z}/{X}/{Y}.png","photoCount":supported.count,"selectedPhotoCount":photos.count,"excludedPhotoCount":photos.count-supported.count,"captureTimeLocal":photos.first?.capturedAt ?? "","captureEndTimeLocal":photos.last?.capturedAt ?? "","processedAt":ISO8601DateFormatter().string(from:Date()),"processingSeconds":Int(Date().timeIntervalSince(started)),"engine":"regions-v13-best-effort-mesh04","regionCount":regions.count,"surfaceModel":["type":"r2c_surface_collection","path":"surface/collection.json","regionCount":regions.count,"meshSpacingMeters":0.4],"assignmentBoundary":ring,"fillUnknown2DAreas":!filled.isEmpty,"quality":recoveryWarning+"\(supported.count) of \(photos.count) originals support \(regions.count) overlapping reconstructed regions. "+fillNote+"Visible canopy surface; not bare earth."+positioningWarning+scaleWarning]
        if !filled.isEmpty {
            layer["estimatedFillCells"]=estimatedCells
            layer["estimatedFillFraction"]=fillFraction
            if let fillRadius {layer["fillRadiusMeters"]=fillRadius}
        }
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
        try checkBudget()
        let destination=root.appendingPathComponent("results/\(resultID)")
        try fm.createDirectory(at:destination.deletingLastPathComponent(),withIntermediateDirectories:true)
        try fm.moveItem(at:result,to:destination)
        // After move, budget checks must use the published destination — never the deleted publishing URL.
        budgetRoot=destination
        budgetEnabled=false
        try ensureCancel()
        try reportPhase(MappingProgressPhases.publish, local:0)
        processingLog.debug("publish layerId=\(resultID.prefix(8), privacy: .public)")
        if let lastPhase {
            let endedMs=Int64(Date().timeIntervalSince(phaseStarted)*1000)
            processingLog.debug("phase end id=\(lastPhase, privacy: .public) elapsedMs=\(endedMs, privacy: .public) photos=\(photoCount, privacy: .public)")
            MappingProgressPhases.Costs.record(supportRoot:supportRoot, phaseId:lastPhase, elapsedMs:endedMs, photoCount:photoCount)
        }
        if let encoded=try? JSONSerialization.data(withJSONObject:["active":false,"stage":"Map ready","layerId":resultID,"photoCount":photos.count,"progress":1.0,"updatedAt":ISO8601DateFormatter().string(from:Date())],options:[.sortedKeys]) {
            try? encoded.write(to:root.appendingPathComponent("processing-status.json"),options:.atomic)
        }
        finishedCleanly = true
        try ensureCancel()
        progress(MappingProgressSnapshot(stage:"Map ready",fraction:1,startedAt:started,activityAt:Date(),estimateSummary:estimateSummary,remainingSeconds:0))
        return destination
    }
    private static func regionReport(index: Int, g: MappingGeometryResult, surface: URL) -> [String:Any] {
        var report:[String:Any]=["index":index,"reconstructionMode":g.approximate ? "approximate-plane":"measured-surface","registeredPhotoCount":g.used.count,"featureTracks":g.featureTracks,"measuredStereoCells":g.measuredCells,"knownProjectionCells":g.heights.filter{$0.isFinite}.count,"surfaceCells":g.heights.count,"reprojectionRmsSamplePixels":g.approximate ? NSNull():g.adjustment[7] as Any,"fillUnknown2DAreas":false]
        if let data=try? Data(contentsOf:surface),let meta=try? JSONSerialization.jsonObject(with:data) as? [String:Any],(meta["fillUnknown2DAreas"] as? Bool)==true {
            report["fillUnknown2DAreas"]=true
            for key in ["estimatedFillCells","estimatedFillAssignmentCells","assignmentSurfaceCells","estimatedFillFraction","fillRadiusMeters"] {
                if let value=meta[key] {report[key]=value}
            }
        }
        return report
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
