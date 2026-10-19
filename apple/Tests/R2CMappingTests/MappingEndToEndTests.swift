import XCTest
import ImageIO
import CoreGraphics
import UniformTypeIdentifiers
@testable import R2CMapping
import R2CCore

final class MappingEndToEndTests:XCTestCase{
    // Optional local evidence check, never a dependency of release tests or a device connection.
    func testExplicitLocalFlightCopy() throws {
        guard let path=ProcessInfo.processInfo.environment["R2C_LOCAL_FLIGHT_COPY"] else {throw XCTSkip("No local photo fixture requested")}
        let source=URL(fileURLWithPath:path),data=try Data(contentsOf:source.appendingPathComponent("mission.json"))
        let plan=try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any]),id=try XCTUnwrap(plan["missionId"] as? String)
        let root=FileManager.default.temporaryDirectory.appendingPathComponent("r2c-local-recovery-"+UUID().uuidString)
        let store=try MappingMissionStore(root:root)
        try FileManager.default.copyItem(at:source,to:root.appendingPathComponent(id))
        let output=try MappingReconstruction.run(store:store,missionID:id,cancellation:MappingCancellation()){snapshot in
            print("LOCAL RECOVERY: \(snapshot.stage)")
        }
        let report=try XCTUnwrap(JSONSerialization.jsonObject(with:Data(contentsOf:output.appendingPathComponent("layer.json"))) as? [String:Any])
        XCTAssertGreaterThan(report["photoCount"] as? Int ?? 0,0)
        XCTAssertTrue(FileManager.default.fileExists(atPath:output.appendingPathComponent("share.zip").path))
        print("LOCAL RECOVERY RESULT: \(output.path)")
    }

    func testOriginalImportReconstructionExportAndVerifiedResume() throws {try runFlow(nonFixed:false)}
    func testNonFixedOriginalsReconstructWithExplicitPositioningWarning() throws {try runFlow(nonFixed:true)}
    func testTexturelessPartialPhotosPublishApproximateMapAndResume() throws {try runFlow(nonFixed:true,textureless:true)}
    private func runFlow(nonFixed:Bool,textureless:Bool=false) throws {
        let fixture=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Build/Mapping/Fixture")
        let description=try XCTUnwrap(JSONSerialization.jsonObject(with:Data(contentsOf:fixture.appendingPathComponent("fixture.json"))) as? [String:Any])
        let cameras=try XCTUnwrap(description["cameras"] as? [[String:Any]])
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);defer{try? FileManager.default.removeItem(at:root)}
        let store=try MappingMissionStore(root:root.appendingPathComponent("missions")),feature=Data(#"{"type":"Feature","id":"A1","properties":{"class":"Assignment","title":"Synthetic mapping qualification"},"geometry":{"type":"Polygon","coordinates":[[[-0.00006,-0.00006],[0.00006,-0.00006],[0.00006,0.00006],[-0.00006,0.00006],[-0.00006,-0.00006]]]}}"#.utf8)
        let mission=try MappingMission(feature:feature,incidentID:"FIXTURE"),id=mission.id.uuidString.lowercased()
        var plan=try XCTUnwrap(JSONSerialization.jsonObject(with:mission.json()) as? [String:Any]);var specifications=plan["cameraSpecifications"] as! [String:Any]
        specifications["diagonalFOVDegrees"]=2*atan(hypot(1008.0,756.0)/(2*700))*180 / .pi;plan["cameraSpecifications"]=specifications
        _ = try store.savePlan(JSONSerialization.data(withJSONObject:plan),boundary:mission.boundaryKML())
        for (i,camera) in cameras.enumerated(){try autoreleasepool{
            let data=textureless ? Data(repeating:127,count:1008*756):try Data(contentsOf:fixture.appendingPathComponent(camera["path"] as! String))
            let provider=try XCTUnwrap(CGDataProvider(data:data as CFData)),image=try XCTUnwrap(CGImage(width:1008,height:756,bitsPerComponent:8,bitsPerPixel:8,bytesPerRow:1008,space:CGColorSpaceCreateDeviceGray(),bitmapInfo:CGBitmapInfo(rawValue:CGImageAlphaInfo.none.rawValue),provider:provider,decode:nil,shouldInterpolate:false,intent:.defaultIntent))
            let context=try XCTUnwrap(CGContext(data:nil,width:4032,height:3024,bitsPerComponent:8,bytesPerRow:4032,space:CGColorSpaceCreateDeviceGray(),bitmapInfo:CGImageAlphaInfo.none.rawValue));context.interpolationQuality = .none;context.draw(image,in:CGRect(x:0,y:0,width:4032,height:3024))
            let full=try XCTUnwrap(context.makeImage()),encoded=NSMutableData(),destination=try XCTUnwrap(CGImageDestinationCreateWithData(encoded,UTType.jpeg.identifier as CFString,1,nil))
            CGImageDestinationAddImage(destination,full,[kCGImageDestinationLossyCompressionQuality:1.0] as CFDictionary);XCTAssertTrue(CGImageDestinationFinalize(destination))
            let x=camera["x"] as! Double,y=camera["y"] as! Double,lx=camera["laserX"] as! Double,ly=camera["laserY"] as! Double
            func lon(_ x:Double)->Double{x*360/MappingOriginal.world}
            func lat(_ y:Double)->Double{atan(sinh(y*2 * .pi/MappingOriginal.world))*180 / .pi}
            let distance=sqrt(pow(lx-x,2)+pow(ly-y,2)+3600)
            let xmp="http://ns.adobe.com/xap/1.0/\0<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description xmlns:drone-dji=\"http://www.dji.com/drone-dji/1.0/\" drone-dji:ImageSource=\"WideCamera\" drone-dji:RtkFlag=\"\(nonFixed ? 16:50)\" drone-dji:GpsLatitude=\"\(lat(y))\" drone-dji:GpsLongitude=\"\(lon(x))\" drone-dji:RelativeAltitude=\"60\" drone-dji:AbsoluteAltitude=\"100\" drone-dji:GimbalYawDegree=\"90\" drone-dji:GimbalPitchDegree=\"-90\" drone-dji:GimbalRollDegree=\"-90\" drone-dji:RtkStdLon=\"0.02\" drone-dji:RtkStdLat=\"0.02\" drone-dji:RtkStdHgt=\"0.03\" drone-dji:LRFStatus=\"\(nonFixed ? "Unavailable":"Normal")\" drone-dji:LRFTargetLat=\"\(lat(ly))\" drone-dji:LRFTargetLon=\"\(lon(lx))\" drone-dji:LRFTargetAbsAlt=\"40\" drone-dji:LRFTargetDistance=\"\(distance)\"/></rdf:RDF></x:xmpmeta>"
            let segment=Data(xmp.utf8),length=segment.count+2
            var jpeg=Data([0xff,0xd8,0xff,0xe1,UInt8(length>>8),UInt8(length&255)]);jpeg.append(segment);jpeg.append((encoded as Data).dropFirst(2))
            let file=root.appendingPathComponent("capture-\(i).jpg");try jpeg.write(to:file)
            try store.importOriginal(file,missionID:id){_ in}
        }}
        let originals=try store.originals(id);XCTAssertEqual(originals.count,9)
        XCTAssertEqual(originals[0].rotation[0],1,accuracy:1e-9);XCTAssertEqual(originals[0].rotation[4],-1,accuracy:1e-9)
        let first=try MappingReconstruction.run(store:store,missionID:id,cancellation:MappingCancellation()){_ in}
        XCTAssertTrue(FileManager.default.fileExists(atPath:first.appendingPathComponent("share.zip").path))
        let report=try XCTUnwrap(JSONSerialization.jsonObject(with:Data(contentsOf:first.appendingPathComponent("layer.json"))) as? [String:Any]);XCTAssertGreaterThan(report["photoCount"] as? Int ?? 0,5)
        if textureless {XCTAssertEqual(report["reconstructionMode"] as? String,"approximate-plane");XCTAssertEqual(report["photogrammetric3D"] as? Bool,false);XCTAssertTrue((report["quality"] as? String ?? "").contains("not measured depth"))}
        else {XCTAssertEqual(report["reconstructionMode"] as? String,"measured-surface")}
        let second=try MappingReconstruction.run(store:store,missionID:id,cancellation:MappingCancellation()){snapshot in
            XCTAssertFalse(snapshot.stage.contains("Finding 3D image detail"),"Completed regions must be verified and reused")
        }
        XCTAssertNotEqual(first,second);XCTAssertEqual(try store.originals(id).count,9)
        let list=try store.list(incidentID:"FIXTURE",newestFirst:true);XCTAssertEqual(list.count,1);XCTAssertEqual(list[0].results.count,2)
        XCTAssertEqual(report["positioningMode"] as? String,nonFixed ? "gnss-assisted":"fixed-rtk")
        if nonFixed{XCTAssertEqual(report["scaleMode"] as? String,"nominal-calibration");XCTAssertTrue((report["quality"] as? String ?? "").contains("absolute placement"))}
        XCTAssertGreaterThan(list[0].exportBytes,0)
        XCTAssertGreaterThan(list[0].modelBytes,0)
        let files=try XCTUnwrap(FileManager.default.enumerator(at:store.directory(id),includingPropertiesForKeys:[.isRegularFileKey,.fileSizeKey]))
        var actualBytes:Int64=0
        for case let file as URL in files{let info=try file.resourceValues(forKeys:[.isRegularFileKey,.fileSizeKey]);if info.isRegularFile==true{actualBytes+=Int64(info.fileSize ?? 0)}}
        XCTAssertEqual(list[0].totalBytes,actualBytes,"Mission storage must include exports and metadata exactly once")
        let selected=originals.dropLast().map(\.digest)
        try store.completeSelection(selected,missionID:id)
        XCTAssertEqual(Set(try store.completedSelection(id)),Set(selected))
        XCTAssertThrowsError(try store.completeSelection([String(repeating:"f",count:64)],missionID:id))
        let subset=try MappingReconstruction.run(store:store,missionID:id,selectedDigests:Set(selected),cancellation:MappingCancellation()){_ in}
        let subsetReport=try XCTUnwrap(JSONSerialization.jsonObject(with:Data(contentsOf:subset.appendingPathComponent("layer.json"))) as? [String:Any])
        XCTAssertEqual(subsetReport["selectedPhotoCount"] as? Int,8)
        XCTAssertEqual(try store.originals(id).count,9)
        if let destination=ProcessInfo.processInfo.environment["R2C_MAPPING_UI_FIXTURE"] {
            let output=URL(fileURLWithPath:destination)
            if FileManager.default.fileExists(atPath:output.path){try FileManager.default.removeItem(at:output)}
            try FileManager.default.createDirectory(at:output.deletingLastPathComponent(),withIntermediateDirectories:true)
            try FileManager.default.copyItem(at:store.root,to:output)
        }
    }
}
