import Foundation

public struct RidProximityDrone: Sendable, Equatable {
    public let remoteID: String
    public let mappedID: String
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public let sampleDate: Date
    public let distanceToOperatorMeters: Double?
    public let teamDrone: Bool
    public let localAlertEligible: Bool
    public let telemetry: RidProximityTelemetry

    public init(
        remoteID: String,
        mappedID: String,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double?,
        sampleDate: Date = Date(),
        distanceToOperatorMeters: Double? = nil,
        teamDrone: Bool,
        localAlertEligible: Bool,
        telemetry: RidProximityTelemetry = .init()
    ) {
        self.remoteID = remoteID
        self.mappedID = mappedID
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
        self.sampleDate = sampleDate
        self.distanceToOperatorMeters = distanceToOperatorMeters
        self.teamDrone = teamDrone
        self.localAlertEligible = localAlertEligible
        self.telemetry = telemetry
    }
}

public struct RidProximityAlertState: Sendable, Equatable, Identifiable {
    public var id: Int64 { alertInstanceID }
    public let alertInstanceID: Int64
    public let pairKey: String
    public let thresholdFeet: Double
    public let highSeverity: Bool
    public let nearestDroneMappedID: String
    public let farthestDroneMappedID: String
    public let highestDroneMappedID: String
    public let lowestDroneMappedID: String
    public let horizontalSeparationFeet: Double
    public let verticalSeparationFeet: Double
    public let currentHorizontalSeparationFeet: Double
    public let currentVerticalSeparationFeet: Double
    public let usesProjection: Bool
    public let verticalSeparationKnown: Bool
    public let firstLatitude: Double
    public let firstLongitude: Double
    public let secondLatitude: Double
    public let secondLongitude: Double
}

public struct RidProximityAlertOutput: Sendable, Equatable {
    public let activeAlert: RidProximityAlertState?
    public let suspendedAlert: RidProximityAlertState?
    public let canResume: Bool
    public let isSuspended: Bool
}

/// Stateful Android-parity alert policy. UI presentation, speech, and haptics
/// remain platform responsibilities.
public struct RidProximityAlertEngine: Sendable {
    private struct DroneSample: Sendable {
        let latitude: Double
        let longitude: Double
        let sampleDate: Date
        let horizontalAccuracyMeters: Double
    }

    private struct EvaluatedDrone: Sendable {
        let input: RidProximityDrone
        let effectiveLatitude: Double
        let effectiveLongitude: Double
        let effectiveAltitudeFeet: Double
        let ageSeconds: Double
        let horizontalUncertaintyFeet: Double
        let projectedUncertaintyFeet: Double
        let projectionSeconds: Double
    }

    private struct PairEvaluation: Sendable {
        let pairKey: String
        let first: EvaluatedDrone
        let second: EvaluatedDrone
        let horizontalFeet: Double
        let verticalFeet: Double
        let currentHorizontalFeet: Double
        let currentVerticalFeet: Double
        let usesProjection: Bool
        let altitudeSensitive: Bool
        let decisionHorizontalFeet: Double
        let decisionVerticalFeet: Double
        let shouldAlert: Bool
        let highSeverity: Bool
        let severityScore: Double

        func isInside(thresholdFeet: Double) -> Bool {
            decisionHorizontalFeet <= thresholdFeet && decisionVerticalFeet <= thresholdFeet
        }
    }

    private var sampleHistory: [String: [DroneSample]] = [:]
    private var latestPairs: [String: PairEvaluation] = [:]
    private var activeAlert: RidProximityAlertState?
    private var suspendedAlert: RidProximityAlertState?
    private var alertsSuspended = false
    private var clearEligibleSince: Date?
    private var nextAlertInstanceID: Int64 = 1
    private var alertAllAircraft = false

    public init() {}

