import Foundation
#if canImport(Darwin)
import Darwin

/// Filesystem events, not polling. Directory events discover new files; only growing
/// native video files need individual vnode watches. Ordinary app writes report their size.
final class FlightStorageFileMonitor: @unchecked Sendable {
    private let queue = DispatchQueue(label: "flight-storage-files", qos: .utility)
    private var sources: [String: DispatchSourceFileSystemObject] = [:]
    private var children: [String: Set<String>] = [:]
    private var pending = Set<String>()
    private var stopped = false
    private let changed: @Sendable (URL, Int64) -> Void
    init(roots: [URL], changed: @escaping @Sendable (URL, Int64) -> Void) {
        self.changed = changed
        queue.async { [self] in roots.forEach { discover($0, initial: true) } }
    }
    func stop() {
        queue.async { [self] in
            stopped = true
            sources.values.forEach { $0.cancel() }
            sources.removeAll(); children.removeAll()
        }
    }
    private func watch(_ url: URL, directory: Bool) {
        guard sources[url.path] == nil, !stopped else { return }
        let descriptor = open(url.path, O_EVTONLY)
        guard descriptor >= 0 else { return }
        let source = DispatchSource.makeFileSystemObjectSource(fileDescriptor: descriptor,
            eventMask: [.write, .extend, .delete, .rename], queue: queue)
        sources[url.path] = source
        source.setCancelHandler { close(descriptor) }
        source.setEventHandler { [weak self, weak source] in
            guard let self, !self.stopped else { return }
            if source?.data.contains(.delete) == true || source?.data.contains(.rename) == true {
                self.sources.removeValue(forKey: url.path)?.cancel()
            }
            guard self.pending.insert(url.path).inserted else { return }
            // Coalesce a burst of write notifications; this is a one-shot event drain.
            self.queue.asyncAfter(deadline: .now() + .milliseconds(250)) { [weak self] in
                guard let self, !self.stopped else { return }
                self.pending.remove(url.path)
                if directory { self.discover(url, initial: false) }
                else { self.changed(url, self.fileSize(url)) }
            }
        }
        source.resume()
    }
    private func fileSize(_ url: URL) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber)?.int64Value ?? 0
    }
    private func discover(_ directory: URL, initial: Bool) {
        guard !stopped else { return }
        // Install the watch before enumerating so creation during discovery is not missed.
        watch(directory, directory: true)
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory,
            includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey, .fileSizeKey])) ?? []
        let current = Set(urls.map(\.path))
        for removed in (children[directory.path] ?? []).subtracting(current) {
            changed(URL(fileURLWithPath: removed), 0)
            sources.removeValue(forKey: removed)?.cancel()
            // Account for children of a moved/deleted directory too.
            let descendants = children.keys.filter { $0 == removed || $0.hasPrefix(removed + "/") }
            for parent in descendants {
                for path in children.removeValue(forKey: parent) ?? [] { changed(URL(fileURLWithPath: path), 0) }
                sources.removeValue(forKey: parent)?.cancel()
            }
        }
        let previous = children[directory.path] ?? []
        children[directory.path] = current
        for url in urls {
            guard let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey, .fileSizeKey]), values.isSymbolicLink != true else { continue }
            if values.isDirectory == true {
                if initial || !previous.contains(url.path) { discover(url, initial: initial) }
            } else {
                if ["mp4", "fmp4"].contains(url.pathExtension.lowercased()) &&
                    (!ManagedVideoRecordingIdentity.isCompletedRecordingPath(url.path) || url.lastPathComponent.hasPrefix(".")) {
                    watch(url, directory: false)
                }
                // Read after the watch is live to include growth during installation.
                changed(url, fileSize(url))
            }
        }
    }
}
#endif
