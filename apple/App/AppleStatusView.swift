import Foundation
import R2CCore
import SwiftUI
import UIKit

struct AppleStatusSnapshot {
    let bluetoothStatus: String
    let bluetoothObservations: Int
    let bluetoothRejected: Int
    let locationStatus: String
    let configSource: String
    let organization: String
    let hasManagedTrackerEnrollment: Bool
    let notamProxyStatus: String
    let incident: String
    let operationalPeriod: String
    let trackerStatus: String
    let trackerDetail: String
    let trackerArchiveStatus: String
    let peerCount: Int
    let caltopoStatus: String
    let activeAircraft: Int
    let acceptedTrackPoints: Int
    let filteredObservations: Int
    let duplicatePositionFilters: Int
    let minimumDistanceFilters: Int
    let implausibleSpeedFilters: Int
    let horizontalAccuracyFilters: Int
    let invalidObservationFilters: Int
    let archivedTracks: Int
    let mediaMTXStatus: String
    let videoStatus: String
    let anomalyMode: String
    let importedMappings: [RidAircraftIdentity]

    var buildVersion: String {
        AppleBuildMetadata.version
    }

    var buildNumber: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown"
    }

    var buildArtifactDate: String {
        guard let date = AppleBuildMetadata.artifactDate else { return "unknown" }
        return date.formatted(date: .abbreviated, time: .standard)
    }

    var copyText: String {
        let sortedMappings = importedMappings.sorted {
            $0.mappedID.localizedCaseInsensitiveCompare($1.mappedID) == .orderedAscending
        }
        var lines = [
            "RID2Caltopo Apple Status",
            "BUILD_VERSION: \(buildVersion)",
            "BUILD_NUMBER: \(buildNumber)",
            "BUILD_ARTIFACT_DATE: \(buildArtifactDate)",
            "",
            "Scanner Status",
            "Bluetooth Remote ID: \(bluetoothStatus)",
            "Bluetooth observations: \(bluetoothObservations)",
            "Rejected Bluetooth packets: \(bluetoothRejected)",
            "Wi-Fi Remote ID bridge: DS110 to Bluetooth",
            "Location: \(locationStatus)",
            "",
            "Loaded Configuration (not an access grant)",
            "Source: \(configSource)",
            "Organization: \(display(organization))",
            "Incident: \(display(incident))",
            "Operational period: \(display(operationalPeriod))",
            "Tracker coordination: \(trackerStatus)",
            "Tracker coordination detail: \(trackerDetail)",
            "Tracker archive: \(trackerArchiveStatus)",
            "Peer zones: \(peerCount)",
            "CalTopo: \(caltopoStatus)",
            "",
            "FAA / Tracker Access",
            "Teams configuration: \(display(organization)) (operational identity only)",
            "Managed device enrollment: \(hasManagedTrackerEnrollment ? "Present" : "Not present")",
            "Latest NOTAM proxy result: \(notamProxyStatus)",
            "",
            "Runtime",
            "Active aircraft: \(activeAircraft)",
            "Accepted track points: \(acceptedTrackPoints)",
            "Filtered observations: \(filteredObservations)",
            "  Duplicate position: \(duplicatePositionFilters)",
            "  Under minimum distance: \(minimumDistanceFilters)",
            "  Implausible speed: \(implausibleSpeedFilters)",
            "  Horizontal accuracy: \(horizontalAccuracyFilters)",
            "  Invalid observation: \(invalidObservationFilters)",
            "Archived tracks: \(archivedTracks)",
            "MediaMTX: \(mediaMTXStatus)",
            "Video decoder: \(videoStatus)",
            "Anomaly detector: \(anomalyMode)",
            "",
            "Persisted Drone Mappings",
        ]
        if importedMappings.isEmpty {
            lines.append("No persisted drone mappings.")
        } else {
            lines.append(contentsOf: sortedMappings.map { identity in
                "remoteId: \(identity.remoteID)    droneDesig: \(identity.mappedID)    org: \(identity.organization)    owner: \(identity.pilotCallsign)    model: \(identity.droneDescription)"
            })
        }
        return lines.joined(separator: "\n")
    }

    private func display(_ value: String) -> String {
        value.isEmpty ? "Not configured" : value
    }
}

struct AppleStatusView: View {
    let snapshot: AppleStatusSnapshot
    @State private var copied = false

