import Foundation
import os

/// Phase-weighted mapping progress. Weights come from measured per-phase cost
/// (ms and photo count) with photo-scaled defaults. Never maps optimizer cost into a fraction.
///
/// ETA uses remaining weighted work / EMA of weighted progress per second — never
/// elapsed/overall alone (that grows when a long stage sits flat).
public enum MappingProgressPhases {
    public static let checkOriginals = "check-originals"
    public static let reconstruct = "reconstruct"
    public static let prepareColors = "prepare-colors"
    public static let matchSeams = "match-seams"
    public static let projectTiles = "project-tiles"
    public static let zoomLevels = "zoom-levels"
    public static let package = "package"
    public static let publish = "publish"

    /// Late tiles dominate wall time (A5 Pro: early ~2 s, late ~45 s). Count alone runs ahead.
    public static let tileLocalExponent = 3.5

    public struct Phase: Sendable, Equatable {
        public let id: String
        public let weight: Double
        public let title: String
    }

    private static let ids = [
        checkOriginals, reconstruct, prepareColors, matchSeams,
        projectTiles, zoomLevels, package, publish,
    ]

    private static let titles: [String: String] = [
        checkOriginals: "Checking originals",
        reconstruct: "Reconstructing geometry",
        prepareColors: "Preparing colors",
        matchSeams: "Matching seams",
        projectTiles: "Projecting tiles",
        zoomLevels: "Building zoom levels",
        package: "Packaging",
        publish: "Publishing",
    ]

    /// ≈ 1.5 × N when tile count is not yet known.
    public static func estimateTileCount(photoCount: Int) -> Int {
        max(1, (max(1, photoCount) * 3 + 1) / 2)
    }

    /// Full-run calibration (A5 Pro, 134 photos, 195 tiles, ~6490 s wall):
    /// early ~167 s; colors ~180 s (~1.35 s/photo); seams ~3527 s (~26.3 s/photo);
    /// tiles ~2500 s (~12.8 s/tile); zoom ~12 s; package ~102 s.
    public static func defaultCostsMs(photoCount: Int, engine: String, tileCount: Int? = nil) -> [String: Double] {
        let n = Double(max(1, photoCount))
        let tiles = Double(max(1, tileCount ?? estimateTileCount(photoCount: photoCount)))
        let reconstructMs = engine == "rtk-surface" ? 1170.0 * n : 900.0 * n
        return [
            checkOriginals: 80.0 * n,
            reconstruct: reconstructMs,
            prepareColors: 1350.0 * n,
            matchSeams: 26_300.0 * n,
            projectTiles: 12_800.0 * tiles,
            zoomLevels: 12_000.0,
            package: 102_000.0,
            publish: 8_000.0,
        ]
    }

    /// Intra-phase fraction for tile projection. Prefers work units when known; otherwise
    /// a back-loaded curve so early cheap tiles do not run ahead of wall time.
    public static func tileLocalFraction(
        unitIndex: Int,
        unitTotal: Int,
        workDone: Double? = nil,
        workTotal: Double? = nil
    ) -> Double {
        if let workDone, let workTotal, workTotal > 0 {
            return min(1, max(0, workDone / workTotal))
        }
        guard unitTotal > 0 else { return 0 }
        let f = min(1, max(0, Double(unitIndex) / Double(unitTotal)))
        return min(1, max(0, pow(f, tileLocalExponent)))
    }

    public static func weightsFromCosts(_ costs: [String: Double]) -> [Phase] {
        let ordered = ids.map { ($0, max(1.0, costs[$0] ?? 1.0)) }
        let sum = ordered.reduce(0.0) { $0 + $1.1 }
        return ordered.map { Phase(id: $0.0, weight: $0.1 / sum, title: titles[$0.0]!) }
    }