    public mutating func update(
        drones: [RidProximityDrone],
        thresholdFeet: Double,
        enabled: Bool = false,
        alertAllAircraft: Bool = false,
        predictiveEnabled: Bool = true,
        now: Date = Date()
    ) -> RidProximityAlertOutput {
        if self.alertAllAircraft != alertAllAircraft {
            let wasSuspended = alertsSuspended
            reset()
            alertsSuspended = wasSuspended
            self.alertAllAircraft = alertAllAircraft
        }
        guard enabled, thresholdFeet.isFinite else {
            reset()
            return output
        }

        let thresholdFeet = max(50, thresholdFeet)
        // Track retention is longer than collision telemetry validity. Never expand
        // uncertainty indefinitely around an old position.
        let freshDrones = drones.filter { (0...RidProximityTelemetry.maximumPositionAgeSeconds).contains(now.timeIntervalSince($0.sampleDate)) }
        updateSampleHistory(drones: freshDrones)
        let evaluated = freshDrones.map { evaluateDrone($0, predictiveEnabled: predictiveEnabled, now: now) }
        let evaluations = evaluatePairs(drones: evaluated, thresholdFeet: thresholdFeet, predictiveEnabled: predictiveEnabled)
        latestPairs = Dictionary(uniqueKeysWithValues: evaluations.map { ($0.pairKey, $0) })
        let best = evaluations
            .filter(\.shouldAlert)
            .min {
                if $0.severityScore == $1.severityScore {
                    return hypot($0.horizontalFeet, $0.verticalFeet) < hypot($1.horizontalFeet, $1.verticalFeet)
                }
                return $0.severityScore < $1.severityScore
            }

        let current = activeAlert ?? suspendedAlert
        if let best {
            let instanceID: Int64
            if current?.pairKey == best.pairKey {
                instanceID = current?.alertInstanceID ?? nextAlertInstanceID
            } else {
                instanceID = nextAlertInstanceID
                nextAlertInstanceID += 1
            }
            let alert = makeAlert(best, thresholdFeet: thresholdFeet, instanceID: instanceID)
            if alertsSuspended {
                activeAlert = nil
                suspendedAlert = alert
            } else {
                activeAlert = alert
                suspendedAlert = nil
            }
            clearEligibleSince = nil
        } else if let current {
            let evaluation = latestPairs[current.pairKey]
            if evaluation?.isInside(thresholdFeet: thresholdFeet) != true {
                if clearEligibleSince == nil { clearEligibleSince = now }
                if now.timeIntervalSince(clearEligibleSince ?? now) >= 3 {
                    activeAlert = nil
                    suspendedAlert = nil
                    clearEligibleSince = nil
                }
            } else if let evaluation {
                let refreshed = makeAlert(
                    evaluation,
                    thresholdFeet: thresholdFeet,
                    instanceID: current.alertInstanceID
                )
                if alertsSuspended {
                    activeAlert = nil
                    suspendedAlert = refreshed
                } else {
                    activeAlert = refreshed
                    suspendedAlert = nil
                }
                clearEligibleSince = nil
            }
        } else {
            suspendedAlert = nil
        }

        return output
    }

    public mutating func suspend() -> RidProximityAlertOutput {
        alertsSuspended = true
        clearEligibleSince = nil
        suspendedAlert = activeAlert ?? suspendedAlert
        activeAlert = nil
        return output
    }

    public mutating func resume() -> RidProximityAlertOutput {
        alertsSuspended = false
        guard let suspendedAlert else { return output }
        let evaluation = latestPairs[suspendedAlert.pairKey]
        alertsSuspended = false
        if let evaluation, evaluation.isInside(thresholdFeet: suspendedAlert.thresholdFeet) {
            activeAlert = makeAlert(
                evaluation,
                thresholdFeet: suspendedAlert.thresholdFeet,
                instanceID: suspendedAlert.alertInstanceID
            )
            self.suspendedAlert = nil
        } else {
            activeAlert = nil
            self.suspendedAlert = nil
        }
        clearEligibleSince = nil
        return output
    }

    public mutating func reset() {
        sampleHistory.removeAll()
        latestPairs.removeAll()
        activeAlert = nil
        suspendedAlert = nil
        alertsSuspended = false
        clearEligibleSince = nil
    }

