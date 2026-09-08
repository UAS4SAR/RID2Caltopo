import CryptoKit
import Combine
import Foundation
import R2CCore
import Security
import SwiftUI
import UniformTypeIdentifiers

@MainActor
final class AppleConfigurationTransferManager: ObservableObject {
    @Published private(set) var status = "Ready"
    @Published private(set) var exportURL: URL?
    @Published private(set) var isWorking = false

    private static let backupFormat = "rid2caltopo_apple_config_backup"
    private static let packageFormat = "rid2caltopo_mutual_aid_package"

    func prepareBackup(
        passphrase: String,
        caltopo: AppleCaltopoSettings,
        organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore
    ) {
        guard passphrase.count >= 8 else { status = "Use a backup passphrase of at least eight characters."; return }
        isWorking = true
        status = "Preparing encrypted configuration backup…"
        do {
            let data = try configurationBackupData(
                passphrase: passphrase, caltopo: caltopo, organization: organization, identities: identities
            )
            exportURL = try writeExport(data, name: "RID2Caltopo_Config_\(stamp()).json")
            status = "Encrypted configuration backup ready to share."
        } catch { status = "Backup failed: \(error.localizedDescription)" }
        isWorking = false
    }

    func importFile(
        _ url: URL,
        passphrase: String,
        caltopo: AppleCaltopoSettings,
        organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore
    ) async {
        isWorking = true
        status = "Inspecting imported file…"
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() }; isWorking = false }
        do {
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            if url.pathExtension.lowercased() == "zip" || data.starts(with: [0x50, 0x4b]) {
                try importMutualAidPackage(data, caltopo: caltopo, organization: organization)
            } else {
                try restoreConfigurationBackup(data, passphrase: passphrase, caltopo: caltopo, organization: organization, identities: identities)
            }
        } catch { status = "Import failed: \(error.localizedDescription)" }
    }

    func prepareMutualAidPackage(
        bounds: OperationalMapBounds,
        preset: OperationalOfflinePreset,
        layer: OperationalMapBaseLayer,
        includeDEM: Bool,
        packageName: String,
        displayName: String,
        expiresAt: Date,
        caltopo: AppleCaltopoConfiguration,
        organization: AppleOrgConfigSettings
    ) async {
        guard let template = organization.mutualAidTemplate else {
            status = "Configure the Mutual Aid account in Settings before exporting an MA package."
            return
        }
        isWorking = true
        status = "Collecting cached map data…"
        defer { isWorking = false }
        do {
            guard let tiles = OperationalOfflineMapPlanner.tiles(
                bounds: bounds, minimumZoom: preset.minimumZoom, maximumZoom: preset.maximumZoom
            ) else { throw TransferError.packageTooLarge }
            let trackerAPIKey = organization.trackerAPIKey
            let cleanName = sanitize(packageName.isEmpty ? displayName : packageName)
            let profile: [String: Any] = [
                "profile_id": "mai-\(sanitize(template.sourceLabel))-\(sanitize(organization.incident))-op\(sanitize(organization.operationalPeriod))",
                "display_name": displayName.isEmpty ? "\(template.sourceLabel) \(organization.incident)" : displayName,
                "team_id": template.teamID,
                "credential_id": template.credentialID,
                "credential_secret": template.credentialSecret,
                "domain_and_port": template.domainAndPort,
                "connect_key": template.connectKey,
                "track_folder": organization.trackFolder,
                "incident": organization.incident,
                "op_period": organization.operationalPeriod,
                "tracker_api_key": trackerAPIKey,
                "tracker_url_prefix": organization.trackerURLPrefix,
                "auto_connect": true,
                "expires_at_epoch_ms": Int64(expiresAt.timeIntervalSince1970 * 1_000),
                "quiet_remove_on_expiry": true,
                "source_label": template.sourceLabel,
                "target_map_id": caltopo.mapID,
                "target_map_title": organization.incident,
                "target_folder_hint": template.targetFolderHint,
                "imported_at_epoch_ms": Int64(Date().timeIntervalSince1970 * 1_000),
                "import_dedupe_key": "\(template.sourceLabel)|\(organization.incident)|\(organization.operationalPeriod)",
            ]
            let profileEncrypted = try AndroidConfigTokenCodec.encryptMutualAidProfile(profile)
            var entries: [OperationalZipArchive.Entry] = []
            let maximumPackageBytes = 512 * 1_024 * 1_024
            var packageBytes = 0
            var tileManifest: [[String: Any]] = []
            for tile in tiles {
                let source = AppleMapCachePaths.tile(tile, layerKey: layer.cacheKey, fileExtension: layer.fileExtension)
                guard let data = AppleMapCacheAccess.synchronized({ try? Data(contentsOf: source) }) else { continue }
                guard data.count <= maximumPackageBytes - packageBytes else { throw TransferError.packageBytesTooLarge }
                packageBytes += data.count
                let path = "tiles/\(layer == .openStreetMap ? "osm-standard" : "arcgis-worldimagery")/\(tile.zoom)/\(tile.x)/\(tile.y).bin"
                entries.append(.init(path: path, data: data))
                tileManifest.append([
                    "source": layer == .openStreetMap ? "OSM-Standard" : "ArcGIS-WorldImagery",
                    "z": tile.zoom, "x": tile.x, "y": tile.y,
                    "expires_at_epoch_ms": 0, "path": path,
                ])
            }
            var demManifest: [[String: Any]] = []
            if includeDEM {
                for tileName in OperationalOfflineMapPlanner.demTileNames(bounds: bounds) {
                    let fileName = "USGS_1_\(tileName).tif"
                    let source = AppleMapCachePaths.demRoot.appendingPathComponent(fileName)
                    guard let data = AppleMapCacheAccess.synchronized({ try? Data(contentsOf: source) }) else { continue }
                    guard data.count <= maximumPackageBytes - packageBytes else { throw TransferError.packageBytesTooLarge }
                    packageBytes += data.count
                    let path = "dem/\(fileName)"
                    entries.append(.init(path: path, data: data))
                    demManifest.append(["tile_name": fileName, "file_name": fileName, "path": path])
                }
            }
            let manifest: [String: Any] = [
                "format": Self.packageFormat, "version": 1,
                "generated": ISO8601DateFormatter().string(from: Date()),
                "package_name": packageName, "source_org": template.sourceLabel,
                "profile_enc": profileEncrypted,
                "tile_entries": tileManifest, "dem_entries": demManifest,
            ]
            entries.append(.init(path: "manifest.json", data: try JSONSerialization.data(withJSONObject: manifest, options: [.prettyPrinted, .sortedKeys])))
            status = "Building mutual-aid package…"
            let archive = try await Task.detached(priority: .utility) { try OperationalZipArchive.encode(entries) }.value
            exportURL = try writeExport(archive, name: "\(cleanName)_mutual_aid_package.zip")
            status = "MA package ready: \(tileManifest.count) tile(s), \(demManifest.count) DEM tile(s)."
        } catch { status = "MA package export failed: \(error.localizedDescription)" }
    }

    func configurationBackupData(
        passphrase: String,
        caltopo: AppleCaltopoSettings,
        organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore
    ) throws -> Data {
        guard passphrase.count >= 8 else { throw TransferError.shortPassphrase }
        let mappings = identities.importedMappings.map {
            ["remote_id": $0.remoteID, "mapped_id": $0.mappedID, "organization": $0.organization,
             "owner": $0.pilotCallsign, "model": $0.droneDescription]
        }
        let payload: [String: Any] = [
            "version": 1,
            "generated": ISO8601DateFormatter().string(from: Date()),
            "caltopo": caltopo.transferSnapshot(),
            "organization": organization.transferSnapshot(),
            "rid_mappings": mappings,
        ]
        let plaintext = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        let salt = Data((0 ..< 16).map { _ in UInt8.random(in: .min ... .max) })
        let key = SymmetricKey(data: SHA256.hash(data: salt + Data(passphrase.utf8)))
        let sealed = try AES.GCM.seal(plaintext, using: key)
        guard let combined = sealed.combined else { throw CocoaError(.fileWriteUnknown) }
        let envelope: [String: Any] = [
            "format": Self.backupFormat, "version": 1,
            "salt": salt.base64EncodedString(), "sealed": combined.base64EncodedString(),
        ]
        return try JSONSerialization.data(withJSONObject: envelope, options: [.prettyPrinted, .sortedKeys])
    }

    func restoreConfigurationBackup(
        _ data: Data,
        passphrase: String,
        caltopo: AppleCaltopoSettings,
        organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore
    ) throws {
        guard let envelope = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              envelope["format"] as? String == Self.backupFormat,
              let saltText = envelope["salt"] as? String,
              let salt = Data(base64Encoded: saltText),
              let sealedText = envelope["sealed"] as? String,
              let combined = Data(base64Encoded: sealedText)
        else { throw TransferError.invalidBackup }
        let key = SymmetricKey(data: SHA256.hash(data: salt + Data(passphrase.utf8)))
        let box = try AES.GCM.SealedBox(combined: combined)
        let plaintext = try AES.GCM.open(box, using: key)
        guard let payload = try JSONSerialization.jsonObject(with: plaintext) as? [String: Any],
              let caltopoObject = payload["caltopo"] as? [String: Any],
              let orgObject = payload["organization"] as? [String: Any]
        else { throw TransferError.invalidBackup }
        try organization.applyTransferSnapshot(orgObject)
        try caltopo.applyTransferSnapshot(caltopoObject)
        let mappings = (payload["rid_mappings"] as? [[String: Any]] ?? []).compactMap { item -> OrgConfigRIDMapping? in
            guard let remoteID = item["remote_id"] as? String, !remoteID.isEmpty else { return nil }
            return OrgConfigRIDMapping(
                remoteID: remoteID,
                mappedID: item["mapped_id"] as? String ?? "",
                organization: item["organization"] as? String ?? "",
                model: item["model"] as? String ?? "",
                owner: item["owner"] as? String ?? ""
            )
        }
        identities.applyImportedMappings(mappings)
        status = "Configuration restored. Reconnect tracker and CalTopo services to apply it."
    }

    private func importMutualAidPackage(
        _ data: Data,
        caltopo: AppleCaltopoSettings,
        organization: AppleOrgConfigSettings
    ) throws {
        let decoded = try OperationalZipArchive.decode(data)
        let lookup = Dictionary(uniqueKeysWithValues: decoded.map { ($0.path, $0.data) })
        guard let manifestData = lookup["manifest.json"],
              let manifest = try JSONSerialization.jsonObject(with: manifestData) as? [String: Any],
              manifest["format"] as? String == Self.packageFormat,
              let profileEncrypted = manifest["profile_enc"] as? String
        else { throw TransferError.invalidPackage }
        let profile = try AndroidConfigTokenCodec.decryptMutualAidProfile(profileEncrypted)
        if profile.expiresAtEpochMilliseconds > 0,
           Date().timeIntervalSince1970 * 1_000 >= Double(profile.expiresAtEpochMilliseconds) {
            throw TransferError.expiredPackage
        }
        let active = try AppleCaltopoProfileLifecycle.shared.install(
            profile,
            org: organization,
            caltopo: caltopo
        )
        guard active else { throw TransferError.expiredPackage }
        try organization.apply(mutualAid: profile, normalizedToken: "local-ma-package")
        try caltopo.applyImported(mutualAid: profile)
        var importedTiles = 0
        for item in manifest["tile_entries"] as? [[String: Any]] ?? [] {
            guard let path = item["path"] as? String, let bytes = lookup[path],
                  let z = (item["z"] as? NSNumber)?.intValue,
                  let x = (item["x"] as? NSNumber)?.intValue,
                  let y = (item["y"] as? NSNumber)?.intValue else { continue }
            let layer: OperationalMapBaseLayer = (item["source"] as? String) == "ArcGIS-WorldImagery" ? .imagery : .openStreetMap
            let destination = AppleMapCachePaths.tile(.init(zoom: z, x: x, y: y), layerKey: layer.cacheKey, fileExtension: layer.fileExtension)
            let bytesAdded = try AppleMapCacheAccess.write(bytes, to: destination)
            AppleMapOfflineManager.noteTileCached(bytes: Int(bytesAdded))
            importedTiles += 1
        }
        var importedDEM = 0
        for item in manifest["dem_entries"] as? [[String: Any]] ?? [] {
            guard let path = item["path"] as? String, let bytes = lookup[path],
                  let fileName = item["file_name"] as? String,
                  !fileName.contains("/") && !fileName.contains("..") else { continue }
            let destination = AppleMapCachePaths.demRoot.appendingPathComponent(fileName)
            _ = try AppleMapCacheAccess.write(bytes, to: destination)
            importedDEM += 1
        }
        status = "Imported MA package: \(importedTiles) tile(s), \(importedDEM) DEM tile(s)."
    }

    private func writeExport(_ data: Data, name: String) throws -> URL {
        let root = (FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory)
            .appendingPathComponent("RID2Caltopo/Exports", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let destination = root.appendingPathComponent(name)
        try data.write(to: destination, options: .atomic)
        return destination
    }

    private func stamp() -> String {
        let formatter = DateFormatter(); formatter.dateFormat = "yyyyMMdd_HHmmss"
        return formatter.string(from: Date())
    }

    private func sanitize(_ value: String) -> String {
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
        case .invalidPackage: "The selected archive is not an Android-compatible mutual-aid package."
        case .expiredPackage: "The mutual-aid package has expired."
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
            let data = try transfer.configurationBackupData(
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
            try transfer.restoreConfigurationBackup(
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

struct AppleConfigurationTransferView: View {
    @ObservedObject var caltopo: AppleCaltopoSettings
    @ObservedObject var organization: AppleOrgConfigSettings
    @ObservedObject var identities: AppleDroneConfirmationStore
    @StateObject private var manager = AppleConfigurationTransferManager()
    @ObservedObject private var iCloud = AppleICloudBackupCenter.shared
    @State private var passphrase = ""
    @State private var importing = false

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
            Section("Encrypted local backup") {
                SecureField("Backup passphrase", text: $passphrase)
                Text("The file includes operational configuration, Remote ID mappings, and credentials. It is encrypted with this passphrase; RID2Caltopo cannot recover a forgotten passphrase.")
                    .font(.footnote).foregroundStyle(.secondary)
                Button("Prepare Configuration Backup", systemImage: "lock.doc") {
                    manager.prepareBackup(passphrase: passphrase, caltopo: caltopo, organization: organization, identities: identities)
                }
                .disabled(manager.isWorking)
                if let url = manager.exportURL {
                    ShareLink(item: url) { Label("Share Prepared File", systemImage: "square.and.arrow.up") }
                }
            }
            Section("Restore or join") {
                Button("Import Backup or MA Package…", systemImage: "square.and.arrow.down") { importing = true }
                    .disabled(manager.isWorking)
                Text("Configuration backups require their passphrase. Android-compatible mutual-aid packages install their incident profile plus cached map and DEM data without a passphrase.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Status") {
                if manager.isWorking { ProgressView() }
                Text(manager.status).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Backup & Transfer")
        .task { iCloud.configure(caltopo: caltopo, organization: organization, identities: identities) }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .zip, .data]) { result in
            if case let .success(url) = result {
                Task { await manager.importFile(url, passphrase: passphrase, caltopo: caltopo, organization: organization, identities: identities) }
            }
        }
    }
}

struct AppleMutualAidExportView: View {
    let bounds: OperationalMapBounds
    let layer: OperationalMapBaseLayer
    let caltopo: AppleCaltopoConfiguration
    @ObservedObject var organization: AppleOrgConfigSettings
    @StateObject private var manager = AppleConfigurationTransferManager()
    @State private var preset = OperationalOfflinePreset.operations
    @State private var includeDEM = true
    @State private var packageName = "Mutual Aid"
    @State private var displayName = "Mutual Aid"
    @State private var expiresAt = Calendar.current.date(byAdding: .day, value: 1, to: Date()) ?? Date()

    var body: some View {
        NavigationStack {
            Form {
                Section("Package") {
                    TextField("Package name", text: $packageName)
                    TextField("Display name", text: $displayName)
                    DatePicker("Expires", selection: $expiresAt, in: Date()...)
                    Picker("Map detail", selection: $preset) {
                        ForEach(OperationalOfflinePreset.all) { Text($0.label).tag($0) }
                    }
                    Toggle("Include cached DEM", isOn: $includeDEM)
                }
                Section("Source") {
                    LabeledContent("Organization", value: organization.mutualAidTemplate?.sourceLabel ?? "MA credentials not loaded")
                    LabeledContent("Incident", value: organization.incident)
                    LabeledContent("Map", value: caltopo.mapID.isEmpty ? "Not selected" : caltopo.mapID)
                    LabeledContent("Base layer", value: layer.label)
                }
                Section {
                    Button("Prepare MA Package", systemImage: "shippingbox") {
                        Task {
                            await manager.prepareMutualAidPackage(
                                bounds: bounds, preset: preset, layer: layer, includeDEM: includeDEM,
                                packageName: packageName, displayName: displayName, expiresAt: expiresAt,
                                caltopo: caltopo, organization: organization
                            )
                        }
                    }
                    .disabled(manager.isWorking || caltopo.mapID.isEmpty || organization.mutualAidTemplate == nil)
                    if let url = manager.exportURL { ShareLink(item: url) { Label("Share MA Package", systemImage: "square.and.arrow.up") } }
                    if manager.isWorking { ProgressView() }
                    Text(manager.status).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Export MA Package")
        }
    }
}
