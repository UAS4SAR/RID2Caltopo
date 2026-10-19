import XCTest
import Foundation
import R2CCore
@testable import R2CMapping

final class MappingOriginalAccessTests: XCTestCase {
    private func original(digest: String, name: String) -> MappingOriginal {
        MappingOriginal(digest: digest, name: name, capturedAt: "2026:10:18 15:24:00", width: 10, height: 10,
            latitude: 39, longitude: -121, heightMeters: 50, absoluteAltitude: 100, yaw: 0, pitch: -90, roll: 0,
            sigmaEast: 0.1, sigmaNorth: 0.1, sigmaHeight: 0.1, laser: nil)
    }
    func testArchiveUsesAssignmentNameAndPreservesPackageBytes() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:root) }
        try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
        let bytes=Data([1,2,3,4]); try bytes.write(to:root.appendingPathComponent("share.zip"))
        let data=try JSONSerialization.data(withJSONObject:["type":"r2c_mapping_layer","version":1,"tileScheme":"xyz","layerId":UUID().uuidString,"incidentId":"incident","title":"Assignment 7 · Visible · RTK surface","assignmentName":"Assignment 7","tileUrlTemplate":"http://localhost/{Z}/{X}/{Y}.png","minZoom":14,"maxZoom":20])
        try data.write(to:root.appendingPathComponent("layer.json"))
        let layer=try MappingLayer.decode(data,incidentID:"incident")
        let files=try MappingResultFiles.inspect(result:root,layer:layer,missionsRoot:root)
        let shared=try XCTUnwrap(files.archive)
        defer { try? FileManager.default.removeItem(at:shared.deletingLastPathComponent()) }
        XCTAssertEqual(shared.lastPathComponent,"Assignment 7_2D_map.zip")
        XCTAssertEqual(try Data(contentsOf:shared),bytes)
        XCTAssertEqual(MappingResultFiles.archiveName(assignment:"../path/name"),"_path_name_2D_map.zip")
    }
    func testShareCopiesPreserveBytesAndIdenticalNamesDoNotCollide() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store=try MappingMissionStore(root:root), id=UUID().uuidString.lowercased(), folder=try store.directory(id)
        try FileManager.default.createDirectory(at:folder.appendingPathComponent("inventory"),withIntermediateDirectories:true)
        try FileManager.default.createDirectory(at:folder.appendingPathComponent("originals"),withIntermediateDirectories:true)
        for (digest,byte) in [(String(repeating:"a",count:64),UInt8(1)),(String(repeating:"b",count:64),UInt8(2))] {
            try JSONEncoder().encode(original(digest:digest,name:"DJI_original.jpg")).write(to:folder.appendingPathComponent("inventory/\(digest).json"))
            try Data(repeating:byte,count:100).write(to:folder.appendingPathComponent("originals/\(digest).jpg"))
        }
        let photos=try MappingOriginalAccess.list(store:store,missionID:id)
        XCTAssertEqual(photos.count,2)
        let urls=try MappingOriginalAccess.prepare(photos)
        defer { for url in urls { try? FileManager.default.removeItem(at:url.deletingLastPathComponent().deletingLastPathComponent()) } }
        XCTAssertNotEqual(urls[0],urls[1])
        XCTAssertEqual(urls.map(\.lastPathComponent),["DJI_original.jpg","DJI_original.jpg"])
        for (photo,url) in zip(photos,urls) { XCTAssertEqual(try Data(contentsOf:photo.url),try Data(contentsOf:url)) }
        let cancel=MappingCancellation();cancel.cancel()
        XCTAssertThrowsError(try MappingOriginalAccess.prepare(photos,cancellation:cancel))
    }
    func testInspectorRecoversCameraAndSharesOnlySafeRegionPaths() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:root) }
        let mission=UUID().uuidString.lowercased(), id=UUID().uuidString.lowercased()
        let result=root.appendingPathComponent("\(mission)/results/\(id)")
        try FileManager.default.createDirectory(at:result.appendingPathComponent("surface/regions/0"),withIntermediateDirectories:true)
        try Data([1,2,3]).write(to:result.appendingPathComponent("surface/regions/0/surface.glb"))
        try Data("{\"regions\":[{\"path\":\"regions/0/surface.glb\"},{\"path\":\"../../outside.glb\"}]}".utf8).write(to:result.appendingPathComponent("surface/collection.json"))
        try Data("{\"cameraProfileId\":\"m4td.visible.medium-tele\"}".utf8).write(to:root.appendingPathComponent("\(mission)/mission.json"))
        let data=try JSONSerialization.data(withJSONObject:["type":"r2c_mapping_layer","version":1,"tileScheme":"xyz","layerId":id,"incidentId":"incident","title":"Result","tileUrlTemplate":"http://localhost/{Z}/{X}/{Y}.png","minZoom":14,"maxZoom":20])
        let layer=try MappingLayer.decode(data,incidentID:"incident")
        let files=try MappingResultFiles.inspect(result:result,layer:layer,missionsRoot:root)
        XCTAssertEqual(files.cameraLabel,"Medium tele (3×), 12 MP")
        XCTAssertEqual(files.missionID,mission)
        XCTAssertEqual(files.surfaces.count,1)
        XCTAssertFalse(files.hasOriginals)
        XCTAssertNil(files.archive)
    }
}
