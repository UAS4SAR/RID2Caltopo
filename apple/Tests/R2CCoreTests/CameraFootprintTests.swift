import XCTest
@testable import R2CCore

final class CameraFootprintTests: XCTestCase {
    private func input(tilt: Double = -90, azimuth: Double = 0) -> CameraFootprintInput {
        .init(latitude:39,longitude:-121,launchLatitude:39,launchLongitude:-121,
              height:100,azimuth:azimuth,tilt:tilt,horizontalFov:90,verticalFov:60)
    }
    func testNadirRectangleUsesBothFieldsOfView() {
        let points=CameraFootprintGeometry.project(input())
        XCTAssertEqual(points.count,4)
        XCTAssertTrue(points.allSatisfy { !$0.clipped && $0.corner })
        let north=(points[0].latitude-39)*Double.pi/180*6378137
        let east=(points[0].longitude+121)*Double.pi/180*6378137*cos(39*Double.pi/180)
        XCTAssertEqual(north,100*tan(Double.pi/6),accuracy:0.001)
        XCTAssertEqual(east,-100,accuracy:0.001)
    }
    func testTerrainMatchesFlatPlaneRegardlessOfAbsoluteElevation() {
        let flat=CameraFootprintGeometry.project(input())
        let terrain=CameraFootprintGeometry.project(input(),elevation:{ _,_ in 900 })
        XCTAssertEqual(terrain.count,32)
        for index in 0..<4 {
            XCTAssertEqual(flat[index].latitude,terrain[index*8].latitude,accuracy:1e-7)
            XCTAssertEqual(flat[index].longitude,terrain[index*8].longitude,accuracy:1e-7)
        }
    }
    func testCoverageBoundaryClipsBeforeFlatIntersection() {
        let points=CameraFootprintGeometry.project(input(),elevation:{ lat,lon in
            abs(lat-39) < 0.0002 && abs(lon+121) < 0.0002 ? 900 : nil
        })
        XCTAssertEqual(points.count,32)
        XCTAssertTrue(points.allSatisfy(\.clipped))
        XCTAssertTrue(points.allSatisfy { abs($0.latitude-39) < 0.000201 && abs($0.longitude+121) < 0.000201 })
    }
    func testHorizonAndMissingCoverageDoNotProduceInfiniteVertices() {
        let points=CameraFootprintGeometry.project(input(tilt:0))
        XCTAssertTrue(points[0].clipped && points[1].clipped)
        XCTAssertTrue(points.allSatisfy { $0.latitude.isFinite && $0.longitude.isFinite })
        XCTAssertTrue(CameraFootprintGeometry.project(input(),elevation:{ _,_ in nil }).isEmpty)
        XCTAssertTrue(CameraFootprintGeometry.project(input(),cancelled:{ true }).isEmpty)
    }
    func testAzimuthRotatesNadirRectangle() {
        let p=CameraFootprintGeometry.project(input(azimuth:90))[0]
        let north=(p.latitude-39)*Double.pi/180*6378137
        let east=(p.longitude+121)*Double.pi/180*6378137*cos(39*Double.pi/180)
        XCTAssertEqual(north,100,accuracy:0.001)
        XCTAssertEqual(east,100*tan(Double.pi/6),accuracy:0.001)
    }
    func testLaunchReferencedHeightAccountsForRaisedGroundAtAircraft() {
        let i=CameraFootprintInput(latitude:39,longitude:-121,launchLatitude:38.99,launchLongitude:-121,
                                   height:100,azimuth:0,tilt:-90,horizontalFov:90,verticalFov:60)
        let points=CameraFootprintGeometry.project(i,elevation:{ lat,_ in lat < 38.995 ? 900 : 950 })
        let east=(points[0].longitude+121)*Double.pi/180*6378137*cos(39*Double.pi/180)
        XCTAssertEqual(east,-50,accuracy:0.01)
        XCTAssertTrue(points.allSatisfy { !$0.clipped })
    }
    func testInvalidHeightAndFovAreRejected() {
        let i=CameraFootprintInput(latitude:39,longitude:-121,launchLatitude:39,launchLongitude:-121,
                                   height:0,azimuth:0,tilt:-90,horizontalFov:180,verticalFov:60)
        XCTAssertTrue(CameraFootprintGeometry.project(i).isEmpty)
    }
    func testCornersSurviveMissingOrUnfinishedTerrain() {
        let pending=CameraFootprintDrawing(input:input(),terrain:nil)
        let missing=CameraFootprintDrawing(input:input(),terrain:[])
        XCTAssertEqual(pending.corners.count,4)
        XCTAssertEqual(pending.corners,missing.corners)
        XCTAssertTrue(pending.boundary.isEmpty && missing.boundary.isEmpty)
    }
    func testFourRayTerrainPassHasBoundedSampleCount() {
        var samples=0
        let points=CameraFootprintGeometry.project(input(),elevation:{ _,_ in samples += 1; return 900 },edgeSubdivisions:1)
        XCTAssertEqual(points.count,4)
        XCTAssertTrue(points.allSatisfy(\.corner))
        XCTAssertLessThan(samples,200)
        let drawing=CameraFootprintDrawing(input:input(),terrain:points)
        XCTAssertEqual(drawing.boundary,points)
        XCTAssertEqual(drawing.corners,points)
    }
    func testCancelledTerrainBudgetCannotEraseImmediateCorners() {
        var samples=0
        let terrain=CameraFootprintGeometry.project(input(tilt:0),elevation:{ _,_ in samples += 1; return 900 },
                                                  cancelled:{ samples >= 12 },edgeSubdivisions:1)
        XCTAssertTrue(terrain.isEmpty)
        XCTAssertLessThanOrEqual(samples,12)
        XCTAssertEqual(CameraFootprintDrawing(input:input(tilt:0),terrain:terrain).corners.count,4)
    }
    func testCornerArmsPointAlongAdjacentSkewedEdges() {
        let points=[MapScreenPoint(x:0,y:0),MapScreenPoint(x:100,y:20),
                    MapScreenPoint(x:80,y:120),MapScreenPoint(x:-20,y:80)]
        let strokes=CameraFootprintGeometry.cornerStrokes(points:points,length:12)
        XCTAssertEqual(strokes.count,8)
        for (index,stroke) in strokes.enumerated() {
            let corner=index/2, neighbor=points[(index/2+(index%2 == 0 ? 3 : 1))%4]
            let dx=stroke.end.x-stroke.start.x, dy=stroke.end.y-stroke.start.y
            XCTAssertEqual(stroke.start,points[corner])
            XCTAssertEqual(hypot(dx,dy),12,accuracy:1e-8)
            XCTAssertEqual(dx*(neighbor.y-stroke.start.y)-dy*(neighbor.x-stroke.start.x),0,accuracy:1e-8)
            XCTAssertGreaterThan(dx*(neighbor.x-stroke.start.x)+dy*(neighbor.y-stroke.start.y),0)
        }
    }
    func testShortOrCollapsedEdgesDoNotProduceOverlappingOrInvalidArms() {
        let points=[MapScreenPoint(x:0,y:0),MapScreenPoint(x:6,y:0),
                    MapScreenPoint(x:6,y:6),MapScreenPoint(x:0,y:6)]
        for arm in CameraFootprintGeometry.cornerStrokes(points:points,length:12) {
            XCTAssertEqual(hypot(arm.end.x-arm.start.x,arm.end.y-arm.start.y),2.4,accuracy:1e-8)
        }
        var collapsed=points; collapsed[1]=collapsed[0]
        XCTAssertEqual(CameraFootprintGeometry.cornerStrokes(points:collapsed,length:12).count,6)
        XCTAssertTrue(CameraFootprintGeometry.cornerStrokes(points:points,length:.nan).isEmpty)
    }
}