    var body: some View {
        List {
            Section("Build") {
                LabeledContent("Version", value: versionText)
                LabeledContent("Artifact date", value: artifactDate)
            }

            Section("Scanner Status") {
                LabeledContent("Bluetooth Remote ID", value: snapshot.bluetoothStatus)
                LabeledContent("Bluetooth observations", value: "\(snapshot.bluetoothObservations)")
                LabeledContent("Rejected Bluetooth packets", value: "\(snapshot.bluetoothRejected)")
                LabeledContent("Wi-Fi Remote ID bridge", value: "DS110 to Bluetooth")
                LabeledContent("Location", value: snapshot.locationStatus)
            }

            Section("Loaded Configuration (not an access grant)") {
                LabeledContent("Source", value: snapshot.configSource)
                LabeledContent("Organization", value: display(snapshot.organization))
                LabeledContent("Incident", value: display(snapshot.incident))
                if !snapshot.operationalPeriod.isEmpty {
                    LabeledContent("Operational period", value: snapshot.operationalPeriod)
                }
                LabeledContent("Tracker coordination", value: snapshot.trackerStatus)
                Text(snapshot.trackerDetail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                LabeledContent("Tracker archive", value: snapshot.trackerArchiveStatus)
                LabeledContent("Peer zones", value: "\(snapshot.peerCount)")
                LabeledContent("CalTopo", value: snapshot.caltopoStatus)
            }

            Section("FAA / Tracker Access") {
                LabeledContent("Teams configuration", value: "\(display(snapshot.organization)) (operational identity only)")
                LabeledContent(
                    "Managed device enrollment",
                    value: snapshot.hasManagedTrackerEnrollment ? "Present" : "Not present"
                )
                Text("Latest NOTAM proxy result: \(snapshot.notamProxyStatus)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Runtime") {
                LabeledContent("Active aircraft", value: "\(snapshot.activeAircraft)")
                LabeledContent("Accepted track points", value: "\(snapshot.acceptedTrackPoints)")
                LabeledContent("Filtered observations", value: "\(snapshot.filteredObservations)")
                LabeledContent("Duplicate position", value: "\(snapshot.duplicatePositionFilters)")
                LabeledContent("Under minimum distance", value: "\(snapshot.minimumDistanceFilters)")
                LabeledContent("Implausible speed", value: "\(snapshot.implausibleSpeedFilters)")
                LabeledContent("Horizontal accuracy", value: "\(snapshot.horizontalAccuracyFilters)")
                LabeledContent("Invalid observation", value: "\(snapshot.invalidObservationFilters)")
                LabeledContent("Archived tracks", value: "\(snapshot.archivedTracks)")
                LabeledContent("MediaMTX", value: snapshot.mediaMTXStatus)
                LabeledContent("Video decoder", value: snapshot.videoStatus)
                LabeledContent("Anomaly detector", value: snapshot.anomalyMode)
            }

            Section("Persisted Drone Mappings") {
                if snapshot.importedMappings.isEmpty {
                    Text("No persisted drone mappings.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(snapshot.importedMappings.sorted { lhs, rhs in
                        lhs.mappedID.localizedCaseInsensitiveCompare(rhs.mappedID) == .orderedAscending
                    }, id: \.remoteID) { identity in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(identity.remoteID).font(.headline)
                            Text("droneDesig: \(identity.mappedID)")
                            Text(mappingDetail(identity))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .textSelection(.enabled)
                    }
                }
            }

            Section {
                Button(copied ? "Status Copied" : "Copy Status", systemImage: copied ? "checkmark" : "doc.on.doc") {
                    copyStatus()
                }
            } footer: {
                Text("The copied report intentionally excludes configuration tokens and credential secrets.")
            }
        }
        .navigationTitle("Status")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Copy", systemImage: "doc.on.doc", action: copyStatus)
            }
        }
    }

    private var versionText: String {
        "\(snapshot.buildVersion) (\(snapshot.buildNumber))"
    }

    private var artifactDate: String {
        snapshot.buildArtifactDate
    }

    private func display(_ value: String) -> String {
        value.isEmpty ? "Not configured" : value
    }

    private func mappingDetail(_ identity: RidAircraftIdentity) -> String {
        [identity.organization, identity.pilotCallsign, identity.droneDescription]
            .filter { !$0.isEmpty }
            .joined(separator: " • ")
    }

    private func copyStatus() {
        UIPasteboard.general.string = snapshot.copyText
        copied = true
        AppleLog.info("Status", "Status copied to clipboard")
    }
}
