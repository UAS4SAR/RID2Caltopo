import CoreLocation
import Foundation
import R2CCore
import SwiftUI

struct AppleLandRestrictionState: Equatable {
    var visible = false
    var enabled = false
    var loading = false
    var stale = false
    var severity: OperationalLandSeverity = .neutral
    var chipLabel = "Land rules pending"
    var statusLine = "Waiting for location"
    var lastUpdated: Date?
    var areas: [OperationalLandArea] = []
    var sourceErrors: [String] = []
}

@MainActor
final class AppleLandRestrictionCenter: ObservableObject {
    static let shared = AppleLandRestrictionCenter()

    @Published private(set) var state = AppleLandRestrictionState()
    @Published var enabled: Bool { didSet { defaults.set(enabled, forKey: "landRestrictions.enabled") } }
    @Published var showOnMap: Bool { didSet { defaults.set(showOnMap, forKey: "landRestrictions.showOnMap") } }
    @Published var autoRefresh: Bool { didSet { defaults.set(autoRefresh, forKey: "landRestrictions.autoRefresh") } }
    @Published var radiusStatuteMiles: Int {
        didSet { defaults.set(radiusStatuteMiles, forKey: "landRestrictions.radiusStatuteMiles") }
    }

    private let defaults: UserDefaults
    private let cacheURL: URL?
    private var areas: [OperationalLandArea] = []
    private var lastCoordinate: CLLocationCoordinate2D?
    private var lastAttempt = Date.distantPast
    private var lastUpdated: Date?
    private var refreshTask: Task<Void, Never>?
    private let refreshInterval: TimeInterval = 15 * 60

    private init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        cacheURL = Self.makeCacheURL()
        enabled = defaults.object(forKey: "landRestrictions.enabled") as? Bool ?? true
        showOnMap = defaults.object(forKey: "landRestrictions.showOnMap") as? Bool ?? true
        autoRefresh = defaults.object(forKey: "landRestrictions.autoRefresh") as? Bool ?? true
        if let storedRadius = defaults.object(forKey: "landRestrictions.radiusStatuteMiles") as? Int {
            radiusStatuteMiles = max(1, min(50, storedRadius))
        } else if let legacyRadiusNM = defaults.object(forKey: "landRestrictions.radiusNM") as? Int {
            radiusStatuteMiles = max(1, min(50, Int(ceil(Double(legacyRadiusNM) / 0.868976))))
        } else {
            radiusStatuteMiles = 1
        }
        restoreCache()
        rebuild(loading: false, errors: [], waitingForLocation: true)
    }

    func update(location: CLLocation?, force: Bool = false) {
        guard enabled else {
            state = AppleLandRestrictionState(
                visible: true,
                enabled: false,
                chipLabel: "Land rules off",
                statusLine: "Protected-land checks are disabled."
            )
            return
        }
        guard let location else {
            rebuild(
                loading: false,
                errors: state.sourceErrors,
                statusOverride: "Waiting for GPS location",
                waitingForLocation: true
            )
            return
        }
        guard force || shouldRefresh(location) else { return }
        guard refreshTask == nil else { return }
        lastAttempt = Date()
        rebuild(loading: true, errors: state.sourceErrors)
        let coordinate = OperationalLandCoordinate(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude
        )
        refreshTask = Task { [weak self] in
            guard let self else { return }
            defer { refreshTask = nil }
            var fetched: [OperationalLandArea] = []
            var errors: [String] = []
            for source in OperationalLandRestriction.sources {
                do {
                    guard let url = OperationalLandRestriction.queryURL(
                        source: source,
                        center: coordinate,
                        radiusStatuteMiles: Double(radiusStatuteMiles)
                    ) else { throw URLError(.badURL) }
                    var request = URLRequest(url: url)
                    request.timeoutInterval = 40
                    request.setValue("RID2Caltopo/Apple (contact: kjt@uas4sar.com)", forHTTPHeaderField: "User-Agent")
                    let (data, response) = try await URLSession.shared.data(for: request)
                    guard let http = response as? HTTPURLResponse, (200 ..< 300).contains(http.statusCode) else {
                        throw AppleLandRestrictionError.service("HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)")
                    }
                    fetched += try OperationalLandRestriction.parse(
                        data,
                        source: source,
                        center: coordinate,
                        operatingRadiusNM: 1
                    )
                } catch {
                    errors.append("\(source.agency.name): \(error.localizedDescription)")
                }
            }
            if !fetched.isEmpty || errors.count < OperationalLandRestriction.sources.count {
                areas = Self.deduplicated(fetched)
                lastCoordinate = location.coordinate
                lastUpdated = Date()
                saveCache()
            }
            rebuild(loading: false, errors: errors)
            AppleLog.info(
                "LandRules",
                "Loaded \(areas.count) protected-land area(s); sourceErrors=\(errors.count)"
            )
        }
    }

    func refreshNow(location: CLLocation?) { update(location: location, force: true) }

    private func shouldRefresh(_ location: CLLocation) -> Bool {
        guard autoRefresh else { return areas.isEmpty && Date().timeIntervalSince(lastAttempt) >= 30 }
        if lastUpdated == nil { return Date().timeIntervalSince(lastAttempt) >= 15 }
        if Date().timeIntervalSince(lastAttempt) >= refreshInterval { return true }
        guard let lastCoordinate else { return true }
        return location.distance(from: CLLocation(latitude: lastCoordinate.latitude, longitude: lastCoordinate.longitude)) >= 926
    }

    private func rebuild(
        loading: Bool,
        errors: [String],
        statusOverride: String? = nil,
        waitingForLocation: Bool = false
    ) {
        let nearby = areas.filter(\.intersectsOperatingArea)
        let stale = lastUpdated.map { Date().timeIntervalSince($0) > 24 * 60 * 60 } ?? false
        state = AppleLandRestrictionState(
            visible: enabled,
            enabled: enabled,
            loading: loading,
            stale: stale,
            severity: OperationalLandRestriction.severity(
                nearby,
                hasError: !errors.isEmpty,
                waitingForLocation: waitingForLocation
            ),
            chipLabel: OperationalLandRestriction.chipLabel(
                nearby,
                loading: loading,
                hasError: !errors.isEmpty,
                waitingForLocation: waitingForLocation
            ),
            statusLine: statusOverride ?? statusLine(nearby: nearby, loading: loading, errors: errors),
            lastUpdated: lastUpdated,
            areas: nearby,
            sourceErrors: errors
        )
    }

    private func statusLine(nearby: [OperationalLandArea], loading: Bool, errors: [String]) -> String {
        if loading { return "Checking federal and Colorado protected lands…" }
        if nearby.isEmpty, !errors.isEmpty { return "Some protected-land sources could not be checked." }
        if nearby.isEmpty { return "No protected-land boundaries intersect the one-mile operating area." }
        return "\(nearby.count) protected-land area(s) intersect or border the operating area."
    }

    private struct Cache: Codable {
        let updated: Date
        let latitude: Double?
        let longitude: Double?
        let areas: [OperationalLandArea]
    }

    private func restoreCache() {
        guard let cacheURL,
              let data = try? Data(contentsOf: cacheURL),
              let cache = try? JSONDecoder().decode(Cache.self, from: data)
        else { return }
        lastUpdated = cache.updated
        areas = cache.areas
        if let latitude = cache.latitude, let longitude = cache.longitude {
            lastCoordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        }
    }

    private func saveCache() {
        let cache = Cache(
            updated: lastUpdated ?? Date(),
            latitude: lastCoordinate?.latitude,
            longitude: lastCoordinate?.longitude,
            areas: areas
        )
        guard let cacheURL else { return }
        do {
            try JSONEncoder().encode(cache).write(to: cacheURL, options: .atomic)
        } catch {
            AppleLog.warning("LandRules", "Could not save protected-land cache: \(error.localizedDescription)")
        }
    }

    private static func makeCacheURL() -> URL? {
        guard let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            return nil
        }
        let directory = root.appending(path: "RID2Caltopo", directoryHint: .isDirectory)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            return directory.appending(path: "LandRestrictions.json")
        } catch {
            AppleLog.warning("LandRules", "Could not create protected-land cache directory: \(error.localizedDescription)")
            return nil
        }
    }

    private static func deduplicated(_ values: [OperationalLandArea]) -> [OperationalLandArea] {
        var seen = Set<String>()
        return values.filter { seen.insert($0.id).inserted }.sorted {
            if $0.containsOperator != $1.containsOperator { return $0.containsOperator }
            if $0.distanceNM != $1.distanceNM { return $0.distanceNM < $1.distanceNM }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }
}

