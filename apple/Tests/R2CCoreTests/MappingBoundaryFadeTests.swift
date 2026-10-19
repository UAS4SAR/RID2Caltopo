import Foundation
import XCTest
@testable import R2CCore

final class MappingBoundaryFadeTests: XCTestCase {
    private func ring(_ points: [[Double]]) -> [[Double]] {
        points.map { [$0[0] / 6_378_137 * 180 / .pi,
                      (2 * atan(exp($0[1] / 6_378_137)) - .pi / 2) * 180 / .pi] }
    }
    func testFiveMetreFadeRetainsInteriorAndFadesOnlyOutside() throws {
        let fade = try XCTUnwrap(MappingBoundaryFade(ring: ring([[0,0],[100,0],[100,100],[0,100],[0,0]])))
        XCTAssertEqual(fade.opacity(projectedX: 50, projectedY: 50), 1)
        XCTAssertEqual(fade.opacity(projectedX: 102.5, projectedY: 50), 0.5, accuracy: 0.000001)
        XCTAssertEqual(fade.opacity(projectedX: 106, projectedY: 50), 0)
    }
    func testInteriorTileBypassesPixelWorkButBoundaryTileDoesNot() throws {
        // Zoom 20 tile immediately east/south of the equator spans about 38 m.
        let fade = try XCTUnwrap(MappingBoundaryFade(ring: ring([[-100,-100],[100,-100],[100,100],[-100,100],[-100,-100]])))
        XCTAssertEqual(fade.coverage(zoom: 20, x: 524288, y: 524288), .inside)
        XCTAssertEqual(fade.coverage(zoom: 20, x: 524290, y: 524288), .edge)
        XCTAssertEqual(fade.coverage(zoom: 20, x: 524300, y: 524288), .outside)
    }
    func testConcaveNotchIsNotMistakenForInterior() throws {
        let fade = try XCTUnwrap(MappingBoundaryFade(ring: ring([[-100,-100],[100,-100],[100,-50],[-50,-50],[-50,100],[-100,100],[-100,-100]])))
        XCTAssertEqual(fade.coverage(zoom: 22, x: 2097152, y: 2097152), .outside)
        XCTAssertEqual(fade.opacity(projectedX: 0, projectedY: 0), 0)
        XCTAssertEqual(fade.opacity(projectedX: -75, projectedY: 0), 1)
    }
    func testOutsideTileWithinFadeDistanceStillReceivesEdgeTreatment() throws {
        // This tile starts at x=0; the polygon ends two metres to its west.
        let fade = try XCTUnwrap(MappingBoundaryFade(ring: ring([[-100,-100],[-2,-100],[-2,100],[-100,100],[-100,-100]])))
        XCTAssertEqual(fade.coverage(zoom: 22, x: 2097152, y: 2097152), .edge)
        XCTAssertGreaterThan(fade.opacity(projectedX: 0.5, projectedY: -5), 0)
    }
    func testDiagonalBoundaryBoundingBoxDoesNotForceInteriorTileToBeProcessed() throws {
        let fade = try XCTUnwrap(MappingBoundaryFade(ring: ring([[-100,-100],[100,-100],[-100,100],[-100,-100]])))
        // The diagonal crosses the tile's surrounding bounding box, but this
        // tile is wholly southwest of the actual edge x+y=0.
        XCTAssertEqual(fade.coverage(zoom: 22, x: 2097147, y: 2097153), .inside)
    }
    func testInvalidBoundariesAreNotUsedForMasking() {
        XCTAssertNil(MappingBoundaryFade(ring: []))
        XCTAssertNil(MappingBoundaryFade(ring: [[0,0],[1,0],[Double.nan,1]]))
        XCTAssertNil(MappingBoundaryFade(ring: [[0,0],[1,0],[1,90]]))
    }
}
