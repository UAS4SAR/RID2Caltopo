import Foundation

public struct MapLabelRect: Sendable, Equatable {
    public let left: Double
    public let top: Double
    public let right: Double
    public let bottom: Double

    public init(left: Double, top: Double, right: Double, bottom: Double) {
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    public var width: Double { right - left }
    public var height: Double { bottom - top }
    public var centerX: Double { (left + right) / 2 }
    public var centerY: Double { (top + bottom) / 2 }

    public func intersects(_ other: MapLabelRect) -> Bool {
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    fileprivate func fits(width: Double, height: Double) -> Bool {
        left >= 0 && top >= 0 && right <= width && bottom <= height
    }

    fileprivate func overlapArea(_ other: MapLabelRect) -> Double {
        max(0, min(right, other.right) - max(left, other.left))
            * max(0, min(bottom, other.bottom) - max(top, other.top))
    }

    fileprivate func outsideArea(width: Double, height: Double) -> Double {
        let horizontal = max(0, -left) + max(0, right - width)
        let vertical = max(0, -top) + max(0, bottom - height)
        return horizontal * self.height + vertical * self.width
    }
}

public struct MapAircraftLabelInput: Sendable, Equatable {
    public let id: String
    public let anchor: MapScreenPoint
    public let nameWidth: Double
    public let nameHeight: Double
    public let statusWidth: Double
    public let statusHeight: Double

    public init(
        id: String,
        anchor: MapScreenPoint,
        nameWidth: Double,
        nameHeight: Double,
        statusWidth: Double,
        statusHeight: Double
    ) {
        self.id = id
        self.anchor = anchor
        self.nameWidth = nameWidth
        self.nameHeight = nameHeight
        self.statusWidth = statusWidth
        self.statusHeight = statusHeight
    }
}

public struct MapAircraftLabelLayout: Sendable, Equatable {
    public let id: String
    public let bounds: MapLabelRect
    public let nameBounds: MapLabelRect
    public let statusBounds: MapLabelRect
    public let leaderEnd: MapScreenPoint?
}

public enum OperationalAircraftDisplay {
    /// Highlight only a displayed numeric clearance below zero, never a status placeholder.
    public static func negativeAOLRange(in text: String) -> Range<String.Index>? {
        guard let prefix = text.range(of: "AOL:") else { return nil }
        guard let valueStart = text[prefix.upperBound...].firstIndex(where: { !$0.isWhitespace }) else { return nil }
        let end = text[valueStart...].firstIndex(where: { $0.isWhitespace }) ?? text.endIndex
        let value = text[valueStart..<end].replacingOccurrences(of: "'", with: "")
        guard let feet = Double(value), feet.isFinite, feet < 0 else { return nil }
        return prefix.lowerBound..<end
    }

    public static let displayedPositionStaleAfter: TimeInterval = 5
    public static let displayedPositionVeryStaleAfter: TimeInterval = 10

    public static func isDisplayedPositionStale(
        lastAcceptedPositionAt: Date?,
        now: Date,
        staleAfter: TimeInterval = displayedPositionStaleAfter
    ) -> Bool {
        guard let lastAcceptedPositionAt else { return false }
        return now.timeIntervalSince(lastAcceptedPositionAt) >= max(0, staleAfter)
    }

    public static func positionIconAlpha(
        lastAcceptedPositionAt: Date?,
        now: Date
    ) -> Double {
        guard let lastAcceptedPositionAt else { return 1 }
        switch now.timeIntervalSince(lastAcceptedPositionAt) {
        case displayedPositionVeryStaleAfter...:
            return 0.25
        case displayedPositionStaleAfter..<displayedPositionVeryStaleAfter:
            return 0.5
        default:
            return 1
        }
    }

    public static func streamHeader(
        designator: String,
        atoFeet: Double?,
        aglFeet: Double?,
        aglStale: Bool,
        rangeFeet: Double?,
        headingDegrees: Double?,
        aol: OperationalAOLState? = nil,
        atoStatus: OperationalMeasurementStatus = .available,
        aglStatus: OperationalMeasurementStatus? = nil,
        telemetryStale: Bool = false
    ) -> String {
        "\(designator)  " + statusLabel(
            atoFeet: atoFeet,
            aglFeet: aglFeet,
            aglStale: aglStale,
            rangeFeet: rangeFeet,
            headingDegrees: headingDegrees,
            headingLabel: "TRK",
            aol: aol,atoStatus:atoStatus,aglStatus:aglStatus,telemetryStale:telemetryStale
        )
    }

    public static func statusLabel(
        atoFeet: Double?,
        aglFeet: Double?,
        aglStale: Bool,
        rangeFeet: Double?,
        headingDegrees: Double?,
        headingLabel: String = "HDG",
        positionStale: Bool = false,
        aol: OperationalAOLState? = nil,
        atoStatus: OperationalMeasurementStatus = .available,
        aglStatus: OperationalMeasurementStatus? = nil,
        telemetryStale: Bool = false
    ) -> String {
        func label(_ value:Double?,status:OperationalMeasurementStatus = .available,suffix:String="'",cap:Double = .infinity) -> String {
            (positionStale || telemetryStale ? OperationalMeasurementStatus.stale : status).label(value,suffix:suffix,maxAbs:cap)
        }
        let ato=label(atoFeet,status:atoStatus,cap:1000)
        let agl=(positionStale || telemetryStale ? OperationalMeasurementStatus.stale :
            (aglStatus ?? (aglStale ? .unknown : .available))).label(aglFeet, suffix: "'", maxAbs: 1000, showPendingValue: true)
        let surface=label(aol?.feet,status:aol?.status ?? .unknown)
        let range=label(rangeFeet)
        // Preserve a valid recent heading when altitude/other telemetry ages out.
        // The map's bearing line uses the same recent motion, so the label should
        // not become `Unk` solely because the altitude coordinator is stale.
        let heading = (positionStale ? OperationalMeasurementStatus.stale : .available).label(
            RidHeading.roundedWholeDegrees(headingDegrees).map(Double.init),
            suffix: "°"
        )
        return "ATO:\(ato) AGL:\(agl) AOL:\(surface) RNG:\(range) \(headingLabel):\(heading)"
    }

    public static func layoutLabels(
        _ inputs: [MapAircraftLabelInput],
        viewportWidth: Double,
        viewportHeight: Double
    ) -> [MapAircraftLabelLayout] {
        var placed: [MapLabelRect] = []
        return inputs.map { input in
            let groupWidth = max(input.nameWidth, input.statusWidth)
            let groupHeight = input.nameHeight + 3 + input.statusHeight
            let side = groupWidth / 2 + 44
            let farSide = groupWidth / 2 + 92
            let centeredY = -(groupHeight / 2)
            let offsets: [(Double, Double)] = [
                (0, 28), (0, -(groupHeight + 28)), (side, centeredY), (-side, centeredY),
                (side, 34), (-side, 34), (0, groupHeight + 34), (farSide, centeredY), (-farSide, centeredY),
            ]
            let candidates = offsets.map { offset in
                candidate(input: input, offsetX: offset.0, offsetY: offset.1)
            }
            let selected = candidates.first { candidate in
                candidate.bounds.fits(width: viewportWidth, height: viewportHeight)
                    && placed.allSatisfy { !$0.intersects(candidate.bounds) }
            } ?? candidates.min { lhs, rhs in
                let lhsOverlap = placed.reduce(0) { $0 + lhs.bounds.overlapArea($1) }
                let rhsOverlap = placed.reduce(0) { $0 + rhs.bounds.overlapArea($1) }
                if lhsOverlap != rhsOverlap { return lhsOverlap < rhsOverlap }
                return lhs.bounds.outsideArea(width: viewportWidth, height: viewportHeight)
                    < rhs.bounds.outsideArea(width: viewportWidth, height: viewportHeight)
            }!
            placed.append(selected.bounds)
            return MapAircraftLabelLayout(
                id: input.id,
                bounds: selected.bounds,
                nameBounds: selected.name,
                statusBounds: selected.status,
                leaderEnd: selected.offsetX == 0 && selected.offsetY == 28
                    ? nil
                    : MapScreenPoint(x: selected.bounds.centerX, y: selected.bounds.centerY)
            )
        }
    }

    public static func predictedCoordinate(
        previous: MapCoordinate,
        previousTime: Date,
        current: MapCoordinate,
        currentTime: Date,
        now: Date
    ) -> MapCoordinate? {
        // Predictive heads are retired. Callers render the accepted current position.
        return nil
    }

    private struct Candidate {
        let bounds: MapLabelRect
        let name: MapLabelRect
        let status: MapLabelRect
        let offsetX: Double
        let offsetY: Double
    }

    private static func candidate(input: MapAircraftLabelInput, offsetX: Double, offsetY: Double) -> Candidate {
        let width = max(input.nameWidth, input.statusWidth)
        let height = input.nameHeight + 3 + input.statusHeight
        let left = input.anchor.x + offsetX - width / 2
        let top = input.anchor.y + offsetY
        let bounds = MapLabelRect(left: left, top: top, right: left + width, bottom: top + height)
        let nameLeft = left + (width - input.nameWidth) / 2
        let name = MapLabelRect(left: nameLeft, top: top, right: nameLeft + input.nameWidth, bottom: top + input.nameHeight)
        let statusLeft = left + (width - input.statusWidth) / 2
        let statusTop = name.bottom + 3
        let status = MapLabelRect(
            left: statusLeft, top: statusTop,
            right: statusLeft + input.statusWidth, bottom: statusTop + input.statusHeight
        )
        return Candidate(bounds: bounds, name: name, status: status, offsetX: offsetX, offsetY: offsetY)
    }

    private static func destination(
        from origin: MapCoordinate,
        bearingDegrees: Double,
        distanceMeters: Double
    ) -> MapCoordinate {
        let radius = 6_371_008.8
        let angular = distanceMeters / radius
        let bearing = bearingDegrees * .pi / 180
        let latitude = origin.latitude * .pi / 180
        let longitude = origin.longitude * .pi / 180
        let destinationLatitude = asin(
            sin(latitude) * cos(angular) + cos(latitude) * sin(angular) * cos(bearing)
        )
        let destinationLongitude = longitude + atan2(
            sin(bearing) * sin(angular) * cos(latitude),
            cos(angular) - sin(latitude) * sin(destinationLatitude)
        )
        return MapCoordinate(
            latitude: destinationLatitude * 180 / .pi,
            longitude: ((destinationLongitude * 180 / .pi + 540).truncatingRemainder(dividingBy: 360)) - 180
        )
    }
}

public enum OperationalStreamConfirmationMatch {
    public static func remoteID(designator: String, mappings: [(remoteID:String,designator:String)]) -> String? {
        let key=designator.trimmingCharacters(in:.whitespacesAndNewlines).lowercased()
        guard !key.isEmpty else { return nil }
        let matches=Set(mappings.filter { $0.designator.trimmingCharacters(in:.whitespacesAndNewlines).lowercased()==key }.map(\.remoteID))
        return matches.count==1 ? matches.first : nil
    }
}

/// Fixed monospaced value slots for the video overlay, including uncertainty markers.
public func stableVideoTelemetryText(_ text: String) -> String {
    let tokens = text.components(separatedBy: " ")
    return (tokens.filter { !$0.hasPrefix("RNG:") } + tokens.filter { $0.hasPrefix("RNG:") }).map { token in
        guard let separator = token.firstIndex(of: ":") else { return token }
        var value = String(token[token.index(after: separator)...])
        let uncertain = value.hasSuffix("?")
        if uncertain { value.removeLast() }
        let width = ["CAM:", "TRK:", "HDG:"].contains(String(token[...separator])) ? 4 : 5
        return String(token[...separator]) + String(repeating: " ", count: max(0, width - value.count)) + value + (uncertain ? "?" : " ")
    }.joined(separator: " ")
}
