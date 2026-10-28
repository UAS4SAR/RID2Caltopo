import Foundation
import Testing
@testable import R2CCore

private let attributionFixture = Data(#"""
{"contributors":[
{"attribution":"Local High","coverageAreas":[{"zoomMin":10,"zoomMax":20,"score":100,"bbox":[30,-125,45,-110]}]},
{"attribution":"Other Area","coverageAreas":[{"zoomMin":0,"zoomMax":23,"score":80,"bbox":[0,10,5,15]}]},
{"attribution":"Overview","coverageAreas":[{"zoomMin":0,"zoomMax":10,"score":50,"bbox":[-85,-180,85,180]}]},
{"attribution":"Local High","coverageAreas":[{"zoomMin":10,"zoomMax":20,"score":60,"bbox":[30,-125,45,-110]}]},
{"attribution":"Dateline East","coverageAreas":[{"zoomMin":0,"zoomMax":23,"score":80,"bbox":[-20,175,20,180]}]},
{"attribution":"Dateline West","coverageAreas":[{"zoomMin":0,"zoomMax":23,"score":80,"bbox":[-20,-180,20,-175]}]}
]}
"""#.utf8)

@Test func imageryAttributionSelectsAreaAndZoomAndDeduplicates() throws {
    let catalog = try EsriImageryAttribution(data: attributionFixture)
    let sources = catalog.sources(in: .init(south: 37.7, west: -119.6, north: 37.8, east: -119.5, zoom: 18))
    #expect(sources.contains("Local High"))
    #expect(sources.components(separatedBy: "Local High").count == 2)
    #expect(!sources.contains("Other Area"))
    #expect(!sources.contains("Overview"))
    #expect(sources.contains("Esri") && sources.contains("Vantor"))
}
@Test func imageryAttributionHandlesDatelineWithoutCreditingUnrelatedAreas() throws {
    let sources = try EsriImageryAttribution(data: attributionFixture).sources(in: .init(south: -1, west: 178, north: 1, east: -178, zoom: 18))
    #expect(sources.contains("Dateline East") && sources.contains("Dateline West"))
    #expect(!sources.contains("Other Area") && !sources.contains("Local High"))
}
@Test func imageryAttributionIncludesBothTileLevelsAtFractionalZoom() throws {
    let sources = try EsriImageryAttribution(data: attributionFixture).sources(in: .init(south: 37, west: -120, north: 38, east: -119, zoom: 9.5))
    #expect(sources.contains("Overview") && sources.contains("Local High"))
}
@Test func imageryAttributionRejectsEmptyCatalogAndInvalidViewportFallsBack() throws {
    #expect(throws: (any Error).self) { try EsriImageryAttribution(data: Data("{\"contributors\":[]}".utf8)) }
    let catalog = try EsriImageryAttribution(data: attributionFixture)
    #expect(catalog.sources(in: .init(south: .nan, west: 0, north: 1, east: 1, zoom: 18)) == EsriImageryAttribution.fallbackSources)
}
@Test func bundledImageryCreditsRemainAvailableWithoutNetwork() async {
    let sources = await EsriImageryAttributionProvider.shared.sources(in: .init(south: 37.7, west: -119.65, north: 37.8, east: -119.5, zoom: 18))
    #expect(sources.contains("Microsoft"))
    #expect(sources.contains("Vantor"))
}
