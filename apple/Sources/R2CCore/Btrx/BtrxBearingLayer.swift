import CBtrxBearing
import Foundation

/// Fade settings (spec section 2.4): Developer Tools > BTRX, saved in the iPad settings.
/// Same ranges, defaults and readouts as Android's BtrxBearingSettings.
public struct BtrxBearingSettings: Sendable, Equatable, Codable {
    public var fadeTotalS: Float
    public var fadeShapeP: Float

    public static let minTotalS: Float = 60, maxTotalS: Float = 1800, defaultTotalS: Float = 600
    public static let minShapeP: Float = 1, maxShapeP: Float = 4, defaultShapeP: Float = 1

    public init(fadeTotalS: Float = BtrxBearingSettings.defaultTotalS, fadeShapeP: Float = BtrxBearingSettings.defaultShapeP) {
        self.fadeTotalS = fadeTotalS; self.fadeShapeP = fadeShapeP
    }

    public var fadeMinutes: Int { Int((fadeTotalS / 60).rounded()) }

    public func clamped() -> BtrxBearingSettings {
        BtrxBearingSettings(fadeTotalS: min(Self.maxTotalS, max(Self.minTotalS, fadeTotalS)),
                            fadeShapeP: min(Self.maxShapeP, max(Self.minShapeP, fadeShapeP)))
    }

    /// The age at half strength, from the library (weight 1 - (a/N)^p).
    public var halfStrengthS: Float {
        let c = clamped()
        return btrx_bearing_half_strength_s(c.fadeTotalS, c.fadeShapeP)
    }

    /// "m:ss".
    public static func minSec(_ seconds: Float) -> String {
        let s = max(0, Int(seconds.rounded()))
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    public static func shapeLabel(_ p: Float) -> String {
        if p < 1.05 { return "Linear" }
        if p > 3.95 { return "Accelerating" }
        return String(format: "p = %.1f", p)
    }
}

public struct BtrxBearingEstimate: Sendable, Equatable {
    public let lat: Double, lon: Double
    public let axisMajor1sM: Float, axisMinor1sM: Float, axisMajorDeg: Float
    public let ambiguous: Bool, secondLat: Double, secondLon: Double, secondMass: Float
    /// 2 sigma long axis, the "± m" of the label.
    public var plusMinus2sM: Float { 2 * axisMajor1sM }
}

public struct BtrxBearingRay: Sendable, Equatable {
    /// lengthM: the RSSI range estimate times a margin, up to the 400 m footprint (Nov 16).
    public let apexLat: Double, apexLon: Double, centreTrueDeg: Float, halfWidthDeg: Float, lengthM: Float
    public let mirrorTrueDeg: Float?
    public let tUs: Int64
    /// Front and back about equally likely: draw both lobes alike (centre = the front one).
    public var ambiguous: Bool = false
    /// RSSI slant range estimate, nil when unknown.
    public var rangeM: Float? = nil
}

/// Strength trail point of the focused source: drone position and strongest-patch RSSI.
public struct BtrxBearingTrailPoint: Sendable, Equatable {
    public let lat: Double, lon: Double, rssiDbm: Int
}

/// Fine layer (2.5 m cells; reports while low and slow, or strong), drawn over the coarse raster.
public struct BtrxBearingFine: Sendable, Equatable {
    public let cells: Int
    public let north: Double, south: Double, east: Double, west: Double
    /// cells * cells * 4, premultiplied RGBA, row 0 north.
    public let rgba: Data
}

public struct BtrxBearingSource: Sendable, Equatable, Identifiable {
    public var id: UInt64 { key }
    public let index: Int
    public let key: UInt64
    public let shortID: String
    public let provisional: Bool
    public let devClass: Int
    /// 0xRRGGBB.
    public let rgb: UInt32
    public let flags: UInt32
    public let firstHeardUs: Int64, lastHeardUs: Int64
    public let reportsIn: Int, reportsUsed: Int, reportsInWindow: Int
    public let peakAlpha: Float
    public let estimate: BtrxBearingEstimate?
    public let latestRay: BtrxBearingRay?
    /// The Drone Pod's src_id in the latest report of this source (0 = none). Opaque 16-bit key.
    public var srcId: Int = 0
    /// Build 0x0106+: seen only as an extra so far, keyed by its stable src_id.
    public var srcKeyed: Bool = false
    /// Strongest-patch mean RSSI of the latest report / strongest in the fade window; nil = none.
    public var lastRssiDbm: Int? = nil
    public var maxRssiDbm: Int? = nil
    /// "Strongest here": where the drone heard it strongest (fade window).
    public var strongestLat: Double? = nil
    public var strongestLon: Double? = nil
    /// Label point inside the shaded area.
    public var labelLat: Double? = nil
    public var labelLon: Double? = nil
    /// Estimate valid and tight (2 sigma <= 30 m): drawn as a distinct "pinned" marker.
    public var pinned: Bool = false
    /// Bluetooth SIG company id from the Drone Pod (0 = unknown).
    public var companyId: Int = 0
    /// A focus (source, class or maker) is active and this source is in it.
    public var focusMatch: Bool = false
    /// Strong at the launch point before takeoff (Nov 20); muted by the layer while launch auto mute is on.
    public var launch: Bool { flags & UInt32(BTRX_BEARING_SRC_LAUNCH) != 0 }
    /// Short maker name, "0x1234" when not in ``BtrxMakers``, nil when unknown.
    public var maker: String? { BtrxMakers.maker(companyId) }
    public var muted: Bool { flags & UInt32(BTRX_BEARING_SRC_MUTED) != 0 }
    /// Looks like it rides on the drone (a suggestion; Ken decides).
    public var ownSuspect: Bool { flags & UInt32(BTRX_BEARING_SRC_OWN_SUSPECT) != 0 }
    public var ignored: Bool { flags & UInt32(BTRX_BEARING_SRC_IGNORED) != 0 }
    public var className: String { BtrxBearingText.className(devClass) }
}

public struct BtrxBearingLag: Sendable, Equatable {
    public let count: Int, p50Ms: Int, p90Ms: Int, p99Ms: Int, maxMs: Int, lateDrops: Int
}

public struct BtrxBearingStats: Sendable, Equatable {
    public let reportsIn: Int, entriesIn: Int, used: Int, gated: Int, noHeading: Int, noPosition: Int
    public let lateDropped: Int, ringDropped: Int, bucketsExpiredEarly: Int, ignored: Int, backlogHistory: Int
    public let steerTruncated: Int, extrasUnresolved: Int, provisionalMerges: Int
    public let deskLostKnown: Bool, deskLost: UInt32, deskLostSinceConnect: UInt32, podALost: UInt32
    public let tilesInUse: Int, tilesTotal: Int, fadeTotalS: Float, fadeShapeP: Float
    public let podOffsetMs: Int, podOffsetErrMs: Int, offsetSource: Int, podSession: UInt32, displayLagMs: Int
    /// By btrx_bearing_stage_t.
    public let lag: [BtrxBearingLag]
}

public struct BtrxBearingSnapshot: Sendable, Equatable {
    public let seq: UInt64, tUs: Int64, width: Int, height: Int
    public let north: Double, south: Double, east: Double, west: Double
    /// width * height * 4, premultiplied RGBA, row 0 north.
    public let rgba: Data
    public let sources: [BtrxBearingSource]
    public let stats: BtrxBearingStats
    /// Focused source key, 0 = none.
    public var focusKey: UInt64 = 0
    public var fine: BtrxBearingFine? = nil
    /// Strength trail of the focused source, oldest first.
    public var trail: [BtrxBearingTrailPoint] = []
    /// Focus filter in effect (Nov 20): by class bits and/or maker, the others hidden or dimmed.
    public var focusFilter = BtrxBearingFocusFilter()
    /// A focus (key or filter) is active: ``BtrxBearingSource/focusMatch`` says who is in it.
    public var focusActive: Bool = false
}

/// Why the layer draws nothing (field test, Nov 13): `line` is the short map status, nil
/// while shading is on screen; `stats` are the library counters (also before the first
/// snapshot, when no drone pose exists yet). Same as Android.
public struct BtrxBearingStatus: Sendable, Equatable {
    public let line: String?
    public let stats: BtrxBearingStats?
}

/// Inputs of `BtrxBearingText.statusLine`, the same on Android. Ages in seconds, nil = never.
public struct BtrxBearingStatusInput: Sendable, Equatable {
    public var stats: BtrxBearingStats?
    public var designator: String?
    public var liveStreams: Int
    public var steerAgeS: Float?
    public var yawAgeS: Float?
    public var positionAgeS: Float?
    /// Unmuted shading is on screen.
    public var drawing: Bool
    /// Shading exists but every source with it is hidden (muted or ignored).
    public var allHidden: Bool
    public init(stats: BtrxBearingStats?, designator: String?, liveStreams: Int, steerAgeS: Float?, yawAgeS: Float?,
                positionAgeS: Float?, drawing: Bool, allHidden: Bool) {
        self.stats = stats; self.designator = designator; self.liveStreams = liveStreams; self.steerAgeS = steerAgeS
        self.yawAgeS = yawAgeS; self.positionAgeS = positionAgeS; self.drawing = drawing; self.allHidden = allHidden
    }
}

/// Where the video and RID code hand yaw, position and AGL to a running bearing layer
/// without depending on the Desk Pod center: nil while the layer is off. Same as Android.
public enum BtrxBearingFeed {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var current: BtrxBearingLayer?
    public static var sink: BtrxBearingLayer? {
        get { lock.lock(); defer { lock.unlock() }; return current }
        set { lock.lock(); current = newValue; lock.unlock() }
    }
}

/// iPad boundary of the BTRX bearing layer (docs/btrx/BEARING-LAYER-spec.md sections 3
/// and 7): owns the `btrx_bearing` handle (deinit destroys), stamps and pushes inputs,
/// is ticked every 200 ms on the BTRX serial queue, schedules TIME_PING and publishes
/// snapshots. Same surface and behaviour as Android's BtrxBearingLayer.kt.
///
/// Threads: `start`, `stop`, `onFrame`, `onConnected` and `tick` on the BTRX queue;
/// `onYaw`, `onPosition`, `noteAgl` from any thread; `setMuted`, `sourcesAt`,
/// `noteRendered` from the main thread. Every C call holds a read lock; only `stop`
/// takes the write lock, so the UI never waits on grid work.
public final class BtrxBearingLayer: @unchecked Sendable {
    public static let tickMs = 200
    public static let pingBurst = 3
    public static let pingBurstSpacingUs: Int64 = 1_000_000
    public static let pingIntervalUs: Int64 = 30_000_000
    static let maxPongRttUs: Int64 = 10_000_000
    static let maxArrivalBackMs: Int64 = 2_000, maxYawBackMs: Int64 = 2_000, maxPosBackMs: Int64 = 3_000
    static let aglMaxAgeUs: Int64 = 10_000_000
    static let rayFadeUs: Int64 = 5_000_000

