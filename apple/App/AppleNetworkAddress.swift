import Darwin
import Combine
import CryptoKit
import Foundation
import Network
import NetworkExtension
import R2CCore
import Security
import UIKit

enum AppleNetworkAddress {
    static func preferredIPv4Address(for path: NWPath? = nil) -> String? {
        let candidates = ipv4Candidates()
        let availableInterfaces = path?.availableInterfaces ?? []
        let reportedWiFiNames = availableInterfaces
            .filter { $0.type == .wifi }
            .map(\.name)
        let wifiNames = reportedWiFiNames.isEmpty
            ? candidates.map(\.name).filter { $0 == "en0" || $0.hasPrefix("en") }
            : reportedWiFiNames
        let wiredNames = availableInterfaces
            .filter { $0.type == .wiredEthernet }
            .map(\.name)
        return ControllerIPv4Selection.preferredAddress(
            candidates: candidates.map {
                ControllerIPv4Candidate(interfaceName: $0.name, address: $0.address)
            },
            wifiInterfaceNames: wifiNames,
            wiredInterfaceNames: wiredNames
        )
    }

    static func ipv4DiagnosticSummary() -> String {
        let candidates = ipv4Candidates()
        guard !candidates.isEmpty else { return "none" }
        return candidates.map { "\($0.name)=\($0.address)" }.joined(separator: ",")
    }

    private static func ipv4Candidates() -> [(name: String, address: String)] {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let first = interfaces else { return [] }
        defer { freeifaddrs(interfaces) }

        var candidates: [(name: String, address: String)] = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let interface = cursor?.pointee {
            defer { cursor = interface.ifa_next }
            guard let socketAddress = interface.ifa_addr,
                  socketAddress.pointee.sa_family == UInt8(AF_INET),
                  interface.ifa_flags & UInt32(IFF_UP) != 0,
                  interface.ifa_flags & UInt32(IFF_LOOPBACK) == 0
            else { continue }

            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(
                socketAddress,
                socklen_t(socketAddress.pointee.sa_len),
                &host,
                socklen_t(host.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            guard result == 0 else { continue }
            let address = String(
                decoding: host.lazy.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) },
                as: UTF8.self
            )
            candidates.append((
                name: String(cString: interface.ifa_name),
                address: address
            ))
        }
        return candidates
    }

    static func currentWiFiSSID() async -> String? {
        await withCheckedContinuation { continuation in
            NEHotspotNetwork.fetchCurrent { network in
                let ssid = network?.ssid.trimmingCharacters(in: .whitespacesAndNewlines)
                continuation.resume(returning: ssid?.isEmpty == false ? ssid : nil)
            }
        }
    }
}

/// Event-driven network diagnostics. NWPathMonitor supplies changes; no polling or probes are used.
@MainActor
final class AppleNetworkDiagnosticCenter: ObservableObject {
    enum RefreshReason: String {
        case networkPathChanged = "path_changed"
        case locationAuthorizationChanged = "location_authorization_changed"
        case applicationBecameActive = "application_became_active"
    }

    static let shared = AppleNetworkDiagnosticCenter()

    @Published private(set) var currentSnapshotID = "none"
    @Published private(set) var currentWiFiSSID: String?
    @Published private(set) var currentControllerIPv4Address: String?

    private var monitor: NWPathMonitor?
    private var latestPath: NWPath?
    private let monitorQueue = DispatchQueue(label: "org.ncssar.rid2caltopo.network-diagnostics")
    private var previousTransitionKey: String?
    private var nextSnapshotNumber = 1

