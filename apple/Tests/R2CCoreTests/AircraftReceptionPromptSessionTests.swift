import Foundation
import Testing
@testable import R2CCore

struct AircraftReceptionPromptSessionTests {
    @Test func firstSightingOpensOnceAndASecondPacketDoesNot() {
        let session = AircraftReceptionPromptSession()
        #expect(session.observe(aircraftID: "BIRD0001", unidentified: true, dispositionedThisSession: false, panelOpen: false) == .open)
        #expect(session.observe(aircraftID: "BIRD0001", unidentified: true, dispositionedThisSession: false, panelOpen: false) == .skip)
        #expect(session.observe(aircraftID: "  BIRD0001  ", unidentified: true, dispositionedThisSession: false, panelOpen: true) == .skip)
    }

    @Test func aNewIdOpensAgain() {
        let session = AircraftReceptionPromptSession()
        #expect(session.observe(aircraftID: "BIRD0001", unidentified: true, dispositionedThisSession: false, panelOpen: false) == .open)
        #expect(session.observe(aircraftID: "BIRD0002", unidentified: true, dispositionedThisSession: false, panelOpen: true) == .alreadyVisible)
        #expect(session.observe(aircraftID: "BIRD0002", unidentified: true, dispositionedThisSession: false, panelOpen: true) == .skip)
    }

    @Test func clearedSessionOpensAgain() {
        let session = AircraftReceptionPromptSession()
        #expect(session.observe(aircraftID: "BIRD0001", unidentified: true, dispositionedThisSession: false, panelOpen: false) == .open)
        session.reset()
        #expect(session.observe(aircraftID: "BIRD0001", unidentified: true, dispositionedThisSession: false, panelOpen: false) == .open)
    }

    @Test func anOpenPanelDoesNotStackAndStillRecordsTheAircraft() {
        let session = AircraftReceptionPromptSession()
        #expect(session.observe(aircraftID: "BIRD0001", unidentified: true, dispositionedThisSession: false, panelOpen: true) == .alreadyVisible)
        #expect(session.observe(aircraftID: "BIRD0001", unidentified: true, dispositionedThisSession: false, panelOpen: false) == .skip)
    }

    @Test func teamListAndDontPublishDoNotAutoOpen() {
        let session = AircraftReceptionPromptSession()
        #expect(!AircraftReceptionPromptSession.isUnidentified(remoteID: "TEAM0001", mappedID: "Mini4", organization: "NCSSAR"))
        #expect(AircraftReceptionPromptSession.isUnidentified(remoteID: "BIRD0001", mappedID: "BIRD0001", organization: ""))
        #expect(AircraftReceptionPromptSession.isUnidentified(remoteID: "BIRD0001", mappedID: "", organization: "  "))
        #expect(session.observe(aircraftID: "TEAM0001", unidentified: false, dispositionedThisSession: false, panelOpen: false) == .skip)
        #expect(session.observe(aircraftID: "TEAM0001", unidentified: true, dispositionedThisSession: false, panelOpen: false) == .open)
        #expect(session.observe(aircraftID: "NOPUB001", unidentified: true, dispositionedThisSession: true, panelOpen: false) == .skip)
        #expect(session.observe(aircraftID: "NOPUB001", unidentified: true, dispositionedThisSession: false, panelOpen: false) == .skip)
    }

    @Test func logTokenOmitsTheAircraftId() {
        let aircraftID = "1581F8HGX255S00A0FZT"
        let token = AircraftReceptionPromptSession.logToken(aircraftID)
        #expect(token.count == 8)
        #expect(token.allSatisfy { $0.isHexDigit })
        #expect(token != aircraftID)
        #expect(!token.contains(aircraftID))
        #expect(!aircraftID.contains(token))
        #expect(AircraftReceptionPromptSession.logToken("  \(aircraftID)  ") == token)
    }
}
