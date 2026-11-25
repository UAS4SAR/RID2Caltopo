import Foundation

/// Session log size limit. Android's log stopped at 10 MB (Nov 24 2026 field review lost the
/// rest of a search evening); both platforms now continue in a new Log_ file at the limit.
/// Same rule as Android's SessionLogRollover.
public enum SessionLogRollover {
    public static let maxBytes = 10_000_000

    public static func shouldRollOver(bytesWritten: Int, incomingBytes: Int, maxBytes: Int = maxBytes, fileAttached: Bool) -> Bool {
        fileAttached && bytesWritten > 0 && bytesWritten + incomingBytes > maxBytes
    }
}
