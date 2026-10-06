import Foundation

// Clue-to-waypoint binding. Android mirrors these rules in
// org.ncssar.rid2caltopo.data.ClueBinding (ClueBinding.kt); keep thresholds and wording identical.

/// One track waypoint on the drone's clock. `timeMs` is what binding uses; `receivedAtMs` is the
/// app's receive time, kept only as a diagnostic so a drone clock offset can be measured.
public struct ClueBindingPoint: Codable, Sendable, Equatable {
    public let timeMs: Int64
    public let receivedAtMs: Int64?
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    /// "rid", "dji-stream", "relay" or similar.
    public let source: String
    /// False when the point had no drone timestamp and `timeMs` fell back to the receive time.
    public let droneClock: Bool

    public init(timeMs: Int64, receivedAtMs: Int64?, latitude: Double, longitude: Double,
                altitudeMeters: Double?, source: String, droneClock: Bool) {
        self.timeMs = timeMs
        self.receivedAtMs = receivedAtMs
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
        self.source = source
        self.droneClock = droneClock
    }
}

public enum ClueBindingQuality: String, Codable, Sendable, Equatable {
    /// Waypoint within 2 s of the capture (or the drone was hovering).
    case exact
    /// 2–10 s.
    case approximate
    /// 10–30 s; the position should be checked.
    case approximateWarning = "approximate-warning"
    /// No waypoint within 30 s.
    case unbound
}

public struct ClueBindingFramePosition: Codable, Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double
    public init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }
}

/// The waypoint a clue is bound to. Offsets are waypoint time minus capture time, in milliseconds.
public struct ClueBinding: Codable, Sendable, Equatable {
    public var aircraftID: String
    /// Awaiting-map journal entry (or live track) the clue belongs to, when known.
    public var flightID: String?
    /// Drone-clock time of the bound flight's first waypoint.
    public var flightStartMs: Int64?
    /// Frame capture time on the drone clock when available.
    public var captureTimeMs: Int64
    /// "stream-pts" (drone encoder clock) or "app-receive" (fallback).
    public var captureTimeSource: String
    /// App receive time of the captured frame (diagnostic).
    public var captureReceivedAtMs: Int64
    public var waypoint: ClueBindingPoint?
    public var offsetMs: Int64?
    /// Offset to the nearest waypoint even when it is beyond the binding limit (diagnostic).
    public var nearestOffsetMs: Int64?
    public var quality: ClueBindingQuality
    public var hovering: Bool
    /// True when the clue was projected from the bound waypoint (no frame-matched drone position).
    public var originIsWaypoint: Bool
    /// Drone position decoded from the frame itself (DJI SEI), when present.
    public var framePosition: ClueBindingFramePosition?
    /// Waypoint nearest in time even beyond the binding limit; the projection origin when
    /// `originIsWaypoint` (equals `waypoint` whenever the clue is bound).
    public var nearest: ClueBindingPoint?

    public init(aircraftID: String, flightID: String?, flightStartMs: Int64?, captureTimeMs: Int64,
                captureTimeSource: String, captureReceivedAtMs: Int64, waypoint: ClueBindingPoint?,
                offsetMs: Int64?, nearestOffsetMs: Int64?, quality: ClueBindingQuality, hovering: Bool,
                originIsWaypoint: Bool, framePosition: ClueBindingFramePosition?) {
        self.aircraftID = aircraftID
        self.flightID = flightID
        self.flightStartMs = flightStartMs
        self.captureTimeMs = captureTimeMs
        self.captureTimeSource = captureTimeSource
        self.captureReceivedAtMs = captureReceivedAtMs
        self.waypoint = waypoint
        self.offsetMs = offsetMs
        self.nearestOffsetMs = nearestOffsetMs
        self.quality = quality
        self.hovering = hovering
        self.originIsWaypoint = originIsWaypoint
        self.framePosition = framePosition
    }

    /// True when the operator should look at the position (approximate and not hovering, or unbound).
    public var flagged: Bool { quality != .exact }
}

public enum ClueBinder {
    public static let exactLimitMs: Int64 = 2_000
    public static let approximateLimitMs: Int64 = 10_000
    /// No binding beyond this distance in time.
    public static let bindingLimitMs: Int64 = 30_000
    /// Waypoints this close together (or to the frame's own position) mean the drone was hovering.
    public static let hoverRadiusMeters = 5.0

