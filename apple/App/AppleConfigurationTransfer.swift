import CryptoKit
import Combine
import Foundation
import R2CCore
import Security
import SwiftUI

/// Runs only on this worker actor. No credential values are requested or copied.
actor AppleCredentialBackupProtection {
    static let shared = AppleCredentialBackupProtection()
    private var lastAttempt = Date.distantPast

    func apply() {
        guard Date().timeIntervalSince(lastAttempt) > 5 else { return }
        lastAttempt = Date()
        let manager = FileManager.default
        guard var library = manager.urls(for: .libraryDirectory, in: .userDomainMask).first,
              let documents = manager.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let privateDirectories = ["Preferences", "WebKit", "Cookies", "HTTPStorages", "Saved Application State", "Application Support/PilotCloudProvisioning"]
            .map { library.appendingPathComponent($0, isDirectory: true) }
            + [documents.appendingPathComponent("RID2Caltopo/Exports", isDirectory: true)]
        var protectedDirectories = true
        for var url in privateDirectories {
            do {
                try manager.createDirectory(at: url, withIntermediateDirectories: true)
                var values = URLResourceValues(); values.isExcludedFromBackup = true
                try url.setResourceValues(values)
            } catch {
                protectedDirectories = false
                AppleLog.warning("BackupProtection", "Could not exclude a private app directory from system backup")
            }
        }
        if protectedDirectories {
            do {
                // Undo the initial broad exclusion: operational data in Application Support
                // must remain eligible. Protect private children before lifting the parent flag.
                var values = URLResourceValues(); values.isExcludedFromBackup = false
                try library.setResourceValues(values)
                AppleLog.info("BackupProtection", "Private storage excluded; operational Library data remains backup-eligible")
            } catch { AppleLog.warning("BackupProtection", "Could not restore operational Library backup eligibility") }
        }
        var result: CFTypeRef?
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecReturnAttributes as String: true, kSecMatchLimit as String: kSecMatchLimitAll]
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let items = result as? [[String: Any]] else { return }
        var failed = 0
        for item in items {
            guard let service = item[kSecAttrService as String] as? String,
                  service.hasPrefix("org.ncssar."), let account = item[kSecAttrAccount as String] as? String else { continue }
            let key: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service, kSecAttrAccount as String: account]
            let existing = item[kSecAttrAccessible as String] as? String
            if existing == (kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String) ||
               existing == (kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String) ||
               existing == (kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly as String) { continue }
            let protection = existing == (kSecAttrAccessibleAfterFirstUnlock as String)
                ? kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly : kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            let updated = SecItemUpdate(key as CFDictionary,
                [kSecAttrAccessible as String: protection] as CFDictionary)
            if updated != errSecSuccess { failed += 1 }
        }
        AppleLog.info("BackupProtection", "Device-bound credential protection checked; pending=\(failed)")
    }
}

@MainActor
final class AppleConfigurationTransferManager: ObservableObject {
    @Published private(set) var status = "Ready"
    @Published private(set) var exportURL: URL?
    @Published private(set) var isWorking = false

    private nonisolated static let packageFormat = "rid2caltopo_mutual_aid_package"

    func discardPreparedPackage() {
        exportURL = nil
        status = "Prepare the package with the selected options."
    }

    func prepareMutualAidPackage(
        bounds: OperationalMapBounds,
        selectedTiles: Set<String>,
        packageName: String,
        incidentID: String
    ) async {
        exportURL = nil
        isWorking = true
        status = "Collecting cached map data…"
        defer { isWorking = false }
        do {
            let layers = AppleMappingLayerStore.shared.layers.filter { $0.incidentId == incidentID }
            let result = try await Task.detached(priority: .utility) {
                try await Self.buildMapPackage(bounds: bounds, selectedTiles: selectedTiles, packageName: packageName, layers: layers)
            }.value
            exportURL = result.0
            status = result.1
        } catch { status = "Map package export failed: \(error.localizedDescription)" }
    }

