import CoreLocation
import Foundation
import R2CCore
import SwiftUI

struct AppleNotamState: Equatable {
    var visible = false
    var enabled = false
    var configured = false
    var loading = false
    var stale = false
    var chipSeverity: OperationalNotamSeverity = .neutral
    var chipLabel = "NOTAMs unavailable"
    var statusLine = ""
    var lastUpdated: Date?
    var queryCoordinate: OperationalNotamCoordinate?
    var radiusStatuteMiles = 1
    var notices: [OperationalNotam] = []
    var suppressedCount = 0
    var errorMessage: String?
}

@MainActor
final class AppleNotamCenter: ObservableObject {
    static let shared = AppleNotamCenter()
    private static let enrollmentActivationMigrationKey = "notam.enrollmentActivationMigration.v1"

    @Published private(set) var state = AppleNotamState()
    @Published var enabled: Bool {
        didSet {
            defaults.set(enabled, forKey: "notam.enabled")
            rebuildState(loading: false, coordinate: state.queryCoordinate, error: state.errorMessage)
        }
    }
    @Published var showOnMap: Bool { didSet { defaults.set(showOnMap, forKey: "notam.showOnMap") } }
    @Published var autoRefresh: Bool { didSet { defaults.set(autoRefresh, forKey: "notam.autoRefresh") } }
    @Published var radiusStatuteMiles: Int {
        didSet { defaults.set(radiusStatuteMiles, forKey: "notam.radiusStatuteMiles") }
    }
    @Published var refreshIntervalSeconds: Int { didSet { defaults.set(refreshIntervalSeconds, forKey: "notam.refreshSeconds") } }

