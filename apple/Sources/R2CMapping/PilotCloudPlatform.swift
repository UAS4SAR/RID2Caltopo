import Foundation
import R2CCore

public struct PilotCloudCredentials:Codable,Sendable {
    public let appId:String,appKey:String,appLicense:String
    public init(appId:String,appKey:String,appLicense:String)throws{guard appId.range(of:"^[0-9]{1,12}$",options:.regularExpression) != nil,(16...256).contains(appKey.count),(32...8192).contains(appLicense.count) else{throw MappingMission.Failure("Enter the Cloud API app ID, app key and basic license from DJI Developer.")};self.appId=appId;self.appKey=appKey;self.appLicense=appLicense}
}
final class PilotCloudPlatform:@unchecked Sendable {
    let mission:[String:Any],id:String,store:MappingMissionStore,root:URL,route:MappingFlightRoute,storage:PilotCloudStorage,credentials:PilotCloudCredentials
    var base="",token="",mqttUser="",mqttPassword=""
    let expires:Date
    var flags:[String:Bool]=[:],status="Waiting for Pilot 2",topology:[[String:Any]]=[],favorited=false
    private let lock=NSRecursiveLock()
    private let changed:@Sendable(String,[String:Bool])->Void
    init(store:MappingMissionStore,id:String,credentials:PilotCloudCredentials,expires:Date,changed:@escaping @Sendable(String,[String:Bool])->Void)throws{
        self.store=store;self.id=id;self.credentials=credentials;self.expires=expires;self.changed=changed;root=try store.directory(id);let data=try Data(contentsOf:root.appendingPathComponent("mission.json"));mission=try pilotObject(root.appendingPathComponent("mission.json"));route=try MappingFlightRoute(mission:data);storage=try PilotCloudStorage(root:root.appendingPathComponent("cloud/storage"),prefix:id,expires:expires)
        try FileManager.default.createDirectory(at:root.appendingPathComponent("cloud/receipts"),withIntermediateDirectories:true)
        let routeFile=root.appendingPathComponent("mission.kmz")
        if FileManager.default.fileExists(atPath:routeFile.path) {
            let old=try Data(contentsOf:routeFile),corrected=try route.correctingCameraSelection(in:old)
            if corrected != old{try corrected.write(to:routeFile,options:.atomic)}
        }else{try route.kmz().write(to:routeFile,options:.atomic)}
        try recoverRetainedOriginals()
    }
    func update(_ message:String){lock.lock();defer{lock.unlock()};status=message;changed(status,flags)}
    func flag(_ name:String,_ value:Bool){lock.lock();defer{lock.unlock()};flags[name]=value;changed(status,flags)}
    func snapshot()throws->[String:Any]{lock.lock();defer{lock.unlock()};let originals=try store.originals(id),receipts=try self.receipts();return ["status":status,"flags":flags,"photoCount":originals.count,"storedBytes":originals.reduce(Int64(0)){$0+Int64((try? root.appendingPathComponent("originals/\($1.digest).jpg").resourceValues(forKeys:[.fileSizeKey]).fileSize) ?? 0)},"storedUploadCount":(try? FileManager.default.contentsOfDirectory(atPath:storage.root.appendingPathComponent("objects").path).filter{$0.hasSuffix(".jpg")}.count) ?? 0,"unmatchedCount":receipts.filter{$0["assigned"] as? Bool != true}.count]}
    func bootstrap()throws->[String:Any]{var summary=try JSONSerialization.jsonObject(with:route.summary()) as! [String:Any];summary["groundResolutionCmPerPixel"]=(mission["parameters"] as? [String:Any])?["desiredGroundResolutionCmPerPixel"] ?? 0
        let ws=base.replacingOccurrences(of:"http:",with:"ws:")
        return ["license":["appId":credentials.appId,"appKey":credentials.appKey,"appLicense":credentials.appLicense],"workspaceId":id,"workspaceName":mission["mapName"] ?? mission["title"] ?? "Mapping mission","assignmentName":mission["title"] ?? "Mapping mission","api":["host":base,"token":token],"ws":["host":ws+"/cloud/ws","token":token,"connectCallback":"r2cWsConnected"],"thing":["host":ws+"/cloud/mqtt?x-auth-token="+token,"username":mqttUser,"password":mqttPassword,"connectCallback":"r2cMqttConnected"],"route":summary,"expiresAt":Int64(expires.timeIntervalSince1970*1000)]
    }
    func api(_ r:PilotRequest)throws->PilotResponse {lock.lock();defer{lock.unlock()}
        if r.path=="/cloud/bootstrap",r.method=="GET"{return try .success(bootstrap())}
        if r.path=="/cloud/status",r.method=="GET"{return try .success(snapshot())}
        if r.path=="/cloud/report",r.method=="POST" {let value=try r.object(),allowed:Set<String>=["licenseVerified","apiLoaded","mediaLoaded","missionLoaded","automaticOriginals","downloadOwner"];guard Set(value.keys).isSubset(of:allowed),value.values.allSatisfy({$0 is Bool}) else{throw MappingMission.Failure("Unsupported connection report.")};for (k,v) in value{flags[k]=v as? Bool};if flags["missionLoaded"]==true && flags["mediaLoaded"]==true{update(flags["automaticOriginals"]==true && flags["downloadOwner"]==true ? "Pilot 2 mission library and automatic original-photo return ready":"Pilot 2 photo return needs attention; reconnect before starting another mission")}else{update(status)};return try .success()}
        let parts=r.path.split(separator:"/").map(String.init);guard parts.count>=6,parts[1]=="api",parts[2]=="v1",parts[3]=="workspaces",parts[4]==id else{return .failure("Unknown platform workspace.",status:404)};let action=parts.dropFirst(5).joined(separator:"/")
        switch parts[0] {
        case "storage":if action=="sts",r.method=="POST"{return try .success(storage.credentials(base))}
        case "wayline":
            if action=="waylines",r.method=="GET" {let page=max(1,Int(r.query["page"] ?? "") ?? 1),pageSize=min(100,max(1,Int(r.query["page_size"] ?? "") ?? 20)),first=route.coordinate(route.legs[0].start)
                let entry:[String:Any]=["id":id,"name":(mission["title"] as? String ?? "Mission")+" · "+((mission["profile"] as? String)=="thermal" ? "IR":"Visible"),"drone_model_key":"0-100-1","payload_model_keys":["1-99-0"],"template_types":[0],"favorited":favorited,"action_type":0,"update_time":Int64(((try? root.appendingPathComponent("mission.kmz").resourceValues(forKeys:[.contentModificationDateKey]).contentModificationDate) ?? Date()).timeIntervalSince1970*1000),"user_name":"R2C","start_wayline_point":["start_latitude":first[1],"start_lontitude":first[0]]]
                let include=(r.query["favorited"] != "true" || favorited) && (r.query["drone_model_keys"]?.contains("0-100-1") ?? true)
                return try .success(["list":page==1 && include ? [entry]:[],"pagination":["page":page,"page_size":pageSize,"total":include ? 1:0]])
            }
            if action=="waylines/\(id)/url",r.method=="GET"{update("Pilot 2 requested the mapping route");return PilotResponse(status:302,headers:["Location":base+"/cloud/route.kmz?ticket="+token])}
            if action=="favorites",["POST","DELETE"].contains(r.method){favorited=r.method=="POST";return try .success()}
            if action=="waylines/duplicate-names"{let title=mission["title"] as? String ?? "";return try .success(r.query["name"]==title ? [title]:[])}
        case "manage":if action=="devices/topologies",r.method=="GET"{return try .success(["list":topology])}
        case "media":
            let value=try r.object()
            if action=="files/tiny-fingerprints" {let existing=Set(try verifiedReceipts().compactMap{$0["tinyFingerprint"] as? String});return try .success(["tiny_fingerprints":(value["tiny_fingerprints"] as? [String] ?? []).filter{existing.contains($0)}])}
            if action=="fast-upload" {let fingerprint=value["fingerprint"] as? String ?? "";if !fingerprint.isEmpty,try verifiedReceipts().contains(where:{$0["fingerprint"] as? String==fingerprint}){return try .success()};return .failure("The original needs upload.",status:200)}
            if action=="upload-callback"{return try uploaded(value)}
            if action=="group-upload-callback"{guard let count=value["file_count"] as? Int,let uploaded=value["file_uploaded_count"] as? Int,(0...10000).contains(count),(0...count).contains(uploaded),let group=value["file_group_id"] as? String,group.count<=256 else{throw MappingMission.Failure("Invalid upload group.")};try reconcile(["fileGroupId":group,"expectedCount":count,"reportedUploaded":uploaded]);return try .success()}
        default:break
        };return .failure("Unsupported platform operation.",status:404)
    }
    func routeResponse()->PilotResponse{PilotResponse(type:"application/vnd.google-earth.kmz",file:root.appendingPathComponent("mission.kmz"),headers:["Content-Disposition":"attachment; filename=\"R2C-\(id.prefix(8)).kmz\""])}
    private func receipts()throws->[[String:Any]]{try FileManager.default.contentsOfDirectory(at:root.appendingPathComponent("cloud/receipts"),includingPropertiesForKeys:nil).filter{$0.pathExtension=="json"}.map{try pilotObject($0)}}
    // A retained extra camera image is already local, even though it is not a reconstruction input.
    private func verifiedReceipts()throws->[[String:Any]] {
        let available=Set(try store.originals(id).map(\.digest))
        return try receipts().filter { value in
            guard let digest=value["sha256"] as? String else{return false}
            if value["assigned"] as? Bool==true{return available.contains(digest)}
            guard value["isOriginal"] as? Bool==true,value["assignmentReason"] as? String=="camera_mismatch",let key=value["objectKey"] as? String else{return false}
            return storage.hasRetainedObject(key,digest:digest)
        }
    }
    // Only camera-mismatch receipts already passed the original and mission-tag checks.
    private func recoverRetainedOriginals() throws {
        for var receipt in try receipts() where receipt["assigned"] as? Bool != true && receipt["isOriginal"] as? Bool == true && receipt["assignmentReason"] as? String == "camera_mismatch" {
            guard let key=receipt["objectKey"] as? String,let digest=receipt["sha256"] as? String,let name=receipt["name"] as? String else{continue}
            let file=try storage.object(key)
            guard (try? MappingFileIO.sha(file,progress:{_ in})) == digest else{continue}
            // Import validates dimensions, focal length and exposure metadata before acceptance.
            do{_ = try store.importOriginal(file,missionID:id,originalName:name){_ in}}catch{continue}
            receipt["assigned"]=true;receipt["assignmentReason"]="matched"
            try pilotJSON(receipt,to:root.appendingPathComponent("cloud/receipts/"+pilotHash(Data(key.utf8))+".json"))
        }
        if let report=try? pilotObject(root.appendingPathComponent("cloud/group-upload.json")){try reconcile(report)}
    }
    private func uploaded(_ value:[String:Any])throws->PilotResponse {
        if let result=value["result"] as? Int,result != 0{return .failure("Original upload was not completed.",status:200)}
        guard let key=value["object_key"] as? String,let name=value["name"] as? String,(1...180).contains(name.count),!name.contains("/"),!name.contains("\\"),!name.unicodeScalars.contains(where:{$0.value<32}) else{throw MappingMission.Failure("Invalid original callback.")}
        let file=try storage.object(key),digest=try storage.digest(key),ext=value["ext"] as? [String:Any] ?? [:],original=ext["is_original"] as? Bool==true,group=String((ext["file_group_id"] as? String ?? "").prefix(256)),path=value["path"] as? String ?? ""
        guard FileManager.default.fileExists(atPath:file.path),try MappingFileIO.sha(file,progress:{_ in})==digest else{throw MappingMission.Failure("Original checksum is not verified.")}
        let tagged=name.localizedCaseInsensitiveContains(route.photoTag) || path.localizedCaseInsensitiveContains(route.photoTag)
        let input=try FileHandle(forReadingFrom:file);let head=String(decoding:try input.read(upToCount:2*1024*1024) ?? Data(),as:UTF8.self);try input.close()
        let sensor=head.contains("InfraredCamera") ? "thermal":(head.contains("ZoomCamera") || head.contains("TeleCamera")) ? "visible-zoom":head.contains("WideCamera") ? "visible-wide":"unknown",expected=(mission["profile"] as? String)=="thermal" ? "thermal":(["m4td.visible.medium-tele","m4td.visible.tele"].contains(mission["cameraProfileId"] as? String ?? "") ? "visible-zoom":"visible-wide")
        let matches=sensor==expected || sensor=="unknown" || (["visible-wide","visible-zoom"].contains(expected) && ["visible-wide","visible-zoom"].contains(sensor));var assigned=original && tagged && matches,reason = !original ? "not_original":!tagged ? "mission_tag_missing":!matches ? "camera_mismatch":"matched"
        if assigned {do{try store.importOriginal(file,missionID:id,originalName:name){_ in}}catch{assigned=false;reason="metadata_unavailable";update("Original retained; mapping metadata could not be verified: \(error.localizedDescription)")}}
        try pilotJSON(["name":name,"objectKey":key,"sha256":digest,"assigned":assigned,"isOriginal":original,"groupId":group,"fingerprint":String((value["fingerprint"] as? String ?? "").prefix(256)),"tinyFingerprint":String((ext["tinny_fingerprint"] as? String ?? "").prefix(256)),"sensor":sensor,"assignmentReason":reason],to:root.appendingPathComponent("cloud/receipts/"+pilotHash(Data(key.utf8))+".json"))
        if assigned{update("Original photo returned and verified by Pilot 2")}else if reason=="camera_mismatch"{update(sensor=="thermal" && expected=="visible-wide" ? "Extra IR photo retained separately; visible mapping originals remain available":"Returned photo retained separately; its camera differs from this mission’s selected camera")}else if reason != "metadata_unavailable"{update("Returned photo retained separately; its original flag or mission tag did not match")}
        if let report=try? pilotObject(root.appendingPathComponent("cloud/group-upload.json")),report["fileGroupId"] as? String==group{try reconcile(report)}
        return try .success(key)
    }
    private func reconcile(_ report:[String:Any])throws {var report=report;let group=report["fileGroupId"] as? String ?? "",count=report["expectedCount"] as? Int ?? 0,uploaded=report["reportedUploaded"] as? Int ?? 0,available=Set(try store.originals(id).map(\.digest))
        let returned=try receipts().filter{r in guard r["groupId"] as? String==group,r["isOriginal"] as? Bool==true else{return false};if r["assigned"] as? Bool==true{return available.contains(r["sha256"] as? String ?? "")};return ["camera_mismatch","metadata_unavailable"].contains(r["assignmentReason"] as? String ?? "") && (try? storage.digest(r["objectKey"] as? String ?? ""))==r["sha256"] as? String}
        let matched=Array(Set(returned.filter{$0["assigned"] as? Bool==true}.compactMap{$0["sha256"] as? String})).sorted(),ready=count>0 && uploaded==count && returned.count==count && !matched.isEmpty
        report["verifiedCount"]=matched.count;report["returnedOriginalCount"]=returned.count;report["ready"]=ready;try pilotJSON(report,to:root.appendingPathComponent("cloud/group-upload.json"));if ready{try store.completeSelection(matched,missionID:id)};flags["originalsComplete"]=ready;update(ready ? "All \(count) returned originals stored; \(matched.count) selected-camera mission originals verified locally":"\(uploaded)/\(count) reported uploads; \(matched.count) selected-camera mission originals verified")
    }
    func mqtt(_ topic:String,_ bytes:Data,notify:([String:Any])->Void)throws->[(String,Data)]{lock.lock();defer{lock.unlock()};guard let value=try? JSONSerialization.jsonObject(with:bytes) as? [String:Any] else{return []};let method=value["method"] as? String ?? "",data=value["data"] as? [String:Any] ?? [:],parts=topic.split(separator:"/");guard parts.count==4 else{return []};let gateway=String(parts[2])
        func reply(_ data:[String:Any])throws->Data{try JSONSerialization.data(withJSONObject:["bid":value["bid"] ?? "","tid":value["tid"] ?? "","timestamp":Int64(Date().timeIntervalSince1970*1000),"method":method,"data":data])}
        if method=="update_topo",topic.hasSuffix("/status") {
            func device(_ sn:String,_ domain:Int,_ type:Int,_ sub:Int)->[String:Any]{["sn":String(sn.prefix(128)),"online_status":true,"device_model":["key":"\(domain)-\(type)-\(sub)","domain":String(domain),"type":String(type),"sub_type":String(sub)],"device_callsign":domain==0 ? "Mapping aircraft":"Mapping controller","user_id":id,"user_callsign":"R2C"]}
            topology=[["hosts":(data["sub_devices"] as? [[String:Any]] ?? []).prefix(64).map{device($0["sn"] as? String ?? "",0,$0["type"] as? Int ?? 100,$0["sub_type"] as? Int ?? 1)},"parents":[device(gateway,2,data["type"] as? Int ?? 174,data["sub_type"] as? Int ?? 0)]]];update("Pilot 2 controller registered on the local tablet");notify(["biz_code":"device_update_topo","version":"1.0","timestamp":Int64(Date().timeIntervalSince1970*1000),"data":["sn":gateway]]);return [("sys/product/\(gateway)/status_reply",try reply(["result":0]))]
        }
        if topic.hasSuffix("/requests"){return [(topic+"_reply",try reply(method=="storage_config_get" ? ["result":0,"output":storage.credentials(base)]:["result":100]))]}
        if topic.hasSuffix("/events"),value["need_reply"] as? Int==1{return [(topic+"_reply",try reply(["result":0]))]};return []
    }
}