    private nonisolated static func buildMapPackage(bounds: OperationalMapBounds, selectedTiles: Set<String>, packageName: String, layers: [MappingLayer]) async throws -> (URL, String) {
            AppleMapCachePaths.prepare()
            let cleanName = Self.sanitize(packageName)
            var entries: [OperationalZipArchive.Entry] = []
            let maximumPackageBytes = 512 * 1_024 * 1_024
            var packageBytes = 0
            var tileManifest: [[String: Any]] = []
            var mappingManifest: [[String: Any]] = []
            var mappingSurfaces: [[String: Any]] = []
            let sources: [(label: String, key: String, ext: String, source: String)] = [
                ("Contours", "usgsContours", "png", "USGS-Contours"),
            ]
            for selected in sources where selectedTiles.contains(selected.label) && OperationalMapTilePolicy.allowsPackageExport(source: selected.source) {
                let tiles = try MapPackageRegion.cachedTiles(
                    root: AppleMapCachePaths.root.appendingPathComponent(selected.key),
                    fileExtension: selected.ext, bounds: bounds)
                for tile in tiles {
                    let source = AppleMapCachePaths.tile(tile, layerKey: selected.key, fileExtension: selected.ext)
                    guard let data = AppleMapCacheAccess.synchronized({ try? Data(contentsOf: source) }) else { continue }
                    guard data.count <= maximumPackageBytes - packageBytes else { throw TransferError.packageBytesTooLarge }
                    packageBytes += data.count
                    let path = "tiles/\(selected.source.lowercased())/\(tile.zoom)/\(tile.x)/\(tile.y).bin"
                    entries.append(.init(path: path, data: data))
                    tileManifest.append([
                        "source": selected.source,
                        "z": tile.zoom, "x": tile.x, "y": tile.y,
                        "expires_at_epoch_ms": 0, "path": path,
                    ])
                }
            }
            if selectedTiles.contains("R2C Mapping layers") {
                for layer in layers {
                    let root=AppleMappingTileCache.layerRoot(layer.id)
                    let tiles=try MapPackageRegion.cachedTiles(root:root,fileExtension:"png",bounds:bounds)
                    guard !tiles.isEmpty else { continue }
                    guard let value=try JSONSerialization.jsonObject(with:JSONEncoder().encode(layer)) as? [String:Any] else { continue }
                    mappingManifest.append(value)
                    let surface=root.appendingPathComponent("surface")
                    if let files=FileManager.default.enumerator(at:surface,includingPropertiesForKeys:[.isRegularFileKey]) {
                        for file in ({ files.allObjects.compactMap{$0 as? URL} })() where (try? file.resourceValues(forKeys:[.isRegularFileKey]).isRegularFile)==true {
                            let bytes=try Data(contentsOf:file)
                            guard bytes.count<=maximumPackageBytes-packageBytes else{throw TransferError.packageBytesTooLarge}
                            packageBytes+=bytes.count
                            let relative=String(file.path.dropFirst(root.path.count+1)),path="mapping-layers/\(layer.id)/"+relative
                            entries.append(.init(path:path,data:bytes))
                            mappingSurfaces.append(["layerId":layer.id,"path":path,"bytes":bytes.count,"sha256":SHA256.hash(data:bytes).map{String(format:"%02x",$0)}.joined()])
                        }
                    }
                    for tile in tiles {
                        let source=AppleMappingTileCache.tile(layerID:layer.id,zoom:tile.zoom,x:tile.x,y:tile.y)
                        guard let bytes=AppleMapCacheAccess.synchronized({try? Data(contentsOf:source)}) else {continue}
                        guard bytes.count<=maximumPackageBytes-packageBytes else {throw TransferError.packageBytesTooLarge}
                        packageBytes+=bytes.count
                        let path="mapping-layers/\(layer.id)/\(tile.zoom)/\(tile.x)/\(tile.y).png"
                        entries.append(.init(path:path,data:bytes))
                        tileManifest.append(["source":"R2C-Mapping-\(layer.id)","z":tile.zoom,"x":tile.x,"y":tile.y,"expires_at_epoch_ms":0,"path":path])
                    }
                }
            }
            var demManifest: [[String: Any]] = []
            if selectedTiles.contains("DEM") {
                let demFiles = FileManager.default.fileExists(atPath: AppleMapCachePaths.demRoot.path)
                    ? try FileManager.default.contentsOfDirectory(at: AppleMapCachePaths.demRoot, includingPropertiesForKeys: [.isRegularFileKey]) : []
                for source in demFiles {
                    let fileName = source.lastPathComponent
                    guard ["tif", "tiff"].contains(source.pathExtension.lowercased()),
                          let b = GeoTiffElevationSource.tileBounds(fileName: fileName),
                          MapPackageRegion.overlaps(bounds, .init(north: b.north, south: b.south, west: b.west, east: b.east)) else { continue }
                    guard let data = AppleMapCacheAccess.synchronized({ try? Data(contentsOf: source) }) else { continue }
                    guard data.count <= maximumPackageBytes - packageBytes else { throw TransferError.packageBytesTooLarge }
                    packageBytes += data.count
                    let path = "dem/\(fileName)"
                    entries.append(.init(path: path, data: data))
                    demManifest.append(["tile_name": fileName, "file_name": fileName, "path": path])
                }
            }
            let aolFiles: [OperationalZipArchive.Entry]
            if selectedTiles.contains("AOL") {
                aolFiles = try await AppleSurfaceStore.shared.exportSets(bounds)
            } else {
                aolFiles = []
            }
            for entry in aolFiles {
                guard entry.data.count <= maximumPackageBytes-packageBytes else { throw TransferError.packageBytesTooLarge }
                packageBytes+=entry.data.count;entries.append(entry)
            }
            let manifest: [String: Any] = [
                "aol_entries": aolFiles.map(\.path),
                "format": Self.packageFormat, "version": 1,
                "generated": ISO8601DateFormatter().string(from: Date()),
                "package_name": packageName, "source_org": "",
                "profile_enc": "",
                "tile_entries": tileManifest, "dem_entries": demManifest, "mapping_layers": mappingManifest, "surface_entries":mappingSurfaces,
            ]
            entries.append(.init(path: "manifest.json", data: try JSONSerialization.data(withJSONObject: manifest, options: [.prettyPrinted, .sortedKeys])))
            let archive = try OperationalZipArchive.encode(entries)
            let url = try Self.writeExport(archive, name: "\(cleanName)_map_package.zip")
            return (url, "Map package ready: \(aolFiles.filter { $0.path.hasSuffix(".aol") }.count) AOL tile(s), \(tileManifest.count) tile(s), \(demManifest.count) DEM tile(s).")
    }

