import Foundation

public enum MediaMTXRuntimeConfiguration {
    public static func build(
        base: Data,
        captureStreams: Bool,
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

        var lines = text.trimmingCharacters(in: .newlines).components(separatedBy: .newlines)
        if let pathDefaults = lines.firstIndex(where: {
            $0.trimmingCharacters(in: .whitespaces) == "pathDefaults:"
        }) {
            lines.insert(contentsOf: settings, at: pathDefaults + 1)
        } else {
            lines += ["pathDefaults:"] + settings
        }
        return Data((lines.joined(separator: "\n") + "\n").utf8)
    }
}