    public static func quality(offsetMs: Int64?, hovering: Bool) -> ClueBindingQuality {
        guard let offsetMs else { return .unbound }
        let magnitude = abs(offsetMs)
        if magnitude > bindingLimitMs { return .unbound }
        if hovering || magnitude <= exactLimitMs { return .exact }
        if magnitude <= approximateLimitMs { return .approximate }
        return .approximateWarning
    }

    /// Index of the waypoint nearest in time; ties go to the earlier waypoint.
    public static func nearestIndex(_ points: [ClueBindingPoint], captureTimeMs: Int64) -> Int? {
        var best: Int?
        var bestDistance = Int64.max
        for (index, point) in points.enumerated() {
            let distance = abs(point.timeMs - captureTimeMs)
            if distance < bestDistance || (distance == bestDistance && best.map { point.timeMs < points[$0].timeMs } == true) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    public static func bind(
        aircraftID: String,
        flightID: String?,
        captureTimeMs: Int64,
        captureTimeSource: String,
        captureReceivedAtMs: Int64,
        points unsorted: [ClueBindingPoint],
        framePosition: ClueBindingFramePosition?,
        originIsWaypoint: Bool
    ) -> ClueBinding {
        let points = unsorted.sorted { $0.timeMs < $1.timeMs }
        var binding = ClueBinding(
            aircraftID: aircraftID, flightID: flightID, flightStartMs: points.first?.timeMs,
            captureTimeMs: captureTimeMs, captureTimeSource: captureTimeSource,
            captureReceivedAtMs: captureReceivedAtMs, waypoint: nil, offsetMs: nil, nearestOffsetMs: nil,
            quality: .unbound, hovering: false, originIsWaypoint: originIsWaypoint,
            framePosition: framePosition)
        if let index = nearestIndex(points, captureTimeMs: captureTimeMs) {
            let nearest = points[index]
            let offset = nearest.timeMs - captureTimeMs
            binding.nearestOffsetMs = offset
            binding.nearest = nearest
            if abs(offset) <= bindingLimitMs {
                binding.waypoint = nearest
                binding.offsetMs = offset
                binding.hovering = hovering(points: points, captureTimeMs: captureTimeMs, waypoint: nearest,
                                            framePosition: framePosition)
            }
        }
        binding.quality = quality(offsetMs: binding.offsetMs, hovering: binding.hovering)
        return binding
    }

    /// Re-binds against the waypoints available now (used while the clue form is open and once more
    /// on Submit). Without any points the stored binding is kept. A submitted clue is never re-bound.
    public static func refresh(_ binding: ClueBinding, points: [ClueBindingPoint]) -> ClueBinding {
        guard !points.isEmpty else { return binding }
        var next = bind(aircraftID: binding.aircraftID, flightID: binding.flightID,
                        captureTimeMs: binding.captureTimeMs, captureTimeSource: binding.captureTimeSource,
                        captureReceivedAtMs: binding.captureReceivedAtMs, points: points,
                        framePosition: binding.framePosition, originIsWaypoint: binding.originIsWaypoint)
        if next.flightStartMs == nil { next.flightStartMs = binding.flightStartMs }
        return next
    }

    static func hovering(points: [ClueBindingPoint], captureTimeMs: Int64, waypoint: ClueBindingPoint,
                         framePosition: ClueBindingFramePosition?) -> Bool {
        if let framePosition,
           let distance = distanceMeters(framePosition.latitude, framePosition.longitude,
                                         waypoint.latitude, waypoint.longitude),
           distance <= hoverRadiusMeters {
            return true
        }
        guard let before = points.last(where: { $0.timeMs <= captureTimeMs }),
              let after = points.first(where: { $0.timeMs >= captureTimeMs }),
              let distance = distanceMeters(before.latitude, before.longitude, after.latitude, after.longitude)
        else { return false }
        return distance <= hoverRadiusMeters
    }

    static func distanceMeters(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double? {
        RidGeometry.relativePosition(fromLatitude: lat1, longitude: lon1, toLatitude: lat2, longitude: lon2)?.distanceMeters
    }

    /// The origin move a Submit applies: when the clue was projected from the bound waypoint and the
    /// submit-time binding found a nearer waypoint than the one the form last showed.
    public static func originShift(shown: ClueBinding?, submitted: ClueBinding?)
        -> (from: ClueBindingPoint, to: ClueBindingPoint)? {
        guard let submitted, submitted.originIsWaypoint, let from = shown?.nearest, let to = submitted.nearest,
              from != to else { return nil }
        return (from, to)
    }

    /// Moves a clue that was projected from the bound waypoint when a nearer waypoint replaces it at
    /// Submit: the camera vector (bearing, range) is unchanged, so the clue shifts by the origin's
    /// displacement.
    public static func translated(latitude: Double, longitude: Double, from old: ClueBindingPoint,
                                  to new: ClueBindingPoint) -> (latitude: Double, longitude: Double) {
        let originCos = cos(old.latitude * .pi / 180)
        let clueCos = cos(latitude * .pi / 180)
        let longitudeShift = new.longitude - old.longitude
        // Same east/north displacement in metres as the origin moved.
        let scaled = abs(clueCos) > 1e-9 ? longitudeShift * originCos / clueCos : longitudeShift
        return (latitude + (new.latitude - old.latitude), longitude + scaled)
    }
}

public enum ClueBindingText {
    private static let posix = Locale(identifier: "en_US_POSIX")

    /// Signed seconds with millisecond precision, e.g. "+1.234 s" or "-0.250 s".
    public static func offset(_ milliseconds: Int64) -> String {
        let sign = milliseconds < 0 ? "-" : "+"
        let magnitude = milliseconds.magnitude
        return String(format: "%@%llu.%03llu s", locale: posix, sign, magnitude / 1_000, magnitude % 1_000)
    }

    public static func qualityLabel(_ binding: ClueBinding) -> String {
        switch binding.quality {
        case .exact: return binding.hovering && abs(binding.offsetMs ?? 0) > ClueBinder.exactLimitMs ? "exact (hovering)" : "exact"
        case .approximate: return "approximate"
        case .approximateWarning: return "approximate - check position"
        case .unbound: return "not bound (no waypoint within 30 s)"
        }
    }

    /// One line for the clue form.
    public static func formSummary(_ binding: ClueBinding) -> String {
        if let bound = binding.offsetMs { return "\(Self.offset(bound)) · \(qualityLabel(binding))" }
        if let nearest = binding.nearestOffsetMs { return "\(qualityLabel(binding)); nearest \(Self.offset(nearest))" }
        return qualityLabel(binding)
    }

    public static func iso(_ milliseconds: Int64) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: Date(timeIntervalSince1970: Double(milliseconds) / 1_000))
    }

    /// Lines appended to the clue description (CalTopo marker and KMZ).
    public static func descriptionLines(_ binding: ClueBinding) -> [String] {
        var lines = ["Waypoint binding:"]
        if let bound = binding.offsetMs {
            lines.append("  Offset: \(Self.offset(bound)) (waypoint minus capture, drone clock)")
        } else if let nearest = binding.nearestOffsetMs {
            lines.append("  Offset: none; nearest waypoint \(Self.offset(nearest))")
        } else {
            lines.append("  Offset: none; no waypoint")
        }
        lines.append("  Quality: \(qualityLabel(binding))")
        if let waypoint = binding.waypoint {
            lines.append(String(format: "  Waypoint: %.6f, %.6f at %@ (%@%@)", locale: posix,
                                waypoint.latitude, waypoint.longitude, iso(waypoint.timeMs), waypoint.source,
                                waypoint.droneClock ? "" : ", receive time"))
        }
        lines.append("  Capture: \(iso(binding.captureTimeMs)) (\(binding.captureTimeSource))")
        var diagnostics = ["capture \(iso(binding.captureReceivedAtMs))"]
        if let received = binding.waypoint?.receivedAtMs { diagnostics.append("waypoint \(iso(received))") }
        lines.append("  App receive times (diagnostic): \(diagnostics.joined(separator: ", "))")
        return lines
    }

    /// Text for CalTopo and the KMZ: the stored description plus the binding block.
    public static func publishedDescription(_ description: String, binding: ClueBinding?) -> String {
        guard let binding else { return description }
        let block = descriptionLines(binding).joined(separator: "\n")
        let trimmed = description.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? block : trimmed + "\n\n" + block
    }

    /// KML ExtendedData name/value pairs.
    public static func extendedData(_ binding: ClueBinding) -> [(String, String)] {
        var pairs: [(String, String)] = [
            ("r2c_binding_quality", binding.quality.rawValue),
            ("r2c_binding_hovering", binding.hovering ? "true" : "false"),
            ("r2c_capture_time", iso(binding.captureTimeMs)),
            ("r2c_capture_time_source", binding.captureTimeSource),
            ("r2c_capture_received_at", iso(binding.captureReceivedAtMs)),
        ]
        if let offset = binding.offsetMs {
            pairs.append(("r2c_binding_offset_s", String(format: "%.3f", locale: posix, Double(offset) / 1_000)))
        }
        if let waypoint = binding.waypoint {
            pairs.append(("r2c_waypoint_time", iso(waypoint.timeMs)))
            pairs.append(("r2c_waypoint_lat", String(format: "%.6f", locale: posix, waypoint.latitude)))
            pairs.append(("r2c_waypoint_lon", String(format: "%.6f", locale: posix, waypoint.longitude)))
            pairs.append(("r2c_waypoint_source", waypoint.source))
            if let received = waypoint.receivedAtMs { pairs.append(("r2c_waypoint_received_at", iso(received))) }
        }
        if let flightID = binding.flightID { pairs.append(("r2c_flight_id", flightID)) }
        return pairs
    }
}

public extension ClueBindingPoint {
    static func milliseconds(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1_000).rounded()) }

    init(trackPoint point: RidTrackPoint) {
        self.init(timeMs: Self.milliseconds(point.bindingTime), receivedAtMs: Self.milliseconds(point.receivedAt),
                  latitude: point.latitude, longitude: point.longitude, altitudeMeters: point.altitudeMeters,
                  source: point.source?.rawValue ?? "unknown", droneClock: point.droneTime != nil)
    }

    init(journalPoint point: CaltopoInterruptedPublicationPoint) {
        self.init(timeMs: Self.milliseconds(point.bindingTime), receivedAtMs: Self.milliseconds(point.receivedAt),
                  latitude: point.latitude, longitude: point.longitude, altitudeMeters: point.altitudeMeters,
                  source: point.source.rawValue, droneClock: point.droneTime != nil)
    }
}

