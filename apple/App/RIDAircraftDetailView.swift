import CoreLocation
import R2CCore
import SwiftUI

struct RIDAircraftSummaryRow: View {
    let track: RidAircraftTrack
    let operatorLocation: CLLocation?
    let identity: RidAircraftIdentity?
    var confirmedForCurrentFlight = false

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(identity?.mappedID ?? track.aircraftID)
                    .font(.headline.monospaced())
                Spacer()
                if let bridgeRSSI = OperationalMainScreenPresentation.droneToBridgeRSSIText(
                    track.lastDroneToBridgeSignalStrengthDbm
                ) {
                    RIDSignalStrengthBars(rssi: track.lastDroneToBridgeSignalStrengthDbm)
                        .frame(width: 38, height: 18)
                    Text(bridgeRSSI)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 8) {
                if identity != nil {
                    Text(track.aircraftID)
                }
                Label(sourceSummary, systemImage: sourceIcon)
                Text("\(track.points.count) pts")
                if let altitude = track.lastObservation.altitudeMeters {
                    Text("\(Int((altitude * 3.28084).rounded())) ft")
                }
                if let relativePosition {
                    Text(formatRange(relativePosition.distanceMeters))
                }
                Spacer(minLength: 4)
                Label(
                    confirmedForCurrentFlight ? "Saved" : "Tap to Save",
                    systemImage: confirmedForCurrentFlight ? "checkmark.circle.fill" : "exclamationmark.circle"
                )
                .foregroundStyle(confirmedForCurrentFlight ? .green : .orange)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.75)
        }
        .padding(.vertical, 2)
    }

    private var sourceSummary: String {
        track.acceptedCountBySource
            .sorted { $0.key.rawValue < $1.key.rawValue }
            .map { "\(sourceShortName($0.key)) \($0.value)" }
            .joined(separator: " • ")
    }

    private var sourceIcon: String {
        switch track.lastObservation.source {
        case .bluetoothLegacy, .bluetoothExtended: "antenna.radiowaves.left.and.right"
        case .wifiBeacon, .wifiNan: "wifi"
        case .trackerRelay: "arrow.triangle.2.circlepath"
        }
    }

    private var relativePosition: RidRelativePosition? {
        guard let operatorLocation else { return nil }
        return RidGeometry.relativePosition(
            fromLatitude: operatorLocation.coordinate.latitude,
            longitude: operatorLocation.coordinate.longitude,
            toLatitude: track.lastObservation.latitude,
            longitude: track.lastObservation.longitude
        )
    }
}

struct RIDAircraftDetailView: View {
    let track: RidAircraftTrack
    let operatorLocation: CLLocation?
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    let onConfirm: (RidAircraftIdentity) -> Void
    @State private var showConfirmation = false

