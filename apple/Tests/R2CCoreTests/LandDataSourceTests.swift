import Foundation
import Testing
@testable import R2CCore

@Test func authoritativeNpsSourceUsesThePolygonLayerAndDocumentedFields() throws {
    let source = try #require(OperationalLandRestriction.sources.first { $0.id == "nps" })
    #expect(source.queryEndpoint.host == "services1.arcgis.com")
    #expect(source.queryEndpoint.path.contains("/fBc8EJBxQRMcHlei/"))
    #expect(source.queryEndpoint.path.hasSuffix("/NPS_Land_Resources_Division_Boundary_and_Tract_Data_Service/FeatureServer/2/query"))
    #expect(source.identifierFields == ["UNIT_CODE", "GlobalID", "OBJECTID"])
    #expect(source.detailsURLFields == ["METADATA"])
}
@Test func authoritativeNpsGeoJSONParsesRealYosemitePolygon() throws {
    let url = try #require(Bundle.module.url(forResource: "nps-yosemite", withExtension: "geojson", subdirectory: "Fixtures"))
    let source = try #require(OperationalLandRestriction.sources.first { $0.id == "nps" })
    let areas = try OperationalLandRestriction.parse(Data(contentsOf:url),source:source,
        center:.init(latitude:37.75,longitude:-119.55),operatingRadiusNM:1)
    let park = try #require(areas.first)
    #expect(areas.count == 1)
    #expect(park.name == "Yosemite National Park" && park.id == "nps:YOSE")
    #expect(park.polygons[0][0].count > 100)
    #expect(park.containsOperator)
    #expect(park.detailsURL?.host == "landsnet.nps.gov")
}
@Test func authoritativeNpsCacheRejectsLegacyMirrorResults() {
    #expect(!OperationalLandRestriction.acceptsCacheRevision(nil))
    #expect(!OperationalLandRestriction.acceptsCacheRevision(1))
    #expect(OperationalLandRestriction.acceptsCacheRevision(OperationalLandRestriction.cacheRevision))
}
@Test func landNoticesCreditProvidersAndKeepWildernessAdvisory() throws {
    for agency in OperationalLandAgency.allCases {
        #expect(!agency.dataCredit.isEmpty && !agency.dataNotice.isEmpty)
        #expect(agency.dataMetadataURL.scheme == "https")
    }
    let source = try #require(OperationalLandRestriction.sources.first { $0.agency == .forestService })
    #expect(source.rule == .mappedWildernessAdvisory)
    #expect(source.rule.label == "Mapped wilderness—verify current rules")
    #expect(OperationalLandAgency.forestService.dataNotice.contains("must not be used to determine"))
    #expect(OperationalLandAgency.nationalParkService.dataNotice.contains("ownership"))
    #expect(OperationalStatusChipText.land(severity:.danger,detailedLabel:"Mapped land: verify rules") == "Verify land rules")
}
