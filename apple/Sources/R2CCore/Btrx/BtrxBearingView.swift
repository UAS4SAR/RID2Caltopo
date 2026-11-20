import Foundation

/// Focus by class and/or maker (Nov 20 back-deck test: 40 devices around the takeoff spot).
/// `classMask`: bit (1 << class) per bearing class (1 phone, 2 watch, 3 hearing aid, 4 tag,
/// 5 audio, 6 computer, 0 unknown); `companyId`: Bluetooth SIG company id, -1 = any;
/// `hideOthers`: the others are not drawn at all instead of dimmed. Same as Android.
public struct BtrxBearingFocusFilter: Sendable, Equatable {
    public var classMask: Int
    public var companyId: Int
    public var hideOthers: Bool

    public init(classMask: Int = 0, companyId: Int = -1, hideOthers: Bool = false) {
        self.classMask = classMask; self.companyId = companyId; self.hideOthers = hideOthers
    }

    public static let none = BtrxBearingFocusFilter()

    public var active: Bool { classMask != 0 || companyId >= 0 }

    public func withClass(_ devClass: Int) -> BtrxBearingFocusFilter {
        var f = self; f.classMask ^= (1 << devClass); return f
    }

    public func withCompany(_ id: Int) -> BtrxBearingFocusFilter {
        var f = self; f.companyId = companyId == id ? -1 : id; return f
    }

    public func withHideOthers(_ on: Bool) -> BtrxBearingFocusFilter {
        var f = self; f.hideOthers = on; return f
    }

    public var logText: String {
        guard active else { return "none" }
        return String(format: "classes 0x%x, company %@%@", classMask,
                      companyId < 0 ? "any" : String(format: "0x%04x", companyId), hideOthers ? ", others hidden" : "")
    }
}

/// Map display thresholds (Developer Tools > BTRX > Bearing map layer, Nov 20): estimates wider
/// than ±`maxPlusMinusM` (2 sigma; 0 = no limit) are not drawn, nor labels of sources with fewer
/// than `minReports` reports in the fade window; `launchAutoMute` mutes sources strong at the
/// takeoff spot before takeoff. The focused source always shows. Same as Android.
public struct BtrxBearingDisplay: Sendable, Equatable, Codable {
    public static let defaultMaxPlusMinusM = 50
    public static let defaultMinReports = 5
    public static let minPlusMinusM = 10
    /// The engine's own limit (est_max_axis_2s_m 100) is ±100 m; above that the slider means "no limit".
    public static let maxPlusMinusLimitM = 100
    public static let maxMinReports = 30

    public var maxPlusMinusM: Int
    public var minReports: Int
    public var launchAutoMute: Bool

    public init(maxPlusMinusM: Int = BtrxBearingDisplay.defaultMaxPlusMinusM, minReports: Int = BtrxBearingDisplay.defaultMinReports, launchAutoMute: Bool = true) {
        self.maxPlusMinusM = maxPlusMinusM; self.minReports = minReports; self.launchAutoMute = launchAutoMute
    }

    public func clamped() -> BtrxBearingDisplay {
        BtrxBearingDisplay(maxPlusMinusM: maxPlusMinusM <= 0 ? 0 : min(max(maxPlusMinusM, Self.minPlusMinusM), Self.maxPlusMinusLimitM),
                           minReports: min(max(minReports, 1), Self.maxMinReports), launchAutoMute: launchAutoMute)
    }
}

/// How the map draws one source (Android and iPad overlays).
public enum BtrxBearingDrawMode: Sendable, Equatable { case full, dim, hidden }

/// One entry of the focus menu: a class or a maker present on the map, with its count.
public struct BtrxBearingFocusOption: Sendable, Equatable, Identifiable {
    public let isClass: Bool, value: Int, label: String, count: Int
    public var id: String { "\(isClass ? "c" : "m")\(value)" }
    public init(isClass: Bool, value: Int, label: String, count: Int) {
        self.isClass = isClass; self.value = value; self.label = label; self.count = count
    }
}

