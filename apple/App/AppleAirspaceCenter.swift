import CoreLocation
import Foundation
import R2CCore
import SwiftUI

@MainActor
final class AppleAirspaceCenter: ObservableObject {
    static let shared = AppleAirspaceCenter()

    @Published private(set) var state = OperationalAirspaceState()
    @Published var enabled: Bool { didSet { defaults.set(enabled, forKey: "airspace.enabled") } }
    @Published var autoRefresh: Bool { didSet { defaults.set(autoRefresh, forKey: "airspace.autoRefresh") } }

    private let defaults: UserDefaults
    private var records: [OperationalFacilityMapRecord] = []
    private var lastAttempt = Date.distantPast
    @Published private(set) var lastSuccessfulCheck: Date?
    private var lastCoordinate: CLLocationCoordinate2D?
    private var refreshTask: Task<Void, Never>?
    private var hasCompletedRefresh = false

    private init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        enabled = defaults.object(forKey: "airspace.enabled") as? Bool ?? true
        autoRefresh = defaults.object(forKey: "airspace.autoRefresh") as? Bool ?? true
    }

    func update(location: CLLocation?, force: Bool = false) {
        guard enabled else {
            records = []
            lastCoordinate = nil
            lastSuccessfulCheck = nil
            hasCompletedRefresh = false
            publish(.init(chipLabel: "Airspace off"))
            return
        }
        guard let location else {
            publish(OperationalFacilityMap.state(
                records: records, loading: false,
                errorMessage: "Waiting for GPS location",
                pilotCoordinate: nil
            ))
            return
        }
        guard force || shouldRefresh(location) else {
            if let lastSuccessfulCheck, Date().timeIntervalSince(lastSuccessfulCheck) > 180 {
                publish(OperationalFacilityMap.state(records: records, loading: false, errorMessage: "Last successful facility-map check is over 3 minutes old. Refresh before relying on these results.", pilotCoordinate: .init(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude)))
            }
            return
        }
        guard refreshTask == nil else { return }
        lastAttempt = Date()
        if !hasCompletedRefresh {
            publish(OperationalFacilityMap.state(
                records: records,
                loading: true,
                errorMessage: state.errorMessage,
                pilotCoordinate: .init(
                    latitude: location.coordinate.latitude,
                    longitude: location.coordinate.longitude
                )
            ))
        }
        refreshTask = Task { [weak self] in
            guard let self else { return }
            defer { refreshTask = nil }
            do {
                guard let url = OperationalFacilityMap.queryURL(
                    latitude: location.coordinate.latitude,
                    longitude: location.coordinate.longitude
                ) else { throw URLError(.badURL) }
                var request = URLRequest(url: url)
                request.timeoutInterval = 30
                request.setValue("RID2Caltopo/Apple (contact: kjt@uas4sar.com)", forHTTPHeaderField: "User-Agent")
                let (data, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse, (200 ..< 300).contains(http.statusCode) else {
                    throw AppleAirspaceError.service("Controlled-airspace lookup failed.")
                }
                records = try OperationalFacilityMap.parse(data)
                lastCoordinate = location.coordinate
                lastSuccessfulCheck = Date()
                hasCompletedRefresh = true
                publish(OperationalFacilityMap.state(
                    records: records,
                    loading: false,
                    errorMessage: nil,
                    pilotCoordinate: .init(
                        latitude: location.coordinate.latitude,
                        longitude: location.coordinate.longitude
                    )
                ))
                AppleLog.info("Airspace", "FAA Facility Map returned \(records.count) record(s)")
            } catch {
                hasCompletedRefresh = true
                publish(OperationalFacilityMap.state(
                    records: records,
                    loading: false,
                    errorMessage: error.localizedDescription,
                    pilotCoordinate: .init(
                        latitude: location.coordinate.latitude,
                        longitude: location.coordinate.longitude
                    )
                ))
                AppleLog.error("Airspace", error.localizedDescription)
            }
        }
    }

    var queryLocationText: String {
        guard let lastCoordinate else { return "No successful query location" }
        return String(format: "%.5f, %.5f • radius: 1 statute mile", lastCoordinate.latitude, lastCoordinate.longitude)
    }

    func refreshNow(location: CLLocation?) { update(location: location, force: true) }

    func installSimulatorDemo() {
        enabled = true
        records = [.init(
            objectID: 1, ceilingFeet: 200, unit: "FEET",
            primaryAirportFAAID: "DEN", primaryAirportICAO: "KDEN",
            primaryAirportName: "Denver International Airport (DEN)",
            laancAvailable: true, airspaceClasses: ["B"],
            rings: [[
                .init(latitude: 39.735, longitude: -105.000),
                .init(latitude: 39.745, longitude: -105.000),
                .init(latitude: 39.745, longitude: -104.986),
                .init(latitude: 39.735, longitude: -104.986),
                .init(latitude: 39.735, longitude: -105.000),
            ]]
        )]
        hasCompletedRefresh = true
        publish(OperationalFacilityMap.state(
            records: records,
            loading: false,
            errorMessage: nil,
            pilotCoordinate: .init(latitude: 39.740, longitude: -104.993)
        ))
    }

    private func shouldRefresh(_ location: CLLocation) -> Bool {
        guard autoRefresh else { return records.isEmpty }
        if Date().timeIntervalSince(lastAttempt) >= 60 { return true }
        guard let lastCoordinate else { return records.isEmpty }
        return location.distance(from: CLLocation(latitude: lastCoordinate.latitude, longitude: lastCoordinate.longitude)) >= 402
    }

    private func publish(_ newState: OperationalAirspaceState) {
        guard state != newState else { return }
        state = newState
    }
}

