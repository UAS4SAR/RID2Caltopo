import Foundation

/// Where the map's BTRX popups go (Ken, Nov 20 A5 test: "BTRX sources here" opened at a tap near
/// the right edge ran off the screen and under the stream PiP). The popup stays inside the map,
/// clear of `obstacle` (the stream PiP) by `margin`: first the asked-for spot as is, then flipped
/// left, up, or both, then any of those slid back on screen, then beside or above the obstacle
/// with the height capped so it scrolls. Points, map coordinates. Same rules as Android's
/// BtrxPopupPlacement.kt; both run test-fixtures/btrx/popup-placement.tsv.
public enum BtrxPopupPlacement {
    public struct Box: Sendable, Equatable {
        public var left: Double, top: Double, right: Double, bottom: Double
        public init(left: Double, top: Double, right: Double, bottom: Double) {
            self.left = left; self.top = top; self.right = right; self.bottom = bottom
        }
    }

    /// `choice`: 0-3 a candidate as is (0 asked for, 1 left, 2 up, 3 left and up), 4-7 the same slid on screen,
    /// 8 left of the obstacle, 9 above it (height capped), 10 nothing clear (asked-for spot slid on screen).
    public struct Plan: Sendable, Equatable {
        public var x: Double, y: Double, maxHeight: Double, clear: Bool, choice: Int
    }

    /// The smallest height a popup is squeezed to above the obstacle before overlap is accepted.
    public static let minBand = 96.0

    /// The tap panel: its top-left corner at the tap point, flipping left or up around it.
    public static func atPoint(width: Double, height: Double, popupW: Double, popupH: Double, x: Double, y: Double,
                               obstacle: Box?, margin: Double) -> Plan {
        let w = min(popupW, max(1, width - 2 * margin)), h = min(popupH, max(1, height - 2 * margin))
        return place(width, height, w, h, [(x, y), (x - w, y), (x, y - h), (x - w, y - h)], obstacle, margin)
    }

    /// The inspect card: `left` from the map's left edge and `bottom` up from its bottom edge.
    public static func atBottomStart(width: Double, height: Double, popupW: Double, popupH: Double, left: Double, bottom: Double,
                                     obstacle: Box?, margin: Double) -> Plan {
        let w = min(popupW, max(1, width - 2 * margin)), h = min(popupH, max(1, height - 2 * margin))
        return place(width, height, w, h, [(left, height - bottom - h)], obstacle, margin)
    }

    private static func place(_ width: Double, _ height: Double, _ w: Double, _ h: Double, _ cands: [(Double, Double)],
                              _ obstacle: Box?, _ m: Double) -> Plan {
        let cap = max(1, height - 2 * m)
        func fits(_ x: Double, _ y: Double) -> Bool {
            x >= m - 0.01 && y >= m - 0.01 && x + w <= width - m + 0.01 && y + h <= height - m + 0.01
        }
        func clear(_ x: Double, _ y: Double, _ hh: Double? = nil) -> Bool {
            guard let obstacle else { return true }
            return !overlaps(Box(left: x, top: y, right: x + w, bottom: y + (hh ?? h)), obstacle, margin: m)
        }
        func cx(_ x: Double) -> Double { min(max(x, m), max(m, width - m - w)) }
        func cy(_ y: Double) -> Double { min(max(y, m), max(m, height - m - h)) }
        for (i, c) in cands.enumerated() where fits(c.0, c.1) && clear(c.0, c.1) {
            return Plan(x: c.0, y: c.1, maxHeight: cap, clear: true, choice: i)
        }
        for (i, c) in cands.enumerated() where clear(cx(c.0), cy(c.1)) {
            return Plan(x: cx(c.0), y: cy(c.1), maxHeight: cap, clear: true, choice: 4 + i)
        }
        let (x0, y0) = cands[0]
        if let obstacle {
            let lx = obstacle.left - m - w
            if lx >= m, clear(lx, cy(y0)) { return Plan(x: lx, y: cy(y0), maxHeight: cap, clear: true, choice: 8) }
            let band = obstacle.top - 2 * m
            if band >= min(h, minBand) {
                let hh = min(h, band), y = obstacle.top - m - hh
                if clear(cx(x0), y, hh) { return Plan(x: cx(x0), y: y, maxHeight: hh, clear: true, choice: 9) }
            }
        }
        return Plan(x: cx(x0), y: cy(y0), maxHeight: cap, clear: false, choice: 10)
    }

    public static func overlaps(_ a: Box, _ b: Box, margin: Double) -> Bool {
        a.left < b.right + margin && a.right > b.left - margin && a.top < b.bottom + margin && a.bottom > b.top - margin
    }
}