    func configurationBackupData(
        passphrase: String,
        caltopo: AppleCaltopoSettings,
        organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore
    ) async throws -> Data {
        let mapID = caltopo.mapID, mapTitle = caltopo.mapTitle
        let preferences = organization.transferSnapshot().compactMapValues { ($0 as? NSNumber)?.intValue }
        let identitySnapshot = identities.backupIdentitySnapshot
        let data = try await Task.detached(priority: .utility) {
            let mappings = identitySnapshot.values.sorted { $0.remoteID < $1.remoteID }.map {
                OrgConfigRIDMapping(remoteID: $0.remoteID, mappedID: $0.mappedID, organization: $0.organization,
                    model: $0.droneDescription, owner: $0.pilotCallsign, ownerName: $0.ownerName,
                    ownerCallsign: $0.pilotCallsign, readiness: $0.readiness)
            }
            let input = ConfigurationBackupPolicy.Preferences(mapID: mapID, mapTitle: mapTitle, organization: preferences, mappings: mappings)
            return try ConfigurationBackupPolicy.encrypted(input, passphrase: passphrase)
        }.value
        try Task.checkCancellation()
        return data
    }

    func restoreConfigurationBackup(
        _ data: Data,
        passphrase: String,
        caltopo: AppleCaltopoSettings,
        organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore
    ) async throws {
        let initialMapID = caltopo.mapID, initialMapTitle = caltopo.mapTitle
        let initialPreferences = organization.transferSnapshot().compactMapValues { ($0 as? NSNumber)?.intValue }
        let initialMappingRevision = identities.backupRevision
        let restored = try await Task.detached(priority: .utility) {
            try ConfigurationBackupPolicy.decrypted(data, passphrase: passphrase)
        }.value
        try AppleAircraftOrganizationAccess.requireEdit()
        guard identities.backupRevision == initialMappingRevision else { throw CancellationError() }
        try await identities.restoreBackupMappings(restored.mappings) {
            guard caltopo.mapID == initialMapID, caltopo.mapTitle == initialMapTitle,
                  organization.transferSnapshot().compactMapValues({ ($0 as? NSNumber)?.intValue }) == initialPreferences
            else { throw CancellationError() }
            try organization.applyTransferSnapshot(restored.organization)
            try caltopo.applyTransferSnapshot(["map_id": restored.mapID, "map_title": restored.mapTitle])
        }
        status = "Settings restored. Existing device credentials were retained; new devices need tracker enrollment and sign-in."
    }

