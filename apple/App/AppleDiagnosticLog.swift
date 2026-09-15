import Foundation
import R2CCore
import UIKit

struct AppleLogDay: Identifiable, Sendable, Equatable {
    let name: String
    let logCount: Int
    let isToday: Bool

    var id: String { name }
}

actor AppleDiagnosticLogStore {
    static let shared = AppleDiagnosticLogStore()

    private var handle: FileHandle?
    private var currentURL: URL?
    private var rootURL: URL?
    private var trackRootURL: URL?
    private var pendingLines: [String] = []

    func start(metadata: String) throws -> URL {
        if let currentURL { return currentURL }
        guard let documents = FileManager.default.urls(
            for: .documentDirectory,
            in: .userDomainMask
        ).first else {
            throw CocoaError(.fileNoSuchFile)
        }
        let appRoot = documents
            .appendingPathComponent("RID2Caltopo", isDirectory: true)
        let root = appRoot
            .appendingPathComponent("FlightStorage", isDirectory: true)
        let trackRoot = root
        let day = Self.dayName(for: Date())
        let directory = root.appendingPathComponent(day, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent(
            "Log_\(OperationalDiagnosticLogFormat.filenameTimestamp(Date())).txt"
        )
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let handle = try FileHandle(forWritingTo: destination)
        self.rootURL = root
        self.trackRootURL = trackRoot
        self.currentURL = destination
        AppleFlightStorage.protect(day, owner: "diagnostic-log")
        self.handle = handle

        try append("########################################################################\n")
        try append(metadata)
        if !metadata.hasSuffix("\n") { try append("\n") }
        try append("# Writing logs to: \(destination.path)\n")
        try append("########################################################################\n")
        for line in pendingLines { try append(line) }
        pendingLines.removeAll()
        return destination
    }

    func write(
        level: String,
        processAndThread: String,
        category: String,
        message: String,
        at date: Date = Date()
    ) {
        let line = OperationalDiagnosticLogFormat.line(
            level: level,
            processAndThread: processAndThread,
            category: category,
            message: message,
            at: date
        )
        guard handle != nil else {
            pendingLines.append(line)
            if pendingLines.count > 256 { pendingLines.removeFirst() }
            return
        }
        try? append(line)
    }

    func availableDays() throws -> [AppleLogDay] {
        guard let rootURL else { return [] }
        let urls = try FileManager.default.contentsOfDirectory(
            at: rootURL,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )
        let today = Self.dayName(for: Date())
        return try urls.compactMap { directory in
            guard try directory.resourceValues(forKeys: [.isDirectoryKey]).isDirectory == true else {
                return nil
            }
            let logs = try FileManager.default.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]
            ).filter { $0.pathExtension.lowercased() == "txt" }
            guard !logs.isEmpty else { return nil }
            return AppleLogDay(name: directory.lastPathComponent, logCount: logs.count, isToday: directory.lastPathComponent == today)
        }.sorted { $0.name > $1.name }
    }

    func prepareBundle(
        selectedDays: Set<String>,
        includeTracks: Bool,
        manifest: String
    ) throws -> URL {
        guard let rootURL, !selectedDays.isEmpty else { throw CocoaError(.fileNoSuchFile) }
        try handle?.synchronize()
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("R2C_Logs_\(Self.dayName(for: Date())).zip")
        var entries = [
            OperationalZipArchive.Entry(
                path: "diagnostic_manifest.txt",
                data: Data((manifest + "\n").utf8)
            ),
        ]
        for day in selectedDays.sorted() {
            let directory = rootURL.appendingPathComponent(day, isDirectory: true)
            let logs = try FileManager.default.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]
            ).filter { $0.pathExtension.lowercased() == "txt" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
            for log in logs {
                let logText = String(decoding: try Data(contentsOf: log), as: UTF8.self)
                entries.append(.init(
                    path: "\(day)/\(log.lastPathComponent)",
                    data: Data(
                        OperationalDiagnosticLogFormat.redactLocations(inLogText: logText).utf8
                    )
                ))
            }
            if includeTracks, let trackRootURL {
                let trackDirectory = trackRootURL.appendingPathComponent(day, isDirectory: true)
                let tracks = ((try? FileManager.default.contentsOfDirectory(
                    at: trackDirectory,
                    includingPropertiesForKeys: nil,
                    options: [.skipsHiddenFiles]
                )) ?? []).filter { $0.pathExtension.lowercased() == "json" && $0.lastPathComponent != "clues.json" && !$0.lastPathComponent.hasSuffix(".review.json") }
                    .sorted { $0.lastPathComponent < $1.lastPathComponent }
                for track in tracks {
                    entries.append(.init(
                        path: "Tracks/\(day)/\(track.lastPathComponent)",
                        data: try Data(contentsOf: track)
                    ))
                }
            }
        }
        guard entries.count > 1 else { throw CocoaError(.fileNoSuchFile) }
        try OperationalZipArchive.encode(entries, compress: true).write(
            to: destination,
            options: .atomic
        )
        return destination
    }

    private func append(_ text: String) throws {
        try handle?.write(contentsOf: Data(text.utf8))
        if let currentURL { AppleFlightStorage.fileChanged(currentURL) }
    }

    private static func dayName(for date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

}

