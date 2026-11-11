import Foundation
import Testing

@Test func appExitDeletesOwnMarkerWhileBackgroundCleanupKeepsIt() throws {
    let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let publisher = try String(contentsOf: appleRoot.appendingPathComponent("App/AppleCaltopoPublisher.swift"), encoding: .utf8)
    let viewModel = try String(contentsOf: appleRoot.appendingPathComponent("App/RIDTrackViewModel.swift"), encoding: .utf8)
    let contentView = try String(contentsOf: appleRoot.appendingPathComponent("App/ContentView.swift"), encoding: .utf8)
    let app = try String(contentsOf: appleRoot.appendingPathComponent("App/RID2CaltopoAppleApp.swift"), encoding: .utf8)

    let exitDelete = functionBody(publisher, "func removePublishedDeviceMarker()")
    #expect(exitDelete.contains("deleteAllDeviceMarkerOccurrences("))
    #expect(!exitDelete.contains("updated in place"))

    let backgroundCleanup = functionBody(publisher, "func retryMarkerCleanup(credentials")
    #expect(backgroundCleanup.contains("This map's own device marker is updated in place."))
    #expect(backgroundCleanup.contains("continue"))
    #expect(!backgroundCleanup.contains("func removePublishedDeviceMarker"))

    let publishingSwitch = functionBody(viewModel, "func setLocalDeviceMarkerPublishingEnabled")
    #expect(publishingSwitch.contains("await caltopoPublisher.removeDeviceMarker()"))

    #expect(contentView.contains("markerCleanup: {\n                        await ridTracks.setLocalDeviceMarkerPublishingEnabled(false)"))
    #expect(contentView.contains("fullCleanup: {\n                        await ridTracks.setLocalDeviceMarkerPublishingEnabled(false)"))

    let terminate = functionBody(app, "func applicationWillTerminateBestEffort()")
    #expect(terminate.contains("await markerCleanup()"))
    let quit = functionBody(app, "func quitPrimaryWindow(reason: String)")
    #expect(quit.contains("performFullCleanup(reason: reason, dismissWindow: true)"))
    let fullCleanup = functionBody(app, "func performFullCleanup(reason: String, dismissWindow: Bool)")
    #expect(fullCleanup.contains("await fullCleanup()"))
}

private func functionBody(_ source: String, _ signature: String) -> String {
    guard let signatureRange = source.range(of: signature),
          let openBrace = source[signatureRange.upperBound...].firstIndex(of: "{") else {
        return ""
    }
    var depth = 0
    var index = openBrace
    while index < source.endIndex {
        let character = source[index]
        if character == "{" { depth += 1 }
        if character == "}" {
            depth -= 1
            if depth == 0 { return String(source[openBrace...index]) }
        }
        source.formIndex(after: &index)
    }
    return ""
}