    func importMutualAidPackage(
        _ data: Data,
        caltopo: AppleCaltopoSettings,
        organization: AppleOrgConfigSettings,
        onProgress: @escaping @MainActor @Sendable (String, Double?) -> Void = { _, _ in }
    ) async throws -> Bool {
        onProgress("Reading map package…", nil)
        let decoded = try await Task.detached(priority: .userInitiated) {
            let entries = try OperationalZipArchive.decode(data)
            guard let manifest = entries.first(where: { $0.path == "manifest.json" }) else { throw TransferError.invalidPackage }
            try ConfigurationBackupPolicy.validateMapPackage(manifest.data)
            return entries
        }.value
        let result = try await Task.detached(priority: .userInitiated) {
            try await Self.importMapFiles(decoded, progress: onProgress)
        }.value
        try AppleMappingLayerStore.shared.installShared(result.layers, incidentID: result.layers.first?.incidentId ?? "")
        status = result.summary
        onProgress("Import complete.", 1)
        return false
    }

    private struct ImportedMapFiles: Sendable {
        let layers: [MappingLayer]
        let summary: String
    }
    private nonisolated static func importMapFiles(_ decoded: [OperationalZipArchive.Entry], progress: @escaping @MainActor @Sendable (String, Double?) -> Void) async throws -> ImportedMapFiles {
        AppleMapCachePaths.prepare()
        let lookup = Dictionary(uniqueKeysWithValues: decoded.map { ($0.path, $0.data) })
        guard let bytes = lookup["manifest.json"], let manifest = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { throw TransferError.invalidPackage }
        var importedTiles = 0
        var importedMappingLayers:[MappingLayer]=[]
        for value in manifest["mapping_layers"] as? [[String:Any]] ?? [] {
            guard let data=try? JSONSerialization.data(withJSONObject:value),
                  let layer=try? MappingLayer.decode(data,incidentID: value["incidentId"] as? String ?? "") else {continue}
            importedMappingLayers.append(layer)
        }
        let tileEntries = manifest["tile_entries"] as? [[String: Any]] ?? []
        let totalTiles = max(1, tileEntries.count)
        for (index, item) in tileEntries.enumerated() {
            if index.isMultiple(of: 8) {
                let fraction = 0.05 + 0.84 * Double(index) / Double(totalTiles)
                await progress("Importing imagery \(index + 1) of \(tileEntries.count)…", fraction)
            }
            guard let path = item["path"] as? String, let bytes = lookup[path],
                  let z = (item["z"] as? NSNumber)?.intValue,
                  let x = (item["x"] as? NSNumber)?.intValue,
                  let y = (item["y"] as? NSNumber)?.intValue else { continue }
            let key: String
            let ext: String
            let source=item["source"] as? String ?? ""
            if source.hasPrefix("R2C-Mapping-") {
                let layerID=String(source.dropFirst("R2C-Mapping-".count))
                guard UUID(uuidString:layerID) != nil,
                      importedMappingLayers.contains(where:{$0.id==layerID}),
                      (0...23).contains(z),(0..<(1 << z)).contains(x),(0..<(1 << z)).contains(y) else {continue}
                let destination=AppleMappingTileCache.tile(layerID:layerID,zoom:z,x:x,y:y)
                _ = try AppleMapCacheAccess.write(bytes,to:destination)
                importedTiles+=1
                continue
            }
            switch source {
            case "ArcGIS-WorldImagery":
                key = OperationalMapBaseLayer.imagery.cacheKey
                ext = OperationalMapBaseLayer.imagery.fileExtension
            case "OSM-Standard":
                key = OperationalMapBaseLayer.openStreetMap.cacheKey
                ext = OperationalMapBaseLayer.openStreetMap.fileExtension
            case "USGS-Contours":
                key = "usgsContours"
                ext = "png"
            default: continue
            }
            let destination = AppleMapCachePaths.tile(.init(zoom: z, x: x, y: y), layerKey: key, fileExtension: ext)
            let bytesAdded = try AppleMapCacheAccess.write(bytes, to: destination)
            AppleMapOfflineManager.noteTileCached(bytes: Int(bytesAdded))
            importedTiles += 1
        }

        for item in manifest["surface_entries"] as? [[String:Any]] ?? [] {
            guard let id=item["layerId"] as? String,importedMappingLayers.contains(where:{$0.id==id}),
                  let path=item["path"] as? String,path.hasPrefix("mapping-layers/\(id)/surface/"),
                  let bytes=lookup[path],let expected=item["bytes"] as? Int,expected==bytes.count,
                  let digest=item["sha256"] as? String,
                  SHA256.hash(data:bytes).map({String(format:"%02x",$0)}).joined()==digest else{throw TransferError.invalidPackage}
            let relative=String(path.dropFirst("mapping-layers/\(id)/".count))
            guard !relative.split(separator:"/").contains(".."),!relative.contains("\\") else{throw TransferError.invalidPackage}
            _ = try AppleMapCacheAccess.write(bytes,to:AppleMappingTileCache.layerRoot(id).appendingPathComponent(relative))
        }
        await progress("Saving mapping layer and other package data…", 0.92)
        var importedDEM = 0
        for item in manifest["dem_entries"] as? [[String: Any]] ?? [] {
            guard let path = item["path"] as? String, let bytes = lookup[path],
                  let fileName = item["file_name"] as? String,
                  !fileName.contains("/") && !fileName.contains("..") else { continue }
            let destination = AppleMapCachePaths.demRoot.appendingPathComponent(fileName)
            _ = try AppleMapCacheAccess.write(bytes, to: destination)
            importedDEM += 1
        }
        let importedAOL=try await AppleSurfaceStore.shared.importSets(lookup)
        return ImportedMapFiles(layers: importedMappingLayers, summary: "Imported Map package: \(importedAOL) AOL tile(s), \(importedTiles) map tile(s), \(importedMappingLayers.count) R2C mapping layer(s), \(importedDEM) DEM tile(s).")
    }