    private let monoUs: () -> Int64
    private let wallMs: () -> Int64
    private let log: (String) -> Void
    private let traceLog: (String) -> Void
    private let incidentKey: () -> [UInt8]

    private var rw = pthread_rwlock_t()
    private var handle: OpaquePointer?
    private var lagTrace = false
    private let stateLock = NSLock()
    private var _steeringDesignator: String?
    private var aglByDesignator: [String: (Float, Int64)] = [:]
    public var steeringDesignator: String? {
        get { stateLock.lock(); defer { stateLock.unlock() }; return _steeringDesignator }
        set { stateLock.lock(); _steeringDesignator = newValue; stateLock.unlock() }
    }

    /// Called on the BTRX queue with each new snapshot (nil when the layer stops).
    public var onSnapshot: ((BtrxBearingSnapshot?) -> Void)?
    /// Called on the BTRX queue about once a second with the map status (nil when the layer stops).
    public var onStatus: ((BtrxBearingStatus?) -> Void)?
    public static let statusIntervalUs: Int64 = 1_000_000
    /// Live (non-playback) stream count, for the status line.
    public var liveStreamCount: Int {
        get { stateLock.lock(); defer { stateLock.unlock() }; return _liveStreamCount }
        set { stateLock.lock(); _liveStreamCount = newValue; stateLock.unlock() }
    }
    private var _liveStreamCount = 0
    // Host times (us) of the last STEER from the paired pod, yaw and position accepted; 0 = never.
    private var lastSteerUs: Int64 = 0, lastYawUs: Int64 = 0, lastPositionUs: Int64 = 0
    private var nextStatusUs: Int64 = 0
    public private(set) var lastStatus: BtrxBearingStatus?
    public private(set) var lastError: String?

    // Snapshot copy (the BTRX queue is the library's one reader) and the copy the UI queries.
    private let snapLock = NSLock()
    private var snapCopy = btrx_bearing_snapshot_t()
    private var haveSnap = false
    private var rgbaBuf: UnsafeMutablePointer<UInt8>?
    private var alphaBuf: UnsafeMutablePointer<UInt8>?
    private var bufCells = 0
    private var fineBuf: UnsafeMutablePointer<UInt8>?
    private var fineBufBytes = 0
    private let trailBuf = UnsafeMutablePointer<btrx_bearing_trail_point_t>.allocate(capacity: Int(BTRX_BEARING_TRAIL_MAX))
    private var current: BtrxBearingSnapshot?

    // TIME_PING (spec 3.2): every 30 s, plus a burst of 3 at start, pod restart and reconnect.
    private var pingSeq = 0
    private var pingT1Us = [Int64](repeating: 0, count: 256)
    private var pingT1Ms = [Int64](repeating: -1, count: 256)
    private var burstLeft = 0
    private var nextPingUs = Int64.max
    private var lastPodSession: Int64 = -1
    private var lastRenderedSeq: UInt64 = .max

    /// Log lines from the library's callback (tick thread), drained after each tick.
    final class LogSink: @unchecked Sendable {
        let lock = NSLock()
        var lines: [String] = []
    }
    private let logSink = LogSink()

    public init(monoUs: @escaping () -> Int64 = { Int64(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1000) },
                wallMs: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
                log: @escaping (String) -> Void = { _ in },
                traceLog: @escaping (String) -> Void = { _ in },
                incidentKey: @escaping () -> [UInt8] = { (0..<16).map { _ in UInt8.random(in: 0...255) } }) {
        self.monoUs = monoUs; self.wallMs = wallMs; self.log = log; self.traceLog = traceLog; self.incidentKey = incidentKey
        pthread_rwlock_init(&rw, nil)
    }

    deinit {
        if let h = handle { btrx_bearing_destroy(h) }
        rgbaBuf?.deallocate(); alphaBuf?.deallocate(); fineBuf?.deallocate(); trailBuf.deallocate()
        pthread_rwlock_destroy(&rw)
    }

    public var isRunning: Bool { withHandle(false) { _ in true } }

    private func withHandle<T>(_ fallback: T, _ body: (OpaquePointer) -> T) -> T {
        pthread_rwlock_rdlock(&rw)
        defer { pthread_rwlock_unlock(&rw) }
        guard let h = handle else { return fallback }
        return body(h)
    }

