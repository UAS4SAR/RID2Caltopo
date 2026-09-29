import Foundation
import Testing
@testable import R2CCore

struct AirspaceBackoffTests {
    @Test func rateLimitsIncreaseDelayAndSuccessResets() {
        var retry = AirspaceRetryPolicy()
        let now = Date(timeIntervalSince1970: 1_000)
        for expected in [2.0, 4, 8, 16, 32, 32, 32] {
            #expect(retry.failed(rateLimited: true, retryAfter: nil, now: now) == expected)
            #expect(!retry.permits(now.addingTimeInterval(expected - 1)))
            #expect(retry.permits(now.addingTimeInterval(expected)))
        }
        retry.succeeded()
        #expect(retry.permits(now))
        #expect(retry.failed(rateLimited: false, retryAfter: nil, now: now) == 2)
    }
    @Test func serverRetryAfterIsMinimumEvenBeyondLocalCap() {
        let now = Date(timeIntervalSince1970: 0)
        #expect(AirspaceRetryPolicy.serverDelay("Thu, 01 Jan 1970 00:03:00 GMT", now: now) == 180)
        #expect(AirspaceRetryPolicy.serverDelay("invalid", now: now) == nil)
        var retry = AirspaceRetryPolicy()
        #expect(retry.failed(rateLimited: true, retryAfter: "3600", now: now) == 3600)
    }
    @Test func arcgisErrorBodyIsNotAnEmptySuccess() throws {
        for payload in [#"{"error":{"code":429,"message":"Throttled"}}"#,
                        #"{"error":{"code":400,"message":"Unable to perform query. Too many requests."}}"#] {
            do {
                _ = try OperationalFacilityMap.parse(Data(payload.utf8))
                Issue.record("Expected service failure")
            } catch let failure as AirspaceServiceFailure {
                #expect(failure.rateLimited)
            }
        }
    }
}
