import Foundation
@main struct Render {
    static func main() throws {
        let base = try Data(contentsOf: URL(fileURLWithPath: "apple/App/Resources/mediamtx.yml"))
        for restricted in [true, false] {
            let data = try MediaMTXRuntimeConfiguration.build(base: base, captureStreams: true, restrictNetworkAccess: restricted, recordingRoot: URL(fileURLWithPath: "/tmp/r2c-access-recordings"))
            try data.write(to: URL(fileURLWithPath: "app/build/test-media-access/apple-\(restricted ? "restricted" : "open").yml"))
        }
    }
}
