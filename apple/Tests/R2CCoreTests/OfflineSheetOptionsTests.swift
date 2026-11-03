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
    let suite = "offline-sheet-first-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    let first = Options.forOpening(remembered: nil, downloadRunning: false, viewportBounds: movedViewport,
                                   contoursOverlayOn: true, baseLayer: .openStreetMap, boundaryIDs: [],
                                   defaults: defaults)
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
    let suite = "offline-sheet-seed-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    let usgs = Options.forOpening(remembered: nil, downloadRunning: false, viewportBounds: movedViewport,
                                  contoursOverlayOn: false, baseLayer: .usgsScannedTopo, boundaryIDs: [],
                                  defaults: defaults)
    #expect(usgs.includeUsgsScannedTopo && !usgs.includeUsfsTopo)
    #expect(usgs.selectedOfflineBaseLayers == [.usgsScannedTopo])

    let usfs = Options.forOpening(remembered: nil, downloadRunning: false, viewportBounds: movedViewport,
                                  contoursOverlayOn: false, baseLayer: .usfsTopo, boundaryIDs: [],
                                  defaults: defaults)
    #expect(!usfs.includeUsgsScannedTopo && usfs.includeUsfsTopo)
    #expect(usfs.selectedOfflineBaseLayers == [.usfsTopo])
}

@Test func offlineSheetPersistsSourceSelectionAndClampsZoomOnRead() {
    let suite = "offline-sheet-persist-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    Options(
        includeUsgsScannedTopo: true,
        includeUsfsTopo: true,
        includeContours: false,
        usgsScannedTopoZoom: OperationalOfflineSourceZoom(minZoom: -2, maxZoom: 99),
        usfsTopoZoom: OperationalOfflineSourceZoom(minZoom: 12, maxZoom: 14),
        contoursZoom: OperationalOfflineSourceZoom(minZoom: 10, maxZoom: 12),
        viewportBounds: movedViewport
    ).persist(defaults: defaults)
    let loaded = Options.loadPersisted(viewportBounds: movedViewport, defaults: defaults)!
    #expect(loaded.includeUsgsScannedTopo && loaded.includeUsfsTopo && !loaded.includeContours)
    let usgs = loaded.usgsScannedTopoZoom.clamped(to: "USGS-ScannedTopo")
    #expect(usgs.minZoom >= 0 && usgs.maxZoom <= 22 && usgs.minZoom <= usgs.maxZoom)
}

@Test func offlineSheetMissingBoundaryFallsBackToVisibleMap() {
    let reopened = Options.forOpening(remembered: downloadStarted, downloadRunning: false,
                                      viewportBounds: movedViewport, contoursOverlayOn: false,
                                      baseLayer: .imagery, boundaryIDs: ["other"])
    #expect(reopened.selectedBoundaryID.isEmpty)
}

@Test func offlineSheetFirstImageryOpenDoesNotEnableBasemapDownloads() {
    let suite = "offline-sheet-imagery-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    let options = Options.forOpening(remembered: nil, downloadRunning: false,
        viewportBounds: movedViewport, contoursOverlayOn: false,
        baseLayer: .imagery, boundaryIDs: [], defaults: defaults)
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
