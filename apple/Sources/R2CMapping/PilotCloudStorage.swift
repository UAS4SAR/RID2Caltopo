import Foundation

import CryptoKit
import Security
import R2CCore

func pilotHex<S:Sequence>(_ bytes:S)->String where S.Element==UInt8{bytes.map{String(format:"%02x",$0)}.joined()}
func pilotHash(_ data:Data)->String{pilotHex(SHA256.hash(data:data))}
func pilotRandom()throws->String{var bytes=[UInt8](repeating:0,count:32);guard SecRandomCopyBytes(kSecRandomDefault,32,&bytes)==errSecSuccess else{throw MappingMission.Failure("Cannot create session credential.")};return pilotHex(bytes)}
func pilotJSON(_ value:Any,to file:URL)throws{try FileManager.default.createDirectory(at:file.deletingLastPathComponent(),withIntermediateDirectories:true);try JSONSerialization.data(withJSONObject:value,options:[.sortedKeys]).write(to:file,options:.atomic)}
func pilotObject(_ file:URL)throws->[String:Any]{guard let value=try JSONSerialization.jsonObject(with:Data(contentsOf:file)) as? [String:Any] else{throw MappingMission.Failure("Invalid saved metadata.")};return value}
func pilotXML(_ value:String)->String{value.replacingOccurrences(of:"&",with:"&amp;").replacingOccurrences(of:"<",with:"&lt;").replacingOccurrences(of:">",with:"&gt;")}

