import Foundation

public enum MediaMTXRuntimeConfiguration {
    public static func build(
        base: Data,
        captureStreams: Bool,
        restrictNetworkAccess: Bool = true,
        recordingRoot: URL
    ) throws -> Data {
        guard let text = String(data: base, encoding: .utf8) else {
            throw CocoaError(.fileReadInapplicableStringEncoding)
        }

        var settings = ["  record: \(captureStreams ? "yes" : "no")"]
        if captureStreams {
            let escapedPath = recordingRoot.path.replacingOccurrences(of: "'", with: "''")
            settings += [
                "  recordPath: '\(escapedPath)/%Y-%m-%d/%path/%path_%Y-%m-%d_%H-%M-%S-%f'",
                "  recordFormat: fmp4",
                "  recordDeleteAfter: 0s",
            ]
        }

        var lines = withNetworkAccess(text, restricted: restrictNetworkAccess)
            .trimmingCharacters(in: .newlines).components(separatedBy: .newlines)
        if let pathDefaults = lines.firstIndex(where: {
            $0.trimmingCharacters(in: .whitespaces) == "pathDefaults:"
        }) {
            lines.insert(contentsOf: settings, at: pathDefaults + 1)
        } else {
            lines += ["pathDefaults:"] + settings
        }
        return Data((lines.joined(separator: "\n") + "\n").utf8)
    }

    /// Keep controller publishing available while separating network read permissions.
    static func withNetworkAccess(_ base: String, restricted: Bool) -> String {
        let host = restricted ? "127.0.0.1" : ""
        var users = """

          - user: any
            ips: []
            permissions:
              - action: publish
        """
        if !restricted { users += "\n      - action: read\n      - action: playback" }
        users += """

          - user: any
            ips: [127.0.0.1, '::1']
            permissions:
              - action: read
              - action: playback
              - action: api
              - action: metrics
              - action: pprof
        """
        let settings: [(String, String)] = [
            ("rtmpAddress", ":1935"),
            ("rtspAddress", "\(host):8554"),
            ("rtspTransports", restricted ? "[tcp]" : "[udp, multicast, tcp]"),
            ("hlsAddress", "\(host):8888"),
            ("webrtc", restricted ? "no" : "yes"),
            ("webrtcAddress", "\(host):8889"),
            ("webrtcLocalUDPAddress", "\(host):8189"),
            ("webrtcLocalTCPAddress", "\(host):8189"),
            ("webrtcICEServers2", "[]"),
            ("apiAddress", "127.0.0.1:9997"),
            ("metricsAddress", "127.0.0.1:9998"),
            ("pprofAddress", "127.0.0.1:9999"),
            ("playback", "no"),
            ("srt", "no"),
            ("authMethod", "internal"),
            ("authInternalUsers", users),
        ]
        let keys = Set(settings.map { $0.0 })
        var replacing = false
        let lines = base.components(separatedBy: .newlines).filter { line in
            if let first = line.first, !first.isWhitespace, first != "#" {
                replacing = keys.contains(String(line.prefix { $0 != ":" }))
            }
            return !replacing
        }
        return lines.joined(separator: "\n").trimmingCharacters(in: .newlines) + "\n" +
            settings.map { "\($0.0): \($0.1)" }.joined(separator: "\n") + "\n"
    }

}
