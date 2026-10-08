import Testing
@testable import R2CCore

@Test func anomalyHeadroomDistinguishesUnknownAvailableLimitedAndHot() {
    func assess(_ cpu: Double?, _ thermal: Int = 0, _ live: Int = 1, _ software: Int = 0, _ anomaly: Int = 0) -> String {
        OperationalAnomalyHeadroom.assess(cpuFraction: cpu, thermalPressure: thermal, liveStreams: live,
            softwareDecodedStreams: software, anomalyEnabledStreams: anomaly)
    }
    #expect(assess(nil) == "unknown")
    #expect(assess(0.1, 0, 0) == "unknown")
    #expect(assess(0.1) == "ok")
    #expect(assess(0.65) == "limit")
    #expect(assess(0.1, 1) == "limit")
    #expect(assess(0.1, 0, 1, 0, 1) == "limit")
    #expect(assess(0.1, 0, 2, 2) == "limit")
    #expect(assess(0.85) == "hot")
    #expect(assess(nil, 2) == "hot")
}
