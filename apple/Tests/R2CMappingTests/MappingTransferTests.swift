import XCTest
import CryptoKit
@testable import R2CMapping
import R2CCore

private final class TransferAddress:@unchecked Sendable{
    private let lock=NSLock();private var value:(String,String)?
    func set(_ url:String,_ code:String){lock.lock();value=(url,code);lock.unlock()}
    func get()->(String,String)?{lock.lock();defer{lock.unlock()};return value}
}
final class MappingTransferTests:XCTestCase,@unchecked Sendable{
    func testPairedBoundedUploadResumesAndWrongOffsetDoesNotWrite() async throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);defer{try? FileManager.default.removeItem(at:root)}
        let store=try MappingMissionStore(root:root),id=UUID().uuidString.lowercased()
        let plan=try JSONSerialization.data(withJSONObject:["missionId":id,"incidentId":"MAP","title":"Test","parameters":[:]])
        _ = try store.savePlan(plan,boundary:Data("boundary".utf8))
        let ready=expectation(description:"listener ready"),address=TransferAddress()
        let server=try MappingControllerTransfer(store:store,missionID:id,host:"127.0.0.1",assets:{_ in Data("test".utf8)},ready:{url,code in address.set(url,code);ready.fulfill()},changed:{_ in})
        server.start();defer{server.close()};await fulfillment(of:[ready],timeout:10)
        let (base,code)=try XCTUnwrap(address.get())
        XCTAssertEqual(URL(string:base)?.host,"127.0.0.1")
        XCTAssertNotNil(URL(string:base)?.port)
        XCTAssertEqual(code.count,6)
        XCTAssertTrue(code.allSatisfy(\.isNumber))
        func request(_ path:String,method:String="GET",body:Data?=nil,token:String?=nil,offset:String?=nil,origin:String?=nil)async throws->(Int,Data){
            var request=URLRequest(url:URL(string:base+path)!);request.httpMethod=method;request.httpBody=body;request.timeoutInterval=10
            if let token{request.setValue("Bearer "+token,forHTTPHeaderField:"Authorization")};if let offset{request.setValue(offset,forHTTPHeaderField:"X-Upload-Offset")};if let origin{request.setValue(origin,forHTTPHeaderField:"Origin")}
            let (data,response)=try await URLSession.shared.data(for:request);return ((response as! HTTPURLResponse).statusCode,data)
        }
        let unauthenticated=try await request("/api/status");XCTAssertNotEqual(unauthenticated.0,200)
        let paired=try await request("/api/pair",method:"POST",body:JSONSerialization.data(withJSONObject:["code":code]))
        XCTAssertEqual(paired.0,200);let token=try XCTUnwrap((JSONSerialization.jsonObject(with:paired.1) as? [String:Any])?["token"] as? String)
        let cross=try await request("/api/status",token:token,origin:"http://untrusted.test");XCTAssertNotEqual(cross.0,200)
        let bytes=Data([1,2,3,4,5,6]),digest=SHA256.hash(data:bytes).map{String(format:"%02x",$0)}.joined()
        let registered=try await request("/api/photos",method:"POST",body:JSONSerialization.data(withJSONObject:["name":"original.jpg","sizeBytes":6,"sha256":digest]),token:token);XCTAssertEqual(registered.0,200)
        let first=try await request("/api/photos/"+digest,method:"PUT",body:bytes.prefix(4),token:token,offset:"0");XCTAssertEqual(first.0,200)
        let stale=try await request("/api/photos/"+digest,method:"PUT",body:bytes.suffix(2),token:token,offset:"0");XCTAssertNotEqual(stale.0,200)
        let resumed=try await request("/api/photos/"+digest,token:token)
        XCTAssertEqual((try JSONSerialization.jsonObject(with:resumed.1) as? [String:Any])?["offset"] as? Int,4)
        let last=try await request("/api/photos/"+digest,method:"PUT",body:bytes.suffix(2),token:token,offset:"4");XCTAssertEqual(last.0,200)
        let partial=try store.directory(id).appendingPathComponent("incoming/\(digest).partial")
        XCTAssertEqual(try Data(contentsOf:partial),bytes)
        let commit=try await request("/api/photos/\(digest)/commit",method:"POST",body:Data("{}".utf8),token:token)
        XCTAssertNotEqual(commit.0,200);XCTAssertEqual(try store.originals(id).count,0);XCTAssertEqual(try Data(contentsOf:partial),bytes)
    }
}