    private let defaults: UserDefaults
    private var lastFetchCoordinate: OperationalNotamCoordinate?
    private var lastAttempt = Date.distantPast
    private var lastSuccessfulNotices: [OperationalNotam] = []
    private var refreshTask: Task<Void, Never>?
    private var refreshGeneration: UInt = 0
    private var proxyURL: URL?
    private var proxyToken = ""

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        enabled = defaults.object(forKey: "notam.enabled") as? Bool ?? false
        showOnMap = defaults.object(forKey: "notam.showOnMap") as? Bool ?? true
        autoRefresh = defaults.object(forKey: "notam.autoRefresh") as? Bool ?? true
        radiusStatuteMiles = max(
            1,
            min(100, defaults.object(forKey: "notam.radiusStatuteMiles") as? Int ?? 1)
        )
        refreshIntervalSeconds = max(
            1_800,
            defaults.object(forKey: "notam.refreshSeconds") as? Int ?? 1_800
        )
        rebuildState(loading: false, coordinate: nil, error: nil)
    }

    func configure(faaProxyURL: String, trackerURLPrefix: String, trackerAPIKey: String) {
        proxyURL = Self.validatedProxyURL(
            faaProxyURL,
            trackerURLPrefix: trackerURLPrefix
        )
        proxyToken = proxyURL == nil
            ? ""
            : trackerAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
        rebuildState(loading: false, coordinate: state.queryCoordinate, error: nil)
    }

    func reconcileEnrollmentActivation(hasNotamAdminConfiguration: Bool) {
        guard !defaults.bool(forKey: Self.enrollmentActivationMigrationKey) else { return }
        if !hasNotamAdminConfiguration {
            enabled = false
        }
        defaults.set(true, forKey: Self.enrollmentActivationMigrationKey)
    }

    func update(location: CLLocation?, force: Bool = false) {
        let coordinate = location.map { OperationalNotamCoordinate(latitude: $0.coordinate.latitude, longitude: $0.coordinate.longitude) }
        guard enabled else {
            rebuildState(loading: false, coordinate: coordinate, error: nil)
            return
        }
        guard force || shouldRefresh(coordinate) else {
            rebuildState(loading: false, coordinate: coordinate, error: state.errorMessage)
            return
        }
        guard refreshTask == nil else { return }
        lastAttempt = Date()
        rebuildState(loading: true, coordinate: coordinate, error: state.errorMessage)
        refreshTask = Task { [weak self] in
            guard let self else { return }
            let generation = refreshGeneration
            defer {
                if generation == refreshGeneration {
                    refreshTask = nil
                }
            }
            do {
                guard let coordinate else { throw AppleNotamError.locationUnavailable }
                guard isConfigured else { throw AppleNotamError.proxyUnavailable }
                let notices = try await fetchNotams(coordinate: coordinate)
                guard generation == refreshGeneration, !Task.isCancelled else { return }
                lastFetchCoordinate = coordinate
                lastSuccessfulNotices = notices
                rebuildState(loading: false, coordinate: coordinate, error: nil, successfulAt: Date())
                AppleLog.info(
                    "NOTAM",
                    "Fetched \(notices.count) notices radiusStatuteMiles=\(radiusStatuteMiles)"
                )
            } catch {
                guard generation == refreshGeneration, !Task.isCancelled else { return }
                let message = error.localizedDescription
                rebuildState(loading: false, coordinate: coordinate, error: message)
                AppleLog.error("NOTAM", message)
            }
        }
    }

    func resetRuntimeState() {
        refreshGeneration &+= 1
        refreshTask?.cancel()
        refreshTask = nil
        proxyURL = nil
        proxyToken = ""
        lastFetchCoordinate = nil
        lastAttempt = .distantPast
        lastSuccessfulNotices = []
        enabled = false
        showOnMap = true
        autoRefresh = true
        radiusStatuteMiles = 1
        refreshIntervalSeconds = 1_800
        state = AppleNotamState(
            visible: true,
            enabled: false,
            configured: false,
            chipSeverity: .neutral,
            chipLabel: "NOTAMs disabled",
            statusLine: "Nearby NOTAM monitoring is disabled.",
            radiusStatuteMiles: 1
        )
    }

    func refreshNow(location: CLLocation?) { update(location: location, force: true) }

    func installSimulatorDemo() {
        enabled = true
        showOnMap = true
        let ring = [
            OperationalNotamCoordinate(latitude: 39.72, longitude: -105.01),
            OperationalNotamCoordinate(latitude: 39.72, longitude: -104.97),
            OperationalNotamCoordinate(latitude: 39.76, longitude: -104.97),
            OperationalNotamCoordinate(latitude: 39.76, longitude: -105.01),
            OperationalNotamCoordinate(latitude: 39.72, longitude: -105.01),
        ]
        let notice = OperationalNotam(
            id: "demo-tfr", title: "Temporary flight restriction",
            summary: "Training TFR intersects the pilot operating area.", distanceNM: 0,
            intersectsPilotArea: true, effectiveText: "Simulator qualification only",
            details: "Deterministic NOTAM/TFR overlay used for Apple UI qualification.",
            rawText: "TFR SFC-400 FT AGL", reference: "FDC DEMO/26", severity: .danger,
            altitudeBand: .init(floorFeetMSL: nil, ceilingFeetMSL: nil, floorLabel: "SFC", ceilingLabel: "400 FT", reference: "AGL"),
            geometries: [.polygon([ring])]
        )
        lastSuccessfulNotices = [notice]
        state = AppleNotamState(
            visible: true, enabled: true, configured: true, loading: false, stale: false,
            chipSeverity: .danger, chipLabel: "NOTAMs: RESTRICTED 0.0 mi",
            statusLine: "1 nearby NOTAM (simulator).", lastUpdated: Date(),
            queryCoordinate: .init(latitude: 39.7392, longitude: -104.9903),
            radiusStatuteMiles: radiusStatuteMiles,
            notices: [notice]
        )
    }

    private func shouldRefresh(_ coordinate: OperationalNotamCoordinate?) -> Bool {
        if state.lastUpdated == nil { return Date().timeIntervalSince(lastAttempt) >= 15 }
        guard autoRefresh else { return false }
        if Date().timeIntervalSince(lastAttempt) >= Double(refreshIntervalSeconds) { return true }
        guard let coordinate, let previous = lastFetchCoordinate else { return false }
        return distanceNM(coordinate, previous) >= 0.25
    }

    private func rebuildState(
        loading: Bool,
        coordinate: OperationalNotamCoordinate?,
        error: String?,
        successfulAt: Date? = nil
    ) {
        let configured = isConfigured
        guard enabled else {
            state = AppleNotamState(
                visible: true,
                enabled: false,
                configured: configured,
                chipSeverity: .neutral,
                chipLabel: "NOTAMs disabled",
                statusLine: "Nearby NOTAM monitoring is disabled.",
                queryCoordinate: coordinate,
                radiusStatuteMiles: radiusStatuteMiles
            )
            return
        }
        let filtered = OperationalNotamPolicy.filtered(
            lastSuccessfulNotices,
            radiusStatuteMiles: radiusStatuteMiles
        )
        let updated = successfulAt ?? state.lastUpdated
        let stale = updated.map { Date().timeIntervalSince($0) > Double(max(refreshIntervalSeconds * 2, 600)) } ?? false
        state = AppleNotamState(
            visible: enabled,
            enabled: enabled,
            configured: configured,
            loading: loading,
            stale: stale,
            chipSeverity: OperationalNotamPolicy.chipSeverity(notices: filtered.visible, configured: configured, hasError: error != nil || stale || updated == nil),
            chipLabel: OperationalNotamPolicy.chipLabel(notices: filtered.visible, configured: configured, loading: loading, hasError: error != nil || stale || updated == nil),
            statusLine: statusLine(configured: configured, loading: loading, count: filtered.visible.count, error: error),
            lastUpdated: updated,
            queryCoordinate: lastFetchCoordinate,
            radiusStatuteMiles: radiusStatuteMiles,
            notices: filtered.visible,
            suppressedCount: filtered.suppressed,
            errorMessage: error
        )
    }

    private var isConfigured: Bool {
        proxyURL != nil && !proxyToken.isEmpty
    }

    private func fetchNotams(
        coordinate: OperationalNotamCoordinate
    ) async throws -> [OperationalNotam] {
        guard let proxyURL,
              var components = URLComponents(url: proxyURL, resolvingAgainstBaseURL: false)
        else {
            throw AppleNotamError.invalidConfiguration
        }
        components.queryItems = [
            URLQueryItem(name: "latitude", value: String(format: "%.6f", coordinate.latitude)),
            URLQueryItem(name: "longitude", value: String(format: "%.6f", coordinate.longitude)),
            URLQueryItem(
                name: "radius",
                value: String(
                    OperationalNotamPolicy.faaQueryRadiusNM(
                        radiusStatuteMiles: radiusStatuteMiles
                    )
                )
            ),
        ]
        guard let url = components.url else { throw AppleNotamError.invalidConfiguration }
        var request = URLRequest(url: url)
        request.timeoutInterval = 45
        request.setValue(proxyToken, forHTTPHeaderField: "X-SAR-Token")
        request.setValue(
            String(TrackerCoordinationClient.trackerFunctionalityRelease),
            forHTTPHeaderField: "X-R2C-Functionality-Release"
        )
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw AppleNotamError.service("No HTTP NOTAM response") }
        guard (200 ..< 300).contains(http.statusCode) else { throw AppleNotamError.service("NOTAM query failed (HTTP \(http.statusCode)).") }
        return try OperationalNotamParser.parseResponse(
            data,
            pilot: coordinate,
            operatingRadiusNM: OperationalFacilityMap.operatingRadiusNM
        )
    }

    private static func validatedProxyURL(
        _ value: String,
        trackerURLPrefix: String
    ) -> URL? {
        guard var tracker = URLComponents(
            string: trackerURLPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        ),
        tracker.scheme?.lowercased() == "https",
        let trackerHost = tracker.host?.lowercased(),
        trackerHost == "r2c-tracker.com" || trackerHost.hasSuffix(".r2c-tracker.com")
        else { return nil }
        let explicit = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let proxy: URLComponents
        if explicit.isEmpty {
            tracker.path = "/faa/notams"
            tracker.query = nil
            tracker.fragment = nil
            proxy = tracker
        } else if let parsed = URLComponents(string: explicit) {
            proxy = parsed
        } else {
            return nil
        }
        guard proxy.scheme?.lowercased() == "https",
        let proxyHost = proxy.host?.lowercased(),
        proxyHost == trackerHost,
        proxy.port == tracker.port,
        proxy.path == "/faa/notams",
        proxyHost == "r2c-tracker.com" || proxyHost.hasSuffix(".r2c-tracker.com")
        else { return nil }
        return proxy.url
    }

    private func statusLine(configured: Bool, loading: Bool, count: Int, error: String?) -> String {
        if loading { return "Refreshing nearby NOTAMs…" }
        if let error { return error }
        if !configured { return "FAA proxy access is unavailable in this build." }
        return count == 0 ? "No nearby NOTAM restrictions found." : "\(count) nearby NOTAM(s)."
    }

    private func distanceNM(_ a: OperationalNotamCoordinate, _ b: OperationalNotamCoordinate) -> Double {
        let left = CLLocation(latitude: a.latitude, longitude: a.longitude)
        return left.distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude)) / 1_852
    }
}

