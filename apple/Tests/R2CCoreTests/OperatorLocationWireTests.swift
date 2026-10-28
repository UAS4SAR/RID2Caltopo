import Foundation
import Testing
@testable import R2CCore

private let consentWireClient = TrackerCoordinationClient(mapID: "MAP", zoneID: "zone", name: "Field", appVersion: "test", appVersionCode: 1)

@Test func revokedOperatorLocationExplicitlyClearsHelloAndHeartbeatCoordinates() throws {
    let position = TrackerCoordinationPosition(latitude: nil, longitude: nil, caltopoRTTMilliseconds: 750)
    for data in [try TrackerCoordinationWire.hello(client: consentWireClient, position: position),
                 try TrackerCoordinationWire.heartbeat(client: consentWireClient, position: position, sequence: 9)] {
        let payload = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(payload["lat"] is NSNull)
        #expect(payload["lng"] is NSNull)
        #expect(payload["caltopoRttMs"] as? Int == 750)
        #expect(payload["zoneId"] as? String == "zone")
    }
}

@Test func authorizedOriginIsARealCoordinateAndNotConsentWithdrawal() throws {
    let data = try TrackerCoordinationWire.heartbeat(client: consentWireClient,
        position: .init(latitude: 0, longitude: 0), sequence: 10)
    let payload = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    #expect(payload["lat"] as? Double == 0)
    #expect(payload["lng"] as? Double == 0)
}

@Test func invalidOrPartialOperatorFixCannotLeakOneCoordinate() throws {
    for position in [TrackerCoordinationPosition(latitude: nil, longitude: -104),
                     .init(latitude: .nan, longitude: -104),
                     .init(latitude: 39, longitude: 181)] {
        let data = try TrackerCoordinationWire.hello(client: consentWireClient, position: position)
        let payload = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(payload["lat"] is NSNull)
        #expect(payload["lng"] is NSNull)
    }
}