    var body: some View {
        List {
            Section("Current Flight") {
                Button {
                    showConfirmation = true
                } label: {
                    Label(
                        identityStore.isCurrentFlightConfirmed(track.aircraftID)
                            ? "Update Saved Drone"
                            : "Save Drone for This Flight",
                        systemImage: identityStore.isCurrentFlightConfirmed(track.aircraftID)
                            ? "checkmark.circle.fill"
                            : "square.and.arrow.down"
                    )
                    .font(.headline)
                }
                Text("Saving enables the archive-colored flight tail and, when configured, local ownership, alerts, and CalTopo publishing—matching Android's Save contract.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Identity and signal") {
                LabeledContent("Remote ID", value: track.aircraftID)
                if let identity = identityStore.identity(for: track.aircraftID) {
                    LabeledContent("Operating profile", value: (OperatingProfiles.active(identity.flightReadiness)["profile"] as? [String: Any])?["name"] as? String ?? "Not reported")
                    LabeledContent("Mapped ID", value: identity.mappedID)
                    LabeledContent("Organization", value: identity.organization)
                    LabeledContent("Pilot callsign", value: identity.pilotCallsign)
                    LabeledContent("Drone description", value: identity.droneDescription)
                }
                LabeledContent("Last transport", value: sourceName(track.lastObservation.source))
                HStack {
                    Text("Drone → device")
                    Spacer()
                    RIDSignalStrengthBars(rssi: track.lastDirectSignalStrengthDbm)
                        .frame(width: 48, height: 20)
                    Text(track.lastDirectSignalStrengthDbm.map { "\($0) dBm" } ?? "Unavailable")
                        .foregroundStyle(.secondary)
                }
                LabeledContent(
                    "Drone → bridge",
                    value: track.lastDroneToBridgeSignalStrengthDbm.map { "\($0) dBm" }
                        ?? "Unavailable"
                )
                LabeledContent("Last received", value: track.lastSignalAt.formatted(date: .omitted, time: .standard))
            }

            Section("Latest aircraft position") {
                LabeledContent("Latitude", value: coordinate(track.lastObservation.latitude))
                LabeledContent("Longitude", value: coordinate(track.lastObservation.longitude))
                LabeledContent("Altitude", value: altitudeText)
                LabeledContent("Heading", value: measurement(track.lastObservation.headingDegrees, suffix: "°"))
                LabeledContent("Speed", value: speedText)
            }

            if let operatorLocation,
               let relativePosition = RidGeometry.relativePosition(
                   fromLatitude: operatorLocation.coordinate.latitude,
                   longitude: operatorLocation.coordinate.longitude,
                   toLatitude: track.lastObservation.latitude,
                   longitude: track.lastObservation.longitude
               ) {
                Section("Relative to this device") {
                    LabeledContent("Range", value: formatRange(relativePosition.distanceMeters))
                    LabeledContent(
                        "Bearing",
                        value: "\(Int(relativePosition.bearingDegrees.rounded()))° \(relativePosition.cardinalDirection)"
                    )
                    LabeledContent("Location accuracy", value: "±\(Int(operatorLocation.horizontalAccuracy.rounded())) m")
                }
            }

            if let latitude = track.lastObservation.operatorLatitude,
               let longitude = track.lastObservation.operatorLongitude {
                Section("Reported operator") {
                    LabeledContent("Latitude", value: coordinate(latitude))
                    LabeledContent("Longitude", value: coordinate(longitude))
                }
            }

            Section("Track") {
                LabeledContent("Accepted points", value: "\(track.points.count)")
                LabeledContent("Distance", value: distanceText)
                ForEach(RidObservation.Source.allCases, id: \.self) { source in
                    if let count = track.acceptedCountBySource[source], count > 0 {
                        LabeledContent(sourceName(source), value: "\(count)")
                    }
                }
            }

        }
        .navigationTitle(track.aircraftID)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if ProcessInfo.processInfo.arguments.contains("--show-drone-confirmation") {
                try? await Task.sleep(for: .milliseconds(500))
                showConfirmation = true
            }
        }
        .sheet(isPresented: $showConfirmation) {
            DroneConfirmationView(
                remoteID: track.aircraftID,
                existing: identityStore.identity(for: track.aircraftID),
                identityStore: identityStore,
                onConfirm: onConfirm
            )
        }
    }

    private var altitudeText: String {
        guard let meters = track.lastObservation.altitudeMeters else { return "Unavailable" }
        return "\(Int((meters * 3.28084).rounded())) ft (\(Int(meters.rounded())) m)"
    }

    private var speedText: String {
        guard let metersPerSecond = track.lastObservation.speedMetersPerSecond else { return "Unavailable" }
        return String(format: "%.1f mph (%.1f m/s)", metersPerSecond * 2.23694, metersPerSecond)
    }

    private var distanceText: String {
        if track.distanceMeters >= 1_000 {
            return String(format: "%.2f km", track.distanceMeters / 1_000)
        }
        return "\(Int(track.distanceMeters.rounded())) m"
    }

    private func coordinate(_ value: Double) -> String {
        String(format: "%.7f°", value)
    }

    private func measurement(_ value: Double?, suffix: String) -> String {
        value.map { String(format: "%.1f%@", $0, suffix) } ?? "Unavailable"
    }

}

@MainActor
final class AppleDroneConfirmationStore: ObservableObject {
    @Published private var sessionIdentities: [String: RidAircraftIdentity] = [:]
    @Published private var peerIdentities: [String: RidAircraftIdentity] = [:]
    @Published private var importedIdentities: [String: RidAircraftIdentity] = [:]
    @Published private(set) var preferredPilotCallsign: String
    private var confirmationLifecycle = CurrentFlightConfirmationLifecycle()
    private var ignoredRemoteIDs: Set<String> = []
    private let defaults = UserDefaults.standard
    private static let ignoredRemoteIDsDefaultsKey = "org.ignoredRemoteIDs"
    private static let preferredPilotCallsignDefaultsKey = "operator.pilotCallsign"

