import XCTest
import CryptoKit
import Darwin
import ImageIO
import CoreGraphics
import UniformTypeIdentifiers
@testable import R2CMapping
import R2CCore

final class PilotCloudTests:XCTestCase,@unchecked Sendable {
    func testMissionLibraryAdvertisesAndFiltersTheSelectedAircraft() async throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);defer{try? FileManager.default.removeItem(at:root)}
        let store=try MappingMissionStore(root:root)
        for (cameraID,modelKey,payloadKey) in [("m4t.visible.wide","0-99-1","1-89-0"),("m30.visible.wide","0-67-0","1-52-0"),("m30t.visible.wide","0-67-1","1-53-0")] {
            let profile=try XCTUnwrap(MappingResolution.profiles.first{$0.id==cameraID})
            let mission=try MappingMission(feature:Data(#"{"id":"test","properties":{"class":"Assignment","title":"MapTest"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.9995,39],[-120.9995,39.0005],[-121,39.0005],[-121,39]]]}}"#.utf8),incidentID:"TEST")
            let id=mission.id.uuidString.lowercased()
            _ = try store.savePlan(mission.json(cameraProfile:profile,desiredGsdCmPerPixel:3,canopyHeightMeters:20),boundary:mission.boundaryKML())
            let server=try PilotCloudServer(store:store,missionID:id,host:"127.0.0.1",credentials:PilotCloudCredentials(appId:"12345",appKey:String(repeating:"x",count:32),appLicense:String(repeating:"y",count:64)),port:0,assets:{_ in Data()},changed:{_,_ in})
            server.start();defer{server.close()}
            _ = try server.platform.mqtt("sys/product/RC/status",JSONSerialization.data(withJSONObject:["method":"update_topo","data":["sub_devices":[["sn":"SYNTHETIC"]]]]),notify:{_ in})
            let hosts=try XCTUnwrap(server.platform.topology.first?["hosts"] as? [[String:Any]])
            XCTAssertEqual((hosts.first?["device_model"] as? [String:String])?["key"],modelKey)
            let parents=try XCTUnwrap(server.platform.topology.first?["parents"] as? [[String:Any]])
            XCTAssertEqual((parents.first?["device_model"] as? [String:String])?["key"],cameraID.hasPrefix("m30") ? "2-119-0":"2-174-0")
            func request(_ path:String,body:Data?=nil,token:String="")async throws->[String:Any] {
                var r=URLRequest(url:URL(string:server.url+path)!);r.httpMethod=body==nil ? "GET":"POST";r.httpBody=body;r.timeoutInterval=10
                r.setValue(token,forHTTPHeaderField:"X-Auth-Token");r.setValue("application/json",forHTTPHeaderField:"Content-Type")
                let (data,response)=try await URLSession.shared.data(for:r);XCTAssertEqual((response as? HTTPURLResponse)?.statusCode,200)
                return try XCTUnwrap((JSONSerialization.jsonObject(with:data) as? [String:Any])?["data"] as? [String:Any])
            }
            let login=try await request("/cloud/login",body:JSONSerialization.data(withJSONObject:["code":server.code]))
            let token=try XCTUnwrap(login["token"] as? String),path="/wayline/api/v1/workspaces/\(id)/waylines"
            let response=try await request(path+"?drone_model_keys="+modelKey,token:token)
            let list=try XCTUnwrap(response["list"] as? [[String:Any]])
            XCTAssertEqual(list.count,1)
            XCTAssertEqual(list.first?["drone_model_key"] as? String,modelKey)
            XCTAssertEqual(list.first?["payload_model_keys"] as? [String],[payloadKey])
            let wrong=try await request(path+"?drone_model_keys=0-100-1",token:token)
            XCTAssertEqual((wrong["list"] as? [[String:Any]])?.count,0)
        }
    }

    func testReopeningRecoversWideZoomLabelButKeepsInvalidReceiptsSeparate() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);defer{try? FileManager.default.removeItem(at:root)}
        let store=try MappingMissionStore(root:root)
        let mission=try MappingMission(feature:Data(#"{"type":"Feature","id":"test","properties":{"class":"Assignment","title":"MapTest"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.9995,39],[-120.9995,39.0005],[-121,39.0005],[-121,39]]]}}"#.utf8),incidentID:"TEST")
        let id=mission.id.uuidString.lowercased(),folder=try store.savePlan(mission.json(),boundary:mission.boundaryKML())
        let credentials=try PilotCloudCredentials(appId:"12345",appKey:String(repeating:"x",count:32),appLicense:String(repeating:"y",count:64))
        func open() throws -> PilotCloudPlatform {try PilotCloudPlatform(store:store,id:id,credentials:credentials,expires:Date().addingTimeInterval(3600),changed:{_,_ in})}
        let first=try open();var accepted=""
        for (index,reason,focal,corrupt) in [(0,"camera_mismatch",24,false),(1,"camera_mismatch",70,false),(2,"mission_tag_missing",24,false),(3,"not_original",24,false),(4,"camera_mismatch",24,true)] {
            let name=first.route.photoTag+"_\(index).jpg",key=id+"/"+name,photo=try Self.original(camera:"ZoomCamera",focal:focal),digest=pilotHash(photo)
            try photo.write(to:first.storage.object(key))
            try pilotJSON(["sha256":digest],to:first.storage.record(key))
            try pilotJSON(["name":name,"objectKey":key,"sha256":corrupt ? String(repeating:"0",count:64):digest,"assigned":false,"isOriginal":reason != "not_original","groupId":"group","assignmentReason":reason],to:folder.appendingPathComponent("cloud/receipts/"+pilotHash(Data(key.utf8))+".json"))
            if index==0{accepted=digest}
        }
        _ = try open()
        XCTAssertEqual(try store.originals(id).map(\.digest),[accepted])
        _ = try open()
        XCTAssertEqual(try store.originals(id).count,1)
    }
    func testCloseWaitsForRunningHandlerBeforeMissionDeletion() throws {
        let entered=DispatchSemaphore(value:0),release=DispatchSemaphore(value:0)
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);defer{try? FileManager.default.removeItem(at:root)}
        let store=try MappingMissionStore(root:root)
        let mission=try MappingMission(feature:Data(#"{"type":"Feature","id":"test","properties":{"class":"Assignment","title":"MapTest"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.9995,39],[-120.9995,39.0005],[-121,39.0005],[-121,39]]]}}"#.utf8),incidentID:"TEST")
        let id=mission.id.uuidString.lowercased();_ = try store.savePlan(mission.json(),boundary:mission.boundaryKML())
        let server=try PilotCloudServer(store:store,missionID:id,host:"127.0.0.1",credentials:PilotCloudCredentials(appId:"12345",appKey:String(repeating:"x",count:32),appLicense:String(repeating:"y",count:64)),port:0,assets:{_ in entered.signal();_ = release.wait(timeout:.now()+10);return Data("page".utf8)},changed:{_,_ in})
        server.start();defer{release.signal();server.close()}
        let request=URLSession.shared.dataTask(with:URL(string:server.url+"/cloud")!){_,_,_ in}
        request.resume()
        XCTAssertEqual(entered.wait(timeout:.now()+5),.success)
        XCTAssertThrowsError(try server.closeAndWait(timeout:0.01))
        XCTAssertEqual(try store.list(newestFirst:true).count,1)
        release.signal()
        try server.closeAndWait(timeout:5)
        try store.deleteMission(id,originalsConfirmed:true)
        XCTAssertTrue(try store.list(newestFirst:true).isEmpty)
    }
    func testNativePairingRouteSignedOriginalAndCallbacks()async throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);defer{try? FileManager.default.removeItem(at:root)}
        let store=try MappingMissionStore(root:root)
        let mission=try MappingMission(feature:Data(#"{"type":"Feature","id":"test","properties":{"class":"Assignment","title":"MapTest"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.9995,39],[-120.9995,39.0005],[-121,39.0005],[-121,39]]]}}"#.utf8),incidentID:"TEST")
        let id=mission.id.uuidString.lowercased();_ = try store.savePlan(mission.json(),boundary:mission.boundaryKML())
        let server=try PilotCloudServer(store:store,missionID:id,host:"127.0.0.1",credentials:PilotCloudCredentials(appId:"12345",appKey:String(repeating:"x",count:32),appLicense:String(repeating:"y",count:64)),port:0,assets:{_ in Data("shared native Pilot 2 page".utf8)},changed:{_,_ in})
        server.start();defer{server.close()}
        func request(_ path:String,method:String="GET",body:Data?=nil,headers:[String:String]=[:])async throws->(Int,Data){var r=URLRequest(url:URL(string:server.url+path)!);r.httpMethod=method;r.httpBody=body;r.timeoutInterval=10;headers.forEach{r.setValue($1,forHTTPHeaderField:$0)};let (data,response)=try await URLSession.shared.data(for:r);return((response as! HTTPURLResponse).statusCode,data)}
        func value(_ data:Data)throws->[String:Any]{try XCTUnwrap((JSONSerialization.jsonObject(with:data) as? [String:Any])?["data"] as? [String:Any])}
        let denied=try await request("/cloud/bootstrap");XCTAssertEqual(denied.0,401)
        let cross=try await request("/cloud",headers:["Origin":"http://other.invalid"]);XCTAssertEqual(cross.0,403)
        let login=try await request("/cloud/login",method:"POST",body:JSONSerialization.data(withJSONObject:["code":server.code]));XCTAssertEqual(login.0,200)
        let token=try XCTUnwrap(value(login.1)["token"] as? String),auth=["X-Auth-Token":token,"X-R2C-Page-Revision":"2"]
        for revision:String? in [nil,"1","3"] {
            var staleHeaders=["X-Auth-Token":token]
            if let revision{staleHeaders["X-R2C-Page-Revision"]=revision}
            let stale=try await request("/cloud/bootstrap",headers:staleHeaders)
            XCTAssertEqual(stale.0,409)
            let error=try XCTUnwrap(JSONSerialization.jsonObject(with:stale.1) as? [String:Any])
            XCTAssertTrue((error["message"] as? String)?.contains("Close the Open Platforms page")==true)
            let report=try await request("/cloud/report",method:"POST",body:JSONSerialization.data(withJSONObject:["automaticOriginals":true,"downloadOwner":true]),headers:staleHeaders)
            XCTAssertEqual(report.0,409)
        }
        XCTAssertNotEqual(try server.platform.snapshot()["flags"] as? [String:Bool],["automaticOriginals":true,"downloadOwner":true])
        let bootstrap=try await request("/cloud/bootstrap",headers:auth),config=try value(bootstrap.1);XCTAssertEqual(config["workspaceId"] as? String,id);XCTAssertNotNil(config["thing"]);XCTAssertNotNil(config["license"])
        _ = try await request("/cloud/report",method:"POST",body:JSONSerialization.data(withJSONObject:["missionLoaded":true,"mediaLoaded":true,"automaticOriginals":true,"downloadOwner":true]),headers:auth)
        XCTAssertTrue((try server.platform.snapshot()["status"] as? String)?.contains("return ready")==true)
        _ = try await request("/cloud/report",method:"POST",body:JSONSerialization.data(withJSONObject:["automaticOriginals":false,"downloadOwner":false]),headers:auth)
        XCTAssertTrue((try server.platform.snapshot()["status"] as? String)?.contains("needs attention")==true)
        let list=try await request("/wayline/api/v1/workspaces/\(id)/waylines",headers:auth);XCTAssertEqual((try value(list.1)["list"] as? [[String:Any]])?.count,1)
        let kmz=try await request("/wayline/api/v1/workspaces/\(id)/waylines/\(id)/url",headers:auth);XCTAssertEqual(kmz.0,200);XCTAssertEqual(kmz.1.prefix(2),Data([80,75]))
        let sts=try await request("/storage/api/v1/workspaces/\(id)/sts",method:"POST",body:Data("{}".utf8),headers:auth);XCTAssertEqual(sts.0,200);let storage=try value(sts.1),credentials=try XCTUnwrap(storage["credentials"] as? [String:Any])
        let key=id+"/"+server.platform.route.photoTag+"_001.jpg",path="/r2c-media/"+key,photo=Data([255,216,255,217])
        func signed(_ path:String,method:String,body:Data)->[String:String]{
            let date=DateFormatter();date.locale=Locale(identifier:"en_US_POSIX");date.timeZone=TimeZone(secondsFromGMT:0);date.dateFormat="yyyyMMdd'T'HHmmss'Z'";let stamp=date.string(from:Date()),day=String(stamp.prefix(8)),scope=day+"/us-east-1/s3/aws4_request"
            let url=URL(string:server.url+path)!,components=URLComponents(url:url,resolvingAgainstBaseURL:false)!
            let headerNames="host;x-amz-content-sha256;x-amz-date;x-amz-security-token",hash=pilotHash(body)
            var headers=["host":URL(string:server.url)!.host!+":"+String(URL(string:server.url)!.port!),"x-amz-content-sha256":hash,"x-amz-date":stamp,"x-amz-security-token":credentials["security_token"] as! String]
            let canonicalHeaders=headerNames.split(separator:";").map{String($0)+":"+headers[String($0)]!+"\n"}.joined()
            let canonicalQuery=(components.queryItems ?? []).sorted{$0.name<$1.name}.map{$0.name+"="+($0.value ?? "")}.joined(separator:"&")
            let canonical=method+"\n"+components.percentEncodedPath+"\n"+canonicalQuery+"\n"+canonicalHeaders+"\n"+headerNames+"\n"+hash
            func hmac(_ key:Data,_ text:String)->Data{Data(HMAC<SHA256>.authenticationCode(for:Data(text.utf8),using:SymmetricKey(data:key)))}
            let signing=hmac(hmac(hmac(hmac(Data(("AWS4"+(credentials["access_key_secret"] as! String)).utf8),day),"us-east-1"),"s3"),"aws4_request")
            let signature=pilotHex(hmac(signing,"AWS4-HMAC-SHA256\n"+stamp+"\n"+scope+"\n"+pilotHash(Data(canonical.utf8))))
            headers["Authorization"]="AWS4-HMAC-SHA256 Credential="+(credentials["access_key_id"] as! String)+"/"+scope+", SignedHeaders="+headerNames+", Signature="+signature
            return headers
        }
        let unsigned=try await request(path,method:"PUT",body:photo);XCTAssertEqual(unsigned.0,403)
        let put=try await request(path,method:"PUT",body:photo,headers:signed(path,method:"PUT",body:photo));XCTAssertEqual(put.0,200,String(decoding:put.1,as:UTF8.self));XCTAssertEqual(try Data(contentsOf:server.platform.storage.object(key)),photo)
        let corrupt=try await request(path,method:"PUT",body:Data([255,216,1,2]),headers:signed(path,method:"PUT",body:photo));XCTAssertNotEqual(corrupt.0,200);XCTAssertEqual(try Data(contentsOf:server.platform.storage.object(key)),photo)
        let callback=try JSONSerialization.data(withJSONObject:["object_key":key,"name":key.split(separator:"/").last!,"ext":["is_original":true,"file_group_id":"g1"]])
        let reported=try await request("/media/api/v1/workspaces/\(id)/upload-callback",method:"POST",body:callback,headers:auth);XCTAssertEqual(reported.0,200)
        XCTAssertEqual(try store.originals(id).count,0,"Invalid image is retained, never marked reconstructable")
        XCTAssertEqual(try Data(contentsOf:server.platform.storage.object(key)),photo)
        let group=try await request("/media/api/v1/workspaces/\(id)/group-upload-callback",method:"POST",body:JSONSerialization.data(withJSONObject:["file_group_id":"g1","file_count":1,"file_uploaded_count":1]),headers:auth);XCTAssertEqual(group.0,200)
        XCTAssertTrue(try store.completedSelection(id).isEmpty)
        let multipartPath="/r2c-media/"+id+"/multipart.jpg"
        let begun=try await request(multipartPath+"?uploads=",method:"POST",body:Data(),headers:signed(multipartPath+"?uploads=",method:"POST",body:Data()));XCTAssertEqual(begun.0,200)
        let xml=String(decoding:begun.1,as:UTF8.self),upload=try XCTUnwrap(xml.components(separatedBy:"<UploadId>").last?.components(separatedBy:"</UploadId>").first)
        let partPath=multipartPath+"?partNumber=1&uploadId="+upload
        let part=try await request(partPath,method:"PUT",body:photo,headers:signed(partPath,method:"PUT",body:photo));XCTAssertEqual(part.0,200)
        let completion=Data("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>\"\(pilotHex(Insecure.MD5.hash(data:photo)))\"</ETag></Part></CompleteMultipartUpload>".utf8),finishPath=multipartPath+"?uploadId="+upload
        let finish=try await request(finishPath,method:"POST",body:completion,headers:signed(finishPath,method:"POST",body:completion));XCTAssertEqual(finish.0,200,String(decoding:finish.1,as:UTF8.self));XCTAssertEqual(try Data(contentsOf:server.platform.storage.object(id+"/multipart.jpg")),photo)
        let valid=try Self.original(),validKey=id+"/"+server.platform.route.photoTag+"_002.jpg",validPath="/r2c-media/"+validKey
        let validUpload=try await request(validPath,method:"PUT",body:valid,headers:signed(validPath,method:"PUT",body:valid));XCTAssertEqual(validUpload.0,200)
        let validCallback=try await request("/media/api/v1/workspaces/\(id)/upload-callback",method:"POST",body:JSONSerialization.data(withJSONObject:["object_key":validKey,"name":validKey.split(separator:"/").last!,"ext":["is_original":true,"file_group_id":"g2"]]),headers:auth);XCTAssertEqual(validCallback.0,200)
        XCTAssertEqual(try store.originals(id).count,1)
        let completed=try await request("/media/api/v1/workspaces/\(id)/group-upload-callback",method:"POST",body:JSONSerialization.data(withJSONObject:["file_group_id":"g2","file_count":1,"file_uploaded_count":1]),headers:auth);XCTAssertEqual(completed.0,200)
        XCTAssertEqual(try store.completedSelection(id),[pilotHash(valid)])
        XCTAssertEqual(server.platform.flags["originalsComplete"],true)
        XCTAssertEqual(try Data(contentsOf:store.directory(id).appendingPathComponent("originals/"+pilotHash(valid)+".jpg")),valid)
        let resumed=try PilotCloudPlatform(store:store,id:id,credentials:server.platform.credentials,expires:Date().addingTimeInterval(60),changed:{_,_ in})
        XCTAssertEqual(try resumed.snapshot()["photoCount"] as? Int,1,"Mission photos and receipts survive reopening")
        // One unusable original must not strand a fully returned group that has usable photos.
        for objectKey in [key,validKey]{
            let response=try await request("/media/api/v1/workspaces/\(id)/upload-callback",method:"POST",body:JSONSerialization.data(withJSONObject:["object_key":objectKey,"name":objectKey.split(separator:"/").last!,"ext":["is_original":true,"file_group_id":"mixed-quality"]]),headers:auth)
            XCTAssertEqual(response.0,200)
        }
        _ = try await request("/media/api/v1/workspaces/\(id)/group-upload-callback",method:"POST",body:JSONSerialization.data(withJSONObject:["file_group_id":"mixed-quality","file_count":2,"file_uploaded_count":2]),headers:auth)
        XCTAssertEqual(server.platform.flags["originalsComplete"],true)
        XCTAssertEqual(try store.completedSelection(id),[pilotHash(valid)])
        // Exercise the actual WebSocket upgrade and binary MQTT stream.
        let session=URLSession(configuration:.ephemeral),socket=session.webSocketTask(with:URL(string:server.url.replacingOccurrences(of:"http:",with:"ws:")+"/cloud/mqtt?x-auth-token="+token)!,protocols:["mqtt"]);socket.resume();defer{socket.cancel(with:.normalClosure,reason:nil);session.invalidateAndCancel()}
        let connect=PilotMQTT.frame(0x10,PilotMQTT.text("MQTT")+Data([4,194,0,60])+PilotMQTT.text("test-controller")+PilotMQTT.text(server.platform.mqttUser)+PilotMQTT.text(server.platform.mqttPassword))
        try await socket.send(.data(connect));let ack=try await socket.receive();if case let .data(data)=ack{XCTAssertEqual(data,Data([32,2,0,0]))}else{XCTFail("Binary CONNACK required")}
        try await socket.send(.data(PilotMQTT.frame(0x82,Data([0,1])+PilotMQTT.text("sys/product/RC/status_reply")+Data([0]))));_ = try await socket.receive()
        let topo=try JSONSerialization.data(withJSONObject:["method":"update_topo","tid":"1","bid":"2","data":["type":174,"sub_type":0,"sub_devices":[["sn":"AIRCRAFT","type":100,"sub_type":1]]]])
        try await socket.send(.data(PilotMQTT.frame(0x30,PilotMQTT.text("sys/product/RC/status")+topo)));_ = try await socket.receive()
        let topology=try await request("/manage/api/v1/workspaces/\(id)/devices/topologies",headers:auth);XCTAssertEqual((try value(topology.1)["list"] as? [[String:Any]])?.count,1)
    }
    func testMQTTFragmentationAuthenticationAndQoS2Deduplication()throws {
        let session=PilotMQTT(username:"u",password:"p"),connect=PilotMQTT.frame(0x10,PilotMQTT.text("MQTT")+Data([4,194,0,60])+PilotMQTT.text("rc")+PilotMQTT.text("u")+PilotMQTT.text("p"))
        var output:[Data]=[];for byte in connect{output += try session.consume(Data([byte])){_,_ in []}};XCTAssertEqual(output,[Data([32,2,0,0])])
        var calls=0
        let publish=PilotMQTT.frame(0x34,PilotMQTT.text("sys/product/RC/status")+Data([0,5])+Data("{}".utf8)),release=Data([0x62,2,0,5])
        _ = try session.consume(publish+publish){_,_ in calls+=1;return []};XCTAssertEqual(calls,0)
        _ = try session.consume(release+release){_,_ in calls+=1;return []};XCTAssertEqual(calls,1)
        XCTAssertThrowsError(try PilotMQTT(username:"wrong",password:"p").consume(connect){_,_ in []})
        XCTAssertThrowsError(try session.consume(PilotMQTT.frame(0x30,PilotMQTT.text("thing/product/RC/services")+Data())){_,_ in []})
    }
    private static func original(camera:String="WideCamera",focal:Int?=nil)throws->Data {
        let context=try XCTUnwrap(CGContext(data:nil,width:4032,height:3024,bitsPerComponent:8,bytesPerRow:4032,space:CGColorSpaceCreateDeviceGray(),bitmapInfo:CGImageAlphaInfo.none.rawValue))
        let image=try XCTUnwrap(context.makeImage()),encoded=NSMutableData(),destination=try XCTUnwrap(CGImageDestinationCreateWithData(encoded,UTType.jpeg.identifier as CFString,1,nil));CGImageDestinationAddImage(destination,image,focal.map{[kCGImagePropertyExifDictionary:[kCGImagePropertyExifFocalLenIn35mmFilm:$0]] as CFDictionary});XCTAssertTrue(CGImageDestinationFinalize(destination))
        let fields=["ImageSource":camera,"RtkFlag":"50","GpsLatitude":"39","GpsLongitude":"-121","RelativeAltitude":"60","AbsoluteAltitude":"100","GimbalYawDegree":"0","GimbalPitchDegree":"-90","GimbalRollDegree":"0","RtkStdLon":"0.02","RtkStdLat":"0.02","RtkStdHgt":"0.03"]
        let attributes=fields.sorted{$0.key<$1.key}.map{"drone-dji:\($0.key)=\"\($0.value)\""}.joined(separator:" ")
        let segment=Data(("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description xmlns:drone-dji=\"http://www.dji.com/drone-dji/1.0/\" "+attributes+"/></rdf:RDF></x:xmpmeta>").utf8),length=segment.count+2
        return Data([255,216,255,225,UInt8(length>>8),UInt8(length&255)])+segment+(encoded as Data).dropFirst(2)
    }

}
