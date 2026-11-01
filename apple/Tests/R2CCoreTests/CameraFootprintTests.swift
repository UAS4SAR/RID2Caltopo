import XCTest
@testable import R2CCore

final class CameraFootprintTests: XCTestCase {
    private func input(tilt: Double = -90, azimuth: Double = 0) -> CameraFootprintInput {
        .init(latitude:39,longitude:-121,launchLatitude:39,launchLongitude:-121,
              height:100,azimuth:azimuth,tilt:tilt,horizontalFov:90,verticalFov:60)
    }
    func testNadirRectangleUsesBothFieldsOfView() {
        let outline=CameraFootprintGeometry.project(input())
        XCTAssertEqual(outline.count,4)
        XCTAssertTrue(outline.closed)
        XCTAssertTrue(outline.vertices.allSatisfy { !$0.clipped && $0.corner })
        // Near-left at nadir heading north is south-west of the aircraft.
        let north=(outline.vertices[0].latitude-39)*Double.pi/180*6378137
        let east=(outline.vertices[0].longitude+121)*Double.pi/180*6378137*cos(39*Double.pi/180)
        XCTAssertEqual(north,-100*tan(Double.pi/6),accuracy:0.001)
        XCTAssertEqual(east,-100,accuracy:0.001)
    }
    func testTerrainMatchesFlatPlaneRegardlessOfAbsoluteElevation() {
        let flat=CameraFootprintGeometry.project(input())
        let terrain=CameraFootprintGeometry.project(input(),elevation:{ _,_ in 900 })
        XCTAssertEqual(terrain.count,32)
        XCTAssertTrue(terrain.closed)
        for index in 0..<4 {
            XCTAssertEqual(flat.vertices[index].latitude,terrain.vertices[index*8].latitude,accuracy:1e-7)
            XCTAssertEqual(flat.vertices[index].longitude,terrain.vertices[index*8].longitude,accuracy:1e-7)
        }
    }
    func testCoverageBoundaryClipsBeforeFlatIntersection() {
        let outline=CameraFootprintGeometry.project(input(),elevation:{ lat,lon in
            abs(lat-39) < 0.0002 && abs(lon+121) < 0.0002 ? 900 : nil
        })
        XCTAssertEqual(outline.count,32)
        XCTAssertTrue(outline.closed)
        XCTAssertFalse(outline.clamped)
        XCTAssertTrue(outline.vertices.allSatisfy(\.clipped))
        XCTAssertTrue(outline.vertices.allSatisfy { abs($0.latitude-39) < 0.000201 && abs($0.longitude+121) < 0.000201 })
    }
    func testHorizonAndMissingCoverageDoNotProduceInfiniteVertices() {
        let outline=CameraFootprintGeometry.project(input(tilt:0))
        XCTAssertFalse(outline.closed)
        XCTAssertTrue(outline.clamped)
        XCTAssertTrue(outline.vertices.first?.clipped == true)
        XCTAssertTrue(outline.vertices.allSatisfy { $0.latitude.isFinite && $0.longitude.isFinite })
        XCTAssertTrue(CameraFootprintGeometry.project(input(),elevation:{ _,_ in nil }).isEmpty)
        XCTAssertTrue(CameraFootprintGeometry.project(input(),cancelled:{ true }).isEmpty)
    }
    func testAzimuthRotatesNadirRectangle() {
        let p=CameraFootprintGeometry.project(input(azimuth:90)).vertices[0]
        let north=(p.latitude-39)*Double.pi/180*6378137
        let east=(p.longitude+121)*Double.pi/180*6378137*cos(39*Double.pi/180)
        XCTAssertEqual(north,100,accuracy:0.001)
        XCTAssertEqual(east,-100*tan(Double.pi/6),accuracy:0.001)
    }
    func testLaunchReferencedHeightAccountsForRaisedGroundAtAircraft() {
        let i=CameraFootprintInput(latitude:39,longitude:-121,launchLatitude:38.99,launchLongitude:-121,
                                   height:100,azimuth:0,tilt:-90,horizontalFov:90,verticalFov:60)
        let outline=CameraFootprintGeometry.project(i,elevation:{ lat,_ in lat < 38.995 ? 900 : 950 })
        let east=(outline.vertices[0].longitude+121)*Double.pi/180*6378137*cos(39*Double.pi/180)
        XCTAssertEqual(east,-50,accuracy:0.01)
        XCTAssertTrue(outline.vertices.allSatisfy { !$0.clipped })
    }
    func testInvalidHeightAndFovAreRejected() {
        let i=CameraFootprintInput(latitude:39,longitude:-121,launchLatitude:39,launchLongitude:-121,
                                   height:0,azimuth:0,tilt:-90,horizontalFov:180,verticalFov:60)
        XCTAssertTrue(CameraFootprintGeometry.project(i).isEmpty)
    }
    func testCornersSurviveMissingOrUnfinishedTerrain() {
        let pending=CameraFootprintDrawing(input:input(),terrain:nil)
        let missing=CameraFootprintDrawing(input:input(),terrain:.empty)
        XCTAssertEqual(pending.corners.count,4)
        XCTAssertEqual(pending.corners,missing.corners)
        XCTAssertTrue(pending.boundary.isEmpty && missing.boundary.isEmpty)
    }
    func testFourRayTerrainPassHasBoundedSampleCount() {
        var samples=0
        let outline=CameraFootprintGeometry.project(input(),elevation:{ _,_ in samples += 1; return 900 },edgeSubdivisions:1)
        XCTAssertEqual(outline.count,4)
        XCTAssertTrue(outline.vertices.allSatisfy(\.corner))
        XCTAssertLessThan(samples,200)
        let drawing=CameraFootprintDrawing(input:input(),terrain:outline)
        XCTAssertEqual(drawing.boundary,outline)
        XCTAssertEqual(drawing.corners.vertices,outline.vertices)
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
    func testIdenticalPoseReusesTerrainAndASlowOrbitStaysInsideTolerance() {
        let origin = input()
        XCTAssertTrue(cameraFootprintTerrainReusable(cached: origin, current: origin))
        let moved = CameraFootprintInput(
            latitude: 39.00005, longitude: -121, launchLatitude: 39, launchLongitude: -121,
            height: 104, azimuth: 6, tilt: -88, horizontalFov: 90, verticalFov: 60)
        XCTAssertLessThan(cameraFootprintDistanceMeters(39, -121, 39.00005, -121), cameraFootprintTerrainPositionMeters)
        XCTAssertTrue(cameraFootprintTerrainReusable(cached: origin, current: moved))
        XCTAssertEqual(cameraFootprintAngleDeltaDegrees(359, 5), 6, accuracy: 1e-9)
        XCTAssertTrue(cameraFootprintTerrainReusable(cached: input(azimuth: 359), current: input(azimuth: 5)))
    }
    func testLargePoseChangeDropsTheTerrainOutline() {
        let origin = input()
        XCTAssertFalse(cameraFootprintTerrainReusable(cached: origin, current: input().latitudeShifted(39.01)))
        XCTAssertFalse(cameraFootprintTerrainReusable(cached: input(azimuth: 359), current: input(azimuth: 20)))
        XCTAssertFalse(cameraFootprintTerrainReusable(cached: origin, current: origin.fov(horizontal: 94)))
        XCTAssertFalse(cameraFootprintTerrainReusable(cached: origin, current: origin.fov(vertical: 64)))
        XCTAssertFalse(cameraFootprintTerrainReusable(cached: origin, current: origin.height(120)))
        XCTAssertFalse(cameraFootprintTerrainReusable(cached: origin, current: origin.tilt(-80)))
        XCTAssertFalse(cameraFootprintTerrainReusable(cached: origin, current: origin.launch(39.01)))
        XCTAssertLessThan(cameraFootprintDistanceMeters(0, 179.9, 0, -179.9), 30_000)
    }
    func testCancelledPassKeepsPreviousOutlineUntilThePoseLeavesTolerance() {
        let origin = input()
        let near = origin.longitudeShifted(-121.0001)
        let far = origin.latitudeShifted(40)
        let terrain = CameraFootprintOutline(
            vertices: [CameraFootprintVertex(latitude: 1, longitude: 2, clipped: false, corner: true)],
            closed: true
        )
        let previous = ["a": (origin, terrain)]
        let kept = mergeCameraFootprintTerrain(previous: previous, computed: ["a": (near, .empty)], current: ["a": near])
        XCTAssertEqual(kept["a"]?.1, terrain)
        XCTAssertTrue(mergeCameraFootprintTerrain(previous: previous, computed: [:], current: ["a": far]).isEmpty)
        let fresh = CameraFootprintOutline(
            vertices: [CameraFootprintVertex(latitude: 3, longitude: 4, clipped: false, corner: true)],
            closed: true
        )
        XCTAssertEqual(mergeCameraFootprintTerrain(previous: previous, computed: ["a": (near, fresh)], current: ["a": near])["a"]?.1, fresh)
        XCTAssertTrue(mergeCameraFootprintTerrain(previous: previous, computed: previous, current: [:]).isEmpty)
    }
    func testLiveCornersTrackTheNewPoseWhileAReusableOutlineStays() {
        let origin = input()
        let moved = origin.latitudeShifted(39.00005)
        let terrain = CameraFootprintGeometry.project(origin, elevation: { _, _ in 900 }, edgeSubdivisions: 1)
        let drawing = CameraFootprintDrawing(input: moved, terrain: terrain, liveCorners: true)
        XCTAssertEqual(drawing.boundary, terrain)
        XCTAssertEqual(drawing.corners, CameraFootprintGeometry.project(moved))
        XCTAssertFalse(cameraFootprintTerrainReusable(cached: origin, current: origin.latitudeShifted(39.01)))
        let hidden = CameraFootprintDrawing(input: origin.latitudeShifted(39.01), terrain: nil, liveCorners: true)
        XCTAssertTrue(hidden.boundary.isEmpty)
        XCTAssertEqual(hidden.corners, CameraFootprintGeometry.project(origin.latitudeShifted(39.01)))
    }
    func testAdSubframeRaysScaleTheTangentAndPreserveAspect() throws {
        XCTAssertNil(cameraFootprintAdScanRect(.nan))
        let rect = try XCTUnwrap(cameraFootprintAdScanRect(0.5))
        XCTAssertEqual(rect.left, 0.25, accuracy: 1e-12)
        XCTAssertEqual(rect.bottom, 0.75, accuracy: 1e-12)
        let h = tan(45 * Double.pi / 180)
        let v = tan(30 * Double.pi / 180)
        let offsets = try XCTUnwrap(cameraFootprintSubframeRayOffsets(horizontalFovDeg: 90, verticalFovDeg: 60, rect: rect))
        XCTAssertEqual(offsets[0].0, -0.5 * h, accuracy: 1e-9)
        XCTAssertEqual(offsets[0].1, 0.5 * v, accuracy: 1e-9)
        XCTAssertEqual(offsets[1].0, 0.5 * h, accuracy: 1e-9)
        XCTAssertEqual(offsets[2].1, -0.5 * v, accuracy: 1e-9)
        XCTAssertEqual(offsets[3].0, -0.5 * h, accuracy: 1e-9)
        let shifted = try XCTUnwrap(cameraFootprintSubframeRayOffsets(
            horizontalFovDeg: 90, verticalFovDeg: 60,
            rect: CameraFootprintFrameRect(left: 0, top: 0, right: 0.5, bottom: 0.5)
        ))
        XCTAssertEqual(shifted[0].0, -h, accuracy: 1e-9)
        XCTAssertEqual(shifted[1].0, 0, accuracy: 1e-9)
        XCTAssertEqual(shifted[0].1, v, accuracy: 1e-9)
        XCTAssertNil(cameraFootprintSubframeRayOffsets(
            horizontalFovDeg: 90, verticalFovDeg: 60,
            rect: CameraFootprintFrameRect(left: -0.1, top: 0, right: 0.5, bottom: 1)
        ))
        let full = input()
        XCTAssertEqual(cameraFootprintApplyingAdScanZone(full, scanZoneFraction: nil).horizontalFov, 90)
        XCTAssertEqual(cameraFootprintApplyingAdScanZone(full, scanZoneFraction: 1).horizontalFov, 90, accuracy: 1e-9)
        let half = cameraFootprintApplyingAdScanZone(full, scanZoneFraction: 0.5)
        XCTAssertEqual(half.horizontalFov, cameraFootprintApplyingAdScanZone(full, scanZoneFraction: 0.1).horizontalFov, accuracy: 1e-9)
        XCTAssertEqual(half.horizontalFov, atan(0.5 * h) * 360 / Double.pi, accuracy: 1e-6)
        XCTAssertEqual(half.verticalFov, atan(0.5 * v) * 360 / Double.pi, accuracy: 1e-6)
        XCTAssertEqual(
            tan(half.horizontalFov * Double.pi / 360) / tan(half.verticalFov * Double.pi / 360),
            h / v,
            accuracy: 1e-9
        )
        let outline = CameraFootprintGeometry.project(half)
        let north = (outline.vertices[0].latitude - 39) * Double.pi / 180 * 6_378_137
        let east = (outline.vertices[0].longitude + 121) * Double.pi / 180 * 6_378_137 * cos(39 * Double.pi / 180)
        XCTAssertEqual(north, -50 * v, accuracy: 0.05)
        XCTAssertEqual(east, -50, accuracy: 0.05)
        let halfAngle = CameraFootprintGeometry.project(full.fov(horizontal: 45, vertical: 30))
        let halfAngleEast = (halfAngle.vertices[0].longitude + 121) * Double.pi / 180 * 6_378_137 * cos(39 * Double.pi / 180)
        XCTAssertGreaterThan(abs(halfAngleEast - east), 5)
    }
    func testFootprintPreferenceKeyIsStableAndBlankStaysUnset() {
        XCTAssertEqual(cameraFootprintPreferenceKey(" abc-1 "), "ABC-1")
        XCTAssertEqual(cameraFootprintPreferenceKey("abc-1"), "ABC-1")
        XCTAssertNil(cameraFootprintPreferenceKey("  "))
        XCTAssertNil(cameraFootprintPreferenceKey(""))
    }
    func testToggleOffRemovesOverlayAndToggleOnYieldsExactlyOne() {
        let book = CameraFootprintOverlayBook()
        let pose = input(tilt: -16, azimuth: 203)
        XCTAssertEqual(book.sync(inputs: ["matrice": pose]).count, 1)
        XCTAssertEqual(book.outlineCount("matrice"), 1)
        XCTAssertTrue(book.sync(inputs: [:]).isEmpty)
        XCTAssertEqual(book.outlineCount("matrice"), 0)
        XCTAssertEqual(book.sync(inputs: ["matrice": pose]).count, 1)
        XCTAssertEqual(book.outlineCount("matrice"), 1)
        XCTAssertEqual(book.size, 1)
    }
    func testSingleOverlayPerDroneEvenWhenTerrainAndLiveCornersDiffer() {
        let origin = input(tilt: -45, azimuth: 203)
        let moved = CameraFootprintInput(
            latitude: origin.latitude, longitude: origin.longitude,
            launchLatitude: origin.launchLatitude, launchLongitude: origin.launchLongitude,
            height: origin.height, azimuth: 206, tilt: -44,
            horizontalFov: origin.horizontalFov, verticalFov: origin.verticalFov
        )
        let terrain = CameraFootprintGeometry.project(origin, elevation: { _, _ in 900 }, edgeSubdivisions: 1)
        let drawing = CameraFootprintDrawing(input: moved, terrain: terrain, liveCorners: true)
        XCTAssertGreaterThanOrEqual(drawing.corners.count, 3)
        XCTAssertGreaterThanOrEqual(drawing.boundary.count, 3)
        XCTAssertEqual([cameraFootprintDisplayOutline(drawing)].count, 1)
        let book = CameraFootprintOverlayBook()
        book.sync(inputs: ["a": moved], terrain: ["a": (origin, terrain)])
        XCTAssertEqual(book.outlineCount("a"), 1)
        XCTAssertEqual(book.size, 1)
    }
    func testOverlayRemovedWhenFlightOrStreamEnds() {
        let book = CameraFootprintOverlayBook()
        book.sync(inputs: ["a": input(), "b": input(azimuth: 90)])
        XCTAssertEqual(book.size, 2)
        book.sync(inputs: ["b": input(azimuth: 90)])
        XCTAssertEqual(book.outlineCount("a"), 0)
        XCTAssertEqual(book.outlineCount("b"), 1)
        book.sync(inputs: [:])
        XCTAssertEqual(book.size, 0)
    }
    func testDepthGrowsMonotonicallyFromNadirToShallow() {
        var previousFar = -1.0
        var previousDepth = -1.0
        for tilt in stride(from: -90, through: -5, by: 1) {
            let outline = CameraFootprintGeometry.project(input(tilt: Double(tilt)), edgeSubdivisions: 1)
            XCTAssertFalse(outline.isEmpty)
            XCTAssertGreaterThanOrEqual(
                outline.farGroundMeters + 1e-6, previousFar,
                "Far reach should grow as tilt goes from -90 toward -5; at \(tilt) far=\(outline.farGroundMeters) previous=\(previousFar)"
            )
            previousFar = outline.farGroundMeters
            if outline.closed {
                let depth = cameraFootprintDepthMeters(outline)
                XCTAssertGreaterThanOrEqual(
                    depth + 1e-6, previousDepth,
                    "Closed depth should grow as tilt goes from -90 toward -5; at \(tilt) depth=\(depth) previous=\(previousDepth)"
                )
                previousDepth = depth
            }
        }
    }
    func testUnboundedFootprintIsOpenWithSidesAtMaxRange() {
        let shallow = input(tilt: -10, azimuth: 203).height(30).fov(horizontal: 90, vertical: 60)
        let outline = CameraFootprintGeometry.project(shallow, edgeSubdivisions: 8)
        XCTAssertFalse(outline.isEmpty)
        XCTAssertFalse(outline.closed)
        XCTAssertTrue(outline.clamped)
        let corners = outline.vertices.filter(\.corner)
        XCTAssertEqual(corners.count, 4)
        XCTAssertTrue(corners[0].clipped && corners[3].clipped)
        let farLeftRange = cameraFootprintDistanceMeters(shallow.latitude, shallow.longitude, corners[0].latitude, corners[0].longitude)
        let farRightRange = cameraFootprintDistanceMeters(shallow.latitude, shallow.longitude, corners[3].latitude, corners[3].longitude)
        XCTAssertEqual(farLeftRange, CameraFootprintGeometry.maximumRange, accuracy: 1)
        XCTAssertEqual(farRightRange, CameraFootprintGeometry.maximumRange, accuracy: 1)
        XCTAssertEqual(outline.farGroundMeters, CameraFootprintGeometry.maximumRange, accuracy: 1)
        XCTAssertFalse(cameraFootprintOutlineSelfIntersects(outline))
    }
    func testNoSelfIntersectionAcrossGimbalHeadingAndSlopeSweep() {
        let tilts = stride(from: -90, through: 0, by: 5).map(Double.init)
        let headings = [0.0, 45.0, 90.0, 203.0, 315.0]
        for tilt in tilts {
            for heading in headings {
                let pose = input(tilt: tilt, azimuth: heading).height(30)
                let flat = CameraFootprintGeometry.project(pose, edgeSubdivisions: 8)
                XCTAssertFalse(cameraFootprintOutlineSelfIntersects(flat), "flat tilt=\(tilt) hdg=\(heading)")
                let slope = CameraFootprintGeometry.project(pose, elevation: { lat, lon in
                    let east = (lon + 121) * .pi / 180 * 6_378_137 * cos(39 * .pi / 180)
                    let north = (lat - 39) * .pi / 180 * 6_378_137
                    return 900 + 0.15 * east + 0.08 * north
                }, edgeSubdivisions: 8)
                if slope.isEmpty { continue }
                let display = cameraFootprintDisplayOutline(CameraFootprintDrawing(input: pose, terrain: slope, liveCorners: true))
                XCTAssertFalse(cameraFootprintOutlineSelfIntersects(display), "display tilt=\(tilt) hdg=\(heading)")
            }
        }
    }
    func testDebugLineIncludesNearFarAndClampedWithoutCoordinates() {
        let line = cameraFootprintDebugLine(
            source: "SEI",
            gimbalTiltDeg: -12.3,
            headingDeg: 203,
            aglMeters: 95.4,
            nearGroundMeters: 140.6,
            farGroundMeters: 3000.2,
            clamped: true
        )
        XCTAssertTrue(line.contains("nearM=141"))
        XCTAssertTrue(line.contains("farM=3000"))
        XCTAssertTrue(line.contains("clamped=true"))
        XCTAssertFalse(line.contains("39."))
        XCTAssertFalse(line.contains("-121"))
    }
}

private extension CameraFootprintInput {
    func latitudeShifted(_ latitude: Double) -> CameraFootprintInput {
        CameraFootprintInput(latitude: latitude, longitude: longitude, launchLatitude: launchLatitude,
                             launchLongitude: launchLongitude, height: height, azimuth: azimuth, tilt: tilt,
                             horizontalFov: horizontalFov, verticalFov: verticalFov)
    }
    func longitudeShifted(_ longitude: Double) -> CameraFootprintInput {
        CameraFootprintInput(latitude: latitude, longitude: longitude, launchLatitude: launchLatitude,
                             launchLongitude: launchLongitude, height: height, azimuth: azimuth, tilt: tilt,
                             horizontalFov: horizontalFov, verticalFov: verticalFov)
    }
    func fov(horizontal: Double? = nil, vertical: Double? = nil) -> CameraFootprintInput {
        CameraFootprintInput(latitude: latitude, longitude: longitude, launchLatitude: launchLatitude,
                             launchLongitude: launchLongitude, height: height, azimuth: azimuth, tilt: tilt,
                             horizontalFov: horizontal ?? horizontalFov, verticalFov: vertical ?? verticalFov)
    }
    func height(_ height: Double) -> CameraFootprintInput {
        CameraFootprintInput(latitude: latitude, longitude: longitude, launchLatitude: launchLatitude,
                             launchLongitude: launchLongitude, height: height, azimuth: azimuth, tilt: tilt,
                             horizontalFov: horizontalFov, verticalFov: verticalFov)
    }
    func tilt(_ tilt: Double) -> CameraFootprintInput {
        CameraFootprintInput(latitude: latitude, longitude: longitude, launchLatitude: launchLatitude,
                             launchLongitude: launchLongitude, height: height, azimuth: azimuth, tilt: tilt,
                             horizontalFov: horizontalFov, verticalFov: verticalFov)
    }
    func launch(_ launchLatitude: Double) -> CameraFootprintInput {
        CameraFootprintInput(latitude: latitude, longitude: longitude, launchLatitude: launchLatitude,
                             launchLongitude: launchLongitude, height: height, azimuth: azimuth, tilt: tilt,
                             horizontalFov: horizontalFov, verticalFov: verticalFov)
    }
}
