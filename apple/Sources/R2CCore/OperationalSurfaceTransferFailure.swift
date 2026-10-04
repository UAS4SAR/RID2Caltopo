import Foundation

/// Wording for an AOL lidar download that could not reach the server. Mirrors Android SurfaceTransferRetry
/// (unreachableMessage): a server that never accepted a connection fails at once and names the host; once
/// lidar bytes have arrived the failure is an interrupted transfer and keeps its ordinary description.
public enum OperationalSurfaceTransferFailure {
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