    public static func phases(
        engine: String,
        photoCount: Int = 50,
        measuredMsPerPhoto: [String: Double] = [:],
        tileCount: Int? = nil
    ) -> [Phase] {
        var defaults = defaultCostsMs(photoCount: photoCount, engine: engine, tileCount: tileCount)
        let n = Double(max(1, photoCount))
        for (id, msPerPhoto) in measuredMsPerPhoto {
            guard defaults[id] != nil else { continue }
            let measured = msPerPhoto * n
            defaults[id] = 0.65 * measured + 0.35 * defaults[id]!
        }
        return weightsFromCosts(defaults)
    }

    public static func tracker(
        engine: String,
        photoCount: Int = 50,
        measuredMsPerPhoto: [String: Double] = [:],
        tileCount: Int? = nil
    ) -> Tracker {
        Tracker(
            phases: phases(engine: engine, photoCount: photoCount, measuredMsPerPhoto: measuredMsPerPhoto, tileCount: tileCount),
            photoCount: photoCount
        )
    }

    public enum Costs {
        public static let schema = 1

        public static func fileURL(supportRoot: URL) -> URL {
            supportRoot.appendingPathComponent("mapping-storage-stats/phase-costs.json")
        }

        public static func load(supportRoot: URL) -> [String: Double] {
            guard let data = try? Data(contentsOf: fileURL(supportRoot: supportRoot)),
                  let text = String(data: data, encoding: .utf8) else { return [:] }
            return parse(text)
        }

        public static func parse(_ text: String) -> [String: Double] {
            guard let data = text.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  (object["schema"] as? Int) == schema,
                  let phases = object["msPerPhoto"] as? [String: Any] else { return [:] }
            var out: [String: Double] = [:]
            for (key, value) in phases {
                let number: Double?
                if let d = value as? Double { number = d }
                else if let i = value as? Int { number = Double(i) }
                else { number = nil }
                if let number, number.isFinite, number > 0 { out[key] = number }
            }
            return out
        }

        public static func record(supportRoot: URL, phaseId: String, elapsedMs: Int64, photoCount: Int) {
            guard elapsedMs >= 1, photoCount >= 1, !phaseId.isEmpty else { return }
            do {
                var prior = load(supportRoot: supportRoot)
                let sample = Double(elapsedMs) / Double(photoCount)
                prior[phaseId] = prior[phaseId].map { 0.7 * sample + 0.3 * $0 } ?? sample
                try writeThrowing(supportRoot: supportRoot, msPerPhoto: prior)
            } catch {
                Logger(subsystem: "org.ncssar.RID2Caltopo", category: "MappingProgress")
                    .debug("phase-costs record failed: \(error.localizedDescription, privacy: .public)")
            }
        }

        public static func write(supportRoot: URL, msPerPhoto: [String: Double]) {
            do {
                try writeThrowing(supportRoot: supportRoot, msPerPhoto: msPerPhoto)
            } catch {
                Logger(subsystem: "org.ncssar.RID2Caltopo", category: "MappingProgress")
                    .debug("phase-costs write failed: \(error.localizedDescription, privacy: .public)")
            }
        }

        private static func writeThrowing(supportRoot: URL, msPerPhoto: [String: Double]) throws {
            let url = fileURL(supportRoot: supportRoot)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            let body: [String: Any] = ["schema": schema, "msPerPhoto": msPerPhoto]
            let data = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
            try data.write(to: url, options: .atomic)
        }
    }

    public struct RateEma: Sendable {
        private var rate: Double?
        private var lastOverall: Double?
        private var lastDate: Date?
        private var samples = 0
        private let halfLifeSeconds: Double

        public init(halfLifeSeconds: Double = 45) {
            self.halfLifeSeconds = halfLifeSeconds
        }

        public mutating func observe(overall: Double, now: Date = Date()) -> Double? {
            let prevO = lastOverall
            let prevT = lastDate
            lastOverall = overall
            lastDate = now
            guard let prevO, let prevT else { return rate }
            let dt = now.timeIntervalSince(prevT)
            guard dt >= 0.5 else { return rate }
            let instant = max(0, (overall - prevO) / dt)
            let alpha = 1.0 - exp(-dt * log(2.0) / halfLifeSeconds)
            rate = rate.map { alpha * instant + (1 - alpha) * $0 } ?? instant
            samples += 1
            return rate
        }

