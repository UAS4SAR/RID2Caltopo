import Foundation

/// Pure visual equality for aircraft map annotation configure skips (no MapKit).
public struct AircraftMapAnnotationVisualKey: Equatable, Sendable {
    public var latitude: Double
    public var longitude: Double
    public var headingDegrees: Double?
    public var title: String
    public var statusText: String
    public var focused: Bool
    public var positionIconAlpha: Double
    public var inset: Bool
    public var labelSide: Int

    public init(
        latitude: Double,
        longitude: Double,
        headingDegrees: Double?,
        title: String,
        statusText: String,
        focused: Bool,
        positionIconAlpha: Double,
        inset: Bool,
        labelSide: Int
    ) {
        self.latitude = latitude
        self.longitude = longitude
        self.headingDegrees = headingDegrees
        self.title = title
        self.statusText = statusText
        self.focused = focused
        self.positionIconAlpha = positionIconAlpha
        self.inset = inset
        self.labelSide = labelSide
    }
}

public enum AircraftMapAnnotationDiff {
    public static let coordinateEpsilonDegrees = 1e-7
    public static let headingEpsilonDegrees = 0.5
    public static let alphaEpsilon = 0.01

    /// True when a MapKit `configure` would not change the visible annotation.
    public static func isVisuallyUnchanged(
        _ previous: AircraftMapAnnotationVisualKey,
        _ next: AircraftMapAnnotationVisualKey
    ) -> Bool {
        guard previous.title == next.title,
              previous.statusText == next.statusText,
              previous.focused == next.focused,
              previous.inset == next.inset,
              previous.labelSide == next.labelSide
        else { return false }
        guard abs(previous.latitude - next.latitude) <= coordinateEpsilonDegrees,
              abs(previous.longitude - next.longitude) <= coordinateEpsilonDegrees
        else { return false }
        guard abs(previous.positionIconAlpha - next.positionIconAlpha) <= alphaEpsilon
        else { return false }
        switch (previous.headingDegrees, next.headingDegrees) {
        case (nil, nil):
            return true
        case let (left?, right?):
            return abs(Self.headingDeltaDegrees(left, right)) <= headingEpsilonDegrees
        default:
            return false
        }
    }

    public static func headingDeltaDegrees(_ left: Double, _ right: Double) -> Double {
        var delta = (right - left).truncatingRemainder(dividingBy: 360)
        if delta > 180 { delta -= 360 }
        if delta < -180 { delta += 360 }
        return delta
    }
}

/// Limits MapKit annotation configure work while Remote ID positions churn.
/// Structural changes (aircraft added or removed, focus, inset) apply immediately.
/// Ordinary position and status ticks coalesce to `minimumInterval`.
public enum AircraftMapAnnotationUpdatePolicy {
    public static let minimumInterval: TimeInterval = 0.25

    public static func shouldApply(
        now: TimeInterval,
        lastAppliedAt: TimeInterval?,
        structuralChange: Bool
    ) -> Bool {
        if structuralChange { return true }
        guard let lastAppliedAt else { return true }
        return now - lastAppliedAt >= minimumInterval
    }
}
