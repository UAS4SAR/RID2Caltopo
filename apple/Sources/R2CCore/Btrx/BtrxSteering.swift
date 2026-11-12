import Foundation

/// The pilot's steering overlay (btrx design doc section 41): one source, one circle and
/// vector in its color, one hint word. Fed live STEER frames from the paired Drone Pod by
/// ``BtrxDeskPodModel``. Same as Android's BtrxSteering.kt; keep them matching. Not
/// thread-safe: the model's queue confines it. Nothing here ever makes a clue.
///
/// Hint (section 41.2):
/// - LEFT / RIGHT from delta = port - starboard mean RSSI, roll-corrected as in section
///   24.2 (delta + ``rollDbPerDeg`` x roll, roll starboard-down positive, only with the
///   IMU-ok flag). LEFT at delta >= +3 dB, RIGHT at <= -3 dB, back to STRAIGHT only at
///   |delta| < 2 dB. Any change of the word needs two reports in a row, or one report with
///   |delta| >= 6 dB. Heard on one patch only: that side at once.
/// - CLOSER / FARTHER while STRAIGHT, from the least-squares slope of peak_rssi over the
///   last 3-5 reports: CLOSER at >= +``alongEnterDb`` dB per report, FARTHER at <= -that,
///   back to STRAIGHT when the slope falls under ``alongExitDb``. FARTHER means the source
///   is behind: the vector points back ("turn around").
/// - LEVEL while the pod's attitude gate flag is set: the vector turns gray and the last
///   hint holds underneath.
/// - LOST when no STEER with a target for ``lostMs``; the circle fades. A no-target STEER
///   before that keeps the last hint, faded.
/// The vector has five fixed directions plus "behind"; hard left/right at |delta| >= 8 dB.
/// A new src_id, or the same one after LOST, starts the hint afresh.
public final class BtrxSteering {
    public enum Hint: String, Sendable {
        case left = "LEFT", right = "RIGHT", straight = "STRAIGHT", closer = "CLOSER", farther = "FARTHER", level = "LEVEL", lost = "LOST"
        public var word: String { rawValue }
    }

    enum Side { case left, straight, right }
    enum Along { case none, closer, farther }

    /// Screen direction of the vector: degrees clockwise from straight up.
    public enum Vector: Int, Sendable {
        case hardLeft = -90, left = -45, ahead = 0, right = 45, hardRight = 90, behind = 180
        public var degrees: Int { rawValue }
    }

    public struct View: Sendable, Equatable {
        public let srcId: Int
        /// Index into ``palette``; nil when no source has a color (src_id 0).
        public let colorIndex: Int?
        public let hint: Hint
        /// Nil when LOST: no vector, only the faded circle.
        public let vector: Vector?
        /// LEVEL: draw the vector gray.
        public let gray: Bool
        /// No target right now (LOST, or a no-target STEER within ``lostMs``): draw faded.
        public let faded: Bool
        public let devClass: Int
        /// Bit 5 of the latest STEER: at least one source is muted on the pod.
        public let muted: Bool
        public let steerSeq: Int
        /// Time since the latest STEER arrived.
        public let ageMs: Int64
        /// A source to mute (not LOST, src_id non-zero).
        public let canMute: Bool
    }

    private var colors: [Int: Int] = [:]
    private var colorOrder: [Int] = []
    private var nextColor = 0
    private var src = 0
    private var side: Side?
    private var pendingSide: Side?
    private var pendingCount = 0
    private var hard = false
    private var along = Along.none
    private var peaks: [Int] = []
    private var last: BtrxProtocol.Steer?
    private var lastSteerMs: Int64?
    private var lastTargetMs: Int64?
    private var targetDevClass = BtrxProtocol.DEV_UNKNOWN

    public init() {}

    /// The current target's src_id, or 0.
    public var currentSrc: Int { src }

    /// Forget everything, colors included (a different pod was paired or the pod was forgotten).
    public func reset() {
        colors = [:]; colorOrder = []; nextColor = 0
        src = 0; clearHint()
        last = nil; lastSteerMs = nil; lastTargetMs = nil
    }

    /// Color for a src_id: each new id takes the next palette entry, cycling after 8.
    @discardableResult
    public func colorFor(_ srcId: Int) -> Int? {
        if srcId == 0 { return nil }
        if let c = colors[srcId] { return c }
        let c = nextColor
        nextColor = (nextColor + 1) % Self.palette.count
        colors[srcId] = c
        colorOrder.append(srcId)
        if colorOrder.count > Self.maxRemembered { colors[colorOrder.removeFirst()] = nil }
        return c
    }

    public func onSteer(_ s: BtrxProtocol.Steer, nowMs: Int64) {
        let previousTargetMs = lastTargetMs
        last = s
        lastSteerMs = nowMs
        if !s.target { return }
        lastTargetMs = nowMs
        let wasLost = previousTargetMs.map { nowMs - $0 >= Self.lostMs } ?? true
        if s.srcId != src || wasLost {
            src = s.srcId
            clearHint()
        }
        colorFor(src)
        targetDevClass = s.devClass
        if s.gated { return } // hold the last hint (LEVEL)
        updateSide(s)
        updateAlong(s)
    }

    /// The overlay to draw, or nil when there is nothing to show (no STEER for ``showMs``).
    public func view(nowMs: Int64) -> View? {
        guard let seen = lastSteerMs, nowMs - seen <= Self.showMs else { return nil }
        return lastView(nowMs: nowMs)
    }

