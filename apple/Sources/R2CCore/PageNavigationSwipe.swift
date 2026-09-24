import Foundation

public enum PageNavigationSwipe {
    public static func accepts(dx: Double, dy: Double, toLiveView: Bool) -> Bool {
        (toLiveView ? -dx : dx) >= 72 && abs(dx) >= 2 * abs(dy)
    }
}
