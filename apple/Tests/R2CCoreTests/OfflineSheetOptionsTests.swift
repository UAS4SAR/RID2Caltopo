import Testing
import Foundation
@testable import R2CCore

private typealias Options = OperationalOfflineSheetOptions
private let startViewport = OperationalMapBounds(north: 39.1, south: 39.0, west: -120.1, east: -120.0)
private let movedViewport = OperationalMapBounds(north: 40.1, south: 40.0, west: -121.1, east: -121.0)
private var downloadStarted: Options {
    Options(preset: .fullDetail, includeImagery: false, includeOSM: false, includeContours: true,
            includeDEM: true, includeAOL: true, demResolution: .enhanced10m,
            selectedBoundaryID: "assignment-7", viewportBounds: startViewport)
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
}

@Test func offlineSheetFirstOpenUsesDefaults() {
    let first = Options.forOpening(remembered: nil, downloadRunning: false, viewportBounds: movedViewport,
                                   contoursOverlayOn: true, baseLayer: .openStreetMap, boundaryIDs: [])
    #expect(first == Options(preset: .operations, includeImagery: false, includeOSM: false, includeContours: true,
                             includeDEM: true, includeAOL: false, demResolution: .maximum1m,
                             selectedBoundaryID: "", viewportBounds: movedViewport))
}

@Test func offlineSheetMissingBoundaryFallsBackToVisibleMap() {
    let reopened = Options.forOpening(remembered: downloadStarted, downloadRunning: false,
                                      viewportBounds: movedViewport, contoursOverlayOn: false,
                                      baseLayer: .imagery, boundaryIDs: ["other"])
    #expect(reopened.selectedBoundaryID.isEmpty)
}

@Test func offlineSheetDropsLegacyOSMSelectionDuringRetry() {
    var remembered = downloadStarted
    remembered.includeOSM = true
    let reopened = Options.forOpening(remembered: remembered, downloadRunning: true,
        viewportBounds: movedViewport, contoursOverlayOn: false,
        baseLayer: .openStreetMap, boundaryIDs: ["assignment-7"])
    #expect(!reopened.includeOSM)
    #expect(reopened.includeAOL)
    #expect(reopened.selectedBoundaryID == "assignment-7")
}

@Test func offlineSheetDropsLegacyEsriSelectionAndKeepsTerrainChoices() {
    var remembered = downloadStarted
    remembered.includeImagery = true
    remembered.includeOSM = true
    for running in [false, true] {
        let options = Options.forOpening(remembered: remembered, downloadRunning: running,
            viewportBounds: movedViewport, contoursOverlayOn: true,
            baseLayer: .imagery, boundaryIDs: ["assignment-7"])
        #expect(!options.includeImagery && !options.includeOSM)
        #expect(options.includeDEM && options.includeAOL)
    }
}

@Test func offlineSheetFirstImageryOpenDoesNotEnableBasemapDownloads() {
    let options = Options.forOpening(remembered: nil, downloadRunning: false,
        viewportBounds: movedViewport, contoursOverlayOn: false,
        baseLayer: .imagery, boundaryIDs: [])
    #expect(!options.includeImagery && !options.includeOSM)
    #expect(options.includeDEM)
}