    // MARK: lifecycle

    /// BTRX queue. Creates the native layer; false (and `lastError`) when create fails.
    @discardableResult
    public func start(settings: BtrxBearingSettings, trace: Bool, linkUp: Bool) -> Bool {
        if isRunning { return true }
        let s = settings.clamped()
        var cfg = btrx_bearing_config_t()
        btrx_bearing_config_default(&cfg)
        cfg.fade_total_s = s.fadeTotalS
        cfg.fade_shape_p = s.fadeShapeP
        cfg.lag_trace = trace
        cfg.stale_fade_start_s = Float(BTRX_BEARING_STALE_FADE_START_S)   // Nov 23: same as Android
        cfg.stale_hide_s = Float(BTRX_BEARING_STALE_HIDE_S)
        cfg.launch_auto_mute = launchAutoMute
        cfg.callback_ctx = Unmanaged.passUnretained(logSink).toOpaque()
        cfg.log = { ctx, _, msg in
            guard let ctx, let msg else { return }
            let sink = Unmanaged<BtrxBearingLayer.LogSink>.fromOpaque(ctx).takeUnretainedValue()
            let line = String(cString: msg)
            sink.lock.lock(); if sink.lines.count < 4096 { sink.lines.append(line) }; sink.lock.unlock()
        }
        let key = incidentKey()
        var h: OpaquePointer?
        let st = key.withUnsafeBufferPointer { btrx_bearing_create(&cfg, $0.baseAddress, &h) }
        guard st == BTRX_BEARING_OK, let h else {
            lastError = "The bearing layer could not start."
            log("bearing: create failed (\(st.rawValue)); layer stays off")
            return false
        }
        let cells = Int(cfg.grid_cells) * Int(cfg.grid_cells)
        if bufCells != cells {
            rgbaBuf?.deallocate(); alphaBuf?.deallocate()
            rgbaBuf = .allocate(capacity: cells * 4)
            alphaBuf = .allocate(capacity: cells * Int(BTRX_BEARING_MAX_SOURCES))
            bufCells = cells
        }
        let fineCells = Int(btrx_bearing_fine_cells(&cfg))
        let fineBytes = fineCells * fineCells * 4
        if fineBufBytes != fineBytes {
            fineBuf?.deallocate()
            fineBuf = fineBytes > 0 ? .allocate(capacity: fineBytes) : nil
            fineBufBytes = fineBytes
        }
        lastError = nil
        lagTrace = trace
        pthread_rwlock_wrlock(&rw); handle = h; pthread_rwlock_unlock(&rw)
        snapLock.lock(); haveSnap = false; current = nil; snapLock.unlock()
        lastPodSession = -1
        stableSrcIds = false
        lastRenderedSeq = .max
        pingT1Ms = [Int64](repeating: -1, count: 256)
        stateLock.lock(); lastSteerUs = 0; lastYawUs = 0; lastPositionUs = 0; stateLock.unlock()
        nextStatusUs = 0
        lastStatus = nil
        let now = monoUs()
        if linkUp { btrx_bearing_note_connected(h, now) }
        startBurst(now)
        log("bearing: layer on (fade \(BtrxBearingSettings.minSec(s.fadeTotalS)), p \(String(format: "%.1f", s.fadeShapeP))\(trace ? ", lag trace" : ""))")
        if trace { traceLog("BBv1 F \(now) \(s.fadeTotalS) \(s.fadeShapeP)") }
        return true
    }

    /// BTRX queue. Destroys the native layer and clears the overlay.
    public func stop() {
        pthread_rwlock_wrlock(&rw)
        let h = handle
        handle = nil
        pthread_rwlock_unlock(&rw)
        guard let h else { return }
        btrx_bearing_destroy(h)
        snapLock.lock(); haveSnap = false; current = nil; snapLock.unlock()
        onSnapshot?(nil)
        lastStatus = nil
        onStatus?(nil)
        burstLeft = 0
        nextPingUs = .max
        log("bearing: layer off")
    }

    private func startBurst(_ nowUs: Int64) {
        burstLeft = Self.pingBurst
        nextPingUs = nowUs
    }

    /// Host time of a wall-clock stamp; never in the future, at most `maxBackMs` back.
    private func hostUs(ofWall stampMs: Int64, now: Int64, maxBackMs: Int64) -> Int64 {
        now - min(maxBackMs, max(0, wallMs() - stampMs)) * 1000
    }

    // MARK: inputs

    /// BTRX queue (BtrxClientListener.onFrame). STEER, TIME_PONG and HEALTH only.
    public func onFrame(_ frame: BtrxProtocol.Frame, meta: BtrxRadioMeta, fromPairedPod: Bool, fromDeskPod: Bool) {
        guard isRunning else { return }
        let now = monoUs()
        let arrival = hostUs(ofWall: meta.arrivalMs, now: now, maxBackMs: Self.maxArrivalBackMs)
        switch frame.type {
        case BtrxProtocol.STEER where fromPairedPod:
            if !meta.backlog { stateLock.lock(); lastSteerUs = now; stateLock.unlock() }
            withHandle(()) { h in
                var r = btrx_bearing_report_t()
                let st = frame.payload.withUnsafeBufferPointer {
                    btrx_bearing_parse_steer(h, $0.baseAddress, $0.count, arrival, meta.backlog, &r)
                }
                guard st == BTRX_BEARING_OK else { return }
                r.push_us = now
                if lagTrace { traceLog(Self.reportLine(r)) }
                let pushed = btrx_bearing_push_report(h, &r)
                if pushed.rawValue < 0 && pushed != BTRX_BEARING_E_LATE { log("bearing: STEER not used (status \(pushed.rawValue))") }
            }
        case BtrxProtocol.TIME_PONG where fromPairedPod:
            onPong(frame.payload, t4Us: arrival)
        case BtrxProtocol.HEALTH where fromPairedPod || fromDeskPod:
            withHandle(()) { h in
                if fromPairedPod && frame.payload.count >= 6 {
                    applyStableIds(h, BtrxProtocol.savedIgnoreSupported(Int(frame.payload[4]) | Int(frame.payload[5]) << 8))
                }
                var hl = btrx_bearing_health_t()
                let st = frame.payload.withUnsafeBufferPointer { btrx_bearing_parse_health($0.baseAddress, $0.count, &hl) }
                guard st == BTRX_BEARING_OK else { return }
                _ = btrx_bearing_push_health(h, arrival, hl.role, hl.uptime_s, hl.lost_known, hl.phone_lost)
                if lagTrace { traceLog("BBv1 H \(arrival) \(hl.role) \(hl.uptime_s) \(hl.lost_known ? String(hl.phone_lost) : "unknown")") }
            }
        default:
            break
        }
    }

    /// The BBv1 R line of a parsed report (spec 9.2); keys only, no addresses.
    static func reportLine(_ r: btrx_bearing_report_t) -> String {
        var line = "BBv1 R \(r.arrival_us) \(r.window_end_ms) \(r.steer_seq) \(r.flags) \(r.roll_x2) \(r.pitch_x2) \(r.backlog ? 1 : 0) \(r.ext_format) \(r.truncated ? 1 : 0) \(r.source_count)"
        withUnsafeBytes(of: r.src) { raw in
            let srcs = raw.bindMemory(to: btrx_bearing_report_source_t.self)
            for i in 0..<Int(r.source_count) {
                let s = srcs[i]
                line += " \(s.src_id) \(String(format: "%016llx", s.addr_key)) \(s.rssi_port) \(s.rssi_stbd) \(s.peak_rssi) \(s.hits_port) \(s.hits_stbd) \(s.age_ms) \(s.is_new ? 1 : 0) \(s.linked ? 1 : 0) \(s.pod_dev_class) \(s.company_id)"
            }
        }
        return line
    }