        public func etaSeconds(overall: Double) -> Int64? {
            guard let r = rate, samples >= 3, overall >= 0.05 else { return nil }
            if overall >= 0.99 { return 0 }
            if r < 1e-5 { return nil }
            return Int64(min(24 * 3600, max(0, (1.0 - overall) / r)))
        }
    }

    public struct Tracker: Sendable {
        public let phases: [Phase]
        private let starts: [String: Double]
        private let photoCount: Int
        private var floor: Double
        public private(set) var rate = RateEma()
        private var lastOverallForFlat = 0.0
        private var flatSince: Date?

        public init(phases: [Phase], photoCount: Int) {
            precondition(!phases.isEmpty)
            let sum = phases.reduce(0.0) { $0 + $1.weight }
            precondition(abs(sum - 1.0) < 1e-9, "Phase weights must sum to 1.0")
            self.phases = phases
            self.photoCount = photoCount
            self.floor = 0
            var map: [String: Double] = [:]
            var start = 0.0
            for phase in phases {
                map[phase.id] = start
                start += phase.weight
            }
            self.starts = map
        }

        public func weight(_ phase: String) -> Double {
            phases.first { $0.id == phase }?.weight ?? 0
        }

        public func band(_ phase: String) -> ClosedRange<Double> {
            guard let p = phases.first(where: { $0.id == phase }) else { return 0...0 }
            let start = starts[phase] ?? 0
            return start...(start + p.weight)
        }

        public mutating func overall(_ phase: String, local: Double?, timeHint: Double? = nil) -> Double {
            let range = band(phase)
            let safeLocal = local.flatMap { $0.isFinite ? $0 : nil }
            let safeHint = timeHint.flatMap { $0.isFinite ? $0 : nil }
            let raw: Double
            if let safeLocal {
                let clamped = min(1, max(0, safeLocal))
                raw = range.lowerBound + clamped * (range.upperBound - range.lowerBound)
            } else if let safeHint {
                let clamped = min(0.85, max(0, safeHint))
                raw = range.lowerBound + clamped * (range.upperBound - range.lowerBound)
            } else {
                raw = range.lowerBound
            }
            let value = max(floor, raw)
            if value.isFinite {
                floor = value
                return value
            }
            return floor.isFinite ? floor : 0
        }

        public mutating func overall(
            _ phase: String,
            unitIndex: Int,
            unitTotal: Int,
            workDone: Double? = nil,
            workTotal: Double? = nil
        ) -> Double {
            guard unitTotal > 0 else { return overall(phase, local: nil) }
            let index = min(unitTotal, max(0, unitIndex))
            let local: Double
            if phase == projectTiles {
                local = tileLocalFraction(unitIndex: index, unitTotal: unitTotal, workDone: workDone, workTotal: workTotal)
            } else {
                local = Double(index) / Double(unitTotal)
            }
            return overall(phase, local: local)
        }

        public func stage(_ phase: String, unitIndex: Int? = nil, unitTotal: Int? = nil) -> String {
            let title = phases.first { $0.id == phase }?.title ?? phase
            if let unitIndex, let unitTotal, unitTotal > 0 {
                return "\(title) · \(unitIndex)/\(unitTotal)"
            }
            return title
        }

        public func title(_ phase: String) -> String {
            phases.first { $0.id == phase }?.title ?? phase
        }

        public mutating func etaSeconds(overall: Double, now: Date = Date()) -> Int64? {
            _ = rate.observe(overall: overall, now: now)
            return rate.etaSeconds(overall: overall)
        }

        public mutating func isFlat(overall: Double, now: Date = Date(), flatSeconds: TimeInterval = 60) -> Bool {
            if abs(overall - lastOverallForFlat) > 1e-4 {
                lastOverallForFlat = overall
                flatSince = now
                return false
            }
            let since = flatSince ?? now
            if flatSince == nil { flatSince = now }
            return now.timeIntervalSince(since) >= flatSeconds
        }
    }
}
