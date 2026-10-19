import Foundation
import R2CCore

public struct MappingLocalMission: Identifiable, Sendable {
    public let id, title, incidentID, date: String
    public var plannedAt: String = ""
    public var cameraLabel: String = "Camera unavailable"
    public let photos: [MappingOriginal]
    public let results: [URL]
    public let planningBytes, photoBytes, tileBytes, modelBytes, workBytes, exportBytes, controllerBytes, metadataBytes: Int64
    public var totalBytes:Int64 {planningBytes+photoBytes+tileBytes+modelBytes+workBytes+exportBytes+controllerBytes+metadataBytes}
    public let latitude, longitude: Double?
    public var status: String { !results.isEmpty ? "Map ready" : photos.isEmpty ? "Map prepared" : "Images captured" }
}

/// Every mission owns its plan, byte-preserved originals, resumable work and published results.
/// All operations run on a serial worker, never the main UI thread.
public final class MappingMissionStore: @unchecked Sendable {
    public let root: URL
    public init(root: URL) throws {
        try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true);self.root=root.resolvingSymlinksInPath().standardizedFileURL
    }
    public static func applicationRoot() throws -> URL {
        try FileManager.default.url(for:.applicationSupportDirectory,in:.userDomainMask,appropriateFor:nil,create:true).appendingPathComponent("mapping-missions",isDirectory:true)
    }
    /// iOS can relocate the data container when an app is updated. Resolve only
    /// the validated mission/result suffix against the current container.
    public static func savedResult(_ reference: String, layerID: String, root: URL) -> URL? {
        let parts=reference.split(separator:"/",omittingEmptySubsequences:true)
        guard !parts.contains("."),!parts.contains(".."),parts.count>=3,
              let layer=UUID(uuidString:layerID),parts.last==Substring(layer.uuidString.lowercased()),
              parts[parts.count-2]=="results",
              let mission=UUID(uuidString:String(parts[parts.count-3])) else{return nil}
        if parts.count>3 {guard parts[parts.count-4]=="mapping-missions" else{return nil}}
        return root.appendingPathComponent(mission.uuidString.lowercased(),isDirectory:true)
            .appendingPathComponent("results",isDirectory:true)
            .appendingPathComponent(layer.uuidString.lowercased(),isDirectory:true)
    }
    public func directory(_ id: String) throws -> URL {
        guard let uuid=UUID(uuidString:id),uuid.uuidString.lowercased()==id.lowercased() else {throw MappingMission.Failure("Invalid mapping mission identifier.")}
        return root.appendingPathComponent(uuid.uuidString.lowercased(),isDirectory:true)
    }
    public func savePlan(_ data: Data, boundary: Data) throws -> URL {
        guard let value=try JSONSerialization.jsonObject(with:data) as? [String:Any],let id=value["missionId"] as? String else {throw MappingMission.Failure("Invalid mission plan.")}
        let folder=try directory(id);try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
        let plan=folder.appendingPathComponent("mission.json")
        if FileManager.default.fileExists(atPath:plan.path),try Data(contentsOf:plan) != data {
            throw MappingMission.Failure("This mission is already saved. Create a new mission to change its capture plan.")
        }
        try data.write(to:plan,options:.atomic);try boundary.write(to:folder.appendingPathComponent("boundary.kml"),options:.atomic);return folder
    }
    public func originals(_ id: String) throws -> [MappingOriginal] {
        let folder=try directory(id).appendingPathComponent("inventory")
        guard FileManager.default.fileExists(atPath:folder.path) else{return []}
        return try FileManager.default.contentsOfDirectory(at:folder,includingPropertiesForKeys:nil).filter{$0.pathExtension=="json"}.map{try JSONDecoder().decode(MappingOriginal.self,from:Data(contentsOf:$0))}.sorted{$0.capturedAt==$1.capturedAt ? $0.name<$1.name:$0.capturedAt<$1.capturedAt}
    }
    @discardableResult public func importOriginal(_ source: URL, missionID: String, originalName: String? = nil, progress: (String)throws->Void) throws -> MappingOriginal {
        let folder=try directory(missionID),originals=folder.appendingPathComponent("originals"),inventory=folder.appendingPathComponent("inventory")
        guard FileManager.default.fileExists(atPath:folder.appendingPathComponent("mission.json").path) else {throw MappingMission.Failure("Save the mission before importing photos.")}
        let accessed=source.startAccessingSecurityScopedResource();defer{if accessed{source.stopAccessingSecurityScopedResource()}}
        try FileManager.default.createDirectory(at:originals,withIntermediateDirectories:true);try FileManager.default.createDirectory(at:inventory,withIntermediateDirectories:true)
        let size=Int64(try source.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0)
        let digest=try MappingFileIO.sha(source){try progress("Checking \(source.lastPathComponent) · \(MappingProgressSnapshot.bytes($0,total:size))")}
        let plan=try JSONSerialization.jsonObject(with:Data(contentsOf:folder.appendingPathComponent("mission.json"))) as? [String:Any]
        let record=try MappingOriginal.read(source,digest:digest,name:originalName ?? source.lastPathComponent,cameraProfileID:plan?["cameraProfileId"] as? String ?? "m4td.visible.wide")
        let destination=originals.appendingPathComponent(digest+".jpg"),temporary=originals.appendingPathComponent(digest+".partial")
        if !FileManager.default.fileExists(atPath:destination.path) {
            guard try self.originals(missionID).count<MappingProcessingPolicy.maximumPhotos else {throw MappingMission.Failure("This mission exceeds 10,000 originals.")}
            defer{try? FileManager.default.removeItem(at:temporary)}
            try MappingFileIO.copy(source,to:temporary){try progress("Importing \(source.lastPathComponent) · \(MappingProgressSnapshot.bytes($0,total:size))")}
            guard try MappingFileIO.sha(temporary,progress:{try progress("Verifying saved original · \(MappingProgressSnapshot.bytes($0,total:size))")})==digest else {throw MappingMission.Failure("Original changed during import. Retry the transfer.")}
            try FileManager.default.moveItem(at:temporary,to:destination)
        }
        try MappingFileIO.json(record,to:inventory.appendingPathComponent(digest+".json"));return record
    }
    public func completedSelection(_ id:String)throws->[String] {
        let file=try directory(id).appendingPathComponent("completed-selection.json")
        guard FileManager.default.fileExists(atPath:file.path) else{return []}
        let available=Set(try originals(id).map(\.digest))
        return try JSONDecoder().decode([String].self,from:Data(contentsOf:file)).filter{available.contains($0)}
    }
    public func completeSelection(_ digests:[String],missionID:String)throws {
        let available=Set(try originals(missionID).map(\.digest))
        guard !digests.isEmpty,Set(digests).count==digests.count,Set(digests).isSubset(of:available) else{throw MappingMission.Failure("Some originals are not verified yet.")}
        try MappingFileIO.json(digests,to:directory(missionID).appendingPathComponent("completed-selection.json"))
    }
    public func list(incidentID: String? = nil, newestFirst: Bool) throws -> [MappingLocalMission] {
        let folders=try FileManager.default.contentsOfDirectory(at:root,includingPropertiesForKeys:nil)
        var records:[MappingLocalMission]=[]
        for folder in folders where UUID(uuidString:folder.lastPathComponent) != nil {
            let plan=folder.appendingPathComponent("mission.json")
            guard FileManager.default.fileExists(atPath:plan.path) else {continue}
            guard let value=try JSONSerialization.jsonObject(with:Data(contentsOf:plan)) as? [String:Any],(incidentID == nil || value["incidentId"] as? String == incidentID) else {continue}
            let photos=try originals(folder.lastPathComponent)
            let resultRoot=folder.appendingPathComponent("results")
            let results=FileManager.default.fileExists(atPath:resultRoot.path) ? try FileManager.default.contentsOfDirectory(at:resultRoot,includingPropertiesForKeys:nil).filter{FileManager.default.fileExists(atPath:$0.appendingPathComponent("layer.json").path)} : []
            var planning:Int64=0,original:Int64=0,tiles:Int64=0,models:Int64=0,work:Int64=0,exports:Int64=0,controller:Int64=0,metadata:Int64=0
            if let files=FileManager.default.enumerator(at:folder,includingPropertiesForKeys:[.isRegularFileKey,.fileSizeKey]) {
                for case let file as URL in files {let info=try file.resourceValues(forKeys:[.isRegularFileKey,.fileSizeKey]);guard info.isRegularFile==true else{continue};let bytes=Int64(info.fileSize ?? 0),relative=String(file.path.dropFirst(folder.path.count+1))
                    if relative.hasPrefix("originals/"){original+=bytes}
                    else if relative.hasPrefix("work/"){work+=bytes}
                    else if relative.hasPrefix("incoming/") || relative.hasPrefix("cloud/"){controller+=bytes}
                    else if relative.hasPrefix("results/"){if file.pathExtension=="png"{tiles+=bytes}else if relative.contains("/surface/"){models+=bytes}else if file.pathExtension=="zip"{exports+=bytes}else{metadata+=bytes}}
                    else if ["mission.json","boundary.kml","mission.kmz","route-summary.json"].contains(relative){planning+=bytes}else{metadata+=bytes}
                }
            }
            let feature=value["feature"] as? [String:Any],geometry=feature?["geometry"] as? [String:Any],ring=(geometry?["coordinates"] as? [[[Double]]])?.first
            let latitude=ring.flatMap{$0.isEmpty ? nil:$0.map{$0[1]}.reduce(0,+)/Double($0.count)},longitude=ring.flatMap{$0.isEmpty ? nil:$0.map{$0[0]}.reduce(0,+)/Double($0.count)}
            records.append(MappingLocalMission(id:folder.lastPathComponent,title:value["title"] as? String ?? "Mission",incidentID:value["incidentId"] as? String ?? "",date:photos.first(where:{!$0.capturedAt.isEmpty})?.capturedAt ?? value["createdAt"] as? String ?? "Time unavailable",plannedAt:value["createdAt"] as? String ?? "",cameraLabel:MappingResolution.cameraLabel(profileID:value["cameraProfileId"] as? String,fallbackName:value["cameraName"] as? String),photos:photos,results:results,planningBytes:planning,photoBytes:original,tileBytes:tiles,modelBytes:models,workBytes:work,exportBytes:exports,controllerBytes:controller,metadataBytes:metadata,latitude:latitude,longitude:longitude))
        }
        return records.sorted{newestFirst ? $0.date>$1.date:$0.date<$1.date}
    }
    public func deleteResults(_ id: String) throws {
        let folder=try directory(id)
        for name in ["results","work"]{let path=folder.appendingPathComponent(name);if FileManager.default.fileExists(atPath:path.path){try FileManager.default.removeItem(at:path)}}
    }
    /// UI must obtain explicit original-photo deletion confirmation before invoking this method.
    public func deleteMission(_ id: String, originalsConfirmed: Bool) throws {
        guard originalsConfirmed else{throw MappingMission.Failure("Confirm deletion of original photos first.")}
        try FileManager.default.removeItem(at:directory(id))
    }
}
