import Testing
@testable import R2CCore

@MainActor @Test func settingsEditsDoNotApplyUntilSaveAndCancelDiscards() {
    let edits = OperationalSettingsEdits()
    var value = false
    edits.stage("toggle", value: true, original: value) { value = $0 }
    #expect(edits.hasChanges)
    #expect(!value)
    #expect(edits.value(for: "toggle", fallback: value))
    edits.discard()
    #expect(!edits.hasChanges && !value)
    edits.stage("toggle", value: true, original: value) { value = $0 }
    edits.commit()
    #expect(value && !edits.hasChanges)
}
@MainActor @Test func settingsRevertingAnEditIsCleanAndUnrelatedLiveChangesArePreserved() {
    let edits = OperationalSettingsEdits()
    var volume = 50
    var external = "new map"
    edits.stage("volume", value: 70, original: volume) { volume = $0 }
    edits.stage("volume", value: 50, original: volume) { volume = $0 }
    #expect(!edits.hasChanges)
    edits.stage("volume", value: 60, original: volume) { volume = $0 }
    external = "another map"
    edits.commit()
    #expect(volume == 60 && external == "another map")
}
