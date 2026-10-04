import Testing
import Foundation
@testable import R2CCore

// Wording must match OfflinePrepProgressTextTest on Android.
private typealias Text = OperationalOfflineProgressText
private func rate(_ bytes: Int64) -> String { "\(bytes / 1_000_000) MB" }
private let base = Text.Snapshot(
    phase: "Downloading map tiles", completed: 30, total: 47, fraction: 0.4,
    bytesPerSecond: 8_000_000, etaSeconds: 65,
    tileLayers: ["Imagery", "OSM", "Contours"], tileCompleted: 28, tileTotal: 42,
    tileCached: 20, tileDownloaded: 8, tileFailed: 0,
    includesDEM: true, demCompleted: 2, demTotal: 2, demCached: 2
)
private var withAOL: Text.Snapshot {
    var p = base; p.includesAOL = true; p.aolFilesTotal = 46; p.aolTilesTotal = 3; p.total = 50; p.etaSeconds = nil
    return p
}

@Test func offlineProgressInProgressMapAndTerrain() {
    #expect(Text.headline(base, .running) == "Downloading map tiles…")
    #expect(Text.partLines(base, .running) == [
        "Map tiles (Imagery, OSM, Contours): 28/42 — 20 cached, 8 downloaded, 0 failed",
        "Terrain (DEM): 2/2 — 2 cached, 0 downloaded, 0 failed",
    ])
    #expect(Text.overallLine(base, .running, formatRate: rate) == "Overall: 40% · 30/47 items · 8 MB/s · ETA 01:05")
    #expect(Text.noteLine(base, .running) == nil)
}

@Test func offlineProgressShowsOnlySelectedParts() {
    let demOnly = Text.Snapshot(phase: "Downloading DEM tiles", completed: 1, total: 2,
                                includesDEM: true, demCompleted: 1, demTotal: 2, demDownloaded: 1)
    #expect(Text.partLines(demOnly, .running) == ["Terrain (DEM): 1/2 — 0 cached, 1 downloaded, 0 failed"])
    #expect(Text.headline(demOnly, .running) == "Downloading terrain…")
}

@Test func offlineProgressAOLLinesFollowStages() {
    #expect(Text.aolLine(withAOL, .running) == "AOL: waiting for map and terrain — 46 lidar files, 3 1 m tiles")
    var files = withAOL
    files.aolStage = .files; files.aolFilesCompleted = 12; files.phase = "AOL lidar 13/46: 4/40 MB"
    files.tileCompleted = 42; files.completed = 44
    #expect(Text.aolLine(files, .running) == "AOL: lidar files 12/46")
    var kept = files; kept.aolFilesCompleted = 44; kept.aolFilesKept = 44
    #expect(Text.aolLine(kept, .running) == "AOL: lidar files 44/46 — 44 kept from last attempt")
    #expect(Text.headline(files, .running) == "Preparing AOL…")
    #expect(Text.noteLine(files, .running) == "Now: AOL lidar 13/46: 4/40 MB")
    #expect(Text.overallLine(files, .running, formatRate: rate)
        == "Overall: 44/50 items — map and terrain first, then AOL; time remaining varies with lidar size")
    var tiles = files
    tiles.aolStage = .tiles; tiles.aolFilesCompleted = 46; tiles.aolTilesCompleted = 1
    #expect(Text.aolLine(tiles, .running) == "AOL: building 1 m tiles 1/3")
    var reused = withAOL; reused.aolStage = .reused
    #expect(Text.aolLine(reused, .finished) == "AOL: already prepared — using cached tiles")
    #expect(Text.aolLine(withAOL, .cancelled) == "AOL: not started")
}