    func start() {
        guard monitor == nil else { return }
        let pathMonitor = NWPathMonitor()
        monitor = pathMonitor
        pathMonitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.latestPath = path
                await self.record(path: path, reason: .networkPathChanged)
            }
        }
        pathMonitor.start(queue: monitorQueue)
    }

    func stop() {
        monitor?.cancel()
        monitor = nil
        latestPath = nil
    }

    /// Re-reads Wi-Fi identity even when the network path itself has not changed.
    /// This is required after Location authorization changes because iOS may have
    /// returned no SSID for the same path before permission was granted.
    func refresh(reason: RefreshReason) async {
        guard let latestPath else { return }
        await record(path: latestPath, reason: reason)
    }

    private func record(path: NWPath, reason requestedReason: RefreshReason) async {
        let ssid = await AppleNetworkAddress.currentWiFiSSID()
        let interfaces = Self.interfaceSummary(path)
        let ipv4 = AppleNetworkAddress.ipv4DiagnosticSummary()
        let controllerIPv4 = AppleNetworkAddress.preferredIPv4Address(for: path)
        let status = Self.statusSummary(path.status)
        let bssidHash = await Self.currentBSSIDHash()
        let transitionKey = [
            ssid ?? "unavailable",
            bssidHash,
            ipv4,
            controllerIPv4 ?? "unavailable",
            status,
            interfaces,
            String(path.isExpensive),
            String(path.isConstrained),
        ].joined(separator: "|")
        guard transitionKey != previousTransitionKey else { return }

        let reason = previousTransitionKey == nil ? "startup" : requestedReason.rawValue
        previousTransitionKey = transitionKey
        currentWiFiSSID = ssid
        currentControllerIPv4Address = controllerIPv4
        currentSnapshotID = "net-\(nextSnapshotNumber)"
        nextSnapshotNumber += 1
        AppleLog.info(
            "NetworkDiagnostics",
            "Network snapshotId=\(currentSnapshotID) reason=\(reason) " +
                "ssid=\(ssid ?? "unavailable") bssidHash=\(bssidHash) ipv4=\(ipv4) " +
                "wifiRssiDbm=unavailable status=\(status) interfaces=\(interfaces) " +
                "expensive=\(path.isExpensive) constrained=\(path.isConstrained)"
        )
    }

    private static func currentBSSIDHash() async -> String {
        await withCheckedContinuation { continuation in
            NEHotspotNetwork.fetchCurrent { network in
                guard let bssid = network?.bssid, !bssid.isEmpty else {
                    continuation.resume(returning: "unavailable")
                    return
                }
                let digest = SHA256.hash(data: Data(bssid.utf8))
                continuation.resume(returning: digest.prefix(6).map { String(format: "%02x", $0) }.joined())
            }
        }
    }

    private static func interfaceSummary(_ path: NWPath) -> String {
        let types: [(NWInterface.InterfaceType, String)] = [
            (.wifi, "wifi"), (.wiredEthernet, "ethernet"), (.cellular, "cellular"),
            (.loopback, "loopback"), (.other, "other"),
        ]
        let active = types.compactMap { path.usesInterfaceType($0.0) ? $0.1 : nil }
        return active.isEmpty ? "none" : active.joined(separator: ",")
    }

    private static func statusSummary(_ status: NWPath.Status) -> String {
        switch status {
        case .satisfied: "satisfied"
        case .unsatisfied: "unsatisfied"
        case .requiresConnection: "requires_connection"
        @unknown default: "unknown"
        }
    }
}

enum AppleDeviceIdentity {
    static let storedNameKey = "device.stableDisplayName"
    static let managedNameKey = "device.managedDisplayName"
    static let installationIDKey = "tracker.zoneID"
    private static let installationIDKeychainService = "org.ncssar.rid2caltopo.device-identity"
    private static let installationIDKeychainAccount = "tracker.installation-id"

    static func installationID(defaults: UserDefaults = .standard) -> String {
        if let existing = keychainInstallationID(), !existing.isEmpty {
            if defaults.string(forKey: installationIDKey) != existing {
                defaults.set(existing, forKey: installationIDKey)
            }
            return existing
        }
        if let existing = defaults.string(forKey: installationIDKey), !existing.isEmpty {
            storeKeychainInstallationID(existing)
            return existing
        }
        let value = UUID().uuidString.lowercased()
        defaults.set(value, forKey: installationIDKey)
        storeKeychainInstallationID(value)
        return value
    }

    private static func keychainInstallationID() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: installationIDKeychainService,
            kSecAttrAccount as String: installationIDKeychainAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func storeKeychainInstallationID(_ value: String) {
        let key: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: installationIDKeychainService,
            kSecAttrAccount as String: installationIDKeychainAccount,
        ]
        let data = Data(value.utf8)
        if SecItemUpdate(
            key as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        ) == errSecSuccess { return }
        var insert = key
        insert[kSecValueData as String] = data
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(insert as CFDictionary, nil)
    }

    @MainActor
    static var displayName: String {
        let defaults = UserDefaults.standard
        if let managed = defaults.string(forKey: managedNameKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines), !managed.isEmpty {
            return managed
        }
        let stored = defaults.string(forKey: storedNameKey)
        let userAssignedName = UIDevice.current.name
        let resolved = OperationalDeviceName.preferredDisplayName(
            stored: stored,
            userAssigned: userAssignedName,
            hostname: ProcessInfo.processInfo.hostName
        )
        if stored?.trimmingCharacters(in: .whitespacesAndNewlines) != resolved {
            defaults.set(resolved, forKey: storedNameKey)
        }
        return resolved
    }

    static func applyManagedDisplayName(_ value: String, defaults: UserDefaults = .standard) {
        let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty {
            defaults.removeObject(forKey: managedNameKey)
        } else {
            defaults.set(clean, forKey: managedNameKey)
        }
    }

    static var modelName: String {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { bytes in
            String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self)
        }
        return OperationalDeviceModelName.apple(
            machineIdentifier: machine,
            fallback: "Apple device"
        )
    }

    static func displayName(fromHostname hostname: String) -> String {
        OperationalDeviceName.displayName(fromHostname: hostname) ?? "iPad"
    }
}

enum AppleBuildMetadata {
    static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
    }

    static var artifactDate: Date? {
        guard let executableURL = Bundle.main.executableURL,
              let values = try? executableURL.resourceValues(forKeys: [.contentModificationDateKey])
        else { return nil }
        return values.contentModificationDate
    }

    static var buildTime: String {
        guard let artifactDate else { return "unknown" }
        return RidBuildMetadata.formattedBuildTime(artifactDate)
    }
}
