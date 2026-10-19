import XCTest
@testable import R2CCore

final class MappingAircraftTests: XCTestCase {
    private let feature=Data(#"{"id":"A1","properties":{"class":"Assignment","title":"Aircraft check"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.999,39],[-120.999,39.001],[-121,39.001],[-121,39]]]}}"#.utf8)
    func testNewAircraftProduceTheirOwnRouteAndPayloadIdentity() throws {
        let mission=try MappingMission(feature:feature,incidentID:"MAP")
        for (id,drone,sub,payload) in [("m4t.visible.wide",99,1,89),("m30.visible.wide",67,0,52),("m30t.visible.wide",67,1,53)] {
            let profile=try XCTUnwrap(MappingResolution.profiles.first{$0.id==id})
            let data=try mission.json(cameraProfile:profile,desiredGsdCmPerPixel:3,captureProfile:MappingProcessingPolicy.brushTrees,canopyHeightMeters:20,maximumObstacleHeightMeters:20,maximumCaptureSpeedMetersPerSecond:10,transitSpeedMetersPerSecond:8)
            let route=try MappingFlightRoute(mission:data)
            XCTAssertEqual(Set(route.legs.map(\.pass)),[0,1])
            XCTAssertLessThanOrEqual(route.speed,10);XCTAssertEqual(route.transitSpeed,8)
            XCTAssertGreaterThanOrEqual(route.spacing/route.speed,drone==67 ? 2:1)
            XCTAssertEqual(route.aircraft.droneModelKey,"0-\(drone)-\(sub)")
            XCTAssertEqual(route.aircraft.payloadModelKey,"1-\(payload)-0")
            for entry in try OperationalZipArchive.decode(route.kmz()) {
                let text=String(decoding:entry.data,as:UTF8.self),lens=drone==67 ? "wide":"visable"
                XCTAssertTrue(text.contains("<wpml:droneEnumValue>\(drone)</wpml:droneEnumValue>"))
                XCTAssertTrue(text.contains("<wpml:droneSubEnumValue>\(sub)</wpml:droneSubEnumValue>"))
                XCTAssertTrue(text.contains("<wpml:payloadEnumValue>\(payload)</wpml:payloadEnumValue>"))
                XCTAssertTrue(text.contains("<wpml:imageFormat>\(lens)</wpml:imageFormat>"))
                XCTAssertTrue(text.contains("<wpml:payloadLensIndex>\(lens)</wpml:payloadLensIndex>"))
                XCTAssertEqual(text.contains("<wpml:actionActuatorFunc>zoom</wpml:actionActuatorFunc>"),drone != 67)
            }
            var plan=try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any]);plan["aircraftModel"]="DJI Matrice 4TD"
            XCTAssertThrowsError(try MappingFlightRoute(mission:JSONSerialization.data(withJSONObject:plan)))
        }
    }
    func testM30GeometryAndM4tTelephotoHaveDifferentCoverageAndLensSelection() throws {
        let mission=try MappingMission(feature:feature,incidentID:"MAP")
        let m30=try XCTUnwrap(MappingResolution.profiles.first{$0.id=="m30.visible.wide"})
        let m4t=try XCTUnwrap(MappingResolution.profiles.first{$0.id=="m4t.visible.wide"})
        XCTAssertEqual(m30.sensor.widthPixels,4000);XCTAssertEqual(m30.sensor.heightPixels,3000)
        XCTAssertEqual(m30.sensor.diagonalFOVDegrees,84)
        XCTAssertLessThan(try XCTUnwrap(MappingResolution.heightMeters(gsdCmPerPixel:2,camera:m30.sensor)),try XCTUnwrap(MappingResolution.heightMeters(gsdCmPerPixel:2,camera:m4t.sensor)))
        for (id,focal) in [("m4t.visible.medium-tele",70.0),("m4t.visible.tele",168.0)] {
            let profile=try XCTUnwrap(MappingResolution.profiles.first{$0.id==id})
            let route=try MappingFlightRoute(mission:mission.json(cameraProfile:profile,desiredGsdCmPerPixel:1,captureProfile:MappingProcessingPolicy.openGround))
            XCTAssertGreaterThanOrEqual(route.spacing/route.speed,1.5)
            for entry in try OperationalZipArchive.decode(route.kmz()) {
                XCTAssertTrue(String(decoding:entry.data,as:UTF8.self).contains("<wpml:focalLength>\(Int(focal)).000000000</wpml:focalLength>"))
            }
            XCTAssertTrue(profile.matchesOriginal(sensor:"visible-zoom",focal35mm:focal))
            XCTAssertFalse(profile.matchesOriginal(sensor:"visible-wide",focal35mm:24))
        }
        XCTAssertEqual(MappingResolution.profiles.filter{$0.aircraft=="DJI Matrice 30"}.count,1)
        XCTAssertEqual(MappingResolution.profiles.filter{$0.aircraft=="DJI Matrice 30T"}.count,2)
        XCTAssertEqual(MappingResolution.profiles.filter{$0.aircraft=="DJI Matrice 4T"}.count,4)
        let thermal=try XCTUnwrap(MappingResolution.profiles.first{$0.id=="m30t.thermal"})
        XCTAssertEqual(thermal.sensor.diagonalFOVDegrees,61)
        let route=try MappingFlightRoute(mission:mission.json(cameraProfile:thermal,desiredGsdCmPerPixel:15))
        for entry in try OperationalZipArchive.decode(route.kmz()) {XCTAssertTrue(String(decoding:entry.data,as:UTF8.self).contains("<wpml:payloadLensIndex>ir</wpml:payloadLensIndex>"))}
    }
}
