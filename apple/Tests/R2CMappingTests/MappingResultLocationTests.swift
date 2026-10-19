import XCTest
import Foundation
@testable import R2CMapping

final class MappingResultLocationTests:XCTestCase {
    private let mission="9a30f47c-ea6d-4dea-a864-05862e8b47ee"
    private let layer="567e218f-d34b-4b7f-bcaf-7168b5666955"
    private let root=URL(fileURLWithPath:"/new-container/Library/Application Support/mapping-missions",isDirectory:true)
    func testUpdateRebasesLegacyAbsolutePathWithoutNeedingOldContainer() throws {
        let old="/old-container/Library/Application Support/mapping-missions/\(mission)/results/\(layer)"
        let result=try XCTUnwrap(MappingMissionStore.savedResult(old,layerID:layer,root:root))
        XCTAssertEqual(result.path,root.appendingPathComponent("\(mission)/results/\(layer)").path)
        XCTAssertFalse(result.path.contains("old-container"))
    }
    func testPortableReferenceSurvivesContainerChanges() {
        let reference="\(mission)/results/\(layer)"
        XCTAssertEqual(MappingMissionStore.savedResult(reference,layerID:layer,root:root)?.path,root.appendingPathComponent(reference).path)
    }
    func testRejectsTraversalWrongLayerAndUnrelatedPaths() {
        for reference in ["../\(mission)/results/\(layer)","\(mission)/../results/\(layer)","not-a-mission/results/\(layer)","/unrelated/\(mission)/results/\(layer)","\(mission)/results/\(mission)"] {
            XCTAssertNil(MappingMissionStore.savedResult(reference,layerID:layer,root:root),reference)
        }
    }
}