    private func onPong(_ payload: [UInt8], t4Us: Int64) {
        guard let t = try? BtrxProtocol.parseTime(payload) else { return }
        let i = t.pingSeq & 0xFF
        guard pingT1Ms[i] >= 0, pingT1Ms[i] == Int64(t.t1) else { return }   // not ours, or stale
        let t1Us = pingT1Us[i]
        pingT1Ms[i] = -1
        guard t4Us >= t1Us, t4Us - t1Us <= Self.maxPongRttUs else { return }
        withHandle(()) { h in _ = btrx_bearing_push_time_sync(h, t1Us, t.t2, t.t3, t4Us) }
        if lagTrace { traceLog("BBv1 S \(t1Us) \(t.t2) \(t.t3) \(t4Us)") }
    }

    /// BTRX queue: the app (re)connected to the Desk Pod.
    public func onConnected() {
        guard isRunning else { return }
        let now = monoUs()
        withHandle(()) { h in _ = btrx_bearing_note_connected(h, now) }
        startBurst(now)
    }

    private func sameDrone(_ designator: String) -> Bool {
        guard let chosen = steeringDesignator else { return false }
        return chosen.trimmingCharacters(in: .whitespaces).caseInsensitiveCompare(designator.trimmingCharacters(in: .whitespaces)) == .orderedSame
    }

    /// DJI aircraft yaw, true north (`djiAircraftYawTrueNorthDegrees`), for stream `designator`
    /// decoded at `decodedAtWallMs`. Only the "Drone Pod is on this drone" stream feeds the layer.
    public func onYaw(designator: String, yawTrueDeg: Double, decodedAtWallMs: Int64) {
        guard yawTrueDeg.isFinite, isRunning, sameDrone(designator) else { return }
        let now = monoUs()
        let t = hostUs(ofWall: decodedAtWallMs, now: now, maxBackMs: Self.maxYawBackMs)
        var p = btrx_bearing_pose_t()
        p.t_us = t; p.push_us = now; p.mask = UInt32(BTRX_BEARING_POSE_YAW); p.yaw_true_deg = Float(yawTrueDeg)
        withHandle(()) { h in _ = btrx_bearing_push_pose(h, &p) }
        stateLock.lock(); lastYawUs = now; stateLock.unlock()
        if lagTrace { traceLog("BBv1 P \(t) \(p.mask) 0 0 0 \(String(format: "%.3f", yawTrueDeg)) 0 0") }
    }

    /// The latest height above ground for `designator` (nil = unknown).
    public func noteAgl(designator: String, aglM: Double?) {
        let key = designator.trimmingCharacters(in: .whitespaces).uppercased()
        stateLock.lock()
        if let aglM, aglM.isFinite { aglByDesignator[key] = (Float(aglM), monoUs()) } else { aglByDesignator.removeValue(forKey: key) }
        stateLock.unlock()
    }

    /// A RID track point for `designator` with its fix time (epoch ms).
    public func onPosition(designator: String, lat: Double, lon: Double, fixWallMs: Int64) {
        guard lat.isFinite, lon.isFinite, !(lat == 0 && lon == 0), isRunning, sameDrone(designator) else { return }
        let now = monoUs()
        let t = hostUs(ofWall: fixWallMs > 0 ? fixWallMs : wallMs(), now: now, maxBackMs: Self.maxPosBackMs)
        stateLock.lock()
        let agl = aglByDesignator[designator.trimmingCharacters(in: .whitespaces).uppercased()].flatMap { now - $0.1 <= Self.aglMaxAgeUs ? $0.0 : nil }
        stateLock.unlock()
        var p = btrx_bearing_pose_t()
        p.t_us = t; p.push_us = now; p.lat_deg = lat; p.lon_deg = lon
        p.mask = UInt32(BTRX_BEARING_POSE_POSITION)
        if let agl { p.mask |= UInt32(BTRX_BEARING_POSE_AGL); p.agl_m = agl }
        withHandle(()) { h in _ = btrx_bearing_push_pose(h, &p) }
        stateLock.lock(); lastPositionUs = now; stateLock.unlock()
        if lagTrace { traceLog("BBv1 P \(t) \(p.mask) \(String(format: "%.7f", lat)) \(String(format: "%.7f", lon)) \(agl ?? 0) 0 0 0") }
    }

    // MARK: work

    /// BTRX queue, every 200 ms: library tick, snapshot copy when one was published, log
    /// drain, and any due TIME_PING through `sendPing(pingSeq, t1Ms)` (false = no link).
    public func tick(sendPing: (Int, UInt32) -> Bool) {
        guard isRunning else { return }
        let now = monoUs()
        let published = withHandle(false) { h -> Bool in
            var pub = false
            let st = btrx_bearing_tick(h, now, &pub)
            return st.rawValue >= 0 && pub
        }
        if published { copySnapshot() }
        if now >= nextStatusUs {
            nextStatusUs = now + Self.statusIntervalUs
            updateStatus(now)
        }
        logSink.lock.lock(); let lines = logSink.lines; logSink.lines.removeAll(keepingCapacity: true); logSink.lock.unlock()
        lines.forEach(traceLog)
        if now >= nextPingUs {
            let seq = pingSeq
            let t1Ms = UInt32(truncatingIfNeeded: now / 1000)
            if sendPing(seq, t1Ms) {
                pingT1Us[seq] = now
                pingT1Ms[seq] = Int64(t1Ms)
                pingSeq = (pingSeq + 1) & 0xFF
            }
            // A ping that could not go out is not retried early: the schedule keeps its pace.
            burstLeft = max(0, burstLeft - 1)
            nextPingUs = now + (burstLeft > 0 ? Self.pingBurstSpacingUs : Self.pingIntervalUs)
        }
    }

    /// Test hook: the next TIME_PING time (host us), Int64.max when none is scheduled.
    var nextPingAtUs: Int64 { nextPingUs }

    private static func ageS(_ t: Int64, _ now: Int64) -> Float? { t == 0 ? nil : Float(max(0, now - t)) / 1e6 }

    /// BTRX queue, about once a second: the map status line from the stats and the pose feeds.
    private func updateStatus(_ now: Int64) {
        let fresh: BtrxBearingStats? = withHandle(nil) { h in
            var t = btrx_bearing_stats_t()
            return btrx_bearing_stats_copy(h, &t) == BTRX_BEARING_OK ? Self.decodeStats(t) : nil
        }
        snapLock.lock(); let snap = current; snapLock.unlock()
        let stats = fresh ?? snap?.stats
        let shaded = snap?.sources.filter { $0.peakAlpha > 0 } ?? []
        let drawing = shaded.contains { !$0.muted && !$0.ignored }
        stateLock.lock()
        let input = BtrxBearingStatusInput(stats: stats, designator: _steeringDesignator, liveStreams: _liveStreamCount,
            steerAgeS: Self.ageS(lastSteerUs, now), yawAgeS: Self.ageS(lastYawUs, now), positionAgeS: Self.ageS(lastPositionUs, now),
            drawing: drawing, allHidden: !shaded.isEmpty && !drawing)
        stateLock.unlock()
        let next = BtrxBearingStatus(line: BtrxBearingText.statusLine(input), stats: stats)
        if lastStatus?.line != next.line { log("bearing: status \(next.line?.replacingOccurrences(of: "\n", with: " ") ?? "drawing")") }
        lastStatus = next
        onStatus?(next)
    }

