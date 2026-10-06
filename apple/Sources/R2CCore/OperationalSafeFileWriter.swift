import Foundation
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

public enum OperationalSafeFileWriterError: Error, Equatable {
    case writeFailed(String)
    case verificationFailed(String)
    case renameFailed(String, Int32)
}

/// Crash-safe file replacement for flight archives: write `<name>.partial`, force it to disk, read it
/// back and verify it, then atomically rename it over the target. A crash leaves either the old file
/// or the new one, never a torn file; `sweep` finishes or rolls back anything left behind.
public enum OperationalSafeFileWriter {
    public static let partialExtension = "partial"

    public static func partialURL(for url: URL) -> URL { url.appendingPathExtension(partialExtension) }

    /// Default validator by extension: KMZ must be a readable zip with doc.kml; JSON must parse.
    public static func defaultValidator(for url: URL) -> (Data) -> Bool {
        switch url.pathExtension.lowercased() {
        case "kmz": return OperationalFlightKMZ.isValidArchive
        case "json", "geojson": return { (try? JSONSerialization.jsonObject(with: $0)) != nil }
        default: return { !$0.isEmpty }
        }
    }

    public static func replace(_ url: URL, with data: Data, validator: ((Data) -> Bool)? = nil,
                               fileManager: FileManager = .default) throws {
        let validate = validator ?? defaultValidator(for: url)
        guard validate(data) else { throw OperationalSafeFileWriterError.verificationFailed(url.lastPathComponent) }
        let partial = partialURL(for: url)
        try? fileManager.removeItem(at: partial)
        guard fileManager.createFile(atPath: partial.path, contents: nil) else {
            throw OperationalSafeFileWriterError.writeFailed(partial.lastPathComponent)
        }
        do {
            let handle = try FileHandle(forWritingTo: partial)
            defer { try? handle.close() }
            try handle.write(contentsOf: data)
            try handle.synchronize()
        } catch {
            try? fileManager.removeItem(at: partial)
            throw OperationalSafeFileWriterError.writeFailed("\(partial.lastPathComponent): \(error.localizedDescription)")
        }
        guard let readBack = try? Data(contentsOf: partial), readBack == data, validate(readBack) else {
            try? fileManager.removeItem(at: partial)
            throw OperationalSafeFileWriterError.verificationFailed(partial.lastPathComponent)
        }
        try commit(partial: partial, to: url)
    }

    static func commit(partial: URL, to url: URL) throws {
        guard rename(partial.path, url.path) == 0 else {
            let code = errno
            throw OperationalSafeFileWriterError.renameFailed(url.lastPathComponent, code)
        }
        syncDirectory(url.deletingLastPathComponent())
    }

    static func syncDirectory(_ directory: URL) {
        let descriptor = open(directory.path, O_RDONLY)
        guard descriptor >= 0 else { return }
        _ = fsync(descriptor)
        close(descriptor)
    }

    public enum SweepAction: Equatable, Sendable {
        case completed(String)
        case discarded(String)
    }

    /// Finishes verified `.partial` files (renames them into place) and removes torn ones, in
    /// `directory` and its immediate subdirectories.
    @discardableResult
    public static func sweep(directory: URL, fileManager: FileManager = .default) -> [SweepAction] {
        var actions: [SweepAction] = []
        var directories = [directory]
        let children = (try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isDirectoryKey])) ?? []
        directories += children.filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true }
        for folder in directories {
            let files = (try? fileManager.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
            for partial in files where partial.pathExtension == partialExtension {
                let target = partial.deletingPathExtension()
                let relative = folder == directory ? partial.lastPathComponent
                    : "\(folder.lastPathComponent)/\(partial.lastPathComponent)"
                if let data = try? Data(contentsOf: partial), defaultValidator(for: target)(data),
                   (try? commit(partial: partial, to: target)) != nil {
                    actions.append(.completed(relative))
                } else {
                    try? fileManager.removeItem(at: partial)
                    actions.append(.discarded(relative))
                }
            }
        }
        return actions
    }
}

/// A durable request to rebuild one flight's KMZ from its archived GeoJSON and every clue it owns.
public struct FlightArchiveRewriteJob: Codable, Sendable, Equatable, Identifiable {
    public var id: String { "\(dayDirectory)/\(geoJSONFilename)" }
    public let aircraftID: String
    public let dayDirectory: String
    public let geoJSONFilename: String
    public let kmzFilename: String
    public var attempts: Int
    public var lastError: String?

    public init(aircraftID: String, dayDirectory: String, geoJSONFilename: String, kmzFilename: String,
                attempts: Int = 0, lastError: String? = nil) {
        self.aircraftID = aircraftID
        self.dayDirectory = dayDirectory
        self.geoJSONFilename = geoJSONFilename
        self.kmzFilename = kmzFilename
        self.attempts = attempts
        self.lastError = lastError
    }
}

/// Persistent queue of KMZ rewrites; survives process death and is retried at launch.
public struct FlightArchiveRewriteQueue: Sendable {
    public let fileURL: URL

    public init(fileURL: URL) { self.fileURL = fileURL }

    public func jobs() -> [FlightArchiveRewriteJob] {
        (try? Data(contentsOf: fileURL)).flatMap { try? JSONDecoder().decode([FlightArchiveRewriteJob].self, from: $0) } ?? []
    }

    public func enqueue(_ job: FlightArchiveRewriteJob) throws {
        var current = jobs()
        guard !current.contains(where: { $0.id == job.id }) else { return }
        current.append(job)
        try save(current)
    }

    public func complete(_ id: String) throws {
        try save(jobs().filter { $0.id != id })
    }

    public func recordFailure(_ id: String, error: String) throws {
        try save(jobs().map { job in
            guard job.id == id else { return job }
            var next = job
            next.attempts += 1
            next.lastError = error
            return next
        })
    }

    private func save(_ jobs: [FlightArchiveRewriteJob]) throws {
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try OperationalSafeFileWriter.replace(fileURL, with: JSONEncoder().encode(jobs))
    }
}
