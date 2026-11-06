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

@Test func lidarFileRequestIdentifiesTheApp() {
    let url = URL(string: "https://rockyweb.usgs.gov/vdelivery/file.laz?bbox=-121,39,-120,40")!
    let request = OperationalSurfaceTransferFailure.lidarFileRequest(url: url)
    #expect(request.value(forHTTPHeaderField: "User-Agent") == "RID2Caltopo/Apple (contact: kjt@uas4sar.com)")
    #expect(request.value(forHTTPHeaderField: "Accept-Encoding") == "identity")
    #expect(request.url == url)
}

@Test func transientLidarStatusUsesRetryAfterThenACappedBackoff() {
    let f = OperationalSurfaceTransferFailure.self
    #expect(f.transientStatusDelaySeconds(statusCode: 429, attempt: 0, retryAfterHeader: "120") == 30)
    #expect(f.transientStatusDelaySeconds(statusCode: 429, attempt: 0, retryAfterHeader: "0") == 0)
    #expect(f.transientStatusDelaySeconds(statusCode: 429, attempt: 0, retryAfterHeader: " 2 ") == 2)
    #expect(f.transientStatusDelaySeconds(statusCode: 429, attempt: 0, retryAfterHeader: nil) == 0.5)
    #expect(f.transientStatusDelaySeconds(statusCode: 503, attempt: 1, retryAfterHeader: nil) == 1)
    #expect(f.transientStatusDelaySeconds(statusCode: 408, attempt: 2, retryAfterHeader: nil) == 2)
    #expect(f.transientStatusDelaySeconds(statusCode: 500, attempt: 0, retryAfterHeader: "Wed, 21 Oct 2015 07:28:00 GMT") == 0.5)
    #expect(f.transientStatusDelaySeconds(statusCode: 429, attempt: 3, retryAfterHeader: "1") == nil)
    #expect(f.transientStatusDelaySeconds(statusCode: 599, attempt: 0, retryAfterHeader: "1") == 1)
    #expect(f.transientStatusDelaySeconds(statusCode: 404, attempt: 0, retryAfterHeader: "1") == nil)
    #expect(f.transientStatusDelaySeconds(statusCode: 403, attempt: 0, retryAfterHeader: nil) == nil)
    #expect(f.transientStatusDelaySeconds(statusCode: 200, attempt: 0, retryAfterHeader: nil) == nil)
    let message = f.httpStatusMessage(statusCode: 429, host: "rockyweb.usgs.gov")
    #expect(message == "Lidar download HTTP 429 (rockyweb.usgs.gov)")
    #expect(!message.contains("?"))
    #expect(!message.contains("bbox"))
    #expect(!message.contains("file.laz"))
    #expect(f.httpStatusMessage(statusCode: 503, host: nil) == "Lidar download HTTP 503 (unknown host)")
    #expect(f.httpStatusMessage(statusCode: 503, host: "") == "Lidar download HTTP 503 (unknown host)")
    #expect(f.transientRetryMessage(file: 1, files: 4, retry: 1) == "Retrying AOL lidar 1/4 after the server asked to wait (retry 1/3)")
    #expect(OperationalOfflineProgressText.describeFailure(OperationalSurfacePreparationError.invalid(message), service: "USGS") == "USGS returned HTTP 429")
}
