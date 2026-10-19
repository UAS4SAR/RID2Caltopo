import Foundation
import Network
import CryptoKit
import Security
import R2CCore

/// Bounded, paired LAN transfer. Receives 1 MiB chunks and verifies originals before publication.
public final class MappingControllerTransfer: @unchecked Sendable {
    private let queue=DispatchQueue(label:"org.ncssar.mapping.transfer")
    private let listener:NWListener
    private let store:MappingMissionStore
    private let missionID:String
    private let assets:@Sendable (String)throws->Data
    private let changed:@Sendable (String)->Void
    private let ready:@Sendable (String,String)->Void
    private let failed:@Sendable (String)->Void
    private let host:String
    private var clients:[UUID:NWConnection]=[:]
    private var authority="",failedCodes=0,lockedUntil=Date.distantPast
    private let expiry=Date().addingTimeInterval(7200)
    private let token:String
    private let code:String
    private var closed=false
    private struct Upload:Codable {let name,digest:String;let bytes:Int64}
    public init(store:MappingMissionStore,missionID:String,host:String,assets:@escaping @Sendable (String)throws->Data,
                ready:@escaping @Sendable (String,String)->Void,changed:@escaping @Sendable (String)->Void,failed:@escaping @Sendable (String)->Void = {_ in}) throws {
        self.store=store;self.missionID=missionID;self.host=host;self.assets=assets;self.ready=ready;self.changed=changed;self.failed=failed
        _ = try store.directory(missionID)
        var random=[UInt8](repeating:0,count:32);guard SecRandomCopyBytes(kSecRandomDefault,random.count,&random)==errSecSuccess else{throw MappingMission.Failure("Cannot create transfer credential.")}
        token=random.map{String(format:"%02x",$0)}.joined();code=String(format:"%06d",Int.random(in:0..<1000000))
        let parameters=NWParameters.tcp;parameters.requiredLocalEndpoint = .hostPort(host:NWEndpoint.Host(host),port:.any)
        listener=try NWListener(using:parameters)
    }
    public func start(){queue.async{
        self.listener.stateUpdateHandler={state in
            switch state {
            case .ready: if let port=self.listener.port {self.authority="\(self.host):\(port.rawValue)";self.ready("http://"+self.authority,self.code)}
            case .waiting(let error): self.changed("Waiting for local network access: \(error.localizedDescription)")
            case .failed(let error): self.failed(error.localizedDescription)
            default:break
            }
        }
        self.listener.newConnectionHandler={connection in
            guard !self.closed,self.clients.count<4 else{connection.cancel();return}
            let id=UUID();self.clients[id]=connection;connection.start(queue:self.queue);self.receive(connection,id:id,data:Data())
            self.queue.asyncAfter(deadline:.now()+30){if self.clients[id] != nil{connection.cancel();self.clients.removeValue(forKey:id)}}
        }
        self.listener.start(queue:self.queue)
        self.queue.asyncAfter(deadline:.now()+7200){self.close();self.failed("Transfer expired; originals retained. Retry the connection to continue.")}
    }}
    public func close(){queue.async{self.closed=true;self.listener.cancel();for connection in self.clients.values{connection.cancel()};self.clients.removeAll()}}
    private func receive(_ connection:NWConnection,id:UUID,data:Data){
        connection.receive(minimumIncompleteLength:1,maximumLength:65536){part,_,complete,error in
            var buffer=data;if let part{buffer.append(part)}
            do{
                guard buffer.count<=1024*1024+16384 else{throw MappingMission.Failure("Request too large.")}
                if let delimiter=buffer.range(of:Data("\r\n\r\n".utf8)) {
                    guard delimiter.lowerBound<=16384,let header=String(data:buffer[..<delimiter.lowerBound],encoding:.utf8) else{throw MappingMission.Failure("Invalid request headers.")}
                    let lines=header.components(separatedBy:"\r\n"),request=lines[0].split(separator:" ");guard request.count==3,request[2]=="HTTP/1.1",lines.count<=65 else{throw MappingMission.Failure("Invalid HTTP request.")}
                    var headers:[String:String]=[:]
                    for line in lines.dropFirst(){guard let colon=line.firstIndex(of:":") else{throw MappingMission.Failure("Invalid header.")};let key=line[..<colon].lowercased();guard headers[key]==nil else{throw MappingMission.Failure("Duplicate header.")};headers[key]=line[line.index(after:colon)...].trimmingCharacters(in:.whitespaces)}
                    guard headers["transfer-encoding"]==nil,let length=Int(headers["content-length"] ?? "0"),(0...1048576).contains(length) else{throw MappingMission.Failure("Invalid request length.")}
                    if buffer.count>=delimiter.upperBound+length {
                        guard buffer.count==delimiter.upperBound+length else{throw MappingMission.Failure("Pipelining is unsupported.")}
                        let response=try self.handle(method:String(request[0]),path:String(request[1]),headers:headers,body:Data(buffer[delimiter.upperBound...]))
                        self.respond(connection,id:id,status:200,type:response.0,body:response.1);return
                    }
                } else if buffer.count>16384{throw MappingMission.Failure("Request headers too large.")}
                if complete||error != nil{connection.cancel();self.clients.removeValue(forKey:id)}else{self.receive(connection,id:id,data:buffer)}
            }catch{let body=(try? JSONSerialization.data(withJSONObject:["error":error.localizedDescription])) ?? Data();self.respond(connection,id:id,status:400,type:"application/json",body:body)}
        }
    }
    private func respond(_ connection:NWConnection,id:UUID,status:Int,type:String,body:Data){
        let header="HTTP/1.1 \(status) \(status==200 ? "OK":"Bad Request")\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n\r\n"
        var data=Data(header.utf8);data.append(body);connection.send(content:data,completion:.contentProcessed{_ in connection.cancel();self.clients.removeValue(forKey:id)})
    }
    private func handle(method:String,path:String,headers:[String:String],body:Data)throws->(String,Data){
        guard !closed,Date()<expiry else{throw MappingMission.Failure("Transfer expired. Open another transfer session.")}
        guard headers["host"]==authority,headers["origin"]==nil || headers["origin"]=="http://"+authority,headers["sec-fetch-site"] != "cross-site" else{throw MappingMission.Failure("Use this device's transfer page.")}
        func json(_ value:[String:Any])throws->(String,Data){("application/json",try JSONSerialization.data(withJSONObject:value))}
        if method=="GET",["/","/transfer.js","/sha256.js","/style.css"].contains(path){let name=path=="/" ? "index.html":String(path.dropFirst());return(name.hasSuffix("html") ? "text/html":name.hasSuffix("css") ? "text/css":"application/javascript",try assets(name))}
        func object()throws->[String:Any]{guard let value=try JSONSerialization.jsonObject(with:body) as? [String:Any] else{throw MappingMission.Failure("Invalid request body.")};return value}
        if method=="POST",path=="/api/pair"{
            guard body.count<=1024,Date()>=lockedUntil else{throw MappingMission.Failure("Too many code attempts. Wait one minute.")}
            let value=try object();guard value["code"] as? String==code else{failedCodes+=1;if failedCodes>=5{lockedUntil=Date().addingTimeInterval(60);failedCodes=0};throw MappingMission.Failure("Read the six-digit code on R2C.")}
            changed("Controller browser paired");return try json(["token":token])
        }
        guard headers["authorization"]=="Bearer "+token else{throw MappingMission.Failure("Enter the access code shown on R2C.")}
        let root=try store.directory(missionID),pending=root.appendingPathComponent("incoming")
        try FileManager.default.createDirectory(at:pending,withIntermediateDirectories:true)
        func inventory()throws->[String:Any]{let photos=try store.originals(missionID);let bytes=try photos.reduce(Int64(0)) {sum,p in sum+Int64(try root.appendingPathComponent("originals/\(p.digest).jpg").resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0)}
            return ["photoCount":photos.count,"storedBytes":bytes,"pendingCount":try FileManager.default.contentsOfDirectory(atPath:pending.path).filter{$0.hasSuffix(".json")}.count]}
        if method=="GET",path=="/api/status"{var value=try inventory();value["mission"]=try JSONSerialization.jsonObject(with:Data(contentsOf:root.appendingPathComponent("mission.json")));value["expiresAt"]=Int64(expiry.timeIntervalSince1970*1000);return try json(value)}
        if method=="GET",path=="/download/mission.kmz"{return("application/vnd.google-earth.kmz",try Data(contentsOf:root.appendingPathComponent("mission.kmz")))}
        if method=="GET",path=="/download/boundary.kml"{return("application/vnd.google-earth.kml+xml",try Data(contentsOf:root.appendingPathComponent("boundary.kml")))}
        if method=="GET",path=="/download/mission.zip"{return("application/zip",try OperationalZipArchive.encode([.init(path:"mission.json",data:Data(contentsOf:root.appendingPathComponent("mission.json"))),.init(path:"boundary.kml",data:Data(contentsOf:root.appendingPathComponent("boundary.kml"))),.init(path:"mission.kmz",data:Data(contentsOf:root.appendingPathComponent("mission.kmz")))],compress:true))}
        func validate(_ digest:String)throws{guard digest.count==64,digest.allSatisfy({"0123456789abcdef".contains($0)}) else{throw MappingMission.Failure("Invalid original checksum.")}}
        func state(_ digest:String)throws->[String:Any]{let original=root.appendingPathComponent("originals/\(digest).jpg"),partial=pending.appendingPathComponent(digest+".partial"),complete=FileManager.default.fileExists(atPath:original.path)
            let file=complete ? original:partial,bytes=Int64((try? file.resourceValues(forKeys:[.fileSizeKey]).fileSize) ?? 0);return ["sha256":digest,"offset":bytes,"complete":complete]}
        if method=="POST",path=="/api/photos"{
            let value=try object();guard let digest=value["sha256"] as? String,let name=value["name"] as? String,name.count<=255,!name.contains("/"),!name.contains("\\"),["jpg","jpeg"].contains(URL(fileURLWithPath:name).pathExtension.lowercased()),let bytes=value["sizeBytes"] as? Int64,(3...128*1048576).contains(bytes) else{throw MappingMission.Failure("Choose original JPEGs up to 128 MB.")};try validate(digest)
            if try state(digest)["complete"] as? Bool==true{return try json(state(digest))}
            let record=pending.appendingPathComponent(digest+".json")
            if FileManager.default.fileExists(atPath:record.path){let old=try JSONDecoder().decode(Upload.self,from:Data(contentsOf:record));guard old.bytes==bytes else{throw MappingMission.Failure("Upload size changed.")}}
            else{let count=try store.originals(missionID).count+FileManager.default.contentsOfDirectory(atPath:pending.path).filter{$0.hasSuffix(".json")}.count;guard count<10000 else{throw MappingMission.Failure("Mission exceeds 10,000 originals.")};try MappingFileIO.json(Upload(name:name,digest:digest,bytes:bytes),to:record)}
            return try json(state(digest))
        }
        let pieces=path.split(separator:"/").map(String.init)
        if pieces.count>=3,pieces[0]=="api",pieces[1]=="photos"{
            let digest=pieces[2];try validate(digest)
            let recordURL=pending.appendingPathComponent(digest+".json"),record=try JSONDecoder().decode(Upload.self,from:Data(contentsOf:recordURL)),partial=pending.appendingPathComponent(digest+".partial")
            if method=="PUT",pieces.count==3{
                let existing=try state(digest);guard existing["complete"] as? Bool != true,let offset=Int64(headers["x-upload-offset"] ?? ""),offset==existing["offset"] as? Int64,offset+Int64(body.count)<=record.bytes else{throw MappingMission.Failure("Upload offset changed. Select the same file to resume.")}
                if !FileManager.default.fileExists(atPath:partial.path){FileManager.default.createFile(atPath:partial.path,contents:nil)}
                let handle=try FileHandle(forWritingTo:partial);defer{try? handle.close()};try handle.seekToEnd();try handle.write(contentsOf:body);try handle.synchronize();changed("Receiving originals · \(MappingProgressSnapshot.bytes(offset+Int64(body.count),total:record.bytes))");return try json(state(digest))
            }
            if method=="POST",pieces.count==4,pieces[3]=="commit"{
                guard try MappingFileIO.sha(partial)==digest else{throw MappingMission.Failure("Original checksum mismatch. Restart this original transfer.")}
                let record=try store.importOriginal(partial,missionID:missionID,originalName:record.name){self.changed($0)}
                guard record.digest==digest else{throw MappingMission.Failure("Original changed during verification.")}
                try FileManager.default.removeItem(at:partial);try FileManager.default.removeItem(at:recordURL);changed("Original photo verified and stored");return try json(state(digest))
            }
            if method=="GET",pieces.count==3{return try json(state(digest))}
        }
        if method=="POST",path=="/api/complete"{let value=try object();guard let digests=value["sha256"] as? [String],!digests.isEmpty,digests.count<=10000 else{throw MappingMission.Failure("Invalid photo selection.")};let existing=Set(try store.originals(missionID).map(\.digest));guard digests.allSatisfy({existing.contains($0)}) else{throw MappingMission.Failure("Some originals are not verified yet.")};try store.completeSelection(digests,missionID:missionID);changed("Selected originals received and verified");return try json(inventory())}
        throw MappingMission.Failure("Unknown transfer action.")
    }
}
