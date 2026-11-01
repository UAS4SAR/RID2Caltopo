import Testing
@testable import R2CCore

@Suite struct ClueIndexObservationTests {
    @Test func idleStorageAlwaysPublishes() {
        #expect(ClueIndexObservation.shouldPublish(previousCount: 6, newCount: 0, prepareInProgress: false))
        #expect(ClueIndexObservation.shouldPublish(previousCount: 0, newCount: 6, prepareInProgress: false))
    }

    @Test func prepareKeepsLastGoodWhenReadGoesEmptyOrPartial() {
        #expect(!ClueIndexObservation.shouldPublish(previousCount: 6, newCount: 0, prepareInProgress: true))
        #expect(!ClueIndexObservation.shouldPublish(previousCount: 6, newCount: 3, prepareInProgress: true))
    }

    @Test func prepareAllowsEqualOrRicherReads() {
        #expect(ClueIndexObservation.shouldPublish(previousCount: 6, newCount: 6, prepareInProgress: true))
        #expect(ClueIndexObservation.shouldPublish(previousCount: 6, newCount: 7, prepareInProgress: true))
    }
}