enum AppleLog {
    static func debug(_ category: String, _ message: String) {
        write(level: "DEBUG", category: category, message: message)
    }

    static func info(_ category: String, _ message: String) {
        write(level: "INFO", category: category, message: message)
    }

    static func warning(_ category: String, _ message: String) {
        write(level: "WARN", category: category, message: message)
    }

    static func error(_ category: String, _ message: String) {
        write(level: "ERROR", category: category, message: message)
    }

    private static func write(level: String, category: String, message: String) {
        let processAndThread = currentProcessAndThread()
        Task {
            await AppleDiagnosticLogStore.shared.write(
                level: level,
                processAndThread: processAndThread,
                category: category,
                message: message
            )
        }
    }

    private static func currentProcessAndThread() -> String {
        let processID = ProcessInfo.processInfo.processIdentifier
        if Thread.isMainThread { return "\(processID)-main" }
        let threadID = pthread_mach_thread_np(pthread_self())
        let name = Thread.current.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return name.isEmpty ? "\(processID)-\(threadID)" : "\(processID)-\(threadID):\(name)"
    }
}

@MainActor
enum AppleSEIHexDiagnostics {
    static var enabled = false
}

@MainActor
final class AppleDiagnosticsCenter: ObservableObject {
    @Published private(set) var days: [AppleLogDay] = []
    @Published var selectedDays: Set<String> = []
    @Published var includeTracks = false
    @Published private(set) var bundleURL: URL?
    @Published private(set) var status = "Preparing diagnostics…"
    @Published private(set) var isPreparing = false

    func start() async {
        do {
            let url = try await AppleDiagnosticLogStore.shared.start(metadata: header)
            status = url.lastPathComponent
            AppleLog.info("App", "Diagnostic logging started")
            await refreshDays()
        } catch {
            status = "Logging failed: \(error.localizedDescription)"
        }
    }

    func refreshDays() async {
        do {
            days = try await AppleDiagnosticLogStore.shared.availableDays()
            if selectedDays.isEmpty {
                selectedDays = Set(days.filter(\.isToday).map(\.name))
                if selectedDays.isEmpty, let first = days.first { selectedDays = [first.name] }
            }
        } catch {
            status = "Log scan failed: \(error.localizedDescription)"
        }
    }

    func beginPackagingSession() async {
        bundleURL = nil
        includeTracks = false
        status = "Select log days, then package a fresh bundle."
        await refreshDays()
    }

    func prepareSelectedBundle() async {
        isPreparing = true
        bundleURL = nil
        defer { isPreparing = false }
        do {
            AppleLog.info("App", "Packaging log days: \(selectedDays.sorted().joined(separator: ","))")
            bundleURL = try await AppleDiagnosticLogStore.shared.prepareBundle(
                selectedDays: selectedDays,
                includeTracks: includeTracks,
                manifest: diagnosticManifest
            )
            status = bundleURL?.lastPathComponent ?? "Bundle ready"
        } catch {
            status = "Packaging failed: \(error.localizedDescription)"
        }
    }

    private var header: String {
        "# RID2Caltopo \(appVersion) running on \(UIDevice.current.systemName) \(UIDevice.current.systemVersion) (\(hardwareIdentifier))"
    }

    private var diagnosticManifest: String {
        let fields: [String: Any] = [
            "app": "RID2Caltopo",
            "version": appVersion,
            "bundle_id": Bundle.main.bundleIdentifier ?? "",
            "device_model": UIDevice.current.model,
            "hardware_identifier": hardwareIdentifier,
            "os": "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)",
            "locale": Locale.current.identifier,
            "time_zone": TimeZone.current.identifier,
            "wifi_ipv4": AppleNetworkAddress.preferredIPv4Address() ?? "unavailable",
            "generated_at": OperationalDiagnosticLogFormat.localTimestamp(Date()),
            "note": "No CalTopo credential secret is included.",
        ]
        let data = try? JSONSerialization.data(withJSONObject: fields, options: [.prettyPrinted, .sortedKeys])
        return "RID2Caltopo Diagnostic Manifest\n" + String(decoding: data ?? Data(), as: UTF8.self)
    }

    private var appVersion: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(version) (\(build))"
    }

    private var hardwareIdentifier: String {
        if let simulatorModel = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
            return simulatorModel
        }
        var systemInfo = utsname()
        guard uname(&systemInfo) == 0 else { return UIDevice.current.model }
        let machineSize = MemoryLayout.size(ofValue: systemInfo.machine)
        return withUnsafePointer(to: &systemInfo.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: machineSize) {
                String(cString: $0)
            }
        }
    }
}