private enum AppleNotamError: LocalizedError {
    case locationUnavailable
    case proxyUnavailable
    case invalidConfiguration
    case service(String)

    var errorDescription: String? {
        switch self {
        case .locationUnavailable: "Waiting for GPS location before querying NOTAMs."
        case .proxyUnavailable: "NOTAM monitoring is enabled, but FAA proxy access is unavailable."
        case .invalidConfiguration: "FAA proxy configuration is invalid."
        case let .service(message): message
        }
    }
}

struct AppleNotamPanel: View {
    @ObservedObject var center: AppleNotamCenter
    let location: CLLocation?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                AppleAirspaceSafetyNotice()
                Section("Status") {
                    Text("Query radius: \(center.state.radiusStatuteMiles) statute mile(s)")
                    if let coordinate = center.state.queryCoordinate { Text(String(format: "Query location: %.5f, %.5f", coordinate.latitude, coordinate.longitude)).font(.caption) }
                    Label(center.state.chipLabel, systemImage: "airplane.departure")
                        .foregroundStyle(color(center.state.chipSeverity))
                    Text(center.state.statusLine).font(.caption).foregroundStyle(.secondary)
                    if let date = center.state.lastUpdated {
                        LabeledContent("Last successful check", value: date.formatted(date: .numeric, time: .standard))
                    }
                    if center.state.stale { Label("Results are stale", systemImage: "clock.badge.exclamationmark").foregroundStyle(.orange) }
                    Button("Refresh Now") { center.refreshNow(location: location) }
                        .disabled(center.state.loading || !center.state.enabled)
                }
                Section("Nearby NOTAMs") {
                    if center.state.notices.isEmpty {
                        Text(
                            !center.state.enabled
                                ? "Nearby NOTAM monitoring is disabled."
                                : (center.state.configured
                                    ? "No notices are displayed. Check query status above; this is not a flight clearance."
                                    : "Load the organization tracker configuration to enable queries.")
                        )
                            .foregroundStyle(.secondary)
                    }
                    ForEach(center.state.notices) { notice in
                        DisclosureGroup {
                            if !notice.effectiveText.isEmpty { Text(notice.effectiveText) }
                            Text(notice.details).textSelection(.enabled)
                            if !notice.reference.isEmpty { LabeledContent("Reference", value: notice.reference) }
                        } label: {
                            VStack(alignment: .leading) {
                                Text(notice.title).fontWeight(.semibold)
                                Text(notice.summary).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    if center.state.suppressedCount > 0 {
                        Text("\(center.state.suppressedCount) notice(s) are outside the selected radius.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                Text("Not flight authorization. Verify restrictions and operational conditions independently.")
                    .font(.caption).padding(8).frame(maxWidth: .infinity).background(.bar)
            }
            .navigationTitle("Nearby NOTAMs")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    private func color(_ severity: OperationalNotamSeverity) -> Color {
        switch severity {
        case .danger: .red
        case .caution: .orange
        case .normal: .secondary
        case .neutral: .secondary
        }
    }
}