    private func copySnapshot() {
        guard let rgbaBuf, let alphaBuf else { return }
        let snap: BtrxBearingSnapshot? = withHandle(nil) { h in
            guard let s = btrx_bearing_snapshot_acquire(h) else { return nil }
            defer { btrx_bearing_snapshot_release(h, s) }
            snapLock.lock()
            defer { snapLock.unlock() }
            let st = btrx_bearing_snapshot_copy(s, &snapCopy, rgbaBuf, bufCells * 4, alphaBuf, bufCells * Int(BTRX_BEARING_MAX_SOURCES),
                                                fineBuf, fineBufBytes, trailBuf, Int32(BTRX_BEARING_TRAIL_MAX))
            haveSnap = st == BTRX_BEARING_OK
            guard haveSnap else { return nil }
            let decoded = Self.decode(snapCopy)
            current = decoded
            return decoded
        }
        guard let snap else { return }
        if lastPodSession >= 0 && Int64(snap.stats.podSession) != lastPodSession {
            log("bearing: Drone Pod session \(snap.stats.podSession) (pod restart); TIME_PING burst")
            startBurst(monoUs())
        }
        lastPodSession = Int64(snap.stats.podSession)
        onSnapshot?(snap)
    }

    static func decodeStats(_ t: btrx_bearing_stats_t) -> BtrxBearingStats {
        let lag: [BtrxBearingLag] = withUnsafeBytes(of: t.lag) { raw in
            raw.bindMemory(to: btrx_bearing_lag_t.self).map {
                BtrxBearingLag(count: Int($0.count), p50Ms: Int($0.p50_ms), p90Ms: Int($0.p90_ms), p99Ms: Int($0.p99_ms),
                               maxMs: Int($0.max_ms), lateDrops: Int($0.late_drops))
            }
        }
        return BtrxBearingStats(
            reportsIn: Int(t.reports_in), entriesIn: Int(t.entries_in), used: Int(t.used), gated: Int(t.gated),
            noHeading: Int(t.no_heading), noPosition: Int(t.no_position), lateDropped: Int(t.late_dropped),
            ringDropped: Int(t.ring_dropped), bucketsExpiredEarly: Int(t.buckets_expired_early), ignored: Int(t.ignored),
            backlogHistory: Int(t.backlog_history), steerTruncated: Int(t.steer_truncated),
            extrasUnresolved: Int(t.extras_unresolved), provisionalMerges: Int(t.provisional_merges),
            deskLostKnown: t.desk_queue_lost_known, deskLost: t.desk_queue_lost, deskLostSinceConnect: t.desk_queue_lost_since_connect,
            podALost: t.pod_a_queue_lost, tilesInUse: Int(t.tiles_in_use), tilesTotal: Int(t.tiles_total),
            fadeTotalS: t.fade_total_s, fadeShapeP: t.fade_shape_p, podOffsetMs: Int(t.pod_offset_ms),
            podOffsetErrMs: Int(t.pod_offset_err_ms), offsetSource: Int(t.offset_source), podSession: t.pod_session,
            displayLagMs: Int(t.display_lag_ms), lag: lag)
    }

    static func decode(_ s: btrx_bearing_snapshot_t) -> BtrxBearingSnapshot {
        let n = Int(s.width) * Int(s.height)
        let rgba = s.rgba.map { Data(bytes: $0, count: n * 4) } ?? Data()
        let stats = decodeStats(s.stats)
        let sources: [BtrxBearingSource] = withUnsafeBytes(of: s.sources) { raw in
            let all = raw.bindMemory(to: btrx_bearing_source_t.self)
            return (0..<min(Int(s.source_count), Int(BTRX_BEARING_MAX_SOURCES))).map { i in
                let src = all[i]
                let id = withUnsafeBytes(of: src.short_id) { b in
                    String(decoding: b.prefix(while: { $0 != 0 }), as: UTF8.self)
                }
                let e = src.estimate
                let est = e.valid ? BtrxBearingEstimate(lat: e.lat_deg, lon: e.lon_deg, axisMajor1sM: e.axis_major_1s_m,
                    axisMinor1sM: e.axis_minor_1s_m, axisMajorDeg: e.axis_major_deg, ambiguous: e.ambiguous,
                    secondLat: e.second_lat_deg, secondLon: e.second_lon_deg, secondMass: e.second_mass) : nil
                let r = src.latest_ray
                let ray = r.valid ? BtrxBearingRay(apexLat: r.apex_lat_deg, apexLon: r.apex_lon_deg, centreTrueDeg: r.centre_true_deg,
                    halfWidthDeg: r.half_width_deg, lengthM: r.length_m, mirrorTrueDeg: r.mirror_true_deg.isFinite ? r.mirror_true_deg : nil,
                    tUs: r.t_us, ambiguous: r.ambiguous, rangeM: r.range_m.isFinite ? r.range_m : nil) : nil
                let rgb = withUnsafeBytes(of: src.rgb) { b in (UInt32(b[0]) << 16) | (UInt32(b[1]) << 8) | UInt32(b[2]) }
                return BtrxBearingSource(index: i, key: src.key, shortID: id, provisional: src.provisional,
                    devClass: Int(src.dev_class.rawValue), rgb: rgb, flags: src.flags, firstHeardUs: src.first_heard_us,
                    lastHeardUs: src.last_heard_us, reportsIn: Int(src.reports_in), reportsUsed: Int(src.reports_used),
                    reportsInWindow: Int(src.reports_in_window), peakAlpha: src.peak_alpha, estimate: est, latestRay: ray,
                    srcId: Int(src.src_id), srcKeyed: src.src_keyed,
                    lastRssiDbm: src.last_rssi_dbm == -128 ? nil : Int(src.last_rssi_dbm),
                    maxRssiDbm: src.max_rssi_dbm == -128 ? nil : Int(src.max_rssi_dbm),
                    strongestLat: src.strongest_valid ? src.strongest_lat_deg : nil,
                    strongestLon: src.strongest_valid ? src.strongest_lon_deg : nil,
                    labelLat: src.label_valid ? src.label_lat_deg : nil,
                    labelLon: src.label_valid ? src.label_lon_deg : nil,
                    pinned: src.pinned, companyId: Int(src.company_id), focusMatch: src.focus_match)
            }
        }
        var fine: BtrxBearingFine?
        if s.fine_valid, let f = s.fine_rgba, s.fine_width > 0, s.fine_width == s.fine_height {
            fine = BtrxBearingFine(cells: Int(s.fine_width), north: s.fine_north_deg, south: s.fine_south_deg,
                                   east: s.fine_east_deg, west: s.fine_west_deg,
                                   rgba: Data(bytes: f, count: Int(s.fine_width) * Int(s.fine_height) * 4))
        }
        var trail: [BtrxBearingTrailPoint] = []
        if let t = s.trail, s.trail_count > 0 {
            trail = (0..<Int(s.trail_count)).map { BtrxBearingTrailPoint(lat: t[$0].lat_deg, lon: t[$0].lon_deg, rssiDbm: Int(t[$0].rssi_dbm)) }
        }
        return BtrxBearingSnapshot(seq: s.seq, tUs: s.t_us, width: Int(s.width), height: Int(s.height),
                                   north: s.north_deg, south: s.south_deg, east: s.east_deg, west: s.west_deg,
                                   rgba: rgba, sources: sources, stats: stats,
                                   focusKey: s.focus_key, fine: fine, trail: trail,
                                   focusFilter: BtrxBearingFocusFilter(classMask: Int(s.focus_class_mask), companyId: Int(s.focus_company_id),
                                                                       hideOthers: s.focus_hide_others),
                                   focusActive: s.focus_active)
    }

    // MARK: UI

