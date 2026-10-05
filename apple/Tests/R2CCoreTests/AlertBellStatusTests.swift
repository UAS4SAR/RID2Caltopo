import XCTest
@testable import R2CCore

final class AlertBellStatusTests: XCTestCase {
    func testAltitudeEightyPercentSemantics() {
        XCTAssertEqual(AlertBellThresholdPolicy.altitudeColor(aglFeet: 159), .white)
        XCTAssertEqual(AlertBellThresholdPolicy.altitudeColor(aglFeet: 160), .orange)
        XCTAssertEqual(AlertBellThresholdPolicy.altitudeColor(aglFeet: 199), .orange)
        XCTAssertEqual(AlertBellThresholdPolicy.altitudeColor(aglFeet: 200), .red)
        XCTAssertEqual(AlertBellThresholdPolicy.altitudeColor(aglFeet: nil), .white)
    }

    func testDistanceEightyPercentSemantics() {
        XCTAssertEqual(AlertBellThresholdPolicy.distanceColor(rangeFeet: 4223), .white)
        XCTAssertEqual(AlertBellThresholdPolicy.distanceColor(rangeFeet: 4224), .orange)
        XCTAssertEqual(AlertBellThresholdPolicy.distanceColor(rangeFeet: 5279), .orange)
        XCTAssertEqual(AlertBellThresholdPolicy.distanceColor(rangeFeet: 5280), .red)
    }

    func testProximityOnePointTwoFiveApproach() {
        let threshold = 100.0
        XCTAssertEqual(
            AlertBellThresholdPolicy.proximityColor(
                separationFeet: 126, thresholdFeet: threshold, isActivelyAlerting: false
            ),
            .white
        )
        XCTAssertEqual(
            AlertBellThresholdPolicy.proximityColor(
                separationFeet: 125, thresholdFeet: threshold, isActivelyAlerting: false
            ),
            .orange
        )
        XCTAssertEqual(
            AlertBellThresholdPolicy.proximityColor(
                separationFeet: 100, thresholdFeet: threshold, isActivelyAlerting: false
            ),
            .red
        )
        XCTAssertEqual(
            AlertBellThresholdPolicy.proximityColor(
                separationFeet: 200, thresholdFeet: threshold, isActivelyAlerting: true
            ),
            .red
        )
    }

    func testWifiApproachCeiling() {
        XCTAssertEqual(AlertBellThresholdPolicy.wifiColor(signalPercent: 80), .white)
        XCTAssertEqual(AlertBellThresholdPolicy.wifiColor(signalPercent: 74), .orange)
        XCTAssertEqual(AlertBellThresholdPolicy.wifiColor(signalPercent: 60), .orange)
        XCTAssertEqual(AlertBellThresholdPolicy.wifiColor(signalPercent: 59), .red)
        XCTAssertEqual(AlertBellThresholdPolicy.wifiColor(signalPercent: nil), .white)
    }

    func testBridgeApproach() {
        XCTAssertEqual(
            AlertBellThresholdPolicy.bridgeColor(secondsSinceLastPing: 20, monitoringActive: true),
            .white
        )
        XCTAssertEqual(
            AlertBellThresholdPolicy.bridgeColor(secondsSinceLastPing: 25.6, monitoringActive: true),
            .orange
        )
        XCTAssertEqual(
            AlertBellThresholdPolicy.bridgeColor(secondsSinceLastPing: 32, monitoringActive: true),
            .red
        )
        XCTAssertEqual(
            AlertBellThresholdPolicy.bridgeColor(secondsSinceLastPing: 100, monitoringActive: false),
            .white
        )
        XCTAssertEqual(
            AlertBellThresholdPolicy.bridgeColor(secondsSinceLastPing: nil, monitoringActive: true),
            .red
        )
    }

    func testSessionMuteAndLatch() {
        var state = AlertBellSessionState()
        XCTAssertFalse(state.showBell)
        XCTAssertFalse(state.isMuted(.altitude))

        state.setMuted(.altitude, muted: true)
        XCTAssertTrue(state.isMuted(.altitude))
        state.setMuted(.altitude, muted: false)
        XCTAssertFalse(state.isMuted(.altitude))

        state.updateColors([.altitude: .orange, .proximity: .white])
        XCTAssertFalse(state.showBell)
        XCTAssertEqual(state.aggregateColor, .orange)

        state.updateColors([.altitude: .orange, .proximity: .red])
        XCTAssertTrue(state.showBell)
        XCTAssertEqual(state.aggregateColor, .red)

        state.updateColors([.altitude: .white, .proximity: .white])
        XCTAssertTrue(state.showBell, "Bell stays visible for the session after first alarm")
        XCTAssertEqual(state.aggregateColor, .white)
    }

    func testMetricsColors() {
        let metrics = AlertBellMetrics(
            proximitySeparationFeet: 110,
            proximityThresholdFeet: 100,
            proximityActivelyAlerting: false,
            maxAglFeet: 170,
            maxRangeFeet: 5000,
            droneSignalLossActive: true,
            bridgeSecondsSinceLastPing: 10,
            bridgeMonitoringActive: true,
            wifiSignalPercent: 70,
            videoRequestPending: true
        )
        let colors = metrics.colors()
        XCTAssertEqual(colors[.proximity], .orange)
        XCTAssertEqual(colors[.altitude], .orange)
        XCTAssertEqual(colors[.distance], .orange)
        XCTAssertEqual(colors[.droneSignalLoss], .red)
        XCTAssertEqual(colors[.bridgeSignalLoss], .white)
        XCTAssertEqual(colors[.wifiStrength], .orange)
        XCTAssertEqual(colors[.videoRequest], .red)
    }
}