    private nonisolated static func writeExport(_ data: Data, name: String) throws -> URL {
        let root = TemporaryWorkspace.root.appendingPathComponent("Exports", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let destination = root.appendingPathComponent(name)
        try data.write(to: destination, options: .atomic)
        return destination
    }

    private nonisolated static func sanitize(_ value: String) -> String {
        let safe = value.lowercased().map { $0.isLetter || $0.isNumber || "._-".contains($0) ? $0 : "_" }
        let result = String(safe).trimmingCharacters(in: CharacterSet(charactersIn: "_"))
        return result.isEmpty ? "mutual_aid" : result
    }
}

private enum TransferError: LocalizedError {
    case shortPassphrase, invalidBackup, invalidPackage, expiredPackage, packageTooLarge, packageBytesTooLarge
    var errorDescription: String? {
        switch self {
        case .shortPassphrase: "Use a backup passphrase of at least eight characters."
        case .invalidBackup: "The selected file is not a RID2Caltopo encrypted configuration backup, or the passphrase is incorrect."
        case .invalidPackage: "The selected archive is not a compatible map tile package."
        case .expiredPackage: "The map tile package has expired."
        case .packageTooLarge: "The selected region exceeds the 250,000-tile package safety limit."
        case .packageBytesTooLarge: "The cached data for this package exceeds the 512 MB in-memory export safety limit. Select a smaller region."
        }
    }
}

@MainActor
final class AppleICloudBackupCenter: ObservableObject {
    static let shared = AppleICloudBackupCenter()