/// Maps a stream's presentation timestamps (drone encoder clock) to epoch milliseconds. The anchor is
/// the smallest receive-minus-PTS offset seen, i.e. the least-delayed frame, so jitter never moves a
/// frame later. Resets when the PTS clock jumps (new stream session).
public struct StreamDroneClock: Sendable, Equatable {
    public private(set) var offsetMs: Int64?
    public private(set) var lastPtsMs: Int64?
    public static let resetJumpMs: Int64 = 5_000

    public init() {}

    public mutating func observe(ptsMicroseconds: Int64?, receivedAtMs: Int64) {
        guard let ptsMicroseconds, ptsMicroseconds > 0 else { return }
        let ptsMs = ptsMicroseconds / 1_000
        let candidate = receivedAtMs - ptsMs
        if let lastPtsMs, ptsMs < lastPtsMs - 1_000 { offsetMs = nil }
        if let current = offsetMs, candidate > current + Self.resetJumpMs { offsetMs = nil }
        offsetMs = min(offsetMs ?? candidate, candidate)
        lastPtsMs = ptsMs
    }

    public func droneTimeMs(ptsMicroseconds: Int64?) -> Int64? {
        guard let ptsMicroseconds, ptsMicroseconds > 0, let offsetMs else { return nil }
        return ptsMicroseconds / 1_000 + offsetMs
    }
}

/// F3411 Location timestamps are tenths of a second past the UTC hour. Resolves them to the hour
/// nearest the receive time (same rule as Android's ridTimestampTenthsToUtcMsec).
public enum RidDroneTimestamp {
    public static func utcMilliseconds(tenths rawTenths: UInt16, receivedAtMs: Int64) -> Int64? {
        guard rawTenths != 0xFFFF else { return nil }
        let tenths = Int64(rawTenths) % 36_000
        let withinHour = tenths * 100
        let hour: Int64 = 3_600_000
        let base = (receivedAtMs / hour) * hour
        let candidates = [base - hour + withinHour, base + withinHour, base + hour + withinHour]
        return candidates.min { abs($0 - receivedAtMs) < abs($1 - receivedAtMs) }
    }
}
