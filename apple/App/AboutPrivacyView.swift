import SwiftUI

struct AppleReleaseNotesView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Version \(appVersion)").font(.title2.bold())
                Text(releaseNotes)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .textSelection(.enabled)
        }
        .navigationTitle("Release Notes")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var releaseNotes: String {
        guard let url = Bundle.main.url(forResource: "whats_new", withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8)
        else { return "Release notes are unavailable in this build." }
        return text
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    }
}

struct AboutPrivacyView: View {
    var header: AnyView? = nil
    var body: some View {
        List {
            if let header { Section { header } }
            Section("RID2Caltopo") {
                if case nil = header { LabeledContent("Version", value: appVersion) }
                Text("Incident-support software for receiving Remote ID observations, mapping aircraft, publishing operator-authorized tracks, and analyzing live drone video.")
                    .foregroundStyle(.secondary)
            }

            Section("Privacy") {
                Text("RID2Caltopo contains no advertising or analytics SDKs, does not track people across apps or websites, and does not sell personal data.")
                Text("Remote ID observations, track archives, and diagnostic logs remain on this device unless you enable CalTopo publishing, load a configuration that enables eligible team-track upload or tracker peer coordination, or explicitly share a file.")
                Text("The CalTopo credential secret is stored in Apple Keychain and is never written to diagnostic logs or shared log bundles.")
                Text("If you enable iCloud configuration backup, operational settings and credentials are encrypted with your passphrase before they are saved to the app's iCloud Drive container. The automatic-backup passphrase remains in this device's Keychain.")
            }

            Section("Permissions and network use") {
                Label("Bluetooth receives nearby ASTM Remote ID broadcasts.", systemImage: "antenna.radiowaves.left.and.right")
                Label("Location places the operator relative to aircraft on the map.", systemImage: "location")
                Label("Local Network receives controller video and optional external Remote ID observations.", systemImage: "network")
                Text("When enabled by the operator, CalTopo receives aircraft positions and telemetry for the selected map. Configured tracker peer coordination receives the app-install zone identifier, device zone name, operator position, confirmed drone identity, and aircraft sightings needed to coordinate ownership. Nearby NOTAM monitoring sends the operator location and selected radius to the configured tracker, which queries FAA without exposing FAA credentials to this app. Apple MapKit may contact Apple's map service to load map content.")
                    .foregroundStyle(.secondary)
                Text("When protected-land checks are enabled, public NPS, USFWS, USFS, and Colorado Parks and Wildlife services receive a small geographic search area around the operator location. Returned boundaries are cached locally; these requests do not include aircraft tracks or an operator identity.")
                    .foregroundStyle(.secondary)
            }

            Section("Logs and deletion") {
                Text("You choose which log days to package. Diagnostic log messages omit location details. JSON track files contain aircraft positions and are excluded unless you explicitly include them.")
                Text("Nothing is transmitted until you send the prepared email or choose another destination. Local logs and track archives can be removed through the Files app. An iCloud backup remains until you remove it from iCloud Drive; deleting RID2Caltopo removes local data and the local backup passphrase. CalTopo and the configured tracker control retention of data sent to them.")
            }

            Section("Contact") {
                Link("RID2Caltopo website", destination: URL(string: "https://rid2caltopo.com/")!)
                Link("help@uas4sar.com", destination: URL(string: "mailto:help@uas4sar.com")!)
            }
        }
        .navigationTitle("About & Privacy")
    }

    private var appVersion: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(version) (\(build))"
    }
}