private enum AppleAirspaceError: LocalizedError {
    case service(String)
    var errorDescription: String? { switch self { case let .service(message): message } }
}

struct AppleAirspacePanel: View {
    @ObservedObject var center: AppleAirspaceCenter
    @ObservedObject var notams: AppleNotamCenter
    let location: CLLocation?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                AppleAirspaceSafetyNotice()
                Section("One-mile operating area") {
                    Label(center.state.chipLabel, systemImage: "airplane.circle")
                        .foregroundStyle(severityColor)
                    Text("Last successful facility-map check: " + (center.lastSuccessfulCheck?.formatted(date: .numeric, time: .standard) ?? "None this session")).font(.caption)
                    Text("Query location: " + center.queryLocationText).font(.caption)
                    if let error = center.state.errorMessage { Text(error).foregroundStyle(.orange) }
                    if !center.state.summary.isEmpty { Text(center.state.summary) }
                    if !center.state.detail.isEmpty { Text(center.state.detail).font(.caption).foregroundStyle(.secondary) }
                    Button("Refresh Now") {
                        center.refreshNow(location: location)
                        notams.refreshNow(location: location)
                    }
                    .disabled(center.state.loading || notams.state.loading)
                }
                Section("FAA UAS Facility Map") {
                    if center.state.records.isEmpty { Text("No facility-map grids returned.").foregroundStyle(.secondary) }
                    ForEach(facilityMapDisplayGroups) { group in
                        let record = group.record
                        VStack(alignment: .leading, spacing: 4) {
                            Text(record.primaryAirportName.isEmpty ? "Controlled airspace" : record.primaryAirportName).fontWeight(.semibold)
                            Text(record.airspaceClasses.map { "Class \($0)" }.joined(separator: ", "))
                            LabeledContent("FAA grid limit", value: record.ceilingFeet.map { "\($0) ft AGL" } ?? "Not published")
                            LabeledContent("LAANC", value: record.laancAvailable ? "Available—authorization required" : "Unavailable—authorization required")
                            if !record.primaryAirportFAAID.isEmpty { LabeledContent("Airport", value: record.primaryAirportFAAID) }
                            if group.gridCount > 1 {
                                LabeledContent("Matching grid cells", value: "\(group.gridCount)")
                            }
                        }
                    }
                }
                Section("Nearby NOTAMs") {
                    Label(notams.state.chipLabel, systemImage: "airplane.departure")
                        .foregroundStyle(notamSeverityColor)
                    Text("Query radius: \(notams.state.radiusStatuteMiles) statute mile(s)").font(.caption)
                    if let coordinate = notams.state.queryCoordinate { Text(String(format: "Query location: %.5f, %.5f", coordinate.latitude, coordinate.longitude)).font(.caption) }
                    Text(notams.state.statusLine)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if let date = notams.state.lastUpdated {
                        LabeledContent("Last successful check", value: date.formatted(date: .numeric, time: .standard))
                    }
                    if notams.state.notices.isEmpty {
                        Text(notamEmptyMessage).foregroundStyle(.secondary)
                    }
                    ForEach(notams.state.notices) { notice in
                        DisclosureGroup {
                            if !notice.effectiveText.isEmpty { Text(notice.effectiveText) }
                            Text(notice.details).textSelection(.enabled)
                            if !notice.reference.isEmpty {
                                LabeledContent("Reference", value: notice.reference)
                            }
                        } label: {
                            VStack(alignment: .leading) {
                                Text(notice.title).fontWeight(.semibold)
                                Text(notice.summary).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    if notams.state.suppressedCount > 0 {
                        Text("\(notams.state.suppressedCount) notice(s) are outside the selected radius.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Nearby Airspace Restrictions")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .safeAreaInset(edge: .bottom) {
                Text("Not flight authorization. Verify restrictions and operational conditions independently.\nSources: FAA UAS Facility Map and FAA NOTAM data via the configured r2c-tracker organization proxy.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity)
                    .background(.bar)
            }
        }
    }

    private var facilityMapDisplayGroups: [AppleFacilityMapDisplayGroup] {
        var groups: [AppleFacilityMapDisplayGroup] = []
        var groupIndexByKey: [String: Int] = [:]
        for record in center.state.records {
            let key = AppleFacilityMapDisplayGroup.displayKey(record)
            if let index = groupIndexByKey[key] {
                groups[index].gridCount += 1
            } else {
                groupIndexByKey[key] = groups.count
                groups.append(.init(key: key, record: record, gridCount: 1))
            }
        }
        return groups
    }

    private var notamEmptyMessage: String {
        if !notams.state.enabled { return "Nearby NOTAM monitoring is disabled." }
        if !notams.state.configured {
            return "Import the r2c-tracker organization QR code to enable NOTAM queries."
        }
        return "No notices are displayed. Check query status above; this is not a flight clearance."
    }

    private var severityColor: Color {
        switch center.state.severity {
        case .danger: .red
        case .caution: .orange
        case .normal: .secondary
        case .neutral: .secondary
        }
    }

    private var notamSeverityColor: Color {
        switch notams.state.chipSeverity {
        case .danger: .red
        case .caution: .orange
        case .normal: .secondary
        case .neutral: .secondary
        }
    }
}

private struct AppleFacilityMapDisplayGroup: Identifiable {
    let key: String
    let record: OperationalFacilityMapRecord
    var gridCount: Int

    var id: String { key }

    static func displayKey(_ record: OperationalFacilityMapRecord) -> String {
        [
            record.primaryAirportFAAID,
            record.primaryAirportICAO,
            record.primaryAirportName,
            record.ceilingFeet.map(String.init) ?? "",
            record.unit,
            record.laancAvailable ? "1" : "0",
            record.airspaceClasses.sorted().joined(separator: ","),
        ].joined(separator: "\u{1F}")
    }
}

struct AppleAirspaceSafetyNotice: View {
    var body: some View {
        Section("Verify before flight") {
            Text("Not flight authorization. Verify restrictions and operational conditions independently.").fontWeight(.semibold)
            Text("These queries do not establish that flight is safe. Firefighting aircraft may be present even without a TFR. Emergency-response flights require applicable authorization and incident air-operations coordination.").font(.caption)
            Link("FAA NOTAM / TFR resources", destination: URL(string: "https://www.faa.gov/pilots/safety/notams_tfr")!)
            Link("FAA-approved planning providers", destination: URL(string: "https://www.faa.gov/uas/getting_started/b4ufly")!)
        }
    }
}
