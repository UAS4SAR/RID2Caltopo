import Testing
@testable import R2CCore

@Test func singleStreamTileAlwaysHasFocusWithoutBorder() {
    for explicit in [false, true] {
        let focus = LiveStreamSelectionPolicy.tileFocusPresentation(
            displayedTileCount: 1, explicitlyFocused: explicit)
        #expect(focus.effectiveFocused)
        #expect(!focus.showFocusBorder)
    }
}

@Test func multipleStreamTilesShowOnlySelectedFocus() {
    for explicit in [false, true] {
        let focus = LiveStreamSelectionPolicy.tileFocusPresentation(
            displayedTileCount: 2, explicitlyFocused: explicit)
        #expect(focus.effectiveFocused == explicit)
        #expect(focus.showFocusBorder == explicit)
    }
}