    init() {
        preferredPilotCallsign = PilotDisplayPreference.normalizePilotCallsign(
            UserDefaults.standard.string(forKey: Self.preferredPilotCallsignDefaultsKey)
        ) ?? ""
        if let entries = defaults.array(forKey: "org.ridMappings") as? [[String: String]] {
            importedIdentities = Dictionary(uniqueKeysWithValues: entries.compactMap { entry in
                guard let remoteID = entry["remoteID"], !remoteID.isEmpty else { return nil }
                let identity = RidAircraftIdentity(
                    remoteID: remoteID,
                    organization: entry["organization"] ?? "",
                    ownerName: entry["ownerName"] ?? entry["owner"] ?? "",
                    pilotCallsign: entry["ownerCallsign"] ?? Self.importedPilotCallsign(
                        mappedID: entry["mappedID"] ?? "",
                        model: entry["model"] ?? "",
                        remoteID: remoteID
                    ),
                    droneDescription: entry["model"] ?? "",
                    mappedIDOverride: entry["mappedID"],
                    readiness: AircraftReadiness.decode(entry["readiness"])
                )
                return (remoteID, identity)
            })
        }
        // Save and Ignore are scoped to the current flight. Paired video activity keeps the
        // flight alive through a temporary RID gap; decisions are not persisted across launches.
        defaults.removeObject(forKey: Self.ignoredRemoteIDsDefaultsKey)
    }

    func identity(for remoteID: String) -> RidAircraftIdentity? {
        peerIdentities[remoteID]?.preservingLocalFlightReadiness(from: sessionIdentities[remoteID])
            ?? sessionIdentities[remoteID] ?? importedIdentities[remoteID]
    }

    func isCurrentFlightConfirmed(_ remoteID: String) -> Bool {
        sessionIdentities[remoteID] != nil || peerIdentities[remoteID] != nil
    }

    func activePilotCallsignConflict(remoteID: String, callsign: String) -> RidAircraftIdentity? {
        let active = sessionIdentities.merging(peerIdentities) { peer, _ in peer }
        return active.values.first { identity in
            identity.remoteID != remoteID &&
                PilotDisplayPreference.callsignsMatch(identity.pilotCallsign, callsign)
        }
    }

    /// Prompt once per current flight. RID-only gaps do not reach this method as an ended
    /// flight until the shared 30-second RID/video activity timeout removes the track.
    func reconcileActiveFlights(_ orderedRemoteIDs: [String]) -> String? {
        let reconciliation = confirmationLifecycle.reconcile(
            orderedRemoteIDs: orderedRemoteIDs,
            confirmedRemoteIDs: Set(sessionIdentities.keys).union(peerIdentities.keys),
            ignoredRemoteIDs: ignoredRemoteIDs
        )
        for remoteID in reconciliation.endedRemoteIDs {
            sessionIdentities.removeValue(forKey: remoteID)
            peerIdentities.removeValue(forKey: remoteID)
            ignoredRemoteIDs.remove(remoteID)
        }
        if !reconciliation.endedRemoteIDs.isEmpty {
            AppleLog.info(
                "DroneConfirmation",
                "Cleared current-flight decisions remoteIds=\(reconciliation.endedRemoteIDs.sorted().joined(separator: ","))"
            )
        }
        guard let candidate = reconciliation.candidateRemoteID else { return nil }
        AppleLog.info("DroneConfirmation", "Queueing confirmation for active flight remoteId=\(candidate)")
        return candidate
    }

    func isIgnored(_ remoteID: String) -> Bool {
        ignoredRemoteIDs.contains(remoteID)
    }

    func ignore(_ remoteID: String) {
        guard !remoteID.isEmpty else { return }
        ignoredRemoteIDs.insert(remoteID)
        sessionIdentities.removeValue(forKey: remoteID)
        peerIdentities.removeValue(forKey: remoteID)
        AppleLog.info(
            "DroneConfirmation",
            "Ignored remoteId=\(remoteID) currentFlightRetained=true caltopoSuppressed=true"
        )
    }

    var importedMappingCount: Int { importedIdentities.count }

