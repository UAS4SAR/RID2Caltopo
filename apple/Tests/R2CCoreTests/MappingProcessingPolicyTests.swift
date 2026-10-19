import XCTest
@testable import R2CCore

final class MappingProcessingPolicyTests: XCTestCase {
    func testBoundedRegionsCoverEveryOriginalAndAcreage() throws {
        let photos=(0..<500).map { MappingProcessingPolicy.Photo(digest:String(format:"%04d",$0),x:Double($0%25)*4,y:Double($0/25)*4) }
        let regions=try MappingProcessingPolicy.regions(photos:photos,bounds:.init(west:0,south:0,east:100,north:80),groundScale:1)
        XCTAssertEqual(Set(regions.flatMap(\.core).map(\.digest)),Set(photos.map(\.digest)))
        XCTAssertEqual(regions.reduce(0){$0+$1.core.count},500)
        for region in regions {
            XCTAssertLessThanOrEqual(region.core.count,128);XCTAssertLessThanOrEqual(region.images.count,192)
            XCTAssertLessThanOrEqual(region.bounds.area(scale:1),2*MappingProcessingPolicy.acre)
            XCTAssertTrue(Set(region.core.map(\.digest)).isSubset(of:Set(region.images.map(\.digest))))
        }
        XCTAssertEqual(regions,try MappingProcessingPolicy.regions(photos:photos.reversed(),bounds:.init(west:0,south:0,east:100,north:80),groundScale:1))
    }
    func testSmallFlightStillSplitsLargeArea() throws {
        let photos=(0..<3).map { MappingProcessingPolicy.Photo(digest:String($0),x:Double($0),y:1) }
        let regions=try MappingProcessingPolicy.regions(photos:photos,bounds:.init(west:0,south:0,east:200,north:200),groundScale:1)
        XCTAssertGreaterThan(regions.count,1);XCTAssertTrue(regions.allSatisfy{$0.images.count==3})
    }
    func testStorageWarnsAtEightyPercentWithoutArtificialQuota() throws {
        let estimate=try MappingProcessingPolicy.storage(photos:500,width:4032,height:3024,areaSquareMeters:8000,pixelMeters:0.02,regional:true)
        let capacity:Int64=1_000_000_000_000
        XCTAssertFalse(estimate.approachesCapacity(total:capacity,available:capacity))
        XCTAssertTrue(estimate.approachesCapacity(total:capacity,available:capacity/5))
    }
    func testDisplayClockDoesNotInventWorkerActivity() {
        let start=Date(timeIntervalSince1970:0),activity=Date(timeIntervalSince1970:10)
        let snapshot=MappingProgressSnapshot(stage:"Saving region 2/2",fraction:0.92,startedAt:start,activityAt:activity)
        XCTAssertEqual(snapshot.elapsed(at:Date(timeIntervalSince1970:70)),70)
        XCTAssertEqual(snapshot.activity(at:Date(timeIntervalSince1970:70)),"Last worker update 60 sec ago · waiting for the current operation")
        XCTAssertEqual(snapshot.fraction,0.92)
    }
}
