import Testing
import Foundation
@testable import R2CCore

private typealias Options = OperationalOfflineSheetOptions
private let startViewport = OperationalMapBounds(north: 39.1, south: 39.0, west: -120.1, east: -120.0)
private let movedViewport = OperationalMapBounds(north: 40.1, south: 40.0, west: -121.1, east: -121.0)
private var downloadStarted: Options {
    Options(
        includeUsgsScannedTopo: true,
        includeUsfsTopo: false,
        includeContours: true,
        usgsScannedTopoZoom: OperationalOfflineSourceZoom(minZoom: 10, maxZoom: 15),
        usfsTopoZoom: OperationalOfflineSourceZoom(minZoom: 12, maxZoom: 16),
        contoursZoom: OperationalOfflineSourceZoom(minZoom: 11, maxZoom: 14),
        includeDEM: true,
        includeAOL: true,
        demResolution: .enhanced10m,
        selectedBoundaryID: "assignment-7",
        viewportBounds: startViewport
    )
}

@Test func offlineSheetReopenedDuringDownloadShowsThatDownloadsChoices() {
    let reopened = Options.forOpening(remembered: downloadStarted, downloadRunning: true,
                                      viewportBounds: movedViewport, contoursOverlayOn: false,
                                      baseLayer: .imagery, boundaryIDs: ["assignment-7"])
    #expect(reopened == downloadStarted) // AOL stays on, so Retry after a failure keeps AOL.
}

@Test func offlineSheetFreshOpenKeepsLastChoicesLikeAndroid() {
    let reopened = Options.forOpening(remembered: downloadStarted, downloadRunning: false,
                                      viewportBounds: movedViewport, contoursOverlayOn: false,
                                      baseLayer: .imagery, boundaryIDs: ["assignment-7"])
    var expected = downloadStarted
    expected.includeContours = false   // follows the map's contour overlay
    expected.viewportBounds = movedViewport // visible map captured afresh
    #expect(reopened == expected)
    #expect(reopened.includeAOL)
    #expect(reopened.includeUsgsScannedTopo)
}

@Test func offlineSheetFirstOpenUsesDefaults() {
    let first = Options.forOpening(remembered: nil, downloadRunning: false, viewportBounds: movedViewport,
                                   contoursOverlayOn: true, baseLayer: .openStreetMap, boundaryIDs: [])
    #expect(first == Options(
        includeUsgsScannedTopo: false,
        includeUsfsTopo: false,
        includeContours: true,
        includeDEM: true,
        includeAOL: false,
        demResolution: .maximum1m,
        selectedBoundaryID: "",
        viewportBounds: movedViewport
    ))
}

@Test func offlineSheetFirstOpenSeedsCurrentOfflineAllowedBaseLayer() {
    let usgs = Options.forOpening(remembered: nil, downloadRunning: false, viewportBounds: movedViewport,
                                  contoursOverlayOn: false, baseLayer: .usgsScannedTopo, boundaryIDs: [])
    #expect(usgs.includeUsgsScannedTopo && !usgs.includeUsfsTopo)
    #expect(usgs.selectedOfflineBaseLayers == [.usgsScannedTopo])

    let usfs = Options.forOpening(remembered: nil, downloadRunning: false, viewportBounds: movedViewport,
                                  contoursOverlayOn: false, baseLayer: .usfsTopo, boundaryIDs: [])
    #expect(!usfs.includeUsgsScannedTopo && usfs.includeUsfsTopo)
    #expect(usfs.selectedOfflineBaseLayers == [.usfsTopo])
}

@Test func offlineSheetMissingBoundaryFallsBackToVisibleMap() {
    let reopened = Options.forOpening(remembered: downloadStarted, downloadRunning: false,
                                      viewportBounds: movedViewport, contoursOverlayOn: false,
                                      baseLayer: .imagery, boundaryIDs: ["other"])
    #expect(reopened.selectedBoundaryID.isEmpty)
}

@Test func offlineSheetFirstImageryOpenDoesNotEnableBasemapDownloads() {
    let options = Options.forOpening(remembered: nil, downloadRunning: false,
        viewportBounds: movedViewport, contoursOverlayOn: false,
        baseLayer: .imagery, boundaryIDs: [])
    #expect(!options.includeUsgsScannedTopo && !options.includeUsfsTopo)
    #expect(options.selectedOfflineBaseLayers.isEmpty)
    #expect(options.includeDEM)
}

@Test func offlineSheetZoomsClampToSourceRanges() {
    let options = Options(
        includeContours: true,
        usgsScannedTopoZoom: OperationalOfflineSourceZoom(minZoom: -5, maxZoom: 40),
        usfsTopoZoom: OperationalOfflineSourceZoom(minZoom: 18, maxZoom: 20),
        contoursZoom: OperationalOfflineSourceZoom(minZoom: 25, maxZoom: 30),
        viewportBounds: startViewport
    )
    #expect(options.usgsScannedTopoZoom == OperationalOfflineSourceZoom(minZoom: 0, maxZoom: 16))
    #expect(options.usfsTopoZoom.minZoom == 16 && options.usfsTopoZoom.maxZoom == 16)
    #expect(options.contoursZoom.minZoom == 22 && options.contoursZoom.maxZoom == 22)
}
