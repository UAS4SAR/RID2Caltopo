import Foundation

/// The pilot's steering overlay (btrx design doc section 41): one source, one circle and
/// vector in its color, one hint word. Fed live STEER frames from the paired Drone Pod by
/// ``BtrxDeskPodModel``. Same as Android's BtrxSteering.kt; keep them matching. Not
/// thread-safe: the model's queue confines it. Nothing here ever makes a clue.
///
/// STEER facts (firmware/PROTOCOL.md, PR #18): one report per 1 s window. Bit 0 clear means
/// nothing was heard in that window (common for a weak or slow source): fade, keep the last
/// hint. The pod sends a no-target report once when the target goes, then at most every
/// 5 s. window_end_ms is pod A's clock (ms since power-up), the same clock as DETECT.
/// src_id restarts at 1 after a pod reboot, so window_end_ms going back means the pod
/// restarted: everything here is reset, colors included. Mutes live only in the pod's RAM
/// (max 8; cleared at power-up; a muted source unheard for 60 s is forgotten and comes back
/// unmuted under a new src_id); STEER bit 5 says at least one is held, there is no count.
///
/// Hint (section 41.2):
/// - LEFT / RIGHT from delta = port - starboard RSSI. LEFT at delta >= +3 dB, RIGHT at
///   <= -3 dB, back to STRAIGHT only at |delta| < 2 dB. A side change needs two reports in
///   a row, or one strong report (|delta| >= 6 dB). Heard on one patch only (the other is
///   -128): that side; strong (immediate, hard) only with >= 2 hits on that patch,
///   otherwise confirmed like any other change. Hard left/right at |delta| >= 8.
/// - Roll correction (section 24.2): delta + ``rollDbPerDeg`` x roll, roll positive
///   starboard side down, only with the IMU-ok flag. Off (``rollCorrectionDefault``) until
///   the bench roll-sign check (docs/btrx/BLE-bench-test.md section 6) has passed; the Desk
///   Pod screen has a debug switch.
/// - CLOSER / FARTHER while STRAIGHT: least-squares slope of peak_rssi against
///   window_end_ms over the last ``alongWindowMs``, at least ``alongMinReports`` reports
///   with a target and not gated. CLOSER at >= +``alongEnterDbS`` dB/s and at least
///   ``alongSigma`` x its standard error, FARTHER likewise negative (vector points back:
///   turn around); cleared below ``alongExitDbS`` dB/s.
/// - LEVEL at once while the latest report is gated: vector gray, last hint held.
/// - Faded at once when the latest report has no target, or no STEER for ``staleMs``.
/// - LOST at once after ``lostMs`` without a target: no vector, circle faded.
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
        /// Not current: LOST, a no-target report, or no STEER for ``staleMs``. Draw faded.
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

    /// Least-squares fit of peak RSSI against time: slope in dB/s and its standard error.
    public struct Fit: Sendable, Equatable {
        public let slopeDbPerS: Double, stdErr: Double, n: Int
    }

    /// Roll correction on/off (debug switch; off until the bench roll check passes).
    public var rollCorrection = BtrxSteering.rollCorrectionDefault
    /// Pod restarts seen (window_end_ms went back).
    public private(set) var restarts = 0

    private var colors: [Int: Int] = [:]
    private var colorOrder: [Int] = []
    private var nextColor = 0
    private var src = 0
    private var side: Side?
    private var pendingSide: Side?
    private var pendingCount = 0
    private var hard = false
    private var along = Along.none
    /// (window_end_ms, peak_rssi) of target, ungated reports while STRAIGHT.
    private var peaks: [(UInt32, Int)] = []
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
        targetDevClass = BtrxProtocol.DEV_UNKNOWN
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

    /// Returns true when this report showed the pod restarted (state was reset first).
    @discardableResult
    public func onSteer(_ s: BtrxProtocol.Steer, nowMs: Int64) -> Bool {
        let restarted = last.map { s.windowEndMs < $0.windowEndMs } ?? false
        if restarted {
            reset()
            restarts += 1
        }
        let previousTargetMs = lastTargetMs
        last = s
        lastSteerMs = nowMs
        if !s.target { return restarted }
        lastTargetMs = nowMs
        let wasLost = previousTargetMs.map { nowMs - $0 >= Self.lostMs } ?? true
        if s.srcId != src || wasLost {
            src = s.srcId
            clearHint()
        }
        colorFor(src)
        targetDevClass = s.devClass
        if s.gated { return restarted } // hold the last hint (LEVEL); not in the fit
        updateSide(s)
        updateAlong(s)
        return restarted
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
        let stale = nowMs - seen >= Self.staleMs
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
                    faded: lost || !s.target || stale, devClass: targetDevClass, muted: s.muted, steerSeq: s.steerSeq,
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
        let delta = oneSide != nil ? 0 : Self.deltaDb(s, rollCorrection: rollCorrection)
        let candidate: Side
        if let one = oneSide { candidate = one }
        else if delta >= Self.sideEnterDb { candidate = .left }
        else if delta <= -Self.sideEnterDb { candidate = .right }
        else if abs(delta) < Self.sideExitDb { candidate = .straight }
        else { candidate = side ?? .straight }
        let oneSideHits = oneSide == .left ? s.hitsPort : oneSide == .right ? s.hitsStbd : 0
        let strong = oneSide != nil ? oneSideHits >= Self.oneSideMinHits : abs(delta) >= Self.sideStrongDb
        if side == nil || candidate == side || strong {
            side = candidate; pendingSide = nil; pendingCount = 0
        } else if pendingSide == candidate {
            pendingCount += 1
            if pendingCount >= Self.sideConfirmReports { side = candidate; pendingSide = nil; pendingCount = 0 }
        } else {
            pendingSide = candidate; pendingCount = 1
        }
        let hardNow = oneSide != nil ? strong : abs(delta) >= Self.hardDb
        hard = side != .straight && side == candidate && hardNow
    }

    private func updateAlong(_ s: BtrxProtocol.Steer) {
        if side != .straight {
            along = .none
            peaks = []
            return
        }
        peaks.append((s.windowEndMs, s.peakRssi))
        peaks.removeAll { Int64(s.windowEndMs) - Int64($0.0) > Self.alongWindowMs }
        guard let fit = Self.fit(peaks), fit.n >= Self.alongMinReports else {
            along = .none
            return
        }
        let m = fit.slopeDbPerS
        let significant = abs(m) >= Self.alongEnterDbS && abs(m) >= Self.alongSigma * fit.stdErr
        if significant { along = m > 0 ? .closer : .farther }
        else if abs(m) < Self.alongExitDbS { along = .none }
        else if along == .closer && m > 0 { along = .closer }
        else if along == .farther && m < 0 { along = .farther }
        else { along = .none }
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
    /// A one-patch report is strong (immediate, hard) only with this many hits on that patch.
    public static let oneSideMinHits = 2
    /// Section 41.2: S ~ 0.2 dB per degree of roll for these patches [EST: tune in flight test 1].
    public static let rollDbPerDeg = 0.2
    /// Roll correction stays off until the bench roll-sign check passes
    /// (docs/btrx/BLE-bench-test.md section 6: level about 0, starboard side down about
    /// 10 deg reads about +20 roll_x2; pod B needs firmware 4a68518 or later).
    public static let rollCorrectionDefault = false
    /// CLOSER/FARTHER fit window on the pod's clock (window_end_ms).
    public static let alongWindowMs: Int64 = 10_000
    public static let alongMinReports = 6
    public static let alongEnterDbS = 0.5
    public static let alongExitDbS = 0.25
    /// The slope must be at least this many standard errors from zero.
    public static let alongSigma = 2.0
    public static let lostMs: Int64 = 5_000
    /// No STEER for this long after a report: fade (reports come every second with a target).
    public static let staleMs: Int64 = 2_000
    /// No STEER at all for this long hides the overlay (a no-target STEER comes at most every 5 s).
    public static let showMs: Int64 = 12_000
    public static let maxRemembered = 64

    /// Port minus starboard, roll-corrected when `rollCorrection` is on and the pod's IMU is OK.
    public static func deltaDb(_ s: BtrxProtocol.Steer, rollCorrection: Bool = rollCorrectionDefault) -> Double {
        let raw = Double(s.rssiPort - s.rssiStbd)
        return rollCorrection && s.imuOk ? raw + rollDbPerDeg * (Double(s.rollX2) / 2) : raw
    }

    /// Least-squares slope of peak (dB) against window_end_ms, in dB/s, with its standard error.
    public static func fit(_ points: [(UInt32, Int)]) -> Fit? {
        let n = points.count
        if n < 3 { return nil }
        let t0 = Int64(points[0].0)
        let xs = points.map { Double(Int64($0.0) - t0) / 1000 }
        let ys = points.map { Double($0.1) }
        let mx = xs.reduce(0, +) / Double(n)
        let my = ys.reduce(0, +) / Double(n)
        var sxx = 0.0, sxy = 0.0
        for i in 0..<n { sxx += (xs[i] - mx) * (xs[i] - mx); sxy += (xs[i] - mx) * (ys[i] - my) }
        if sxx <= 0 { return nil }
        let slope = sxy / sxx
        var ss = 0.0
        for i in 0..<n { let r = ys[i] - (my + slope * (xs[i] - mx)); ss += r * r }
        return Fit(slopeDbPerS: slope, stdErr: (ss / Double(n - 2) / sxx).squareRoot(), n: n)
    }
}

/// Which stream tile shows the steering overlay (section 41.2: only the drone carrying the
/// pod). Automatic when exactly one stream is live; with more, only the tile the operator
/// marked "Drone Pod is on this drone". The choice ends when that stream does. Same as
/// Android's BtrxSteeringTarget.
public enum BtrxSteeringTarget {
    public static func showsOverlay(tile: String, live: Set<String>, chosen: String?) -> Bool {
        live.contains(tile) && (live.count == 1 || chosen == tile)
    }

    /// Offer "Drone Pod is on this drone" on this tile: several live, none chosen.
    public static func offersPick(tile: String, live: Set<String>, chosen: String?) -> Bool {
        live.contains(tile) && live.count > 1 && (chosen.map { !live.contains($0) } ?? true)
    }

    /// The choice still standing, or nil once its stream is no longer live.
    public static func retained(_ chosen: String?, live: Set<String>) -> String? {
        chosen.flatMap { live.contains($0) ? $0 : nil }
    }
}