    @Published private(set) var status = "iCloud backup is off."
    @Published private(set) var isWorking = false
    @Published private(set) var lastBackup: Date?
    @Published var isEnabled: Bool {
        didSet { defaults.set(isEnabled, forKey: Self.enabledKey) }
    }

    private static let enabledKey = "apple.icloud.configurationBackup.enabled"
    private static let lastBackupKey = "apple.icloud.configurationBackup.lastDate"
    private nonisolated static let keychainService = "org.ncssar.RID2CaltopoApple.iCloudBackup"
    private nonisolated static let keychainAccount = "configuration-backup-passphrase"
    private nonisolated static let containerIdentifier = "iCloud.org.ncssar.RID2CaltopoApple"
    private nonisolated static let relativePath = "Documents/RID2Caltopo/RID2Caltopo_Config_Backup.json"

    private let defaults = UserDefaults.standard
    private let transfer = AppleConfigurationTransferManager()
    private weak var caltopo: AppleCaltopoSettings?
    private weak var organization: AppleOrgConfigSettings?
    private weak var identities: AppleDroneConfirmationStore?
    private var scheduledBackup: Task<Void, Never>?
    private var configurationChanges: AnyCancellable?

    private init() {
        isEnabled = defaults.bool(forKey: Self.enabledKey)
        lastBackup = defaults.object(forKey: Self.lastBackupKey) as? Date
        if isEnabled { status = "Automatic encrypted iCloud backup is enabled." }
    }

    func configure(
        caltopo: AppleCaltopoSettings,
        organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore
    ) {
        self.caltopo = caltopo
        self.organization = organization
        self.identities = identities
        configurationChanges = Publishers.Merge3(
            caltopo.objectWillChange,
            organization.objectWillChange,
            identities.objectWillChange
        )
        .sink { [weak self] in
            Task { @MainActor in
                await Task.yield()
                self?.scheduleBackup()
            }
        }
    }

    func setEnabled(_ enabled: Bool, passphrase: String) {
        if enabled {
            guard passphrase.count >= 8 else {
                isEnabled = false
                status = "Enter a backup passphrase of at least eight characters before enabling automatic backup."
                return
            }
            do {
                try Self.savePassphrase(passphrase)
                isEnabled = true
                status = "Automatic encrypted iCloud backup is enabled."
                scheduleBackup()
            } catch {
                isEnabled = false
                status = "Could not save the backup passphrase in Keychain: \(error.localizedDescription)"
            }
        } else {
            scheduledBackup?.cancel()
            isEnabled = false
            status = "iCloud backup is off. The existing encrypted cloud file was not deleted."
        }
    }

    func scheduleBackup() {
        guard isEnabled, Self.savedPassphrase() != nil else { return }
        scheduledBackup?.cancel()
        scheduledBackup = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            await self?.backupNow(passphrase: nil)
        }
    }

