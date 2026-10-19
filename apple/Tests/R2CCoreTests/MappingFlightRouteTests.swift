import XCTest
@testable import R2CCore

final class MappingFlightRouteTests:XCTestCase {
    private let feature=Data(#"{"type":"Feature","id":"A1","properties":{"class":"Assignment","title":"Canopy"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.999,39],[-120.999,39.001],[-121,39.001],[-121,39]]]}}"#.utf8)
    func testCaptureSpeedLimitPersistsAndDoesNotChangeTransitOrResolution() throws {
        let profile=try XCTUnwrap(MappingResolution.profiles.first{$0.id=="m4td.visible.wide"})
        let mission=try MappingMission(feature:feature,incidentID:"MAP")
        for limit in [2.0,DisplayUnits.imperial.lengthMeters(10),10.0] {
            let data=try mission.json(cameraProfile:profile,desiredGsdCmPerPixel:1.27,canopyHeightMeters:12.192,maximumObstacleHeightMeters:12.192,maximumCaptureSpeedMetersPerSecond:limit,transitSpeedMetersPerSecond:10)
            let plan=try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any])
            XCTAssertEqual((plan["parameters"] as? [String:Any])?["maximumCaptureSpeedMetersPerSecond"] as? Double,limit)
            let route=try MappingFlightRoute(mission:data)
            XCTAssertEqual(route.speed,limit==2 ? 2:3,accuracy:1e-9)
            XCTAssertLessThanOrEqual(route.speed,limit)
            XCTAssertGreaterThanOrEqual(route.spacing/route.speed,1)
            XCTAssertEqual(route.transitSpeed,10,accuracy:1e-9)
            for entry in try OperationalZipArchive.decode(route.kmz()) {
                let xml=String(decoding:entry.data,as:UTF8.self)
                XCTAssertTrue(xml.contains("<wpml:autoFlightSpeed>"+String(format:"%.9f",locale:Locale(identifier:"en_US_POSIX"),route.speed)+"</wpml:autoFlightSpeed>"))
                XCTAssertTrue(xml.contains("<wpml:globalTransitionalSpeed>10.000000000</wpml:globalTransitionalSpeed>"))
            }
        }
        let independent=try MappingFlightRoute(mission:mission.json(cameraProfile:profile,desiredGsdCmPerPixel:2,captureProfile:MappingProcessingPolicy.openGround,transitSpeedMetersPerSecond:10))
        XCTAssertEqual(independent.speed,4,accuracy:1e-9)
        XCTAssertEqual(independent.transitSpeed,10,accuracy:1e-9)
        for limit in [0.0,0.9,15.1,Double.nan,Double.infinity] {
            XCTAssertThrowsError(try mission.json(cameraProfile:profile,desiredGsdCmPerPixel:2,transitSpeedMetersPerSecond:limit))
        }
        for limit in [0.0,0.9,15.1,Double.nan,Double.infinity] {
            XCTAssertThrowsError(try mission.json(cameraProfile:profile,desiredGsdCmPerPixel:2,maximumCaptureSpeedMetersPerSecond:limit,transitSpeedMetersPerSecond:10))
        }
    }
    func testHalfInchWideBrushRouteRoundsCaptureSpeedDown() throws {
        let profile=try XCTUnwrap(MappingResolution.profiles.first{$0.id=="m4td.visible.wide"})
        let data=try MappingMission(feature:feature,incidentID:"MAP").json(cameraProfile:profile,desiredGsdCmPerPixel:1.27,canopyHeightMeters:12.192,maximumObstacleHeightMeters:12.192)
        let route=try MappingFlightRoute(mission:data)
        XCTAssertEqual(route.speed,3,accuracy:1e-9)
        XCTAssertEqual(route.transitSpeed,4,accuracy:1e-9)
        XCTAssertGreaterThanOrEqual(route.spacing/route.speed,1)
    }
    func testWideOriginalMayHaveZoomCameraLabelOnlyWithWideFocalLength() throws {
        let wide=try XCTUnwrap(MappingResolution.profiles.first{$0.id=="m4td.visible.wide"})
        XCTAssertTrue(wide.matchesOriginal(sensor:"visible-zoom",focal35mm:24))
        for focal:Double? in [nil,.nan,0,70,168]{XCTAssertFalse(wide.matchesOriginal(sensor:"visible-zoom",focal35mm:focal))}
        XCTAssertFalse(wide.matchesOriginal(sensor:"thermal",focal35mm:24))
        XCTAssertFalse(wide.matchesOriginal(sensor:"unknown",focal35mm:24))
    }
    func testTelephotoRoutesPreserveRequestedDetailAndSelectLensBeforeCapture() throws {
        let mission=try MappingMission(feature:feature,incidentID:"MAP")
        for (id,focal) in [("m4td.visible.medium-tele",70.0),("m4td.visible.tele",168.0)] {
            let profile=try XCTUnwrap(MappingResolution.profiles.first{$0.id==id})
            let data=try mission.json(cameraProfile:profile,desiredGsdCmPerPixel:0.254,captureProfile:MappingProcessingPolicy.openGround,maximumObstacleHeightMeters:0)
            let route=try MappingFlightRoute(mission:data)
            if let export=ProcessInfo.processInfo.environment["R2C_TELE_ROUTE_EXPORT"] {
                let directory=URL(fileURLWithPath:export,isDirectory:true)
                try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
                var plan=try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any])
                plan["missionId"]=UUID().uuidString.lowercased()
                plan["title"]="LOCAL CHECK \(focal==70 ? "3x":"7x") - DO NOT FLY"
                let bytes=try JSONSerialization.data(withJSONObject:plan,options:[.sortedKeys])
                try bytes.write(to:directory.appendingPathComponent(id+".json"))
                try MappingFlightRoute(mission:bytes).kmz().write(to:directory.appendingPathComponent("LOCAL-CHECK-"+(focal==70 ? "3x":"7x")+"-DO-NOT-FLY.kmz"))
            }

            XCTAssertEqual(route.speed,1,accuracy:1e-9)
            XCTAssertEqual(route.transitSpeed,4,accuracy:1e-9)
            XCTAssertGreaterThanOrEqual(route.spacing/route.speed,1.5)
            XCTAssertEqual(route.height,focal==70 ? 20.300744010966454:49.39063154258403,accuracy:1e-9)
            XCTAssertEqual(route.height,MappingResolution.heightMeters(gsdCmPerPixel:0.254,camera:profile.sensor)!,accuracy:1e-10)
            for entry in try OperationalZipArchive.decode(route.kmz()) {
                let xml=String(decoding:entry.data,as:UTF8.self)
                XCTAssertTrue(xml.contains("<wpml:focalLength>"+String(format:"%.9f",locale:Locale(identifier:"en_US_POSIX"),focal)+"</wpml:focalLength>"))
                XCTAssertLessThan(try XCTUnwrap(xml.range(of:">zoom<")).lowerBound,try XCTUnwrap(xml.range(of:">takePhoto<")).lowerBound)
                XCTAssertTrue(xml.contains("<wpml:globalTransitionalSpeed>4.000000000</wpml:globalTransitionalSpeed>"))
                XCTAssertTrue(xml.contains("<wpml:autoFlightSpeed>1.000000000</wpml:autoFlightSpeed>"))
                XCTAssertTrue(xml.contains("<wpml:imageFormat>visable</wpml:imageFormat>"))
                XCTAssertFalse(xml.contains("<wpml:payloadLensIndex>ir"))
            }
            XCTAssertTrue(profile.matchesOriginal(sensor:"visible-zoom",focal35mm:focal))
            XCTAssertFalse(profile.matchesOriginal(sensor:"visible-zoom",focal35mm:focal==70 ? 168:70))
            XCTAssertFalse(profile.matchesOriginal(sensor:"thermal",focal35mm:focal))
            XCTAssertFalse(profile.matchesOriginal(sensor:"visible-zoom",focal35mm:nil))
            XCTAssertThrowsError(try mission.json(cameraProfile:profile,desiredGsdCmPerPixel:0.254,captureProfile:MappingProcessingPolicy.openGround,maximumObstacleHeightMeters:route.height))
        }
        let wide=MappingResolution.profiles.first{$0.id=="m4td.visible.wide"}!
        XCTAssertFalse(wide.matchesOriginal(sensor:"visible-wide",focal35mm:70))
        XCTAssertThrowsError(try mission.json(cameraProfile:wide,desiredGsdCmPerPixel:0.254,captureProfile:MappingProcessingPolicy.openGround,maximumObstacleHeightMeters:0))
    }
    func testVisibleCameraMigrationPreservesEveryOtherRouteField() throws {
        let route=try MappingFlightRoute(mission:MappingMission(feature:feature,incidentID:"MAP").json())
        let current=try OperationalZipArchive.decode(route.kmz())
        for entry in current {
            let xml=String(decoding:entry.data,as:UTF8.self)
            XCTAssertTrue(xml.contains("<wpml:imageFormat>visable</wpml:imageFormat>"))
            XCTAssertTrue(xml.contains("<wpml:payloadLensIndex>visable</wpml:payloadLensIndex>"))
            XCTAssertFalse(xml.contains("<wpml:payloadLensIndex>ir"))
        }
        let old=try OperationalZipArchive.encode(current.map{.init(path:$0.path,data:Data(String(decoding:$0.data,as:UTF8.self).replacingOccurrences(of:">visable<",with:">wide<").utf8))},compress:true)
        let corrected=try route.correctingCameraSelection(in:old)
        XCTAssertEqual(try OperationalZipArchive.decode(corrected),current)
        XCTAssertEqual(try route.correctingCameraSelection(in:corrected),corrected)
    }
    func testStandardCanopyRouteHasTwoPassesAndAircraftFirstDistanceCapture() throws {
        let mission=try MappingMission(feature:feature,incidentID:"MAP")
        let data=try mission.json(),route=try MappingFlightRoute(mission:data)
        XCTAssertEqual(Set(route.legs.map(\.pass)),[0,1]);XCTAssertGreaterThan(route.photoEstimate,200)
        let entries=try OperationalZipArchive.decode(route.kmz())
        let wpml=try XCTUnwrap(entries.first{$0.path=="wpmz/waylines.wpml"})
        let xml=String(decoding:wpml.data,as:UTF8.self)
        XCTAssertTrue(xml.contains("multipleDistance"));XCTAssertFalse(xml.contains("multipleTiming"))
        XCTAssertTrue(xml.contains("-70.000000000"));XCTAssertFalse(xml.contains("<wpml:imageFormat>ir"))
        let yaw=try XCTUnwrap(xml.range(of:"<wpml:actionActuatorFunc>rotateYaw"))
        let gimbal=try XCTUnwrap(xml.range(of:"<wpml:actionActuatorFunc>gimbalRotate"))
        XCTAssertLessThan(yaw.lowerBound,gimbal.lowerBound)
        XCTAssertTrue(xml.contains("<wpml:gimbalHeadingYawBase>north"))
        let plan=try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any])
        XCTAssertEqual((plan["captureProfile"] as? [String:Any])?["id"] as? String,"brush-trees-crossed-70-v1")
    }
    func testRouteMatchesAndroidFixtureCoordinatesAndEstimates() throws {
        let root=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("tools/mapping/fixtures")
        let route=try MappingFlightRoute(mission:Data(contentsOf:root.appendingPathComponent("standard-canopy-mission.json")))
        let expected=try XCTUnwrap(JSONSerialization.jsonObject(with:Data(contentsOf:root.appendingPathComponent("standard-canopy-route.json"))) as? [String:Any])
        XCTAssertEqual(route.photoEstimate,expected["photos"] as? Int);XCTAssertEqual(route.durationEstimateSeconds,expected["seconds"] as? Int)
        XCTAssertEqual(route.speed,try XCTUnwrap(expected["speed"] as? Double),accuracy:1e-9)
        let legs=try XCTUnwrap(expected["legs"] as? [[String:Any]]);XCTAssertEqual(route.legs.count,legs.count)
        for (index,leg) in route.legs.enumerated(){XCTAssertEqual(leg.pass,legs[index]["pass"] as? Int)
            for (key,point) in [("start",leg.start),("end",leg.end)]{let coordinate=route.coordinate(point),target=try XCTUnwrap(legs[index][key] as? [Double]);XCTAssertEqual(coordinate[0],target[0],accuracy:1e-9);XCTAssertEqual(coordinate[1],target[1],accuracy:1e-9)}
        }
    }
    func testLowBrushRetainsNadirAndLowerCount() throws {
        let mission=try MappingMission(feature:feature,incidentID:"MAP"),profile=MappingResolution.profiles.first{$0.id=="m4td.visible.wide"}!
        let open=try MappingFlightRoute(mission:mission.json(cameraProfile:profile,captureProfile:MappingProcessingPolicy.openGround))
        let brush=try MappingFlightRoute(mission:mission.json())
        XCTAssertEqual(Set(open.legs.map(\.pass)),[0]);XCTAssertLessThan(open.photoEstimate,brush.photoEstimate)
    }
    func testTrialRoutesExportCorrectPitchAndPreviewCannotExport() throws {
        let mission=try MappingMission(feature:feature,incidentID:"MAP")
        for profile in [MappingProcessingPolicy.nadir60Standard,MappingProcessingPolicy.nadir60] {
            let route=try MappingFlightRoute(mission:mission.json(cameraProfile:MappingResolution.profiles.first{$0.id=="m4td.visible.wide"}!,captureProfile:profile))
            XCTAssertEqual(Set(route.legs.map(\.pass)),[0,1])
            let entries=try OperationalZipArchive.decode(route.kmz())
            let xml=String(decoding:try XCTUnwrap(entries.first{$0.path=="wpmz/waylines.wpml"}).data,as:UTF8.self)
            XCTAssertTrue(xml.contains("-60.000000000"))
        }
        let preview=try MappingFlightRoute(mission:mission.json(cameraProfile:MappingResolution.profiles.first{$0.id=="m4td.visible.wide"}!,captureProfile:MappingProcessingPolicy.nadir45))
        XCTAssertGreaterThan(preview.photoEstimate,0)
        XCTAssertThrowsError(try preview.kmz())
    }
    func testInsufficientCanopyClearanceCannotExport() throws {
        let mission=try MappingMission(feature:feature,incidentID:"MAP"),profile=MappingResolution.profiles.first{$0.id=="m4td.visible.wide"}!
        XCTAssertThrowsError(try mission.json(cameraProfile:profile,desiredGsdCmPerPixel:0.3))
    }
}