    func setPreferredPilotCallsign(_ value: String) {
        preferredPilotCallsign = value
        defaults.set(
            PilotDisplayPreference.normalizePilotCallsign(value) ?? "",
            forKey: Self.preferredPilotCallsignDefaultsKey
        )
    }

    var importedMappings: [RidAircraftIdentity] {
        importedIdentities.values.sorted { $0.remoteID < $1.remoteID }
    }

    func confirm(_ identity: RidAircraftIdentity, recordUnresolvedPilot: Bool = false) {
        // Recording incomplete evidence never establishes pilot qualification.
        guard identity.isComplete || (recordUnresolvedPilot && !identity.remoteID.isEmpty) else { return }
        ignoredRemoteIDs.remove(identity.remoteID)
        sessionIdentities[identity.remoteID] = identity
        AppleLog.info(
            "DroneConfirmation",
            "Confirmed remoteId=\(identity.remoteID) mappedId=\(identity.mappedID) organization='\(identity.organization)' callsign='\(identity.pilotCallsign)' description='\(identity.droneDescription)' sessionOnly=true"
        )
    }

    func applyImportedMappings(_ mappings: [OrgConfigRIDMapping], managedDownload: Bool = false) throws {
        if !managedDownload { try AppleAircraftOrganizationAccess.requireEdit() }
        importedIdentities = Dictionary(uniqueKeysWithValues: mappings.map { mapping in
            (
                mapping.remoteID,
                RidAircraftIdentity(
                    remoteID: mapping.remoteID,
                    organization: mapping.organization,
                    ownerName: mapping.ownerName,
                    pilotCallsign: mapping.ownerCallsign.isEmpty ? (managedDownload ? mapping.owner : Self.importedPilotCallsign(mappedID: mapping.mappedID, model: mapping.model, remoteID: mapping.remoteID)) : mapping.ownerCallsign,
                    droneDescription: mapping.model,
                    mappedIDOverride: mapping.mappedID,
                    readiness: mapping.readiness
                )
            )
        })
        let persisted = mappings.map { mapping in
            [
                "remoteID": mapping.remoteID,
                "mappedID": mapping.mappedID,
                "organization": mapping.organization,
                "model": mapping.model,
                "owner": mapping.owner,
                "ownerName": mapping.ownerName,
                "readiness": mapping.readiness.jsonString,
                "ownerCallsign": mapping.ownerCallsign.isEmpty ? (managedDownload ? mapping.owner : Self.importedPilotCallsign(mappedID: mapping.mappedID, model: mapping.model, remoteID: mapping.remoteID)) : mapping.ownerCallsign,
            ]
        }
        defaults.set(persisted, forKey: "org.ridMappings")
    }

    func replacePersistedMappings(_ mappings: [RidAircraftIdentity]) throws {
        try AppleAircraftOrganizationAccess.requireEdit()
        importedIdentities = Dictionary(uniqueKeysWithValues: mappings.map { ($0.remoteID, $0) })
        defaults.set(
            mappings.map { identity in
                [
                    "remoteID": identity.remoteID,
                    "mappedID": identity.mappedID,
                    "organization": identity.organization,
                    "model": identity.droneDescription,
                    "owner": identity.ownerName,
                    "ownerName": identity.ownerName,
                    "ownerCallsign": identity.pilotCallsign,
                    "readiness": identity.readiness.jsonString,
                ]
            },
            forKey: "org.ridMappings"
        )
    }

    func applyPeerConfirmation(_ identity: TrackerCoordinationIdentity) {
        peerIdentities[identity.remoteID] = RidAircraftIdentity(
            remoteID: identity.remoteID,
            organization: identity.organization,
            ownerName: identity.ownerName,
            pilotCallsign: identity.ownerName,
            droneDescription: identity.model,
            mappedIDOverride: identity.mappedID
        )
    }

    func clearPeerConfirmation(remoteID: String) {
        peerIdentities.removeValue(forKey: remoteID)
    }

    func resetPersistedState() {
        importedIdentities.removeAll()
        sessionIdentities.removeAll()
        peerIdentities.removeAll()
        confirmationLifecycle.reset()
        ignoredRemoteIDs.removeAll()
        defaults.removeObject(forKey: "org.ridMappings")
        defaults.removeObject(forKey: Self.ignoredRemoteIDsDefaultsKey)
    }