    func backupNow(passphrase suppliedPassphrase: String?) async {
        guard !isWorking else { return }
        guard let caltopo, let organization, let identities else {
            status = "Configuration is not ready for backup."
            return
        }
        let passphrase = suppliedPassphrase.flatMap { $0.isEmpty ? nil : $0 } ?? Self.savedPassphrase()
        guard let passphrase, passphrase.count >= 8 else {
            status = "Enter a backup passphrase of at least eight characters."
            return
        }
        isWorking = true
        status = "Encrypting configuration for iCloud…"
        do {
            try Self.savePassphrase(passphrase)
            let data = try await transfer.configurationBackupData(
                passphrase: passphrase, caltopo: caltopo, organization: organization, identities: identities
            )
            status = "Saving encrypted configuration to iCloud Drive…"
            try await Task.detached(priority: .utility) { try Self.writeToICloud(data) }.value
            let now = Date()
            lastBackup = now
            defaults.set(now, forKey: Self.lastBackupKey)
            status = "Encrypted iCloud backup updated."
        } catch {
            status = "iCloud backup failed: \(error.localizedDescription)"
        }
        isWorking = false
    }

    func restoreLatest(passphrase: String) async {
        guard !isWorking else { return }
        guard let caltopo, let organization, let identities else {
            status = "Configuration is not ready for restore."
            return
        }
        guard passphrase.count >= 8 else {
            status = "Enter the backup passphrase of at least eight characters."
            return
        }
        isWorking = true
        status = "Retrieving the encrypted configuration from iCloud Drive…"
        do {
            let data = try await Task.detached(priority: .utility) { try Self.readFromICloud() }.value
            try await transfer.restoreConfigurationBackup(
                data, passphrase: passphrase, caltopo: caltopo, organization: organization, identities: identities
            )
            try Self.savePassphrase(passphrase)
            status = "Configuration restored from iCloud. Reconnect tracker and CalTopo services to apply it."
        } catch {
            status = "iCloud restore failed: \(error.localizedDescription)"
        }
        isWorking = false
    }

    private nonisolated static func iCloudURL() throws -> URL {
        guard let root = FileManager.default.url(forUbiquityContainerIdentifier: containerIdentifier) else {
            throw ICloudBackupError.unavailable
        }
        return root.appendingPathComponent(relativePath)
    }

    private nonisolated static func writeToICloud(_ data: Data) throws {
        let destination = try iCloudURL()
        try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        let box = CoordinatedResultBox<Void>()
        NSFileCoordinator(filePresenter: nil).coordinate(writingItemAt: destination, options: .forReplacing, error: nil) { url in
            box.result = Result { try data.write(to: url, options: .atomic) }
        }
        guard let result = box.result else { throw ICloudBackupError.coordinationFailed }
        try result.get()
    }

    private nonisolated static func readFromICloud() throws -> Data {
        let source = try iCloudURL()
        guard FileManager.default.fileExists(atPath: source.path) else { throw ICloudBackupError.noBackup }
        try? FileManager.default.startDownloadingUbiquitousItem(at: source)
        let box = CoordinatedResultBox<Data>()
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: source, options: [], error: nil) { url in
            box.result = Result { try Data(contentsOf: url, options: .mappedIfSafe) }
        }
        guard let result = box.result else { throw ICloudBackupError.coordinationFailed }
        return try result.get()
    }

    private nonisolated static func savePassphrase(_ passphrase: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
        ]
        SecItemDelete(query as CFDictionary)
        var item = query
        item[kSecValueData as String] = Data(passphrase.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainBackupError(status: status) }
    }

    private nonisolated static func savedPassphrase() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

private final class CoordinatedResultBox<Value>: @unchecked Sendable {
    var result: Result<Value, Error>?
}

private struct KeychainBackupError: LocalizedError {
    let status: OSStatus
    var errorDescription: String? { "Keychain returned error \(status)." }
}

private enum ICloudBackupError: LocalizedError {
    case unavailable, noBackup, coordinationFailed
    var errorDescription: String? {
        switch self {
        case .unavailable: "iCloud Drive is unavailable. Sign in to iCloud and allow iCloud Drive for RID2Caltopo."
        case .noBackup: "No RID2Caltopo configuration backup exists in iCloud Drive."
        case .coordinationFailed: "iCloud Drive did not complete the coordinated file operation."
        }
    }
}

