import Foundation

/// Stateful parser for the verified MediaMTX fork's lifecycle log contract.
/// It accepts either plain lines or MediaMTX structured JSONL log envelopes.
public struct MediaMTXLogEventParser: Sendable {
    private var connectionPaths: [String: String] = [:]
    private var pathConnections: [String: String] = [:]
    private var intentionalCloseConnections: Set<String> = []

    public init() {}

    public mutating func parse(line rawLine: String) -> MediaServerEvent? {
        let line = unwrapStructuredLog(rawLine).trimmingCharacters(in: .whitespacesAndNewlines)

        if let captures = captures(#"MediaMTX (v[0-9]+\.[0-9]+\.[0-9]+)"#, in: line) {
            return .serverStarted(version: captures[0])
        }

        if let captures = captures(
            #"\[path ([^\]]+)]\s+\[recorder]\s+record file complete path=(.+) durationMs=(\d+)"#,
            in: line
        ) {
            return .recordFileCompleted(
                path: captures[0],
                filePath: captures[1],
                durationMilliseconds: Int(captures[2]) ?? 0
            )
        }

        if let captures = captures(#"\[path ([^\]]+)]\s+\[recorder]\s+(.+)"#, in: line) {
            let message = captures[1].trimmingCharacters(in: .whitespaces)
            if message == "recording stopped" || message.hasPrefix("no supported tracks found") {
                return .recorderStopped(path: captures[0])
            }
            if message.hasPrefix("recording ") { return .recorderStarted(path: captures[0]) }
            return nil
        }

        if let captures = captures(#"\[path ([^\]]+)]\s+closing existing publisher"#, in: line) {
            return .streamPublisherHandoff(
                path: captures[0],
                publisherConnectionID: pathConnections[captures[0]]
            )
        }

        if let captures = captures(
            #"\[RTMP]\s+\[conn ([^\]]+)]\s+is publishing to path '([^']+)'"#,
            in: line
        ) {
            let connection = captures[0]
            let path = captures[1]
            let duplicate = connectionPaths[connection] == path && pathConnections[path] == connection
            connectionPaths[connection] = path
            pathConnections[path] = connection
            intentionalCloseConnections.remove(connection)
            return duplicate ? nil : .streamStarted(path: path, publisherConnectionID: connection)
        }

        if let captures = captures(
            #"\[RTMP]\s+\[conn ([^\]]+)]\s+RTMP control:\s+received command '(FCUnpublish|deleteStream)'\s+\(id=\d+\)\s+during publish on path '([^']+)'"#,
            in: line
        ) {
            let connection = captures[0]
            let path = captures[2]
            if connectionPaths[connection] == path || pathConnections[path] == connection {
                intentionalCloseConnections.insert(connection)
            }
            return nil
        }

        if let captures = captures(#"\[RTMP]\s+\[conn ([^\]]+)]\s+closed:\s*(.+)"#, in: line) {
            let connection = captures[0]
            let reason = captures[1].trimmingCharacters(in: .whitespaces)
            guard let path = connectionPaths.removeValue(forKey: connection) else { return nil }
            if pathConnections[path] == connection {
                pathConnections.removeValue(forKey: path)
            }

            let intentional = intentionalCloseConnections.remove(connection) != nil
            if intentional || reason.localizedCaseInsensitiveContains("connection reset by peer") {
                return .streamStopped(path: path, publisherConnectionID: connection)
            }
            return .streamError(
                path: path,
                publisherConnectionID: connection,
                detail: normalizedCloseReason(reason)
            )
        }

        if let captures = captures(#"\[HLS]\s+\[muxer ([^\]]+)]\s+created"#, in: line) {
            return .hlsStreamStarted(path: captures[0])
        }

        if let captures = captures(
            #"(?:no one is publishing to path|no stream is available on path) '([^']+)'"#,
            in: line
        ) {
            let path = captures[0]
            guard let connection = pathConnections.removeValue(forKey: path) else { return nil }
            connectionPaths.removeValue(forKey: connection)
            return .streamStopped(path: path, publisherConnectionID: connection)
        }

        return nil
    }

    private func unwrapStructuredLog(_ line: String) -> String {
        guard let data = line.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let message = object["message"] as? String
        else {
            return line
        }
        return message
    }

    private func normalizedCloseReason(_ reason: String) -> String {
        if reason.localizedCaseInsensitiveContains("extended chunk stream IDs are not supported") {
            return "RTMP closed: publisher uses extended chunk stream IDs unsupported by current MediaMTX"
        }
        if reason.localizedCaseInsensitiveContains("unexpected EOF") {
            return "RTMP closed: publisher disconnected unexpectedly"
        }
        return "RTMP closed: \(reason)"
    }

    private func captures(_ pattern: String, in line: String) -> [String]? {
        guard let expression = try? NSRegularExpression(pattern: pattern),
              let match = expression.firstMatch(
                  in: line,
                  range: NSRange(line.startIndex..., in: line)
              )
        else {
            return nil
        }

        return (1 ..< match.numberOfRanges).compactMap { index in
            let range = match.range(at: index)
            guard range.location != NSNotFound, let swiftRange = Range(range, in: line) else { return nil }
            return String(line[swiftRange])
        }
    }
}
