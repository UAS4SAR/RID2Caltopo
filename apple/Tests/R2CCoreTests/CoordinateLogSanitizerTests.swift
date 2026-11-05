import Foundation
import Testing
@testable import R2CCore

@Suite
struct CoordinateLogSanitizerTests {
    @Test
    func redactsLabeledCoordinatesAndDegreePairs() {
        let line = CoordinateLogSanitizer.redact(
            "Clue projection lat=39.153017 lon=-121.133046 alt=100.0 pair=39.15301, -121.13304"
        )
        #expect(!line.contains("39.153"))
        #expect(!line.contains("-121.133"))
        #expect(line.contains("lat=[redacted]"))
        #expect(line.contains("alt=100.0"))
    }

    @Test
    func sourceLogStatementsDoNotFormatPreciseCoordinates() throws {
        let root = repoRoot()
        let pattern = try NSRegularExpression(
            pattern: #"(lat|lng|lon|latitude|longitude|deltaLat|deltaLon)\s*=\s*%\."#
        )
        var hits: [String] = []
        let roots = [
            root.appendingPathComponent("app/src/main"),
            root.appendingPathComponent("apple"),
        ]
        for dir in roots where FileManager.default.fileExists(atPath: dir.path) {
            guard let enumerator = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: nil) else { continue }
            for case let file as URL in enumerator {
                let ext = file.pathExtension
                guard ext == "kt" || ext == "java" || ext == "swift" else { continue }
                if file.path.contains("/Tests/") || file.path.contains("/src/test/") { continue }
                let text = (try? String(contentsOf: file, encoding: .utf8)) ?? ""
                let range = NSRange(text.startIndex..<text.endIndex, in: text)
                if pattern.firstMatch(in: text, range: range) != nil {
                    hits.append(file.path)
                }
            }
        }
        #expect(hits.isEmpty, "precise coordinate formats: \(hits.joined(separator: "\n"))")
    }

    private func repoRoot() -> URL {
        var dir = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        for _ in 0..<8 {
            let app = dir.appendingPathComponent("app/src/main")
            let apple = dir.appendingPathComponent("apple")
            if FileManager.default.fileExists(atPath: app.path),
               FileManager.default.fileExists(atPath: apple.path) {
                return dir
            }
            dir.deleteLastPathComponent()
        }
        return URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
    }

    @Test
    func clearJournalOnlyAfterConfirmedDelete() {
        #expect(MarkerCleanupRetry.clearJournal(httpStatus: 204, verifiedSuccess: true))
        #expect(MarkerCleanupRetry.clearJournal(httpStatus: 404, verifiedSuccess: false))
        #expect(!MarkerCleanupRetry.clearJournal(httpStatus: 200, verifiedSuccess: false))
        #expect(!MarkerCleanupRetry.clearJournal(httpStatus: 400, verifiedSuccess: false))
        #expect(MarkerCleanupRetry.delayMs(attempts: 1) == 30_000)
        #expect(MarkerCleanupRetry.idPrefix("833a8140-2a8b") == "833a8140")
    }

    @Test
    func resolveUsesServerShapeAndSkipsFolders() {
        let shape = DeviceMarkerDeletePlan.Feature(id: "serverShape", className: "Shape", guid: "marker-guid-test")
        let shapeTarget = DeviceMarkerDeletePlan.resolve(
            markerID: "833a8140-2a8b-3b23-8876-c3af62524ef0",
            deviceGUID: "marker-guid-test",
            features: [shape]
        )
        #expect(shapeTarget.wire == "Shape/serverShape")
        let folder = DeviceMarkerDeletePlan.Feature(id: "folder1", className: "Folder", guid: "marker-guid-test")
        let marker = DeviceMarkerDeletePlan.Feature(id: "srvMarker", className: "Marker", guid: "marker-guid-test")
        let markerTarget = DeviceMarkerDeletePlan.resolve(
            markerID: "client-uuid", deviceGUID: "marker-guid-test", features: [folder, marker]
        )
        #expect(markerTarget.wire == "srvMarker")
    }
}
