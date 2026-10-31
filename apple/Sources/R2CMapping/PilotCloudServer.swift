import Foundation
import Darwin
import R2CCore

/// Local native Pilot 2 platform. It serves the shared Android connection page,
/// DJI mission APIs, MQTT/WebSocket registration and signed original uploads.
public final class PilotCloudServer:@unchecked Sendable {
    public let url:String,code:String
    let platform:PilotCloudPlatform
    private let listener:Int32,assets:@Sendable(String)throws->Data
    private let lock=NSRecursiveLock()
    private let handlers=DispatchGroup()
    private var closed=false,started=false,peers:[Int32:PilotPeer]=[:],websockets:[Int32:PilotPeer]=[:]
    private var failures=0,lockedUntil=Date.distantPast
    private let expires:Date,token:String
    private var timer:DispatchSourceTimer?
    public init(store:MappingMissionStore,missionID:String,host:String,credentials:PilotCloudCredentials,port:UInt16=8443,assets:@escaping @Sendable(String)throws->Data,changed:@escaping @Sendable(String,[String:Bool])->Void)throws {
        self.assets=assets;expires=Date().addingTimeInterval(7200);token=try pilotRandom();code=String(format:"%06d",UInt32.random(in:0..<1000000))
        platform=try PilotCloudPlatform(store:store,id:missionID,credentials:credentials,expires:expires,changed:changed)
        listener=socket(AF_INET,SOCK_STREAM,0);guard listener>=0 else{throw MappingMission.Failure("Cannot create the local Pilot 2 listener.")}
        var address=sockaddr_in();address.sin_len=UInt8(MemoryLayout<sockaddr_in>.size);address.sin_family=sa_family_t(AF_INET);address.sin_port=port.bigEndian
        var reuse:Int32=1;setsockopt(listener,SOL_SOCKET,SO_REUSEADDR,&reuse,4)
        guard inet_pton(AF_INET,host,&address.sin_addr)==1 else{Darwin.close(listener);throw MappingMission.Failure("A local IPv4 address is required.")}
        let descriptor=listener
        let bound=withUnsafePointer(to:&address){$0.withMemoryRebound(to:sockaddr.self,capacity:1){Darwin.bind(descriptor,$0,socklen_t(MemoryLayout<sockaddr_in>.size))}}
        guard bound==0,listen(listener,16)==0 else{Darwin.close(listener);throw MappingMission.Failure("Cannot listen on the tablet address. Close the previous connection and retry.")}
        var length=socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to:&address){$0.withMemoryRebound(to:sockaddr.self,capacity:1){getsockname(descriptor,$0,&length)}}
        url="http://\(host):\(UInt16(bigEndian:address.sin_port))";platform.base=url;platform.token=token;platform.mqttUser="R2C-"+missionID.prefix(8);platform.mqttPassword=try pilotRandom()
    }
    public func start(){lock.lock();defer{lock.unlock()};guard !started,!closed else{return};started=true
        // Form the timer handler on the utility queue so a MainActor caller cannot
        // inherit isolation into setEventHandler (980e4a00 class).
        let timerQueue=DispatchQueue.global(qos:.utility)
        timerQueue.async{[self] in
            let timer=DispatchSource.makeTimerSource(queue:timerQueue)
            let handler:@Sendable ()->Void={[weak self] in self?.keepalive()}
            timer.schedule(deadline:.now()+30,repeating:30)
            timer.setEventHandler(handler:handler)
            self.lock.lock();if self.closed{self.lock.unlock();timer.cancel();return};self.timer=timer;self.lock.unlock()
            timer.resume()
        }
        DispatchQueue.global(qos:.utility).async{[self] in
            while active {
                let fd=accept(listener,nil,nil);if fd<0{break};let peer=PilotPeer(fd)
                lock.lock();let accepted = !closed && peers.count<16;if accepted{peers[fd]=peer;handlers.enter()};lock.unlock()
                if !accepted{peer.close();continue}
                DispatchQueue.global(qos:.utility).async{[self] in defer{peer.close();lock.lock();peers.removeValue(forKey:fd);lock.unlock();handlers.leave()};serve(peer)}
            }
        }
    }
    private var active:Bool{lock.lock();defer{lock.unlock()};return !closed && Date()<expires}
    private func keepalive(){if !active{close();platform.update("Native connection expired; originals retained");return};lock.lock();let sockets=Array(websockets.values);lock.unlock();for peer in sockets{try? peer.frame(Data(),opcode:9)}}
    public func close(){lock.lock();defer{lock.unlock()};guard !closed else{return};closed=true;timer?.cancel();timer=nil;Darwin.shutdown(listener,SHUT_RDWR);Darwin.close(listener);for peer in peers.values{peer.close()}}
    /// Call off the main thread before removing the mission's files.
    /// Closing sockets alone does not wait for an already-running storage callback.
    public func closeAndWait(timeout:TimeInterval = 30)throws {
        close()
        guard handlers.wait(timeout:.now()+timeout) == .success else {
            throw MappingMission.Failure("Photo transfer is still finishing. No files were deleted; try again shortly.")
        }
    }
    private func authenticated(_ r:PilotRequest)->Bool{pilotSame(r.headers["x-auth-token"] ?? r.query["x-auth-token"] ?? "",token)}
    private func login(_ r:PilotRequest)throws->PilotResponse{let input=try r.object();lock.lock();defer{lock.unlock()};if Date()<lockedUntil{return .failure("Wait one minute before trying another code.",status:429)};guard pilotSame(input["code"] as? String ?? "",code) else{failures+=1;if failures>=5{failures=0;lockedUntil=Date().addingTimeInterval(60)};return .failure("Read the access code shown on the R2C tablet.",status:401)};return try .success(["token":token])}
    private func notify(_ value:[String:Any]){guard let data=try? JSONSerialization.data(withJSONObject:value) else{return};lock.lock();let sockets=Array(websockets.values);lock.unlock();for socket in sockets{try? socket.frame(data,opcode:1)}}
    private func serve(_ peer:PilotPeer){do {
        let r=try peer.request()
        guard r.headers["host"]==String(url.dropFirst(7)),r.headers["origin"]==nil || r.headers["origin"]==url,r.headers["sec-fetch-site"] != "cross-site" else{try peer.respond(.failure("Use the R2C tablet's local platform address.",status:403));return}
        guard active else{try peer.respond(.failure("Connection expired. Reopen this mission on the R2C tablet.",status:410));return}
        if ["/cloud/ws","/cloud/mqtt"].contains(r.path){guard r.method=="GET",r.headers["upgrade"]?.lowercased()=="websocket",authenticated(r) else{try peer.respond(.failure("Pair with the R2C tablet first.",status:401));return}
            let mqtt=r.path=="/cloud/mqtt";try peer.upgrade(r,mqtt:mqtt)
            if mqtt{let session=PilotMQTT(username:platform.mqttUser,password:platform.mqttPassword);defer{platform.flag("mqttConnected",false)};while active,let frame=try peer.receiveFrame(){let replies=try session.consume(frame){topic,data in try platform.mqtt(topic,data,notify:notify)};for reply in replies{try peer.frame(reply)};platform.flag("mqttConnected",true)}}
            else{lock.lock();websockets[peer.descriptor]=peer;lock.unlock();platform.flag("wsConnected",true);defer{lock.lock();websockets.removeValue(forKey:peer.descriptor);let remains = !websockets.isEmpty;lock.unlock();platform.flag("wsConnected",remains)};while active,try peer.receiveFrame() != nil{}}
            return
        }
        if r.headers["expect"]?.lowercased()=="100-continue"{try peer.send(Data("HTTP/1.1 100 Continue\r\n\r\n".utf8))}
        let response:PilotResponse
        if r.method=="GET",["/cloud","/cloud/","/cloud/index.html","/cloud/native.js","/cloud/style.css"].contains(r.path){let name=r.path.hasSuffix(".js") ? "native.js":r.path.hasSuffix(".css") ? "style.css":"index.html";if name=="index.html",r.headers["user-agent"]?.localizedCaseInsensitiveContains("Android")==true{platform.flag("androidPageReached",true)};response=PilotResponse(type:name.hasSuffix(".js") ? "text/javascript":name.hasSuffix(".css") ? "text/css":"text/html; charset=utf-8",data:try assets(name),headers:["Content-Security-Policy":"default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"])}
        else if r.method=="POST",r.path=="/cloud/login"{response=try login(r)}
        else if r.path.hasPrefix("/r2c-media"){response=try platform.storage.handle(r)}
        else if r.method=="GET",r.path=="/cloud/route.kmz",pilotSame(r.query["ticket"] ?? "",token){response=platform.routeResponse()}
        else if authenticated(r){response=try platform.api(r)}
        else{response = .failure("Pair with the R2C tablet first.",status:401)}
        try peer.respond(response)
    }catch{try? peer.respond(.failure("Local operation stopped; originals already received remain on the tablet. \(error.localizedDescription.prefix(160))"))}}
}
func pilotSame(_ a:String,_ b:String)->Bool{let x=Array(a.utf8),y=Array(b.utf8);guard x.count==y.count else{return false};var result:UInt8=0;for i in x.indices{result |= x[i]^y[i]};return result==0}
