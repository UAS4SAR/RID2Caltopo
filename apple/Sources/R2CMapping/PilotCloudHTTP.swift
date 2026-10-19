import Foundation
import Darwin
import CryptoKit
import R2CCore

// Same bounded HTTP/WebSocket transport contract as Android CloudHttp.
struct PilotRequest {
    let method:String,rawTarget:String,path:String
    let query:[String:String],headers:[String:String]
    let peer:PilotPeer
    func bodyFile(limit:Int=128*1024*1024)throws->URL {
        let file=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        FileManager.default.createFile(atPath:file.path,contents:nil)
        let output=try FileHandle(forWritingTo:file);var size=0
        do {
            func copy(_ count:Int)throws {guard count>=0,size+count<=limit else{throw MappingMission.Failure("Upload exceeds the permitted size.")};var remaining=count
                let free=(try FileManager.default.attributesOfFileSystem(forPath:file.deletingLastPathComponent().path)[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
                guard free-Int64(count)>=256*1024*1024 else{throw MappingMission.Failure("Tablet storage is full. Originals already received are retained.")}
                while remaining>0 {let data=try peer.read(min(65536,remaining));try output.write(contentsOf:data);size+=data.count;remaining-=data.count}
            }
            if headers["transfer-encoding"]?.lowercased()=="chunked" {
                while true {let line=try peer.line(limit:1024);guard let rawCount=line.split(separator:";").first,let count=Int(rawCount,radix:16),count>=0 else{throw MappingMission.Failure("Invalid HTTP chunk.")}
                    if count==0 {var trailerBytes=0;while true {let trailer=try peer.line(limit:8192);trailerBytes+=trailer.count;guard trailerBytes<=16384 else{throw MappingMission.Failure("Trailer too large.")};if trailer.isEmpty{break}};break}
                    try copy(count);guard try peer.line(limit:2).isEmpty else{throw MappingMission.Failure("Invalid chunk delimiter.")}
                }
            }else{try copy(Int(headers["content-length"] ?? "0") ?? -1)}
            try output.synchronize();try output.close();return file
        }catch{try? output.close();try? FileManager.default.removeItem(at:file);throw error}
    }
    func body(limit:Int=256*1024)throws->Data {let file=try bodyFile(limit:limit);defer{try? FileManager.default.removeItem(at:file)};return try Data(contentsOf:file)}
    func object()throws->[String:Any]{guard let value=try JSONSerialization.jsonObject(with:body()) as? [String:Any] else{throw MappingMission.Failure("Expected JSON object.")};return value}
}
struct PilotResponse {
    var status=200,type="application/json",data=Data(),file:URL?=nil,headers:[String:String]=[:],head=false
    static func success(_ value:Any = [String:String]())throws->Self{Self(data:try JSONSerialization.data(withJSONObject:["code":0,"message":"success","data":value],options:[.sortedKeys]))}
    static func failure(_ message:String,status:Int=400)->Self{Self(status:status,data:(try? JSONSerialization.data(withJSONObject:["code":1,"message":message,"data":[:]])) ?? Data())}
}
final class PilotPeer:@unchecked Sendable {
    let descriptor:Int32
    private var buffer=Data()
    private let sendLock=NSLock(),closeLock=NSLock()
    private var closed=false
    init(_ descriptor:Int32){self.descriptor=descriptor;var timeout=timeval(tv_sec:180,tv_usec:0);setsockopt(descriptor,SOL_SOCKET,SO_RCVTIMEO,&timeout,socklen_t(MemoryLayout<timeval>.size));setsockopt(descriptor,SOL_SOCKET,SO_SNDTIMEO,&timeout,socklen_t(MemoryLayout<timeval>.size));var flag:Int32=1;setsockopt(descriptor,SOL_SOCKET,SO_NOSIGPIPE,&flag,4)}
    func close(){closeLock.lock();defer{closeLock.unlock()};if !closed{closed=true;Darwin.shutdown(descriptor,SHUT_RDWR);Darwin.close(descriptor)}}
    func read(_ count:Int)throws->Data {
        guard count>=0,count<=256*1024 else{throw MappingMission.Failure("Transport frame too large.")}
        while buffer.count<count {var bytes=[UInt8](repeating:0,count:min(65536,max(1,count-buffer.count)));let n=recv(descriptor,&bytes,bytes.count,0);guard n>0 else{throw MappingMission.Failure("Controller connection closed.")};buffer.append(contentsOf:bytes.prefix(n))}
        let result=Data(buffer.prefix(count));buffer.removeFirst(count);return result
    }
    func line(limit:Int)throws->String {var data=Data();while true{let byte=try read(1)[0];data.append(byte);guard data.count<=limit+2 else{throw MappingMission.Failure("HTTP line too large.")};if data.count>=2 && data.suffix(2)==Data([13,10]){return String(decoding:data.dropLast(2),as:UTF8.self)}}}
    func send(_ data:Data)throws {sendLock.lock();defer{sendLock.unlock()};try data.withUnsafeBytes{bytes in var offset=0;while offset<data.count{let n=Darwin.send(descriptor,bytes.baseAddress!.advanced(by:offset),data.count-offset,0);guard n>0 else{throw MappingMission.Failure("Controller connection closed.")};offset+=n}}}
    func request()throws->PilotRequest {
        let first=try line(limit:8192).split(separator:" ");guard first.count==3,first[2].hasPrefix("HTTP/1."),first[1].hasPrefix("/") else{throw MappingMission.Failure("Invalid HTTP request.")}
        let target=String(first[1]);guard let components=URLComponents(string:"http://local"+target),let path=components.percentEncodedPath.removingPercentEncoding else{throw MappingMission.Failure("Invalid request path.")}
        var headers:[String:String]=[:],size=0
        while true {let value=try line(limit:8192);size+=value.count;guard size<=16384 else{throw MappingMission.Failure("HTTP headers too large.")};if value.isEmpty{break};guard let colon=value.firstIndex(of:":") else{throw MappingMission.Failure("Invalid header.")};let name=value[..<colon].lowercased();guard headers[name]==nil else{throw MappingMission.Failure("Duplicate header.")};headers[name]=value[value.index(after:colon)...].trimmingCharacters(in:.whitespaces)}
        guard headers["transfer-encoding"]==nil || (headers["transfer-encoding"]?.lowercased()=="chunked" && headers["content-length"]==nil) else{throw MappingMission.Failure("Unsupported body encoding.")}
        var query:[String:String]=[:];for item in components.queryItems ?? []{guard query[item.name]==nil else{throw MappingMission.Failure("Duplicate query field.")};query[item.name]=item.value ?? ""}
        return PilotRequest(method:String(first[0]),rawTarget:target,path:path,query:query,headers:headers,peer:self)
    }
    func respond(_ response:PilotResponse)throws {
        let size=response.file.flatMap{try? $0.resourceValues(forKeys:[.fileSizeKey]).fileSize} ?? response.data.count
        var headers=response.headers;headers["Content-Type"]=response.type;headers["Content-Length"]=String(size);headers["Connection"]="close";headers["Cache-Control"]="no-store"
        try send(Data(("HTTP/1.1 \(response.status) \(response.status==200 ? "OK":"Response")\r\n"+headers.sorted{$0.key<$1.key}.map{"\($0.key): \($0.value)\r\n"}.joined()+"\r\n").utf8))
        if !response.head {if let file=response.file{let input=try FileHandle(forReadingFrom:file);defer{try? input.close()};while let data=try input.read(upToCount:65536),!data.isEmpty{try send(data)}}else{try send(response.data)}}
    }
    func upgrade(_ request:PilotRequest,mqtt:Bool)throws {
        guard request.headers["sec-websocket-version"]=="13",let key=request.headers["sec-websocket-key"],Data(base64Encoded:key)?.count==16 else{throw MappingMission.Failure("Invalid WebSocket handshake.")}
        if mqtt {guard request.headers["sec-websocket-protocol"]?.split(separator:",").map({$0.trimmingCharacters(in:.whitespaces)}).contains("mqtt")==true else{throw MappingMission.Failure("MQTT subprotocol required.")}}
        let accept=Data(Insecure.SHA1.hash(data:Data((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").utf8))).base64EncodedString()
        try send(Data(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: \(accept)\r\n"+(mqtt ? "Sec-WebSocket-Protocol: mqtt\r\n":"")+"\r\n").utf8))
    }
    func frame(_ data:Data,opcode:UInt8=2)throws {var header=Data([0x80|opcode]);if data.count<126{header.append(UInt8(data.count))}else if data.count<=65535{header.append(126);header.append(contentsOf:[UInt8(data.count>>8),UInt8(data.count&255)])}else{header.append(127);header.append(contentsOf:(0..<8).reversed().map{UInt8((UInt64(data.count)>>($0*8))&255)})};header.append(data);try send(header)}
    func receiveFrame()throws->Data? {
        var message=Data();var fragmented=false
        while true {let h=try read(2),opcode=h[0]&15,fin=h[0]&128 != 0;guard h[0]&112==0,h[1]&128 != 0 else{throw MappingMission.Failure("Invalid WebSocket frame.")}
            var length=Int(h[1]&127);if length==126{length=try read(2).reduce(0){$0*256+Int($1)}}else if length==127{let bytes=try read(8);guard bytes.prefix(4).allSatisfy({$0==0}) else{throw MappingMission.Failure("Frame too large.")};length=bytes.suffix(4).reduce(0){$0*256+Int($1)}}
            guard length<=256*1024,message.count+length<=256*1024 else{throw MappingMission.Failure("Frame too large.")};let mask=try read(4);var data=try read(length);for i in data.indices{data[i]^=mask[i%4]}
            if opcode>=8{guard fin,length<=125 else{throw MappingMission.Failure("Invalid control frame.")};if opcode==8{return nil};if opcode==9{try frame(data,opcode:10)};continue}
            guard (fragmented && opcode==0) || (!fragmented && (opcode==1 || opcode==2)) else{throw MappingMission.Failure("Invalid fragmented message.")};message.append(data);if fin{return message};fragmented=true
        }
    }
}