    private var output: RidProximityAlertOutput {
        let canResume = suspendedAlert.flatMap { alert in
            latestPairs[alert.pairKey]?.isInside(thresholdFeet: alert.thresholdFeet)
        } == true
        return RidProximityAlertOutput(
            activeAlert: activeAlert,
            suspendedAlert: suspendedAlert,
            canResume: canResume,
            isSuspended: alertsSuspended
        )
    }

    private func evaluatePairs(
        drones: [EvaluatedDrone],
        thresholdFeet: Double,
        predictiveEnabled: Bool
    ) -> [PairEvaluation] {
        var result: [PairEvaluation] = []
        for firstIndex in drones.indices {
            for secondIndex in drones.indices where secondIndex > firstIndex {
                let first = drones[firstIndex]
                let second = drones[secondIndex]
                guard first.input.remoteID != second.input.remoteID,
                      let currentRelative = RidGeometry.relativePosition(
                          fromLatitude: first.input.latitude,
                          longitude: first.input.longitude,
                          toLatitude: second.input.latitude,
                          longitude: second.input.longitude
                      ),
                      let effectiveRelative = RidGeometry.relativePosition(
                          fromLatitude: first.effectiveLatitude,
                          longitude: first.effectiveLongitude,
                          toLatitude: second.effectiveLatitude,
                          longitude: second.effectiveLongitude
                      )
                else { continue }

                let currentHorizontalFeet = currentRelative.distanceMeters * 3.28084
                let currentVerticalFeet = verticalSeparationFeet(
                    first.input.telemetry.absoluteAltitudeMeters,
                    second.input.telemetry.absoluteAltitudeMeters
                )
                let currentLowerBound = max(0, currentHorizontalFeet - first.horizontalUncertaintyFeet - second.horizontalUncertaintyFeet)
                let projectedHorizontalFeet = effectiveRelative.distanceMeters * 3.28084
                let projectedLowerBound = max(0, projectedHorizontalFeet - first.projectedUncertaintyFeet - second.projectedUncertaintyFeet)
                let usesProjection = false
                let horizontalFeet = usesProjection ? projectedHorizontalFeet : currentHorizontalFeet
                let decisionHorizontal = min(currentLowerBound, projectedLowerBound)
                let verticalFeet = currentVerticalFeet
                let altitudeSensitive = first.input.teamDrone && second.input.teamDrone
                    && first.input.telemetry.hasUsableAltitude && second.input.telemetry.hasUsableAltitude
                    && first.input.telemetry.altitudeReference == second.input.telemetry.altitudeReference
                    && first.ageSeconds <= RidProximityTelemetry.maximumAltitudeAgeSeconds
                    && second.ageSeconds <= RidProximityTelemetry.maximumAltitudeAgeSeconds
                let verticalUncertainty = ((first.input.telemetry.verticalAccuracyMeters ?? 0)
                    + (second.input.telemetry.verticalAccuracyMeters ?? 0)) * 3.28084
                let decisionVertical = altitudeSensitive ? max(0, verticalFeet - verticalUncertainty) : 0
                let pairKey = Self.pairKey(first.input.remoteID, second.input.remoteID)
                let inside = decisionHorizontal <= thresholdFeet && decisionVertical <= thresholdFeet
                let eligible = alertAllAircraft || ((first.input.teamDrone || second.input.teamDrone)
                    && (first.input.localAlertEligible || second.input.localAlertEligible))
                result.append(
                    PairEvaluation(
                        pairKey: pairKey,
                        first: first,
                        second: second,
                        horizontalFeet: horizontalFeet,
                        verticalFeet: verticalFeet,
                        currentHorizontalFeet: currentHorizontalFeet,
                        currentVerticalFeet: currentVerticalFeet,
                        usesProjection: usesProjection,
                        altitudeSensitive: altitudeSensitive,
                        decisionHorizontalFeet: decisionHorizontal,
                        decisionVerticalFeet: decisionVertical,
                        shouldAlert: inside && eligible,
                        highSeverity: decisionHorizontal < thresholdFeet * 0.75
                            || decisionVertical < thresholdFeet * 0.75,
                        severityScore: max(decisionHorizontal / thresholdFeet, decisionVertical / thresholdFeet)
                    )
                )
            }
        }
        return result
    }