@Test func offlineProgressAllComplete() {
    var done = withAOL
    done.phase = "Complete"; done.completed = 50; done.tileCompleted = 42; done.tileCached = 42; done.tileDownloaded = 0
    done.aolStage = .complete; done.aolFilesCompleted = 46; done.aolTilesCompleted = 3; done.fraction = 0.99
    #expect(Text.headline(done, .finished) == "Map, terrain and AOL complete")
    #expect(!Text.hasFailure(done, .finished))
    #expect(Text.aolLine(done, .finished) == "AOL: complete — 3 1 m tiles from 46 lidar files")
    #expect(Text.overallLine(done, .finished, formatRate: rate) == "Overall: 100% · 50/50 items")
}

@Test func offlineProgressAOLFailureAfterMapAndTerrainComplete() {
    var failed = withAOL
    failed.phase = "Complete with failures"; failed.completed = 44; failed.tileCompleted = 42
    failed.tileCached = 42; failed.tileDownloaded = 0; failed.fraction = 0.5582
    failed.aolStage = .failed; failed.aolFilesCompleted = 44
    failed.aolFailure = "couldn't reach USGS (timed out after 30 s)"
    #expect(Text.headline(failed, .finished) == "Map and terrain complete; AOL failed — retry available")
    #expect(Text.hasFailure(failed, .finished))
    #expect(Text.aolLine(failed, .finished)
        == "AOL failed: couldn't reach USGS (timed out after 30 s) — stopped at lidar files 44/46")
    // No rate or ETA after a failure, and the percent is the overall job's.
    #expect(Text.overallLine(failed, .finished, formatRate: rate) == "Overall: 55% · 44/50 items")
}

@Test func offlineProgressTileFailuresAndWholeJobFailure() {
    var partial = base
    partial.tileCompleted = 42; partial.tileFailed = 3; partial.completed = 44
    #expect(Text.headline(partial, .finished) == "Terrain complete; map tiles had failures — retry available")
    var stopped = base
    stopped.failure = "couldn't reach the download server"
    #expect(Text.headline(stopped, .failed) == "Download failed — retry available")
    #expect(Text.noteLine(stopped, .failed) == "Stopped: couldn't reach the download server")
    #expect(Text.headline(base, .cancelled) == "Download cancelled")
    #expect(Text.headline(base, .cancelling) == "Cancelling download…")
}

@Test func offlineProgressDescribesFailuresInPlainWords() {
    #expect(Text.describeFailure(URLError(.timedOut), service: "USGS") == "couldn't reach USGS (timed out)")
    #expect(Text.describeFailure(URLError(.cannotConnectToHost), service: "USGS") == "couldn't reach USGS")
    #expect(Text.describeFailure(URLError(.notConnectedToInternet), service: "USGS") == "couldn't reach USGS (no network)")
    #expect(Text.describeFailure(OperationalSurfacePreparationError.invalid("Lidar download HTTP 503"), service: "USGS")
        == "USGS returned HTTP 503")
    #expect(Text.describeFailure(OperationalSurfacePreparationError.invalid("Incomplete or oversized lidar download"), service: "USGS")
        == "Incomplete or oversized lidar download")
}

// Same cases as Android OfflinePrepProgressTextTest.autoCloseOnlyAfterCleanRunWhileShowing.
@Test func offlineDownloadAutoClosesOnlyAfterCleanRunWhileShowing() {
    // Successful run, including one with AOL processing, closes the visible sheet.
    #expect(Text.shouldAutoClose(phase: "Complete", failed: 0, aolFailed: false, sheetShown: true))
    // Hidden during the run: nothing to close, and a later reopen must not close by itself.
    #expect(!Text.shouldAutoClose(phase: "Complete", failed: 0, aolFailed: false, sheetShown: false))
    // Failures stay open for Retry.
    #expect(!Text.shouldAutoClose(phase: "Complete with failures", failed: 2, aolFailed: false, sheetShown: true))
    #expect(!Text.shouldAutoClose(phase: "Complete", failed: 0, aolFailed: true, sheetShown: true))
    #expect(!Text.shouldAutoClose(phase: "Cancelled", failed: 0, aolFailed: false, sheetShown: true))
    #expect(Text.hideLabel == "Hide")
    #expect(Text.cancelDownloadLabel == "Cancel download")
}