public enum BtrxBearingView {
    public static let focusMenu = "Focus…"
    public static let focusMenuTitle = "Focus on"
    public static let focusClasses = "Kinds"
    public static let focusMakers = "Makers"
    public static let focusHideOthers = "Hide the others"
    public static let focusNoneOnMap = "No sources on the map yet"
    public static let launchShow = "Show"
    public static let launchHide = "Hide"
    public static let settingMaxPm = "Hide estimates wider than"
    public static let settingMinReports = "Hide labels with fewer reports than"
    public static let settingLaunchMute = "Mute sources at the takeoff spot"
    public static let settingLaunchMuteHint =
        "Devices heard -70 dBm or stronger while the drone sits at its first position (before takeoff) are muted. Show them from the map chip."

    public static func maxPlusMinusText(_ m: Int) -> String { m <= 0 ? "no limit" : "±\(m) m" }

    /// The "Hide estimates wider than" slider runs 10...110 in steps of 10; 110 = no limit (0).
    public static func maxPmFromSlider(_ v: Float) -> Int {
        let m = Int((v / 10).rounded()) * 10
        return m > BtrxBearingDisplay.maxPlusMinusLimitM ? 0 : max(m, BtrxBearingDisplay.minPlusMinusM)
    }

    public static func sliderFromMaxPm(_ m: Int) -> Float { m <= 0 ? Float(BtrxBearingDisplay.maxPlusMinusLimitM + 10) : Float(m) }

    /// Hidden: muted, ignored, or outside a "hide the others" focus; dimmed: outside any other focus.
    public static func drawMode(_ s: BtrxBearingSource, _ snap: BtrxBearingSnapshot) -> BtrxBearingDrawMode {
        drawMode(s, focusKey: snap.focusKey, focusActive: snap.focusActive, hideOthers: snap.focusFilter.hideOthers)
    }

    public static func drawMode(_ s: BtrxBearingSource, focusKey: UInt64, focusActive: Bool, hideOthers: Bool) -> BtrxBearingDrawMode {
        if s.muted || s.ignored { return .hidden }
        if !focusActive || s.focusMatch || (focusKey != 0 && s.key == focusKey) { return .full }
        return hideOthers ? .hidden : .dim
    }

    /// The estimate (dot, ellipses, pin) is drawn: not wider than the limit, unless this is the focused source.
    public static func estimateShown(_ s: BtrxBearingSource, _ d: BtrxBearingDisplay, focusKey: UInt64) -> Bool {
        guard let e = s.estimate else { return false }
        if focusKey != 0 && s.key == focusKey { return true }
        return d.maxPlusMinusM <= 0 || e.plusMinus2sM <= Float(d.maxPlusMinusM)
    }

    /// The label is drawn: enough reports, or pinned, or the focused source.
    public static func labelShown(_ s: BtrxBearingSource, _ d: BtrxBearingDisplay, focusKey: UInt64) -> Bool {
        (focusKey != 0 && s.key == focusKey) || s.pinned || s.reportsInWindow >= d.minReports
    }

    /// Label placement priority: focused, pinned, valid estimate, then more reports.
    public static func labelPriority(_ s: BtrxBearingSource, _ snap: BtrxBearingSnapshot) -> Int {
        labelPriority(s, focusKey: snap.focusKey, focusActive: snap.focusActive)
    }

    public static func labelPriority(_ s: BtrxBearingSource, focusKey: UInt64, focusActive: Bool) -> Int {
        let tier: Int
        if focusKey != 0 && s.key == focusKey { tier = 4 }
        else if focusActive && s.focusMatch { tier = 3 }
        else if s.pinned { tier = 2 }
        else if s.estimate != nil { tier = 1 }
        else { tier = 0 }
        return tier * 100_000 + min(max(s.reportsInWindow, 0), 99_999)
    }

    /// Classes and makers among the sources not ignored, most first (ties by name): the focus menu.
    public static func focusOptions(_ sources: [BtrxBearingSource]) -> [BtrxBearingFocusOption] {
        let live = sources.filter { !$0.ignored }
        let order: (BtrxBearingFocusOption, BtrxBearingFocusOption) -> Bool = { a, b in
            a.count != b.count ? a.count > b.count : a.label < b.label
        }
        let classes = Dictionary(grouping: live, by: \.devClass)
            .map { BtrxBearingFocusOption(isClass: true, value: $0.key, label: BtrxBearingText.className($0.key), count: $0.value.count) }
            .sorted(by: order)
        let makers = Dictionary(grouping: live.filter { $0.companyId != 0 }, by: \.companyId)
            .map { BtrxBearingFocusOption(isClass: false, value: $0.key, label: BtrxMakers.maker($0.key) ?? "?", count: $0.value.count) }
            .sorted(by: order)
        return classes + makers
    }