private enum AppleLandRestrictionError: LocalizedError {
    case service(String)
    var errorDescription: String? { switch self { case let .service(message): message } }
}

struct AppleLandRestrictionPanel: View {
    @ObservedObject var center: AppleLandRestrictionCenter
    let location: CLLocation?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Verify land access") {
                    Text("Mapped sources may be incomplete or outdated. No mapped restrictions does not mean permission to launch, land, or fly. Confirm current rules and permissions with the landowner or responsible agency.").fontWeight(.semibold)
                }
                Section("One-mile operating area") {
                    Label(center.state.chipLabel, systemImage: "leaf.circle")
                        .foregroundStyle(severityColor)
                    Text(center.state.statusLine).font(.caption).foregroundStyle(.secondary)
                    if let date = center.state.lastUpdated {
                        LabeledContent("Updated", value: date.formatted(date: .abbreviated, time: .standard))
                    }
                    if center.state.stale {
                        Label("Cached results are more than 24 hours old", systemImage: "clock.badge.exclamationmark")
                            .foregroundStyle(.orange)
                    }
                    Button("Refresh Now") { center.refreshNow(location: location) }
                        .disabled(center.state.loading)
                }

                ForEach(OperationalLandAgency.allCases, id: \.self) { agency in
                    let agencyAreas = center.state.areas.filter { $0.agency == agency }
                    if !agencyAreas.isEmpty {
                        Section(agency.name) {
                            Link("Agency UAS rules and contact information", destination: agency.rulesURL)
                            ForEach(agencyAreas) { area in
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(area.name).fontWeight(.semibold)
                                    Text(area.rule.label).font(.caption).foregroundStyle(.orange)
                                    Text(area.containsOperator ? "Operator is inside this mapped boundary." : String(format: "Boundary %.1f NM away.", area.distanceNM))
                                        .font(.caption).foregroundStyle(.secondary)
                                    if let detailsURL = area.detailsURL {
                                        Link("Open property details", destination: detailsURL)
                                    }
                                }
                            }
                        }
                    }
                }

                Section("How to interpret this check") {
                    Text("These agency boundaries describe land-management rules. They do not replace FAA airspace, NOTAM, TFR, LAANC, or agency authorization checks.")
                    Text("A boundary warning does not automatically mean that overflight is prohibited. Confirm the displayed rule with the responsible agency before operating.")
                }

                if !center.state.sourceErrors.isEmpty {
                    Section("Source diagnostics") {
                        ForEach(center.state.sourceErrors, id: \.self) { Text($0).font(.caption) }
                    }
                }
            }
            .navigationTitle("Land / Agency Rules")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    private var severityColor: Color {
        switch center.state.severity {
        case .danger: .red
        case .caution: .orange
        case .normal: .secondary
        case .neutral: .secondary
        }
    }
}