    /// Main thread, microseconds: sources with shading within `radiusM` (muted included), by contribution.
    public func sourcesAt(lat: Double, lon: Double, radiusM: Float) -> [BtrxBearingSource] {
        snapLock.lock()
        defer { snapLock.unlock() }
        guard haveSnap, let snap = current else { return [] }
        var hits = [btrx_bearing_hit_t](repeating: btrx_bearing_hit_t(), count: Int(BTRX_BEARING_MAX_SOURCES))
        let n = withUnsafePointer(to: &snapCopy) { p in
            Int(btrx_bearing_snapshot_sources_at(p, lat, lon, radiusM, &hits, BTRX_BEARING_MAX_SOURCES))
        }
        return (0..<n).compactMap { i in
            let idx = Int(hits[i].source_index)
            return idx < snap.sources.count ? snap.sources[idx] : nil
        }
    }

    /// Panel checkboxes and "Mute all"/"Unmute all" (shown at the next tick).
    public func setMuted(_ keys: [UInt64], muted: Bool) {
        guard !keys.isEmpty else { return }
        let now = monoUs()
        let mask = UInt32(BTRX_BEARING_SRC_MUTED)
        withHandle(()) { h in _ = keys.withUnsafeBufferPointer { btrx_bearing_set_source_flags(h, $0.baseAddress, $0.count, mask, muted ? mask : 0) } }
        if lagTrace { keys.forEach { traceLog("BBv1 M \(now) \(String(format: "%016llx", $0)) \(muted ? 1 : 0)") } }
    }

    /// BTRX pane: own aircraft (never accumulated).
    public func setIgnored(_ key: UInt64, ignored: Bool) {
        let mask = UInt32(BTRX_BEARING_SRC_IGNORED)
        var k = key
        withHandle(()) { h in _ = btrx_bearing_set_source_flags(h, &k, 1, mask, ignored ? mask : 0) }
    }

    /// The paired Drone Pod's build decides how extras are keyed (stable src_id from 0x0106). Same as Android.
    public private(set) var stableSrcIds = false

    private func applyStableIds(_ h: OpaquePointer, _ stable: Bool) {
        guard stable != stableSrcIds else { return }
        if btrx_bearing_set_stable_src_ids(h, stable) == BTRX_BEARING_OK {
            stableSrcIds = stable
            log("bearing: src_id " + (stable ? "stable (build 0x0106+): extras keyed by src_id" : "per pod session (older build)"))
        }
    }

    /// Main thread: "Focus this source" (0 = clear). Applied at the next tick, which publishes.
    public func setFocus(_ key: UInt64) {
        withHandle(()) { h in _ = btrx_bearing_set_focus(h, key) }
    }

    /// Main thread: focus by class and/or maker (Nov 20); `.none` clears. Next tick.
    public func setFocusFilter(_ filter: BtrxBearingFocusFilter) {
        withHandle(()) { h in _ = btrx_bearing_set_focus_filter(h, UInt32(filter.classMask), Int32(filter.companyId), filter.hideOthers) }
        log("bearing: focus filter \(filter.logText)")
    }

    /// "Clear focus": the focused source and the class/maker filter.
    public func clearFocus() {
        withHandle(()) { h in
            _ = btrx_bearing_set_focus(h, 0)
            _ = btrx_bearing_set_focus_filter(h, 0, -1, false)
        }
    }

    private var _launchAutoMute = true
    /// Launch-point auto mute (Developer Tools, default on); applied now and at every layer start.
    public var launchAutoMute: Bool { stateLock.lock(); defer { stateLock.unlock() }; return _launchAutoMute }

    public func setLaunchAutoMute(_ on: Bool) {
        stateLock.lock(); _launchAutoMute = on; stateLock.unlock()
        withHandle(()) { h in _ = btrx_bearing_set_launch_auto_mute(h, on) }
    }

    /// "Show launch sources": unmute every source muted at the launch point.
    @discardableResult
    public func unmuteLaunch() -> Int {
        snapLock.lock(); let snap = current; snapLock.unlock()
        let keys = (snap?.sources ?? []).filter { $0.launch && $0.muted }.map(\.key)
        setMuted(keys, muted: false)
        if !keys.isEmpty { log("bearing: \(keys.count) launch-point source(s) unmuted") }
        return keys.count
    }

    // Own-aircraft suggestion answers (Nov 17 watch test): "Own aircraft (saved)" went to the
    // pod's saved list only, so the layer's source stayed own-suspect and not ignored and the
    // card never closed. Answers are kept here, by address key and src_id, for the life of the
    // app (across layer restarts), and the card reads them, so it closes at once and never
    // comes back for that source. Same as Android.
    private var _ownAnswers = BtrxOwnAnswers()
    public var ownAnswers: BtrxOwnAnswers { stateLock.lock(); defer { stateLock.unlock() }; return _ownAnswers }

    /// Main thread: the own-aircraft suggestion answered. `own` = "Own aircraft": ignored on this
    /// device at once (the caller also saves it on the pod when the firmware keeps a list);
    /// otherwise "Not mine": the suspect flag cleared. A flag write and a set update; never waits
    /// on grid work.
    public func answerOwnSuggestion(_ src: BtrxBearingSource, own: Bool) {
        stateLock.lock(); _ownAnswers = _ownAnswers.plus(key: src.key, srcId: src.srcId); stateLock.unlock()
        if own { setIgnored(src.key, ignored: true) } else { clearOwnSuspect(src.key) }
        log("BBOWN \(src.shortID) answered \(own ? "own aircraft" : "not mine")" + (src.srcId != 0 ? " (id \(src.srcId))" : ""))
    }

    // "Ignore all except this" (Nov 17): the keys it ignored here, so "Restore ignored" undoes
    // only those. Same as Android.
    private var _ignoredByAll = Set<UInt64>()
    public var ignoredByAll: Set<UInt64> { stateLock.lock(); defer { stateLock.unlock() }; return _ignoredByAll }

    /// "Ignore all except this": ignore every key in `plan` here (one flag write) and record them.
    public func applyIgnoreAll(_ plan: BtrxIgnoreAllPlan) {
        guard !plan.localKeys.isEmpty else { return }
        let mask = UInt32(BTRX_BEARING_SRC_IGNORED)
        withHandle(()) { h in _ = plan.localKeys.withUnsafeBufferPointer { btrx_bearing_set_source_flags(h, $0.baseAddress, $0.count, mask, mask) } }
        stateLock.lock(); _ignoredByAll.formUnion(plan.localKeys); stateLock.unlock()
        log("BBIGN all except \(plan.keepId): \(plan.localKeys.count) ignored here, \(plan.podSrcIds.count) to the Drone Pod "
            + "(room \(plan.podRoom), \(plan.overLimit) over its list)")
    }

    /// "Restore ignored": stop ignoring only what "Ignore all except this" ignored here. Returns how many.
    @discardableResult
    public func restoreIgnoreAll() -> Int {
        stateLock.lock(); let keys = Array(_ignoredByAll).sorted(); _ignoredByAll.removeAll(); stateLock.unlock()
        guard !keys.isEmpty else { return 0 }
        let mask = UInt32(BTRX_BEARING_SRC_IGNORED)
        withHandle(()) { h in _ = keys.withUnsafeBufferPointer { btrx_bearing_set_source_flags(h, $0.baseAddress, $0.count, mask, 0) } }
        log("BBIGN restore: \(keys.count) no longer ignored here")
        return keys.count
    }

    /// Main thread: the own-aircraft suggestion answered "not on the drone".
    public func clearOwnSuspect(_ key: UInt64) {
        let cleared = UInt32(BTRX_BEARING_SRC_OWN_CLEARED)
        let mask = cleared | UInt32(BTRX_BEARING_SRC_OWN_SUSPECT)
        var k = key
        withHandle(()) { h in _ = btrx_bearing_set_source_flags(h, &k, 1, mask, cleared) }
    }