    /// The latest STEER as a view whatever its age (bench line on the Desk Pod screen).
    public func lastView(nowMs: Int64) -> View? {
        guard let s = last, let seen = lastSteerMs else { return nil }
        let lost = lastTargetMs.map { nowMs - $0 >= Self.lostMs } ?? true
        let hint: Hint = lost ? .lost : s.gated ? .level : heldHint()
        var vector: Vector?
        if !lost {
            switch side {
            case .left: vector = hard ? .hardLeft : .left
            case .right: vector = hard ? .hardRight : .right
            default: vector = along == .farther ? .behind : .ahead
            }
        }
        return View(srcId: src, colorIndex: colors[src], hint: hint, vector: vector, gray: hint == .level,
                    faded: lost || !s.target, devClass: targetDevClass, muted: s.muted, steerSeq: s.steerSeq,
                    ageMs: nowMs - seen, canMute: !lost && src != 0)
    }

    private func heldHint() -> Hint {
        switch side {
        case .left: return .left
        case .right: return .right
        default:
            switch along {
            case .closer: return .closer
            case .farther: return .farther
            case .none: return .straight
            }
        }
    }

    private func clearHint() {
        side = nil; pendingSide = nil; pendingCount = 0; hard = false
        along = .none; peaks = []
    }

    private func updateSide(_ s: BtrxProtocol.Steer) {
        let oneSide: Side?
        switch (s.portHeard, s.stbdHeard) {
        case (true, true): oneSide = nil
        case (true, false): oneSide = .left
        case (false, true): oneSide = .right
        case (false, false): return
        }
        let delta = oneSide != nil ? 0 : Self.deltaDb(s)
        let candidate: Side
        if let one = oneSide { candidate = one }
        else if delta >= Self.sideEnterDb { candidate = .left }
        else if delta <= -Self.sideEnterDb { candidate = .right }
        else if abs(delta) < Self.sideExitDb { candidate = .straight }
        else { candidate = side ?? .straight }
        let strong = oneSide != nil || abs(delta) >= Self.sideStrongDb
        if side == nil || candidate == side || strong {
            side = candidate; pendingSide = nil; pendingCount = 0
        } else if pendingSide == candidate {
            pendingCount += 1
            if pendingCount >= Self.sideConfirmReports { side = candidate; pendingSide = nil; pendingCount = 0 }
        } else {
            pendingSide = candidate; pendingCount = 1
        }
        hard = side != .straight && side == candidate && (oneSide != nil || abs(delta) >= Self.hardDb)
    }

    private func updateAlong(_ s: BtrxProtocol.Steer) {
        if side != .straight {
            along = .none
            peaks = []
            return
        }
        peaks.append(s.peakRssi)
        if peaks.count > Self.alongWindow { peaks.removeFirst(peaks.count - Self.alongWindow) }
        if peaks.count < Self.alongMinReports {
            along = .none
            return
        }
        let slope = Self.slope(peaks)
        switch along {
        case .none: along = enterAlong(slope)
        case .closer: along = slope < Self.alongExitDb ? enterAlong(slope) : .closer
        case .farther: along = slope > -Self.alongExitDb ? enterAlong(slope) : .farther
        }
    }

    private func enterAlong(_ slope: Double) -> Along {
        if slope >= Self.alongEnterDb { return .closer }
        if slope <= -Self.alongEnterDb { return .farther }
        return .none
    }

    /// Okabe-Ito with white in place of black (section 41.3), drawn with a thin dark outline.
    public static let palette: [Int] = [0xE69F00, 0x56B4E9, 0x009E73, 0xF0E442, 0x0072B2, 0xD55E00, 0xCC79A7, 0xFFFFFF]
    /// LEVEL and hold; never a source color.
    public static let gray = 0x9E9E9E
    public static let outline = 0x202020

    public static let sideEnterDb = 3.0
    public static let sideExitDb = 2.0
    public static let sideStrongDb = 6.0
    public static let hardDb = 8.0
    public static let sideConfirmReports = 2
    /// Section 24.2: S ~ 0.12 dB per degree of roll [EST].
    public static let rollDbPerDeg = 0.12
    public static let alongWindow = 5
    public static let alongMinReports = 3
    /// dB per report (about 1 s); section 24.2 expects 0.4-0.65 dB/s at 100 m and 5 m/s [EST].
    public static let alongEnterDb = 0.5
    public static let alongExitDb = 0.25
    public static let lostMs: Int64 = 5_000
    /// No STEER at all for this long hides the overlay (a no-target STEER comes at most every 5 s).
    public static let showMs: Int64 = 12_000
    public static let maxRemembered = 64

    /// Port minus starboard, roll-corrected when the pod's IMU is OK.
    public static func deltaDb(_ s: BtrxProtocol.Steer) -> Double {
        let raw = Double(s.rssiPort - s.rssiStbd)
        return s.imuOk ? raw + rollDbPerDeg * (Double(s.rollX2) / 2) : raw
    }

    /// Least-squares slope of evenly spaced values, per step.
    public static func slope(_ values: [Int]) -> Double {
        let n = values.count
        if n < 2 { return 0 }
        let meanX = Double(n - 1) / 2
        let meanY = Double(values.reduce(0, +)) / Double(n)
        var num = 0.0, den = 0.0
        for (i, y) in values.enumerated() {
            num += (Double(i) - meanX) * (Double(y) - meanY)
            den += (Double(i) - meanX) * (Double(i) - meanX)
        }
        return num / den
    }
}
