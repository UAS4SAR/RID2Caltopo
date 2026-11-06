import Foundation

/// Wording and HTTP retry policy for an AOL lidar file download. Mirrors Android SurfaceTransferRetry.
/// A server that never accepted a connection fails at once and names the host. HTTP 408, 429, and 5xx
/// wait for Retry-After (capped) and retry a bounded number of times. Failure text is host and status
/// only — never the file URL, which can carry a location query.
public enum OperationalSurfaceTransferFailure {
    /// Same identifying User-Agent the USGS catalog request sends.
    public static let usgsUserAgent = "RID2Caltopo/Apple (contact: kjt@uas4sar.com)"
    /// Three waits, then the fourth transient status is final. Matches OperationalCacheRetryPolicy.
    public static let maxTransientStatusRetries = 3

    /// Lidar file GET. Serial: the caller downloads one file, and retries of that file, before the next.
    public static func lidarFileRequest(url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        request.setValue(usgsUserAgent, forHTTPHeaderField: "User-Agent")
        return request
    }

    /// Seconds to wait before another GET, or nil when the status is permanent or the budget is spent.
    /// `attempt` is how many transient failures have already been seen (0 on the first 429).
    public static func transientStatusDelaySeconds(statusCode: Int, attempt: Int, retryAfterHeader: String?) -> TimeInterval? {
        guard statusCode == 408 || statusCode == 429 || (500...599).contains(statusCode) else { return nil }
        return OperationalCacheRetryPolicy.delaySeconds(attempt: attempt, retryAfterHeader: retryAfterHeader)
    }

    /// Operator-facing failure. Host and status only.
    public static func httpStatusMessage(statusCode: Int, host: String?) -> String {
        let name = (host?.isEmpty == false) ? host! : "unknown host"
        return "Lidar download HTTP \(statusCode) (\(name))"
    }

    public static func transientRetryMessage(file: Int, files: Int, retry: Int) -> String {
        "Retrying AOL lidar \(file)/\(files) after the server asked to wait (retry \(retry)/\(maxTransientStatusRetries))"
    }

    /// The operator-facing message when `error` means the lidar server was unreachable, otherwise nil.
    /// e.g. "USGS lidar server unreachable (rockyweb.usgs.gov, timed out)".
    public static func unreachableMessage(for error: Error, host: String?, bytesReceived: Int64) -> String? {
        guard bytesReceived == 0, let urlError = error as? URLError else { return nil }
        let detail: String?
        switch urlError.code {
        case .cannotFindHost, .dnsLookupFailed: detail = "no network or name lookup failed"
        case .notConnectedToInternet, .dataNotAllowed, .internationalRoamingOff: detail = "no network"
        case .timedOut: detail = "timed out"
        case .cannotConnectToHost: detail = nil
        default: return nil
        }
        let name = (host?.isEmpty == false ? host! : "unknown host")
        return "USGS lidar server unreachable (\(name)" + (detail.map { ", \($0)" } ?? "") + ")"
    }
}
