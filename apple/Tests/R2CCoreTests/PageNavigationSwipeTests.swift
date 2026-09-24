import Testing
@testable import R2CCore

@Test func pageNavigationRequiresDeliberateHorizontalMotionInTheCorrectDirection() {
    #expect(PageNavigationSwipe.accepts(dx: -100, dy: 10, toLiveView: true))
    #expect(PageNavigationSwipe.accepts(dx: 100, dy: -10, toLiveView: false))
    #expect(!PageNavigationSwipe.accepts(dx: 100, dy: 0, toLiveView: true))
    #expect(!PageNavigationSwipe.accepts(dx: -100, dy: 0, toLiveView: false))
    #expect(!PageNavigationSwipe.accepts(dx: -60, dy: 0, toLiveView: true))
    #expect(!PageNavigationSwipe.accepts(dx: 100, dy: 70, toLiveView: false))
    #expect(!PageNavigationSwipe.accepts(dx: 0, dy: 150, toLiveView: false))
}