    /// "watch, Garmin · others hidden" for the focus chip; nil with no filter.
    public static func filterText(_ f: BtrxBearingFocusFilter) -> String? {
        guard f.active else { return nil }
        var parts = (0...6).filter { f.classMask & (1 << $0) != 0 }.map { BtrxBearingText.className($0) }
        if f.companyId >= 0, let m = BtrxMakers.maker(f.companyId) { parts.append(m) }
        return parts.joined(separator: ", ") + (f.hideOthers ? " · others hidden" : "")
    }

    /// Launch chip: "3 muted at takeoff" (sources flagged at the launch point and still muted).
    public static func launchChipText(mutedCount: Int, flaggedCount: Int) -> String? {
        if mutedCount > 0 { return "\(mutedCount) muted at takeoff" }
        if flaggedCount > 0 { return "\(flaggedCount) at takeoff shown" }
        return nil
    }

    public static func minReportsText(_ n: Int) -> String { BtrxBearingText.reports(n) }
}

/// Greedy label placement on screen (Nov 20: labels piled on each other and on the drone
/// telemetry label near takeoff). Highest priority first; each label tries 8 spots around its
/// anchor and takes the first that overlaps no placed label and no obstacle (by `margin`);
/// none free: hidden. Pure geometry in points; Android has the same (BtrxLabelLayout in
/// BtrxBearingView.kt) and both run test-fixtures/btrx/label-layout.tsv.
public enum BtrxLabelLayout {
    public struct Box: Sendable, Equatable {
        public let left: Float, top: Float, right: Float, bottom: Float
        public init(left: Float, top: Float, right: Float, bottom: Float) {
            self.left = left; self.top = top; self.right = right; self.bottom = bottom
        }
        public func overlaps(_ o: Box, margin: Float) -> Bool {
            left < o.right + margin && o.left < right + margin && top < o.bottom + margin && o.top < bottom + margin
        }
    }

    public struct Item: Sendable, Equatable {
        public let id: Int64, anchorX: Float, anchorY: Float, width: Float, height: Float, priority: Int
        public init(id: Int64, anchorX: Float, anchorY: Float, width: Float, height: Float, priority: Int) {
            self.id = id; self.anchorX = anchorX; self.anchorY = anchorY; self.width = width; self.height = height; self.priority = priority
        }
    }

    public struct Placed: Sendable, Equatable {
        public let id: Int64, spot: Int, box: Box
    }

    public static let spots = 8

    /// Spot `k` of `it`: 0 right-above (the old fixed place), 1 right-below, 2 left-above, 3 left-below, 4 above, 5 below, 6 right, 7 left.
    public static func spot(_ it: Item, _ k: Int, gap: Float) -> Box {
        let w = it.width, h = it.height, x = it.anchorX, y = it.anchorY
        let l: Float, t: Float
        switch k {
        case 0: (l, t) = (x + gap, y - gap - h)
        case 1: (l, t) = (x + gap, y + gap)
        case 2: (l, t) = (x - gap - w, y - gap - h)
        case 3: (l, t) = (x - gap - w, y + gap)
        case 4: (l, t) = (x - w / 2, y - 2 * gap - h)
        case 5: (l, t) = (x - w / 2, y + 2 * gap)
        case 6: (l, t) = (x + gap, y - h / 2)
        default: (l, t) = (x - gap - w, y - h / 2)
        }
        return Box(left: l, top: t, right: l + w, bottom: t + h)
    }

    /// Placed labels by id; ids missing from the result are hidden.
    public static func place(_ items: [Item], obstacles: [Box], gap: Float, margin: Float) -> [Int64: Placed] {
        let order = items.sorted { a, b in a.priority != b.priority ? a.priority > b.priority : a.id < b.id }
        var taken: [Box] = []
        var out: [Int64: Placed] = [:]
        for it in order {
            for k in 0..<spots {
                let b = spot(it, k, gap: gap)
                if obstacles.contains(where: { b.overlaps($0, margin: margin) }) || taken.contains(where: { b.overlaps($0, margin: margin) }) { continue }
                taken.append(b)
                out[it.id] = Placed(id: it.id, spot: k, box: b)
                break
            }
        }
        return out
    }
}
