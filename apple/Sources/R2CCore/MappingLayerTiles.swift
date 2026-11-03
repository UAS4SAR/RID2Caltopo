import Foundation

/// Shallow tile probes for local mapping layers. IO worker only — never call from MainActor.
public enum MappingLayerTiles {
    /// Cheap shallow tile probe: layer.json plus one zoom/x tile png. Not a DFS.
    /// Prefer IO workers for bulk library refresh; a single shallow probe is bounded.
    public static func hasTileManifest(_ directory: URL) -> Bool {
        let fm = FileManager.default
        let layerJSON = directory.appendingPathComponent("layer.json")
        guard fm.fileExists(atPath: layerJSON.path) else { return false }
        return hasTiles(directory)
    }

    /// Restore the local manifest for imported layers that already have tiles.
    /// Run on an IO worker. Never replace a result's existing full manifest.
    public static func ensureImportedManifest(_ layer: MappingLayer, in directory: URL) throws {
        let manifest = directory.appendingPathComponent("layer.json")
        guard !FileManager.default.fileExists(atPath: manifest.path), hasTiles(directory) else { return }
        try JSONEncoder().encode(layer).write(to: manifest, options: .atomic)
    }

    private static func hasTiles(_ directory: URL) -> Bool {
        let fm = FileManager.default
        guard let zooms = try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) else {
            return false
        }
        for z in zooms {
            guard (try? z.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true,
                  Int(z.lastPathComponent) != nil else { continue }
            guard let xs = try? fm.contentsOfDirectory(at: z, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) else { continue }
            for x in xs {
                guard (try? x.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true else { continue }
                if let tiles = try? fm.contentsOfDirectory(at: x, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles]),
                   tiles.contains(where: { $0.pathExtension.lowercased() == "png" }) {
                    return true
                }
            }
        }
        return false
    }

    public static func hasSurfaceModel(_ directory: URL) -> Bool {
        let surface = directory.appendingPathComponent("surface", isDirectory: true)
        guard FileManager.default.fileExists(atPath: surface.path) else { return false }
        guard let enumerator = FileManager.default.enumerator(at: surface, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles]) else {
            return false
        }
        for case let file as URL in enumerator {
            if file.pathExtension.lowercased() == "glb" { return true }
        }
        return false
    }
}