    private static func importedPilotCallsign(mappedID: String, model: String, remoteID: String) -> String {
        RidAircraftIdentity.guessPilotCallsign(mappedID: mappedID, model: model, remoteID: remoteID)
    }
}

struct DroneConfirmationView: View {
    @State private var profileJSON = "{}"
    @State private var profileTouched = false
    @State private var useForAssignment = false
    @State private var checkedConditions: Set<Int> = []
    @State private var readiness = FlightReadiness()
    @State private var rosterState = AppleAircraftOrganizationAccess.cachedState
    private var managedAircraft: Bool { AppleAircraftOrganizationAccess.belongsToOrganization }
    let remoteID: String
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    let onConfirm: (RidAircraftIdentity) -> Void
    let onIgnore: (() -> Void)?
    private let mappedIDOverride: String?
    @Environment(\.dismiss) private var dismiss
    @State private var organization: String
    @State private var pilotCallsign: String
    @State private var droneDescription: String
    @State private var showEquipment = false

    init(
        remoteID: String,
        existing: RidAircraftIdentity?,
        identityStore: AppleDroneConfirmationStore,
        onConfirm: @escaping (RidAircraftIdentity) -> Void,
        onIgnore: (() -> Void)? = nil
    ) {
        self.remoteID = remoteID
        self.identityStore = identityStore
        self.onConfirm = onConfirm
        self.onIgnore = onIgnore
        mappedIDOverride = existing.flatMap { identity in
            identity.mappedID == remoteID ? nil : identity.mappedID
        }
        let prior = OperatingProfiles.active(existing?.flightReadiness)["profile"] as? [String: Any]
        let remembered = AppleAircraftOrganizationAccess.operatingAssignment.profileJSON
        _profileJSON = State(initialValue: prior.map { OperatingProfiles.json($0) } ?? remembered ?? OperatingProfiles.json(OperatingProfiles.preferredProfile(AppleAircraftOrganizationAccess.cachedState, savedID: UserDefaults.standard.string(forKey: OperatingProfiles.preferenceKey(organization: AppleAircraftOrganizationAccess.scope)))))
        _profileTouched = State(initialValue: prior != nil || remembered != nil)
        _useForAssignment = State(initialValue: remembered != nil)
        _organization = State(initialValue: existing?.organization ?? "")
        _pilotCallsign = State(initialValue: PilotDisplayPreference.preferredPilotCallsign(
            saved: identityStore.preferredPilotCallsign,
            existing: existing?.pilotCallsign
        ))
        _droneDescription = State(initialValue: existing?.droneDescription ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Pilot Callsign / RPIC") {
                        TextField("Callsign", text: $pilotCallsign).multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Drone model") {
                        TextField("Model", text: $droneDescription).multilineTextAlignment(.trailing)
                    }
                    Menu {
                        Button("Base configuration") { readiness.selectedAccessories = []; readiness.payloadDescription = ""; readiness.payloadWeightGrams = nil }
                        Button("Configure equipment / payload…") { showEquipment = true }
                    } label: {
                        LabeledContent("Configuration", value: readiness.selectedAccessories.isEmpty && readiness.payloadDescription.isEmpty ? "Base" : "Custom")
                    }
                    Menu {
                        ForEach(Array(OperatingProfiles.choices(rosterState).enumerated()), id: \.offset) { _, choice in
                            Button(choice["name"] as? String ?? "Profile") {
                                profileJSON = OperatingProfiles.json(choice); profileTouched = true; checkedConditions = []
                            }
                        }
                    } label: { LabeledContent("Type of flight", value: OperatingProfiles.object(profileJSON)["name"] as? String ?? "Other") }
                }
                if managedAircraft && !selectedPilotEligible { Text("RPIC qualifications not verified").font(.caption).foregroundStyle(.orange) }
                if OperatingProfiles.object(profileJSON)["id"] as? String == "bvlos-pending" { Text("BVLOS authority details not configured").font(.caption).foregroundStyle(.orange) }
                Section {
                    DisclosureGroup("Equipment, payload & weight", isExpanded: $showEquipment) { flightReadinessFields }
                    DisclosureGroup("Authority & briefing details") { operatingProfileFields }
                    DisclosureGroup("Aircraft identification") {
                        LabeledContent("Remote ID", value: remoteID)
                        TextField("Organization", text: $organization)
                    }
                }
                if let conflict = pilotCallsignConflict {
                    Section {
                        Text(PilotDisplayPreference.activeAssignmentWarning(
                            callsign: pilotCallsign,
                            aircraftLabel: conflict.mappedID.isEmpty ? conflict.remoteID : conflict.mappedID
                        ))
                        .foregroundStyle(.orange)
                    }
                }
            }
            .task {
                readiness.aircraft = identityStore.importedMappings.first(where: { $0.remoteID == remoteID })?.readiness ?? AircraftReadiness()
                readiness.selectedAccessories = []
                _ = await AppleAircraftOrganizationAccess.refresh(baseURL: AppleAircraftOrganizationAccess.scope)
                applyReadinessState()
            }
            .onReceive(NotificationCenter.default.publisher(for: Notification.Name("aircraftReadinessUpdated"))) { _ in
                applyReadinessState()
            }
            .onChange(of: pilotCallsign) { _, _ in matchReportedPilot() }
            .navigationTitle("Confirm Drone")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if let onIgnore {
                        Button("Don’t publish") {
                            onIgnore()
                            dismiss()
                        }
                    } else {
                        Button("Cancel") { dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Publish track") {
                        if organization.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            organization = UserDefaults.standard.string(forKey: "org.name") ?? ""
                        }
                        matchReportedPilot()
                        readiness = readiness.withReportedPilot(callsign: pilotCallsign, matched: (readiness.dictionary["pilot"] as? [String: Any])?["memberId"] != nil)
                        let profile = OperatingProfiles.object(profileJSON)
                        UserDefaults.standard.set(profile["missingProfileId"] as? String ?? profile["id"] as? String,
                            forKey: OperatingProfiles.preferenceKey(organization: AppleAircraftOrganizationAccess.scope))
                        if useForAssignment { AppleAircraftOrganizationAccess.operatingAssignment.remember(profile) }
                        else { AppleAircraftOrganizationAccess.operatingAssignment.end() }
                        readiness.operatingProfileJSON = OperatingProfiles.json(OperatingProfiles.snapshot(profile, state: rosterState,
                            pilotID: (readiness.dictionary["pilot"] as? [String: Any])?["memberId"] as? String ?? "",
                            aircraftID: readiness.aircraft.recordId, incidentID: AppleAircraftOrganizationAccess.operatingIncidentID,
                            assignmentID: AppleAircraftOrganizationAccess.operatingAssignment.assignmentID, managed: managedAircraft, checked: checkedConditions))
                        readiness = OperatingProfiles.retainingHistory(previous: identityStore.identity(for: remoteID)?.flightReadiness, next: readiness)
                        readiness.confirmedAt = ISO8601DateFormatter().string(from: Date())
                        UserDefaults.standard.set(Array(readiness.selectedAccessories), forKey: "accessories:" + AppleAircraftOrganizationAccess.scope + remoteID)
                        identityStore.confirm(identity, recordUnresolvedPilot: true)
                        onConfirm(identity)
                        dismiss()
                    }
                }
            }
        }
    }

    private var selectedPilotEligible: Bool {
        if !managedAircraft { return true }
        let selected = readiness.dictionary["pilot"] as? [String: Any] ?? [:]
        let formatter = DateFormatter(); formatter.dateFormat = "yyyy-MM-dd"
        return (rosterState["pilots"] as? [[String: Any]] ?? []).contains {
            $0["memberId"] as? String == selected["memberId"] as? String &&
                $0["callsign"] as? String == selected["callsign"] as? String &&
                ($0["callsign"] as? String ?? "").caseInsensitiveCompare(pilotCallsign.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame &&
                $0["eligible"] as? Bool == true && ($0["validUntil"] as? String ?? "") >= formatter.string(from: Date())
        }
    }

    private func applyReadinessState() {
        rosterState = AppleAircraftOrganizationAccess.cachedState
        matchReportedPilot()
        if !profileTouched { profileJSON = OperatingProfiles.json(OperatingProfiles.preferredProfile(rosterState, savedID: UserDefaults.standard.string(forKey: OperatingProfiles.preferenceKey(organization: AppleAircraftOrganizationAccess.scope)))) }
        readiness.rosterFetchedAt = rosterState["fetchedAt"] as? String ?? ""
        readiness.configurationVersion = (rosterState["configurationVersion"] as? NSNumber)?.int64Value ?? 0
        if let entry = (rosterState["aircraft"] as? [[String: Any]] ?? []).first(where: { ($0["remoteId"] as? String)?.uppercased() == remoteID.uppercased() }) {
            readiness = readiness.withAircraft(local: identityStore.importedMappings.first(where: { $0.remoteID.caseInsensitiveCompare(remoteID) == .orderedSame })?.readiness, published: entry["readiness"].map { AircraftReadiness.decode($0) })
            readiness.selectedAccessories.formIntersection(readiness.aircraft.accessories.map(\.id))
            if let service = (entry["history"] as? [[String: Any]])?.first,
               let data = try? JSONSerialization.data(withJSONObject: service) { readiness.serviceJSON = String(decoding: data, as: UTF8.self) }
        }
    }

    private func matchReportedPilot() {
        readiness = readiness.resolvingPilot(callsign: pilotCallsign, roster: rosterState["pilots"] as? [[String: Any]] ?? [])
    }

    private var operatingProfileFields: some View {
        VStack(alignment: .leading) {
            let profile = OperatingProfiles.object(profileJSON)
            Menu(profile["name"] as? String ?? "Other / details pending") {
                ForEach(Array(OperatingProfiles.choices(rosterState).enumerated()), id: \.offset) { _, choice in
                    Button(choice["name"] as? String ?? "Profile") {
                        profileJSON = OperatingProfiles.json(choice); profileTouched = true; checkedConditions = []
                    }
                }
            }
            Toggle("Use for this assignment (across batteries)", isOn: $useForAssignment)
            if !AppleAircraftOrganizationAccess.operatingAssignment.assignmentID.isEmpty {
                Button("End assignment / start a different assignment") {
                    AppleAircraftOrganizationAccess.operatingAssignment.end(); useForAssignment = false
                }
            }
            ForEach(OperatingProfiles.warnings(profile, state: rosterState,
                pilotID: (readiness.dictionary["pilot"] as? [String: Any])?["memberId"] as? String ?? "",
                aircraftID: readiness.aircraft.recordId, incidentID: AppleAircraftOrganizationAccess.operatingIncidentID,
                managed: managedAircraft), id: \.self) { issue in Text(issue + " Continue remains available.").foregroundStyle(.orange) }
            ForEach(Array((profile["conditions"] as? [[String: Any]] ?? []).enumerated()), id: \.offset) { index, condition in
                Toggle(["text", "source", "unit", "reference"].compactMap { condition[$0] as? String }.filter { !$0.isEmpty }.joined(separator: " · "),
                    isOn: Binding(get: { checkedConditions.contains(index) }, set: { if $0 { checkedConditions.insert(index) } else { checkedConditions.remove(index) } }))
            }
            Text("RPIC or VO for the RPIC: review applicability and conditions. Selection does not grant authority. Airspace authorization is separate.").font(.footnote)
        }
    }

    private var flightReadinessFields: some View {
        VStack(alignment: .leading) {
            if managedAircraft {
                let aircraft = (rosterState["aircraft"] as? [[String: Any]] ?? []).first { ($0["remoteId"] as? String)?.uppercased() == remoteID.uppercased() }
                let service = (aircraft?["history"] as? [[String: Any]])?.first ?? [:]
                Text("Service status: " + (service["status"] as? String ?? "Not yet reported").replacingOccurrences(of: "_", with: " "))
                Text(service["note"] as? String ?? "")
                Text("Last synchronized: " + (rosterState["fetchedAt"] as? String ?? "not available")).font(.footnote)
                if let url = URL(string: AppleAircraftOrganizationAccess.scope + "/aircraft") { Link("Report / review aircraft service status", destination: url) }
                let today = ISO8601DateFormatter().string(from: Date()).prefix(10)
                let pilots = (rosterState["pilots"] as? [[String: Any]] ?? []).filter { $0["eligible"] as? Bool == true && ($0["validUntil"] as? String ?? "") >= today }
                if readiness.pilotJSON != "{}" && !selectedPilotEligible { Text("Pilot details changed or are no longer current. Select the RPIC again.").foregroundStyle(.red) }
                Text(["part107", "part107_waiver"].contains(OperatingProfiles.object(profileJSON)["authorityType"] as? String ?? "") ? "RPIC — select the certified pilot serving in command" : "RPIC — reported callsign; this roster records Part 107 qualifications. Other authority requires separate review.")
                ForEach(pilots.indices, id: \.self) { index in
                    let pilot = pilots[index]
                    Button((pilot["callsign"] as? String ?? "") + " — " + (pilot["name"] as? String ?? "")) {
                        pilotCallsign = pilot["callsign"] as? String ?? ""
                        if let data = try? JSONSerialization.data(withJSONObject: pilot) { readiness.pilotJSON = String(decoding: data, as: UTF8.self) }
                        if let data = try? JSONSerialization.data(withJSONObject: service) { readiness.serviceJSON = String(decoding: data, as: UTF8.self) }
                    }
                }
                if pilots.isEmpty { Text("No eligible pilot in the saved roster. Recording continues; synchronize or ask a member administrator to complete qualifications.") }
            }
            ForEach(readiness.aircraft.accessories) { accessory in
                Toggle(accessory.name + (accessory.required ? " (required)" : ""), isOn: Binding(get: { readiness.selectedAccessories.contains(accessory.id) }, set: { selected in
                    if selected {
                        if !accessory.group.isEmpty { readiness.selectedAccessories.subtract(readiness.aircraft.accessories.filter { $0.group == accessory.group }.map(\.id)) }
                        readiness.selectedAccessories.insert(accessory.id)
                    } else { readiness.selectedAccessories.remove(accessory.id) }
                }))
            }
            TextField("Takeoff payload (for example water bottle)", text: $readiness.payloadDescription)
            TextField("Payload weight (grams, if known)", value: $readiness.payloadWeightGrams, format: .number)
            Text("Takeoff weight: " + (readiness.aircraft.totalWeight(selected: readiness.selectedAccessories, payloadDescription: readiness.payloadDescription, payloadGrams: readiness.payloadWeightGrams).map { "\($0) grams" } ?? "incomplete — weight unknown"))
        }
    }

    private var identity: RidAircraftIdentity {
        RidAircraftIdentity(
            remoteID: remoteID,
            organization: organization,
            pilotCallsign: pilotCallsign,
            droneDescription: droneDescription,
            mappedIDOverride: mappedIDOverride,
            readiness: readiness.aircraft,
            flightReadiness: readiness
        )
    }

    private var pilotCallsignConflict: RidAircraftIdentity? {
        identityStore.activePilotCallsignConflict(remoteID: remoteID, callsign: pilotCallsign)
    }
}