struct AppleICloudSettingsBackupView: View {
    @ObservedObject var iCloud: AppleICloudBackupCenter
    @State private var passphrase = ""

    var body: some View {
        Form {
            Section("Encrypted iCloud backup") {
                SecureField("Backup passphrase", text: $passphrase)
                Toggle("Automatic iCloud backup", isOn: Binding(
                    get: { iCloud.isEnabled },
                    set: { iCloud.setEnabled($0, passphrase: passphrase) }
                ))
                Button("Back Up to iCloud Now", systemImage: "icloud.and.arrow.up") {
                    Task { await iCloud.backupNow(passphrase: passphrase) }
                }
                .disabled(iCloud.isWorking)
                Button("Restore Latest iCloud Backup", systemImage: "icloud.and.arrow.down") {
                    Task { await iCloud.restoreLatest(passphrase: passphrase) }
                }
                .disabled(iCloud.isWorking)
                if let lastBackup = iCloud.lastBackup {
                    LabeledContent("Last backup", value: lastBackup.formatted(date: .abbreviated, time: .shortened))
                }
                if iCloud.isWorking { ProgressView() }
                Text(iCloud.status).font(.footnote).foregroundStyle(.secondary)
                Text("The backup is stored in your iCloud Drive and remains encrypted with this passphrase. The passphrase is saved only in this device's Keychain for automatic backup.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("iCloud Settings Backup")
    }
}

struct AppleMutualAidExportView: View {
    let bounds: OperationalMapBounds
    let incidentID: String
    @StateObject private var manager = AppleConfigurationTransferManager()
    @State private var selectedTiles: Set<String> = ["Contours", "DEM", "AOL"]
    @State private var packageName = "Map Package"

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("OSM and Esri basemap tiles are excluded. This exports permitted cached data only; it does not download anything. For the checked tile types, all cached zoom levels overlapping the current visible map region are included. Tiles are included whole, so their edges may extend beyond the visible region.")
                        .font(.footnote)
                }
                Section("Package") {
                    TextField("Package name", text: $packageName)
                    ForEach(["Contours", "DEM", "AOL"] + (AppleMappingLayerStore.shared.layers.contains(where:{$0.incidentId==incidentID}) ? ["R2C Mapping layers"] : []), id: \.self) { tile in
                        Toggle(isOn: Binding(
                            get: { selectedTiles.contains(tile) },
                            set: { checked in
                                if checked { selectedTiles.insert(tile) } else { selectedTiles.remove(tile) }
                            }
                        )) { Text(tile) }
                        .toggleStyle(MapPackageCheckboxStyle())
                    }
                }
                Section {
                    Button("Prepare Map Package", systemImage: "shippingbox") {
                        Task {
                            await manager.prepareMutualAidPackage(
                                bounds: bounds, selectedTiles: selectedTiles, packageName: packageName, incidentID: incidentID
                            )
                        }
                    }
                    .disabled(manager.isWorking || selectedTiles.isEmpty)
                    if let url = manager.exportURL { ShareLink(item: url) { Label("Share Map Package", systemImage: "square.and.arrow.up") } }
                    if manager.isWorking { ProgressView() }
                    Text(manager.status).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .disabled(manager.isWorking)
            .onChange(of: selectedTiles) { _, _ in manager.discardPreparedPackage() }
            .onChange(of: packageName) { _, _ in manager.discardPreparedPackage() }
            .navigationTitle("Export Map Package")
        }
    }
}

private struct MapPackageCheckboxStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack {
                Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
                    .foregroundStyle(Color.accentColor)
                configuration.label.foregroundStyle(.primary)
            }
        }
        .buttonStyle(.plain)
        .accessibilityValue(configuration.isOn ? "Selected" : "Not selected")
    }
}
