import XCTest
@testable import R2CCore

final class MappingMissionTests: XCTestCase {
    private let feature = Data(#"{"type":"Feature","id":"assignment-1","properties":{"class":"Assignment","title":"A & B"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.999,39],[-120.999,39.001],[-121,39.001],[-121,39]]]}}"#.utf8)

    func testAdjustableClearanceAndLegacyDefault() throws {
        let mission=try MappingMission(feature:feature,incidentID:"MAP123"),camera=MappingResolution.profiles.first{$0.id=="m4td.visible.wide"}!
        let data=try mission.json(cameraProfile:camera,desiredGsdCmPerPixel:3,maximumObstacleHeightMeters:70,clearanceMeters:5)
        var object=try JSONSerialization.jsonObject(with:data) as! [String:Any]
        var parameters=object["parameters"] as! [String:Any]
        XCTAssertEqual(parameters["clearanceMeters"] as? Double,5)
        _ = try MappingFlightRoute(mission:data)
        XCTAssertThrowsError(try mission.json(cameraProfile:camera,desiredGsdCmPerPixel:3,maximumObstacleHeightMeters:70,clearanceMeters:30))
        for invalid in [0.0,-1,Double.nan,Double.infinity,501] {
            XCTAssertThrowsError(try mission.json(cameraProfile:camera,desiredGsdCmPerPixel:3,clearanceMeters:invalid))
        }
        parameters["maximumObstacleHeightMeters"]=75;parameters.removeValue(forKey:"clearanceMeters");object["parameters"]=parameters
        XCTAssertThrowsError(try MappingFlightRoute(mission:JSONSerialization.data(withJSONObject:object))) // Legacy default 15 m.
        parameters["clearanceMeters"]=5;object["parameters"]=parameters
        _ = try MappingFlightRoute(mission:JSONSerialization.data(withJSONObject:object))
        parameters["clearanceMeters"]="bad";object["parameters"]=parameters
        XCTAssertThrowsError(try MappingFlightRoute(mission:JSONSerialization.data(withJSONObject:object)))
    }

    func testObstacleClearanceIsSavedAndCheckedWithoutChangingOverlap() throws {
        let mission=try MappingMission(feature:feature,incidentID:"MAP123")
        let camera=MappingResolution.profiles.first{$0.id=="m4td.visible.wide"}!
        for capture in MappingProcessingPolicy.captureProfiles.filter({$0.exportQualified}) {
            let data=try mission.json(cameraProfile:camera,desiredGsdCmPerPixel:3,captureProfile:capture,maximumObstacleHeightMeters:200*0.3048)
            var object=try JSONSerialization.jsonObject(with:data) as! [String:Any]
            var parameters=object["parameters"] as! [String:Any]
            XCTAssertEqual(parameters["maximumObstacleHeightMeters"] as! Double,60.96,accuracy:1e-9)
            let route=try MappingFlightRoute(mission:data)
            XCTAssertEqual(route.photoEstimate,try MappingFlightRoute(mission:mission.json(cameraProfile:camera,desiredGsdCmPerPixel:3,captureProfile:capture)).photoEstimate)
            XCTAssertThrowsError(try mission.json(cameraProfile:camera,desiredGsdCmPerPixel:2,captureProfile:capture,maximumObstacleHeightMeters:60.96))
            parameters["maximumObstacleHeightMeters"]=route.height-14.9;object["parameters"]=parameters
            XCTAssertThrowsError(try MappingFlightRoute(mission:JSONSerialization.data(withJSONObject:object)))
        }
        for value in [-1.0,Double.nan,Double.infinity] {
            XCTAssertThrowsError(try mission.json(cameraProfile:camera,desiredGsdCmPerPixel:3,maximumObstacleHeightMeters:value))
        }
        XCTAssertTrue(MappingProcessingPolicy.openGround.description.contains("Single downward-looking"))
        XCTAssertFalse(MappingProcessingPolicy.openGround.description.contains("−70°"))
    }

    func testResultGenerationIsIndependentOfCaptureAndSurvivesRoundTrip() throws {
        let json = Data(#"{"layerId":"00000000-0000-0000-0000-000000000001","incidentId":"MAP123","title":"MapTest · 2026:10:13 18:23:48","tileUrlTemplate":"http://localhost/{Z}/{X}/{Y}.png","minZoom":18,"maxZoom":22,"captureTimeLocal":"2026:10:13 18:23:48","processedAt":"2026-10-14T16:23:11.949380Z"}"#.utf8)
        let layer = try JSONDecoder().decode(MappingLayer.self,from:json)
        XCTAssertEqual(layer.resultTitle,"MapTest")
        XCTAssertEqual(try JSONDecoder().decode(MappingLayer.self,from:JSONEncoder().encode(layer)).processedAt,layer.processedAt)
        XCTAssertEqual(MappingLayer.generatedLabel(layer.processedAt!,timeZone:TimeZone(identifier:"America/Los_Angeles")!),"Oct 14, 2026 09:23:11 PDT")
        XCTAssertEqual(MappingLayer.capturedLabel("2026:10:13 18:23:48"),"Oct 13, 2026 18:23:48")
        XCTAssertEqual(MappingLayer.generatedLabel(""),"Time unavailable")
        XCTAssertLessThan(layer.generatedDate!,MappingLayer.timestampDate("2026-10-14T16:24:00Z")!)
    }

    func testOriginalGeometryAndStableMissionIdentity() throws {
        let mission = try MappingMission(feature:feature,incidentID:"MAP123")
        let rgb = try XCTUnwrap(JSONSerialization.jsonObject(with:mission.json()) as? [String:Any])
        let ir = try XCTUnwrap(JSONSerialization.jsonObject(with:mission.json(thermal:true)) as? [String:Any])
        XCTAssertEqual(rgb["missionId"] as? String,ir["missionId"] as? String)
        XCTAssertEqual(rgb["incidentId"] as? String,"MAP123")
        XCTAssertEqual(ir["profile"] as? String,"thermal")
        let intake = try XCTUnwrap(ir["photoIntake"] as? [String:Any])
        XCTAssertEqual(intake["missionId"] as? String,ir["missionId"] as? String)
        XCTAssertEqual(intake["collection"] as? String,"originals")
        XCTAssertEqual(intake["captureMode"] as? String,"thermal")
        XCTAssertEqual(intake["preserveOriginalBytes"] as? Bool,true)
        XCTAssertEqual(intake["transport"] as? String,"r2c_active_lan_session")
        XCTAssertNil(intake["url"]); XCTAssertNil(intake["token"])
        XCTAssertEqual((ir["parameters"] as? [String:Any])?["forwardOverlapPercent"] as? Int,85)
        let original = try XCTUnwrap(JSONSerialization.jsonObject(with:feature) as? NSDictionary)
        XCTAssertEqual((rgb["feature"] as? NSDictionary)?["geometry"] as? NSDictionary,original["geometry"] as? NSDictionary)
        XCTAssertTrue(String(decoding:try mission.boundaryKML(),as:UTF8.self).contains("A &amp; B"))
    }

    func testGroundResolutionEstimatesCameraSpecificHeightAndExportsLaunchReference() throws {
        XCTAssertEqual(MappingResolution.gsdCmPerPixel(heightMeters:60,camera:MappingResolution.thermal) ?? 0,6.1,accuracy:0.2)
        XCTAssertEqual(MappingResolution.gsdCmPerPixel(heightMeters:60,camera:MappingResolution.daylightWide) ?? 0,2.07,accuracy:0.1)
        let launch=MappingLaunchLocation(latitude:39,longitude:-121,horizontalAccuracyMeters:8,capturedAt:"2026-10-11T10:00:00Z")
        let mission=try MappingMission(feature:feature,incidentID:"MAP123",launchLocation:launch)
        let payload=try XCTUnwrap(JSONSerialization.jsonObject(with:mission.json(thermal:true,desiredGsdCmPerPixel:12.2)) as? [String:Any])
        let parameters=try XCTUnwrap(payload["parameters"] as? [String:Any])
        XCTAssertEqual(parameters["desiredGroundResolutionCmPerPixel"] as? Double,12.2)
        XCTAssertEqual(parameters["heightMeters"] as? Double ?? 0,120,accuracy:3)
        XCTAssertEqual((payload["launchLocation"] as? [String:Any])?["latitude"] as? Double,39)
    }

    func testCameraProfilesExportAircraftCameraAndResolutionMetadata() throws {
        let tele = try XCTUnwrap(MappingResolution.profiles.first { $0.id == "m4td.visible.tele" })
        let payload = try XCTUnwrap(JSONSerialization.jsonObject(with: MappingMission(feature:feature,incidentID:"MAP123").json(
            cameraProfile: tele, desiredGsdCmPerPixel: 1.0
        )) as? [String:Any])
        XCTAssertEqual(payload["aircraftModel"] as? String, "DJI Matrice 4TD")
        XCTAssertEqual(payload["cameraProfileId"] as? String, "m4td.visible.tele")
        XCTAssertEqual(payload["cameraName"] as? String, "Tele camera")
        XCTAssertEqual((payload["cameraSpecifications"] as? [String:Any])?["widthPixels"] as? Double, 4096)
        XCTAssertEqual((payload["cameraSpecifications"] as? [String:Any])?["diagonalFOVDegrees"] as? Double, 15)
        XCTAssertEqual(payload["profile"] as? String, "daylight-rgb")
        XCTAssertEqual((payload["parameters"] as? [String:Any])?["desiredGroundResolutionCmPerPixel"] as? Double, 1.0)
        XCTAssertEqual(MappingResolution.profiles.filter{$0.aircraft=="DJI Matrice 4TD"}.count, 4)
    }

    func testMultipartAndHolesCannotBeFlattened() {
        let text = String(decoding:feature,as:UTF8.self)
        XCTAssertThrowsError(try MappingMission(feature:Data(text.replacingOccurrences(of:"Polygon",with:"MultiPolygon").utf8),incidentID:"MAP123"))
        let hole = text.replacingOccurrences(of:"]]]}",with:" ]],[[-120.9998,39.0002],[-120.9996,39.0002],[-120.9996,39.0004],[-120.9998,39.0002]]]}" )
        XCTAssertThrowsError(try MappingMission(feature:Data(hole.utf8),incidentID:"MAP123"))
        XCTAssertThrowsError(try MappingMission(feature:feature,incidentID:""))
    }

    func testStationStaysOnLAN() throws {
        for url in ["http://192.168.1.20:8765","http://macbook.local:8765/","http://10.0.0.2:8765","http://172.31.1.2"] { XCTAssertNotNil(try MappingMission.stationURL(url)) }
        for url in ["http://10.evil.example","https://example.com","http://192.168.999.2","file:///tmp","http://user:secret@macbook.local","http://macbook.local/path"] { XCTAssertThrowsError(try MappingMission.stationURL(url)) }
    }

    func testLayerRejectsAnotherIncidentAndExpandsTileCoordinates() throws {
        let data=Data(#"{"type":"r2c_mapping_layer","version":1,"layerId":"00000000-0000-0000-0000-000000000001","incidentId":"MAP123","title":"North search","tileScheme":"xyz","minZoom":5,"maxZoom":22,"tileUrlTemplate":"https://tracker.test/layer/tiles/{Z}/{X}/{Y}.png"}"#.utf8)
        let layer=try MappingLayer.decode(data,incidentID:"MAP123")
        XCTAssertEqual(layer.tileURL(zoom:15,x:5370,y:12523)?.absoluteString,"https://tracker.test/layer/tiles/15/5370/12523.png")
        XCTAssertThrowsError(try MappingLayer.decode(data,incidentID:"OTHER"))
        XCTAssertThrowsError(try MappingLayer.decode(Data(String(decoding:data,as:UTF8.self).replacingOccurrences(of:"\"maxZoom\":22",with:"\"maxZoom\":25").utf8),incidentID:"MAP123"))
    }

    func testLayerAcceptsAndValidatesOptionalGeographicBounds() throws {
        let valid=Data(#"{"type":"r2c_mapping_layer","version":1,"layerId":"00000000-0000-0000-0000-000000000001","incidentId":"MAP123","title":"Thermal","tileScheme":"xyz","minZoom":18,"maxZoom":21,"bounds":[-121.1,39.1,-121.0,39.2],"tileUrlTemplate":"http://192.168.1.20:8765/tiles/{Z}/{X}/{Y}.png"}"#.utf8)
        let layer=try MappingLayer.decode(valid,incidentID:"MAP123")
        XCTAssertEqual(layer.bounds,[-121.1,39.1,-121.0,39.2])
        let invalid=Data(String(decoding:valid,as:UTF8.self).replacingOccurrences(of:"-121.1,39.1,-121.0,39.2",with:"-121.0,39.2,-121.1,39.1").utf8)
        XCTAssertThrowsError(try MappingLayer.decode(invalid,incidentID:"MAP123"))
    }

    func testRegionalSurfaceMetadataSurvivesSharing() throws {
        let value:[String:Any] = ["type":"r2c_mapping_layer","version":1,"tileScheme":"xyz","layerId":"00000000-0000-0000-0000-000000000001","incidentId":"MAP123","title":"Canopy","minZoom":14,"maxZoom":21,"tileUrlTemplate":"http://127.0.0.1/tiles/{Z}/{X}/{Y}.png","missionId":"00000000-0000-0000-0000-000000000002","regionCount":2,"engine":"regions-v2-acreage-mesh04","assignmentBoundary":[[-121.0,39],[-120.9,39],[-120.9,39.1]],"surfaceModel":["type":"r2c_surface_collection","path":"surface/collection.json","regionCount":2,"meshSpacingMeters":0.4]]
        let layer=try MappingLayer.decode(JSONSerialization.data(withJSONObject:value),incidentID:"MAP123")
        let copy=try MappingLayer.decode(JSONEncoder().encode(layer),incidentID:"MAP123")
        XCTAssertEqual(copy,layer)
        XCTAssertEqual(copy.surfaceModel?.regionCount,2)
        XCTAssertEqual(copy.assignmentBoundary?.count,3)
    }

    func testLayerEncodingPreservesInterchangeMetadataAndReadsLegacyCache() throws {
        let manifest=Data(#"{"type":"r2c_mapping_layer","version":1,"layerId":"00000000-0000-0000-0000-000000000001","incidentId":"MAP123","title":"Thermal","tileScheme":"xyz","minZoom":18,"maxZoom":21,"bounds":[-121.1,39.1,-121.0,39.2],"tileUrlTemplate":"http://192.168.1.20:8765/tiles/{Z}/{X}/{Y}.png"}"#.utf8)
        let layer=try MappingLayer.decode(manifest,incidentID:"MAP123")
        let encoded=try JSONEncoder().encode(layer)
        let object=try XCTUnwrap(JSONSerialization.jsonObject(with:encoded) as? [String:Any])
        XCTAssertEqual(object["type"] as? String,"r2c_mapping_layer")
        XCTAssertEqual(object["version"] as? Int,1)
        XCTAssertEqual(object["tileScheme"] as? String,"xyz")
        XCTAssertEqual(try MappingLayer.decode(encoded,incidentID:"MAP123"),layer)

        var legacy=object
        legacy.removeValue(forKey:"type")
        legacy.removeValue(forKey:"version")
        legacy.removeValue(forKey:"tileScheme")
        let oldStoredRecord=try JSONSerialization.data(withJSONObject:legacy)
        let restored=try JSONDecoder().decode(MappingLayer.self,from:oldStoredRecord)
        XCTAssertEqual(restored,layer)
        XCTAssertThrowsError(try MappingLayer.decode(oldStoredRecord,incidentID:"MAP123"))
    }

    func testLayerLabelShowsCameraModeAndCameraLocalCaptureRange() throws {
        let manifest=Data(#"{"type":"r2c_mapping_layer","version":1,"layerId":"00000000-0000-0000-0000-000000000001","incidentId":"MAP123","title":"East Pasture","profile":"daylight-rgb","captureTimesLocal":["2026:10:10 19:30:04","2026:10:10 19:36:58"],"requestedResolutionCm":5,"photoCount":54,"processedAt":"2026-10-11T02:00:00Z","quality":"Review alignment","tileScheme":"xyz","minZoom":18,"maxZoom":21,"tileUrlTemplate":"https://tracker.test/layer/{Z}/{X}/{Y}.png"}"#.utf8)
        let layer=try MappingLayer.decode(manifest,incidentID:"MAP123")
        XCTAssertEqual(layer.displayTitle,"East Pasture · Visible · 2026-10-10 19:30–2026-10-10 19:36")
        XCTAssertEqual(layer.captureTimeSummary,"2026-10-10 19:30 to 2026-10-10 19:36")
        XCTAssertEqual(layer.requestedResolutionCm,5)
        XCTAssertEqual(layer.photoCount,54)
        XCTAssertEqual(layer.processedAt,"2026-10-11T02:00:00Z")
        XCTAssertEqual(layer.quality,"Review alignment")
        let encoded=try JSONEncoder().encode(layer)
        XCTAssertEqual(try MappingLayer.decode(encoded,incidentID:"MAP123"),layer)
    }

    func testLayerDetailsReadLegacySingleCaptureTime() throws {
        let manifest=Data(#"{"type":"r2c_mapping_layer","version":1,"layerId":"00000000-0000-0000-0000-000000000001","incidentId":"MAP123","title":"East Pasture","profile":"thermal","captureTimeLocal":"2026:10:10 19:30:04","tileScheme":"xyz","minZoom":18,"maxZoom":21,"tileUrlTemplate":"https://tracker.test/layer/{Z}/{X}/{Y}.png"}"#.utf8)
        let layer=try MappingLayer.decode(manifest,incidentID:"MAP123")
        XCTAssertEqual(layer.displayTitle,"East Pasture · IR · 2026-10-10 19:30")
        XCTAssertNil(layer.requestedResolutionCm)
    }
}