    private func makeAlert(
        _ evaluation: PairEvaluation,
        thresholdFeet: Double,
        instanceID: Int64
    ) -> RidProximityAlertState {
        let orderedByDistance = [evaluation.first, evaluation.second].sorted {
            ($0.input.distanceToOperatorMeters ?? .greatestFiniteMagnitude, $0.input.mappedID)
                < ($1.input.distanceToOperatorMeters ?? .greatestFiniteMagnitude, $1.input.mappedID)
        }
        let orderedByAltitude = [evaluation.first, evaluation.second].sorted {
            $0.effectiveAltitudeFeet > $1.effectiveAltitudeFeet
        }
        return RidProximityAlertState(
            alertInstanceID: instanceID,
            pairKey: evaluation.pairKey,
            thresholdFeet: thresholdFeet,
            highSeverity: evaluation.highSeverity,
            nearestDroneMappedID: orderedByDistance[0].input.mappedID,
            farthestDroneMappedID: orderedByDistance[1].input.mappedID,
            highestDroneMappedID: orderedByAltitude[0].input.mappedID,
            lowestDroneMappedID: orderedByAltitude[1].input.mappedID,
            horizontalSeparationFeet: evaluation.horizontalFeet,
            verticalSeparationFeet: evaluation.verticalFeet,
            currentHorizontalSeparationFeet: evaluation.currentHorizontalFeet,
            currentVerticalSeparationFeet: evaluation.currentVerticalFeet,
            usesProjection: evaluation.usesProjection,
            verticalSeparationKnown: evaluation.altitudeSensitive,
            firstLatitude: evaluation.first.input.latitude,
            firstLongitude: evaluation.first.input.longitude,
            secondLatitude: evaluation.second.input.latitude,
            secondLongitude: evaluation.second.input.longitude
        )
    }

    private mutating func updateSampleHistory(drones: [RidProximityDrone]) {
        let activeIDs = Set(drones.map(\.remoteID))
        sampleHistory = sampleHistory.filter { activeIDs.contains($0.key) }
        for drone in drones {
            var history = sampleHistory[drone.remoteID, default: []]
            if history.last?.sampleDate != drone.sampleDate {
                history.append(
                    DroneSample(
                        latitude: drone.latitude,
                        longitude: drone.longitude,
                        sampleDate: drone.sampleDate,
                        horizontalAccuracyMeters: drone.telemetry.horizontalAccuracyMeters
                    )
                )
                if history.count > 2 { history.removeFirst(history.count - 2) }
                sampleHistory[drone.remoteID] = history
            }
        }
    }

    private func evaluateDrone(
        _ drone: RidProximityDrone,
        predictiveEnabled: Bool,
        now: Date
    ) -> EvaluatedDrone {
        let age = max(0, now.timeIntervalSince(drone.sampleDate))
        let reportedAccuracy = drone.telemetry.horizontalAccuracyMeters
        let accuracy = reportedAccuracy.isFinite && reportedAccuracy > 0
            ? reportedAccuracy : RidProximityTelemetry.unknownHorizontalMeters
        let uncertainty = accuracy * 3.28084
        return EvaluatedDrone(
            input: drone,
            effectiveLatitude: drone.latitude,
            effectiveLongitude: drone.longitude,
            effectiveAltitudeFeet: (drone.telemetry.hasUsableAltitude ? (drone.telemetry.absoluteAltitudeMeters ?? 0) : 0) * 3.28084,
            ageSeconds: age,
            horizontalUncertaintyFeet: uncertainty,
            projectedUncertaintyFeet: uncertainty,
            projectionSeconds: 0
        )
    }

    private func verticalSeparationFeet(_ first: Double?, _ second: Double?) -> Double {
        guard let first, let second, first.isFinite, second.isFinite else { return 0 }
        return abs(first - second) * 3.28084
    }

    private static func pairKey(_ first: String, _ second: String) -> String {
        first <= second ? "\(first)|\(second)" : "\(second)|\(first)"
    }
}