    public func setFade(_ settings: BtrxBearingSettings) {
        let s = settings.clamped()
        withHandle(()) { h in _ = btrx_bearing_set_fade(h, s.fadeTotalS, s.fadeShapeP) }
        if lagTrace { traceLog("BBv1 F \(monoUs()) \(s.fadeTotalS) \(s.fadeShapeP)") }
    }

    public func reset() { withHandle(()) { h in _ = btrx_bearing_reset(h, Int32(BTRX_BEARING_RESET_ALL), 0) } }

    /// Main thread: the overlay showing snapshot `seq` is on screen (T7).
    public func noteRendered(_ seq: UInt64) {
        snapLock.lock()
        let fresh = seq != lastRenderedSeq
        lastRenderedSeq = seq
        snapLock.unlock()
        guard fresh else { return }
        let now = monoUs()
        withHandle(()) { h in _ = btrx_bearing_note_rendered(h, seq, now) }
    }

    /// Host monotonic microseconds (the layer's clock), for the ray fade.
    public func nowUs() -> Int64 { monoUs() }
}

/// Own-aircraft suggestion answers, by address key and by src_id (an address that rotates keeps its src_id). Same as Android.
public struct BtrxOwnAnswers: Equatable, Sendable {
    public var keys: Set<UInt64> = []
    public var srcIds: Set<Int> = []
    public init(keys: Set<UInt64> = [], srcIds: Set<Int> = []) { self.keys = keys; self.srcIds = srcIds }
    public func plus(key: UInt64, srcId: Int) -> BtrxOwnAnswers {
        var out = self
        out.keys.insert(key)
        if srcId != 0 { out.srcIds.insert(srcId) }
        return out
    }
    public func answered(_ s: BtrxBearingSource) -> Bool { keys.contains(s.key) || (s.srcId != 0 && srcIds.contains(s.srcId)) }
}

/// Which source the own-aircraft card shows: the first suspect not ignored and not answered. Same as Android.
public enum BtrxOwnSuggestion {
    public static func pick(_ sources: [BtrxBearingSource], answers: BtrxOwnAnswers) -> BtrxBearingSource? {
        sources.first { $0.ownSuspect && !$0.ignored && !answers.answered($0) }
    }
}

/// "Ignore all except this" (Nov 17): what one tap ignores. `localKeys` = every other tracked
/// source, ignored on this device; `podSrcIds` = the ones also sent to the Drone Pod's saved
/// list (op 3), strongest first, only those the pod heard in the last 50 s and only as many as
/// its list has room for (`podRoom`). Same as Android.
public struct BtrxIgnoreAllPlan: Sendable, Equatable {
    public let keepKey: UInt64, keepId: String, localKeys: [UInt64], podSrcIds: [Int], podRoom: Int, overLimit: Int
    public init(keepKey: UInt64, keepId: String, localKeys: [UInt64], podSrcIds: [Int], podRoom: Int, overLimit: Int) {
        self.keepKey = keepKey; self.keepId = keepId; self.localKeys = localKeys; self.podSrcIds = podSrcIds
        self.podRoom = podRoom; self.overLimit = overLimit
    }
    public var count: Int { localKeys.count }
}

public enum BtrxIgnoreAllExcept {
    /// The pod saves only a source heard in the last 60 s; leave a margin for the relay.
    public static let podHeardUs: Int64 = 50_000_000
    public static let podListMax = 32

    /// Never the kept source (nor another address with its src_id); skips sources already
    /// ignored here (own aircraft), suspected own aircraft, and src_ids already on the pod's list.
    public static func plan(_ sources: [BtrxBearingSource], nowUs: Int64, keep: BtrxBearingSource, savedSrcIds: [Int],
                            podStored: Int?, podSupported: Bool) -> BtrxIgnoreAllPlan {
        let saved = Set(savedSrcIds)
        let others = sources.filter { s in
            s.key != keep.key && !(keep.srcId != 0 && s.srcId == keep.srcId) && !s.ignored && !s.ownSuspect
                && !(s.srcId != 0 && saved.contains(s.srcId))
        }.enumerated().sorted { a, b in
            let ra = a.element.maxRssiDbm ?? Int.min, rb = b.element.maxRssiDbm ?? Int.min
            if ra != rb { return ra > rb }
            if a.element.reportsIn != b.element.reportsIn { return a.element.reportsIn > b.element.reportsIn }
            return a.offset < b.offset
        }.map(\.element)
        var eligible: [Int] = []
        if podSupported {
            for s in others where s.srcId != 0 && nowUs - s.lastHeardUs <= podHeardUs && !eligible.contains(s.srcId) { eligible.append(s.srcId) }
        }
        let room = max(0, podListMax - (podStored ?? saved.count))
        let pod = Array(eligible.prefix(room))
        return BtrxIgnoreAllPlan(keepKey: keep.key, keepId: keep.shortID, localKeys: others.map(\.key), podSrcIds: pod,
                                 podRoom: room, overLimit: eligible.count - pod.count)
    }
}

/// Panel and pane wording, identical to Android's BtrxBearingText.
public enum BtrxBearingText {
    public static let panelTitle = "BTRX sources here"
    public static let muteAll = "Mute all"
    public static let unmuteAll = "Unmute all"
    public static let done = "Done"
    public static let section = "Bearing map layer"
    public static let layerSwitch = "Show bearing layer on map"
    public static let layerSwitchHint = "On whenever BTRX is enabled. Shades where each BTRX source is likely to be; tap the shading to show or hide sources."
    /// Nov 23: the panel's sources were all not heard for a minute (Show N older lists them).
    public static let panelAllStale = "Not heard in the last minute."
    public static let hideOlder = "Hide older"
    public static func showOlder(_ n: Int) -> String { "Show \(n) older (not heard for 1 min)" }
    public static let fadeTime = "Fade time"
    public static let fadeShape = "Fade shape"
    public static let lagTrace = "Lag trace log (restarts the layer)"
    public static let sources = "Sources"
    public static let noSources = "No sources yet."
    public static let ignore = "Ignore (own aircraft)"
    public static let unignore = "Stop ignoring"
    public static let focus = "Focus this source"
    public static let clearFocus = "Clear focus"
    public static let ownSuggestSave = "Own aircraft"
    public static let ownSuggestKeep = "Not mine"
    public static let ownSuggestCollapse = "Collapse"
    public static let ignoreAllExcept = "Ignore all except this"
    public static let restoreIgnored = "Restore ignored"
    public static let ignoreAllConfirm = "Ignore"
    public static let cancel = "Cancel"

    /// The confirmation title: "Ignore 27 other sources?".
    public static func ignoreAllTitle(_ n: Int) -> String { "Ignore \(n) other source\(n == 1 ? "" : "s")?" }

    /// The confirmation body: what is kept, what goes to the pod and its list limit, and the undo.
    public static func ignoreAllDetail(_ p: BtrxIgnoreAllPlan, podSupported: Bool) -> String {
        let mid: String
        if !podSupported { mid = "Ignored on this device only (the Drone Pod's firmware has no saved list)." }
        else if p.podSrcIds.isEmpty { mid = "Ignored on this device only (none heard by the Drone Pod in the last 50 s, or its list is full)." }
        else {
            mid = "\(p.podSrcIds.count) strongest also saved on the Drone Pod (room for \(p.podRoom) of \(BtrxIgnoreAllExcept.podListMax))"
                + (p.overLimit > 0 ? "; \(p.overLimit) more only on this device" : "") + "; the rest on this device only."
        }
        return "Keeps \(p.keepId). " + mid + " \"\(restoreIgnored)\" undoes only these."
    }

