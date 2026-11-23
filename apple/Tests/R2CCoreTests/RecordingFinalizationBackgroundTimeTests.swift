import Foundation
import Testing

/// Field 2026-11-23 (Android): the app was frozen mid-copy after it left the foreground.
/// iOS finishes the rename and conversion inside a background-time window.
@Test func recordingFinalizationAndRecoveryRunInsideBackgroundTime() throws {
    let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let source = try String(contentsOf: appleRoot.appendingPathComponent("App/MediaMTXMobileController.swift"), encoding: .utf8)
    let finalize = source.components(separatedBy: "private func enqueueRecordingFinalization(")[1]
        .components(separatedBy: "private nonisolated static func localizeCompletedRecordingIfNeeded(")[0]
    #expect(finalize.contains("AppleRecordingBackgroundTime(name:"))
    #expect(finalize.contains("backgroundTime.end()"))
    let recovery = source.components(separatedBy: "private func scheduleLeftoverRecordingRecovery(")[1]
        .components(separatedBy: "private nonisolated static func recoverLeftoverRecording(")[0]
    #expect(recovery.contains("AppleRecordingBackgroundTime(name:"))
    #expect(recovery.contains("backgroundTime.end()"))
    #expect(source.contains("beginBackgroundTask(withName: name)"))
    // The conversion still swaps in its result atomically.
    #expect(source.contains("replaceItemAt("))
    // A cut-off file with the recording's name is replaced, not left beside a "-2" copy.
    #expect(source.contains("LeftoverRecordingRecovery.destination("))
}
