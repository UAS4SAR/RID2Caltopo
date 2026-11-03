import Foundation

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

    /// Initial measured cost table (A5 Pro): prepare-colors ≈ 3.4 s/photo; seams multi-minute.
    public static func defaultCostsMs(photoCount: Int, engine: String) -> [String: Double] {
        let n = Double(max(1, photoCount))
        let reconstructMs = engine == "rtk-surface" ? 3500.0 * n : 900.0 * n
        return [
            checkOriginals: 80.0 * n,
            reconstruct: reconstructMs,
            prepareColors: 3400.0 * n,
            matchSeams: 5500.0 * n,
            projectTiles: 600.0 * n,
            zoomLevels: 12_000.0 + 20.0 * n,
            package: 25_000.0 + 40.0 * n,
            publish: 8_000.0,
        ]
    }

    public static func weightsFromCosts(_ costs: [String: Double]) -> [Phase] {
        let ordered = ids.map { ($0, max(1.0, costs[$0] ?? 1.0)) }
        let sum = ordered.reduce(0.0) { $0 + $1.1 }
        return ordered.map { Phase(id: $0.0, weight: $0.1 / sum, title: titles[$0.0]!) }
    }

    public static func phases(engine: String, photoCount: Int = 50, measuredMsPerPhoto: [String: Double] = [:]) -> [Phase] {
        var defaults = defaultCostsMs(photoCount: photoCount, engine: engine)
        let n = Double(max(1, photoCount))
        for (id, msPerPhoto) in measuredMsPerPhoto {
            guard defaults[id] != nil else { continue }
            let measured = msPerPhoto * n
            defaults[id] = 0.65 * measured + 0.35 * defaults[id]!
        }
        return weightsFromCosts(defaults)
    }

    public static func tracker(engine: String, photoCount: Int = 50, measuredMsPerPhoto: [String: Double] = [:]) -> Tracker {
        Tracker(phases: phases(engine: engine, photoCount: photoCount, measuredMsPerPhoto: measuredMsPerPhoto), photoCount: photoCount)
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
            var prior = load(supportRoot: supportRoot)
            let sample = Double(elapsedMs) / Double(photoCount)
            prior[phaseId] = prior[phaseId].map { 0.7 * sample + 0.3 * $0 } ?? sample
            write(supportRoot: supportRoot, msPerPhoto: prior)
        }

        public static func write(supportRoot: URL, msPerPhoto: [String: Double]) {
            let url = fileURL(supportRoot: supportRoot)
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            let body: [String: Any] = ["schema": schema, "msPerPhoto": msPerPhoto]
            guard let data = try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys]) else { return }
            try? data.write(to: url, options: .atomic)
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
            phases.first { $0.id == phase }!.weight
        }

        public func band(_ phase: String) -> ClosedRange<Double> {
            let p = phases.first { $0.id == phase }!
            let start = starts[phase]!
            return start...(start + p.weight)
        }

        public mutating func overall(_ phase: String, local: Double?, timeHint: Double? = nil) -> Double {
            let range = band(phase)
            let raw: Double
            if let local {
                let clamped = min(1, max(0, local))
                raw = range.lowerBound + clamped * (range.upperBound - range.lowerBound)
            } else if let timeHint {
                let clamped = min(0.85, max(0, timeHint))
                raw = range.lowerBound + clamped * (range.upperBound - range.lowerBound)
            } else {
                raw = range.lowerBound
            }
            let value = max(floor, raw)
            floor = value
            return value
        }

        public mutating func overall(_ phase: String, unitIndex: Int, unitTotal: Int) -> Double {
            precondition(unitTotal > 0 && unitIndex >= 0 && unitIndex <= unitTotal)
            return overall(phase, local: Double(unitIndex) / Double(unitTotal))
        }

        public func stage(_ phase: String, unitIndex: Int? = nil, unitTotal: Int? = nil) -> String {
            let title = phases.first { $0.id == phase }!.title
            if let unitIndex, let unitTotal, unitTotal > 0 {
                return "\(title) · \(unitIndex)/\(unitTotal)"
            }
            return title
        }

        public func title(_ phase: String) -> String {
            phases.first { $0.id == phase }!.title
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
