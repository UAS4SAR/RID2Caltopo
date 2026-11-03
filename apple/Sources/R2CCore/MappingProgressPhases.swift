import Foundation

/// Phase-weighted mapping progress. Never maps optimizer cost into a fraction.
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

    private static let flat: [Phase] = [
        Phase(id: checkOriginals, weight: 0.04, title: "Checking originals"),
        Phase(id: reconstruct, weight: 0.28, title: "Reconstructing geometry"),
        Phase(id: prepareColors, weight: 0.04, title: "Preparing colors"),
        Phase(id: matchSeams, weight: 0.20, title: "Matching seams"),
        Phase(id: projectTiles, weight: 0.24, title: "Projecting tiles"),
        Phase(id: zoomLevels, weight: 0.08, title: "Building zoom levels"),
        Phase(id: package, weight: 0.08, title: "Packaging"),
        Phase(id: publish, weight: 0.04, title: "Publishing"),
    ]

    public static func phases(engine: String) -> [Phase] {
        // iOS regional RTK path uses the same honest band weights as Android rtk-surface / flat.
        _ = engine
        return flat
    }

    public static func tracker(engine: String) -> Tracker { Tracker(phases: phases(engine: engine)) }

    public struct Tracker: Sendable {
        public let phases: [Phase]
        private let starts: [String: Double]

        public init(phases: [Phase]) {
            precondition(!phases.isEmpty)
            let sum = phases.reduce(0.0) { $0 + $1.weight }
            precondition(abs(sum - 1.0) < 1e-9, "Phase weights must sum to 1.0")
            self.phases = phases
            var map: [String: Double] = [:]
            var start = 0.0
            for phase in phases {
                map[phase.id] = start
                start += phase.weight
            }
            self.starts = map
        }

        public func band(_ phase: String) -> ClosedRange<Double> {
            let p = phases.first { $0.id == phase }!
            let start = starts[phase]!
            return start...(start + p.weight)
        }

        /// nil local = indeterminate; overall stays at band.lowerBound.
        public func overall(_ phase: String, local: Double?) -> Double {
            let range = band(phase)
            guard let local else { return range.lowerBound }
            let clamped = min(1, max(0, local))
            return range.lowerBound + clamped * (range.upperBound - range.lowerBound)
        }

        public func overall(_ phase: String, unitIndex: Int, unitTotal: Int) -> Double {
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
    }
}