private struct RIDSignalStrengthBars: View {
    let rssi: Int?

    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0 ..< 4, id: \.self) { index in
                RoundedRectangle(cornerRadius: 1.5)
                    .fill(index < filledBarCount ? Color.green : Color.green.opacity(0.25))
                    .frame(maxWidth: .infinity)
                    .frame(height: CGFloat(5 + index * 4))
            }
        }
        .accessibilityLabel("Signal strength")
        .accessibilityValue(rssi.map { "\($0) decibels milliwatt" } ?? "unavailable")
    }

    private var filledBarCount: Int {
        guard let rssi else { return 0 }
        if rssi >= -60 { return 4 }
        if rssi >= -70 { return 3 }
        if rssi >= -80 { return 2 }
        if rssi >= -90 { return 1 }
        return 0
    }
}

private func sourceShortName(_ source: RidObservation.Source) -> String {
    switch source {
    case .bluetoothLegacy: "BT4"
    case .bluetoothExtended: "BT5"
    case .wifiBeacon: "Wi-Fi"
    case .wifiNan: "NAN"
    case .trackerRelay: "R2C"
    }
}

private func sourceName(_ source: RidObservation.Source) -> String {
    switch source {
    case .bluetoothLegacy: "Bluetooth Legacy"
    case .bluetoothExtended: "Bluetooth Extended"
    case .wifiBeacon: "Wi-Fi Beacon"
    case .wifiNan: "Wi-Fi NAN"
    case .trackerRelay: "Tracker Relay"
    }
}

private func formatRange(_ meters: Double) -> String {
    let feet = meters * 3.28084
    if feet >= 5_280 {
        return String(format: "%.2f mi", feet / 5_280)
    }
    return "\(Int(feet.rounded())) ft"
}