    /// "Restore ignored (27)".
    public static func restoreLabel(_ n: Int) -> String { n > 0 ? "\(restoreIgnored) (\(n))" : restoreIgnored }
    public static let ownSuspectTag = "own aircraft?"
    public static let strongestHere = "strongest here"
    public static let pinnedTitle = "BTRX source located"
    public static let close = "Close"
    /// The × at the top right of a map popup: hides the popup; focus and mutes stay as they are.
    public static let dismiss = "Dismiss"

    /// Own-aircraft suggestion banner (Nov 17: one line; it was a three-line card over the map).
    public static func ownSuggestion(_ s: BtrxBearingSource) -> String { "\(s.shortID) (\(s.className)) on the drone?" }

    /// The banner collapsed to a chip.
    public static func ownSuggestionCollapsed(_ s: BtrxBearingSource) -> String { "\(s.shortID) on drone?" }

    /// Why it is suggested (the banner's accessibility description).
    public static let ownSuggestWhy = "Stronger than -40 dBm for a minute while the drone moved. No estimate is drawn for it until you decide."

    /// Inspect card of a pinned source: one line per field. Same as Android.
    public static func inspectLines(_ s: BtrxBearingSource, nowUs: Int64) -> [String] {
        var out = ["Source \(s.shortID)" + (s.srcId != 0 ? " (id \(s.srcId))" : "") + " · \(s.className)" + (s.maker.map { " · \($0)" } ?? "")]
        if s.launch { out.append("Heard strongly at the takeoff spot" + (s.muted ? " (muted)" : "")) }
        if let e = s.estimate {
            out.append(String(format: "Estimate %.6f, %.6f", e.lat, e.lon))
            out.append("Uncertainty ±\(Int(e.plusMinus2sM.rounded())) m (2σ)" + (e.ambiguous ? " · ambiguous" : ""))
        }
        out.append("Reports \(s.reportsInWindow) in the fade window (\(s.reportsUsed) used of \(s.reportsIn))")
        out.append("Last heard \(lastHeard(nowUs: nowUs, lastUs: s.lastHeardUs))")
        if let peak = s.maxRssiDbm {
            out.append("Peak RSSI \(peak) dBm" + (s.lastRssiDbm.map { " (latest \($0) dBm)" } ?? ""))
        }
        return out
    }

    /// Distance and true bearing from the camera crosshair to a target: "42 m · 135°".
    public static func crosshairToTarget(distanceM: Double, bearingDeg: Double) -> String {
        let b = Int((bearingDeg.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360).rounded()) % 360
        return "\(Int(distanceM.rounded())) m · \(b)°"
    }

    public static func className(_ devClass: Int) -> String {
        switch devClass {
        case 1: return "phone"
        case 2: return "watch"
        case 3: return "hearing aid"
        case 4: return "tag"
        case 5: return "audio"
        case 6: return "computer"
        default: return "unknown"
        }
    }

    public static func minutes(_ m: Int) -> String { m == 1 ? "1 min" : "\(m) min" }
    public static func halfStrength(_ seconds: Float) -> String { "half strength at \(BtrxBearingSettings.minSec(seconds))" }

    public static func lastHeard(nowUs: Int64, lastUs: Int64) -> String {
        let s = max(0, (nowUs - lastUs) / 1_000_000)
        if s < 60 { return "\(s) s ago" }
        if s < 3600 { return "\(s / 60) min ago" }
        return "\(s / 3600) h ago"
    }

    public static func reports(_ n: Int) -> String { n == 1 ? "1 rpt" : "\(n) rpt" }

    /// Map label (spec 5; maker Nov 20): `watch · Garmin · Q3x9Kd · 23 rpt · ±18 m`, plus "ambiguous".
    public static func label(_ s: BtrxBearingSource) -> String {
        let base = "\(s.className)\(s.maker.map { " · \($0)" } ?? "") · \(s.shortID) · \(reports(s.reportsInWindow))" + (s.ownSuspect ? " · \(ownSuspectTag)" : "")
        guard let est = s.estimate else { return base }
        return base + " · ±\(Int(est.plusMinus2sM.rounded())) m" + (est.ambiguous ? " · ambiguous" : "")
    }

    /// Status screen (spec 3.4).
    public static func lostLine(_ st: BtrxBearingStats) -> String {
        st.deskLostKnown
            ? "Reports lost while no app: \(st.deskLostSinceConnect) since connect (\(st.deskLost) since the Desk Pod started)"
            : "Reports lost while no app: unknown (older firmware)"
    }

    public static func lagLine(_ st: BtrxBearingStats) -> String {
        func f(_ l: BtrxBearingLag?) -> String { guard let l, l.count > 0 else { return "–" }; return "p50 \(l.p50Ms) / p99 \(l.p99Ms) ms" }
        let pod = st.lag.count > 0 ? st.lag[0] : nil, total = st.lag.count > 7 ? st.lag[7] : nil
        return "Lag: pod→arrival \(f(pod)); total \(f(total)); late \(st.lateDropped), history \(st.backlogHistory)"
    }

    public static func countsLine(_ st: BtrxBearingStats) -> String {
        "Reports \(st.reportsIn): used \(st.used), gated \(st.gated), no heading \(st.noHeading), no position \(st.noPosition)"
    }

    /// Older than this, the input counts as missing in the status line.
    public static let statusSteerStaleS: Float = 10, statusYawStaleS: Float = 3, statusPositionStaleS: Float = 5

    /// Map status while the layer is on and draws nothing (field test, Nov 13): the first
    /// missing input, then the report counts; nil while shading is on screen. Same as Android.
    public static func statusLine(_ i: BtrxBearingStatusInput) -> String? {
        if i.drawing { return nil }
        func stale(_ age: Float?, _ limit: Float) -> Bool { age.map { $0 > limit } ?? true }
        let reason: String
        if stale(i.steerAgeS, statusSteerStaleS) {
            reason = "Bearing layer: no reports from the paired Drone Pod"
        } else if i.designator == nil && i.liveStreams == 0 {
            reason = "Bearing layer: no drone video. The heading comes from the live stream of the drone carrying the Drone Pod"
        } else if i.designator == nil {
            reason = "Bearing layer: on the stream of the drone carrying the Drone Pod, tap \"Drone Pod is on this drone\""
        } else if stale(i.yawAgeS, statusYawStaleS) {
            reason = "Bearing layer: no heading from \(i.designator!) video (DJI SEI)"
        } else if stale(i.positionAgeS, statusPositionStaleS) {
            reason = "Bearing layer: no Remote ID position for \(i.designator!)"
        } else if i.allHidden {
            reason = "Bearing layer: every source with shading is hidden"
        } else if (i.stats?.used ?? 0) == 0 {
            reason = "Bearing layer: no report placed yet"
        } else {
            reason = "Bearing layer: shading still too faint to show"
        }
        guard let st = i.stats, st.reportsIn > 0 else { return reason }
        return reason + "\nReports \(st.reportsIn): placed \(st.used), no heading \(st.noHeading), " +
            "no position \(st.noPosition), late \(st.lateDropped), gated \(st.gated)"
    }

    public static func clockLine(_ st: BtrxBearingStats) -> String {
        switch st.offsetSource {
        case 2: return "Pod clock: TIME_PONG, ±\(st.podOffsetErrMs) ms"
        case 1: return "Pod clock: arrival minimum (no TIME_PONG yet)"
        default: return "Pod clock: not yet known"
        }
    }
}