/// Mission-scoped MinIO-compatible S3, including signed multipart original uploads.
final class PilotCloudStorage {
    private let lock=NSRecursiveLock()
    let prefix:String,accessKey:String,secret:String,token:String,expires:Date,root:URL
    let bucket="r2c-media",region="us-east-1"
    init(root:URL,prefix:String,expires:Date)throws{self.root=root;self.prefix=prefix;self.expires=expires;accessKey="R2C"+String(try pilotRandom().prefix(17));secret=try pilotRandom();token=try pilotRandom();for name in ["objects","multipart"]{try FileManager.default.createDirectory(at:root.appendingPathComponent(name),withIntermediateDirectories:true)}}
    func credentials(_ endpoint:String)->[String:Any]{["bucket":bucket,"endpoint":endpoint,"object_key_prefix":prefix+"/","provider":"minio","region":region,"credentials":["access_key_id":accessKey,"access_key_secret":secret,"security_token":token,"expire":max(1,Int(expires.timeIntervalSinceNow))]]}
    func authorize(_ request:PilotRequest)->Bool {
        guard Date()<expires else{return false};let q=request.query,h=request.headers
        guard let date=h["x-amz-date"] ?? q["X-Amz-Date"],(h["x-amz-security-token"] ?? q["X-Amz-Security-Token"])==token else{return false}
        let formatter=DateFormatter();formatter.locale=Locale(identifier:"en_US_POSIX");formatter.timeZone=TimeZone(secondsFromGMT:0);formatter.dateFormat="yyyyMMdd'T'HHmmss'Z'"
        guard let time=formatter.date(from:date),abs(time.timeIntervalSinceNow)<=900 else{return false}
        var fields:[String:String]=[:]
        if let authorization=h["authorization"]{guard authorization.hasPrefix("AWS4-HMAC-SHA256 ") else{return false};for part in authorization.dropFirst(17).split(separator:","){let pair=part.trimmingCharacters(in:.whitespaces).split(separator:"=",maxSplits:1);guard pair.count==2 else{return false};fields[String(pair[0])]=String(pair[1])}}
        guard let credential=fields["Credential"] ?? q["X-Amz-Credential"],let signed=fields["SignedHeaders"] ?? q["X-Amz-SignedHeaders"] else{return false}
        let parts=credential.split(separator:"/").map(String.init),names=signed.split(separator:";").map(String.init)
        guard parts.count==5,parts[0]==accessKey,parts[1]==String(date.prefix(8)),parts[2]==region,parts[3]=="s3",parts[4]=="aws4_request",names==names.sorted(),Set(names).count==names.count,names.contains("host"),names.allSatisfy({h[$0] != nil}) else{return false}
        let canonicalHeaders=names.map{$0+":"+h[$0]!.split(whereSeparator:{$0.isWhitespace}).joined(separator:" ")+"\n"}.joined()
        func encode(_ value:String)->String{value.addingPercentEncoding(withAllowedCharacters:CharacterSet(charactersIn:"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~"))!}
        let queryFields = q.filter { h["authorization"] != nil || $0.key != "X-Amz-Signature" }
        var queryPairs: [(String,String)] = queryFields.map { (encode($0.key),encode($0.value)) }
        queryPairs.sort { a,b in a.0 == b.0 ? a.1 < b.1 : a.0 < b.0 }
        let query = queryPairs.map { $0.0 + "=" + $0.1 }.joined(separator:"&")
        let path=String(request.rawTarget.split(separator:"?",maxSplits:1)[0]),payload=h["x-amz-content-sha256"] ?? (h["authorization"]==nil ? "UNSIGNED-PAYLOAD":pilotHash(Data()))
        let canonical="\(request.method)\n\(path)\n\(query)\n\(canonicalHeaders)\n\(signed)\n\(payload)",scope=parts.dropFirst().joined(separator:"/")
        func hmac(_ key:Data,_ text:String)->Data{Data(HMAC<SHA256>.authenticationCode(for:Data(text.utf8),using:SymmetricKey(data:key)))}
        let key=hmac(hmac(hmac(hmac(Data(("AWS4"+secret).utf8),parts[1]),region),"s3"),"aws4_request")
        let expected=pilotHex(hmac(key,"AWS4-HMAC-SHA256\n\(date)\n\(scope)\n\(pilotHash(Data(canonical.utf8)))"))
        if h["authorization"]==nil{guard let lifetime=Int(q["X-Amz-Expires"] ?? ""),(1...7200).contains(lifetime),Date()<time.addingTimeInterval(Double(lifetime)) else{return false}}
        return pilotSame(fields["Signature"] ?? q["X-Amz-Signature"] ?? "",expected)
    }
    func checkKey(_ key:String)throws {guard key.hasPrefix(prefix+"/"),key.count<=512,key.split(separator:"/",omittingEmptySubsequences:false).allSatisfy({!$0.isEmpty && $0 != "." && $0 != ".."}),!key.contains("\\"),!key.unicodeScalars.contains(where:{$0.value<32}),["jpg","jpeg"].contains(URL(fileURLWithPath:key).pathExtension.lowercased()) else{throw MappingMission.Failure("Object is outside this mission's originals.")}}
    func object(_ key:String)throws->URL{try checkKey(key);return root.appendingPathComponent("objects/"+pilotHash(Data(key.utf8))+".jpg")}
    func record(_ key:String)throws->URL{try checkKey(key);return root.appendingPathComponent("objects/"+pilotHash(Data(key.utf8))+".json")}
    // Immutable upload bytes were checksum-verified before the record was committed.
    func hasRetainedObject(_ key:String,digest:String)->Bool {
        guard let record=try? pilotObject(record(key)),record["sha256"] as? String==digest,
              let expected=record["sizeBytes"] as? Int,expected>0,
              let file=try? object(key),let values=try? file.resourceValues(forKeys:[.isRegularFileKey,.fileSizeKey]) else{return false}
        return values.isRegularFile==true && values.fileSize==expected
    }
    func digest(_ key:String)throws->String{try pilotObject(record(key))["sha256"] as? String ?? ""}
    private func hashes(_ file:URL)throws->(String,String,Int){let input=try FileHandle(forReadingFrom:file);defer{try? input.close()};var sha=SHA256(),md5=Insecure.MD5(),size=0;while let data=try input.read(upToCount:65536),!data.isEmpty{sha.update(data:data);md5.update(data:data);size+=data.count};return(pilotHex(sha.finalize()),pilotHex(md5.finalize()),size)}
    private func receive(_ request:PilotRequest)throws->(URL,String,String,Int){guard request.headers["content-encoding"] != "aws-chunked" else{throw MappingMission.Failure("Use the MinIO upload provider.")};let file=try request.bodyFile();do{let h=try hashes(file);if let expected=request.headers["x-amz-content-sha256"],expected != "UNSIGNED-PAYLOAD",expected != h.0{throw MappingMission.Failure("Original checksum failed.")};if let expected=request.headers["content-md5"],Data(base64Encoded:expected).map(pilotHex) != h.1{throw MappingMission.Failure("Original MD5 failed.")};return(file,h.0,h.1,h.2)}catch{try? FileManager.default.removeItem(at:file);throw error}}
    private func retain(_ key:String,file:URL,etag:String)throws {let input=try FileHandle(forReadingFrom:file);let signature=try input.read(upToCount:2);try input.close();guard signature==Data([255,216]) else{throw MappingMission.Failure("Only original JPEG photos are accepted.")};let h=try hashes(file),target=try object(key)
        if FileManager.default.fileExists(atPath:target.path){guard try hashes(target).0==h.0 else{throw MappingMission.Failure("Cannot overwrite an original with different bytes.")};try pilotJSON(["objectKey":key,"sha256":h.0,"etag":etag,"sizeBytes":h.2],to:record(key));return}
        let staging=root.appendingPathComponent(UUID().uuidString+".tmp");defer{try? FileManager.default.removeItem(at:staging)}
        try FileManager.default.copyItem(at:file,to:staging);try FileManager.default.moveItem(at:staging,to:target);try pilotJSON(["objectKey":key,"sha256":h.0,"etag":etag,"sizeBytes":h.2],to:record(key))
    }
    func handle(_ request:PilotRequest)throws->PilotResponse {
        lock.lock();defer{lock.unlock()}
        guard authorize(request) else{return .failure("Upload session or signature is invalid.",status:403)}
        func xml(_ text:String)->PilotResponse{PilotResponse(type:"application/xml",data:Data(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"+text).utf8))}
        if request.path=="/"+bucket{if request.method=="HEAD"{return PilotResponse(type:"application/xml")};if request.query["location"] != nil{return xml("<LocationConstraint>\(region)</LocationConstraint>")}}
        guard request.path.hasPrefix("/"+bucket+"/") else{return .failure("Unsupported bucket.")};let key=String(request.path.dropFirst(bucket.count+2));try checkKey(key)
        let fm=FileManager.default
        if let upload=request.query["uploadId"] {
            guard UUID(uuidString:upload) != nil else{throw MappingMission.Failure("Invalid multipart ID.")};let folder=root.appendingPathComponent("multipart/"+upload)
            guard try pilotObject(folder.appendingPathComponent("upload.json"))["key"] as? String==key else{throw MappingMission.Failure("Multipart key mismatch.")}
            if request.method=="PUT" {guard let part=Int(request.query["partNumber"] ?? ""),(1...1000).contains(part) else{throw MappingMission.Failure("Invalid upload part.")};let received=try receive(request);defer{try? fm.removeItem(at:received.0)};let target=folder.appendingPathComponent("\(part).part")
                let others=try fm.contentsOfDirectory(at:folder,includingPropertiesForKeys:[.fileSizeKey]).filter{$0.pathExtension=="part" && $0 != target}.reduce(0){$0+((try? $1.resourceValues(forKeys:[.fileSizeKey]).fileSize) ?? 0)};guard others+received.3<=128*1024*1024 else{throw MappingMission.Failure("Original exceeds 128 MiB.")}
                if fm.fileExists(atPath:target.path){try fm.removeItem(at:target)};try fm.copyItem(at:received.0,to:target);try pilotJSON(["size":received.3,"md5":received.2,"sha256":received.1],to:folder.appendingPathComponent("\(part).json"));return PilotResponse(type:"application/xml",headers:["ETag":"\"\(received.2)\""])
            }
            if request.method=="DELETE"{try fm.removeItem(at:folder);return PilotResponse(status:204,type:"application/xml")}
            if request.method=="GET" {let parts=try fm.contentsOfDirectory(at:folder,includingPropertiesForKeys:nil).filter{$0.pathExtension=="part"}.sorted{Int($0.deletingPathExtension().lastPathComponent)!<Int($1.deletingPathExtension().lastPathComponent)!};let text=try parts.map{file->String in let n=file.deletingPathExtension().lastPathComponent,record=try pilotObject(folder.appendingPathComponent(n+".json"));return "<Part><PartNumber>\(n)</PartNumber><ETag>\"\(record["md5"]!)\"</ETag><Size>\(record["size"]!)</Size></Part>"}.joined();return xml("<ListPartsResult><Bucket>\(bucket)</Bucket><Key>\(pilotXML(key))</Key><UploadId>\(upload)</UploadId><IsTruncated>false</IsTruncated>\(text)</ListPartsResult>")}
            if request.method=="POST" {let data=try request.body();guard !String(decoding:data,as:UTF8.self).contains("<!") else{throw MappingMission.Failure("XML entities are not accepted.")};let parser=XMLParser(data:data),delegate=PilotParts();parser.delegate=delegate;guard parser.parse(),!delegate.parts.isEmpty,delegate.parts.count<=1000 else{throw MappingMission.Failure("Invalid multipart completion.")}
                let combined=folder.appendingPathComponent("complete.tmp");fm.createFile(atPath:combined.path,contents:nil);let output=try FileHandle(forWritingTo:combined);defer{try? output.close();try? fm.removeItem(at:combined)};var previous=0,total=0,digests=Data()
                for (number,etag) in delegate.parts{guard number>previous else{throw MappingMission.Failure("Invalid part ordering.")};previous=number;let info=try pilotObject(folder.appendingPathComponent("\(number).json"));guard info["md5"] as? String==etag else{throw MappingMission.Failure("Part checksum mismatch.")};for i in stride(from:0,to:etag.count,by:2){let start=etag.index(etag.startIndex,offsetBy:i);guard let byte=UInt8(etag[start..<etag.index(start,offsetBy:2)],radix:16) else{throw MappingMission.Failure("Invalid checksum.")};digests.append(byte)};let input=try FileHandle(forReadingFrom:folder.appendingPathComponent("\(number).part"));defer{try? input.close()};while let bytes=try input.read(upToCount:65536),!bytes.isEmpty{total+=bytes.count;guard total<=128*1024*1024 else{throw MappingMission.Failure("Original exceeds 128 MiB.")};try output.write(contentsOf:bytes)}}
                try output.synchronize();let etag=pilotHex(Insecure.MD5.hash(data:digests))+"-\(delegate.parts.count)";try retain(key,file:combined,etag:etag);try fm.removeItem(at:folder);return xml("<CompleteMultipartUploadResult><Bucket>\(bucket)</Bucket><Key>\(pilotXML(key))</Key><ETag>\"\(etag)\"</ETag></CompleteMultipartUploadResult>")
            }
        }
        if request.method=="POST",request.query["uploads"] != nil{let pending=root.appendingPathComponent("multipart");guard try fm.contentsOfDirectory(atPath:pending.path).count<1000 else{throw MappingMission.Failure("Too many pending originals.")};let id=UUID().uuidString.lowercased();try pilotJSON(["key":key],to:pending.appendingPathComponent(id+"/upload.json"));return xml("<InitiateMultipartUploadResult><Bucket>\(bucket)</Bucket><Key>\(pilotXML(key))</Key><UploadId>\(id)</UploadId></InitiateMultipartUploadResult>")}
        if request.method=="PUT"{let data=try receive(request);defer{try? fm.removeItem(at:data.0)};try retain(key,file:data.0,etag:data.2);return PilotResponse(type:"application/xml",headers:["ETag":"\"\(data.2)\""])}
        if ["GET","HEAD"].contains(request.method),fm.fileExists(atPath:try object(key).path){let info=try pilotObject(record(key));return PilotResponse(type:"image/jpeg",file:try object(key),headers:["ETag":"\"\(info["etag"]!)\""],head:request.method=="HEAD")}
        return .failure("Original is not stored locally.",status:404)
    }
}
private final class PilotParts:NSObject,XMLParserDelegate {
    var parts:[(Int,String)]=[];private var element="",text="",number=0,etag=""
    func parser(_ parser:XMLParser,didStartElement elementName:String,namespaceURI:String?,qualifiedName:String?,attributes:[String:String]){element=elementName;text="";if elementName=="Part"{number=0;etag=""}}
    func parser(_ parser:XMLParser,foundCharacters string:String){text+=string}
    func parser(_ parser:XMLParser,didEndElement elementName:String,namespaceURI:String?,qualifiedName:String?){if elementName=="PartNumber"{number=Int(text.trimmingCharacters(in:.whitespacesAndNewlines)) ?? 0};if elementName=="ETag"{etag=text.trimmingCharacters(in:CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn:"\"")))};if elementName=="Part"{parts.append((number,etag))}}
}
