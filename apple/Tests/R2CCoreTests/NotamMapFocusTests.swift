import Foundation
import Testing
@testable import R2CCore

@Test func notamMapCoordinatesFitWholeIntersectingArea() {
    let west = OperationalNotamCoordinate(latitude: 35, longitude: -131)
    let east = OperationalNotamCoordinate(latitude: 35, longitude: -107)
    let north = OperationalNotamCoordinate(latitude: 45, longitude: -119)
    let south = OperationalNotamCoordinate(latitude: 25, longitude: -119)
    let notice = OperationalNotam(
        id: "area", title: "Service notice", summary: "", distanceNM: 0,
        intersectsPilotArea: true, severity: .caution,
        geometries: [.collection([
            .polygon([[west, north, east, south, west]]),
            .point(.init(latitude: .nan, longitude: -119))
        ])]
    )
    #expect(notice.mapCoordinates == [west, north, east, south, west])
}

@Test func notamMissingGeometryDoesNotInventPilotLocation() {
    let notice = OperationalNotam(
        id: "unknown", title: "Notice", summary: "", distanceNM: nil,
        intersectsPilotArea: false, severity: .caution,
        geometries: [.point(.init(latitude: 91, longitude: 0))]
    )
    #expect(notice.mapCoordinates.isEmpty)
}

@Test func notamServiceOutagePreservesFaaTextWithoutTranslation() throws {
    let payload = #"{"data":{"geojson":[{"type":"Feature","geometry":{"type":"Point","coordinates":[-119,35]},"properties":{"coreNOTAMData":{"notam":{"id":"outage","icaoLocation":"KZOA","text":"AIRSPACE ADS-B, ADS-R, TIS-B, FIS-B SER MAY NOT BE AVBL WI AN AREA DEFINED AS 590NM RADIUS OF 352024N1190648W","effectiveStart":"2026-10-01T09:00:00Z","effectiveEnd":"2026-10-01T11:00:00Z"}}}}]}}"#
    let notice = try #require(OperationalNotamParser.parseResponse(
        Data(payload.utf8), pilot: .init(latitude: 35, longitude: -119), operatingRadiusNM: 1
    ).first)
    #expect(notice.summary.contains("services may be unavailable"))
    #expect(notice.summary.contains("590 NM radius"))
    #expect(notice.details.contains("FAA notice: AIRSPACE ADS-B"))
    #expect(notice.effectiveText == "Effective 2026-10-01T09:00:00Z to 2026-10-01T11:00:00Z")
}
