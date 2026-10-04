import Testing
import Foundation
@testable import R2CCore

// Same words as Android SurfaceTransferRetryTest.
@Test func unreachableLidarServerIsNamedWithTheReason() {
    let f = OperationalSurfaceTransferFailure.self
    #expect(f.unreachableMessage(for: URLError(.timedOut), host: "rockyweb.usgs.gov", bytesReceived: 0)
        == "USGS lidar server unreachable (rockyweb.usgs.gov, timed out)")
    #expect(f.unreachableMessage(for: URLError(.cannotFindHost), host: "rockyweb.usgs.gov", bytesReceived: 0)
        == "USGS lidar server unreachable (rockyweb.usgs.gov, no network or name lookup failed)")
    #expect(f.unreachableMessage(for: URLError(.notConnectedToInternet), host: "rockyweb.usgs.gov", bytesReceived: 0)
        == "USGS lidar server unreachable (rockyweb.usgs.gov, no network)")
    #expect(f.unreachableMessage(for: URLError(.cannotConnectToHost), host: "rockyweb.usgs.gov", bytesReceived: 0)
        == "USGS lidar server unreachable (rockyweb.usgs.gov)")
    #expect(f.unreachableMessage(for: URLError(.cannotConnectToHost), host: nil, bytesReceived: 0)
        == "USGS lidar server unreachable (unknown host)")
}

@Test func midTransferBreaksAndOtherErrorsAreNotCalledUnreachable() {
    let f = OperationalSurfaceTransferFailure.self
    #expect(f.unreachableMessage(for: URLError(.timedOut), host: "rockyweb.usgs.gov", bytesReceived: 4_000_000) == nil)
    #expect(f.unreachableMessage(for: URLError(.networkConnectionLost), host: "rockyweb.usgs.gov", bytesReceived: 0) == nil)
    #expect(f.unreachableMessage(for: URLError(.secureConnectionFailed), host: "rockyweb.usgs.gov", bytesReceived: 0) == nil)
    #expect(f.unreachableMessage(for: OperationalSurfacePreparationError.invalid("Lidar download HTTP 503"), host: "rockyweb.usgs.gov", bytesReceived: 0) == nil)
    // The Download Map failure line passes the message through unchanged.
    let message = f.unreachableMessage(for: URLError(.timedOut), host: "rockyweb.usgs.gov", bytesReceived: 0)!
    #expect(OperationalOfflineProgressText.describeFailure(OperationalSurfacePreparationError.invalid(message), service: "USGS") == message)
}
