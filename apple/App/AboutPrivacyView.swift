import SwiftUI
import R2CCore

struct AppleReleaseNotesView: View {
    @State private var notes: String?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Version \(appVersion)").font(.title2.bold())
                if let notes {Text(notes)} else {ProgressView("Loading release notes…")}
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .textSelection(.enabled)
        }
        .navigationTitle("Release Notes")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let loaded=await Task.detached(priority:.userInitiated) {
                guard let url=Bundle.main.url(forResource:"whats_new",withExtension:"txt"),
                      let text=try? String(contentsOf:url,encoding:.utf8) else {return "Release notes are unavailable in this build."}
                return text
            }.value
            if !Task.isCancelled {notes=loaded}
        }
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
                NavigationLink("Privacy policy") {
                    BundledInformationView(document: .privacyPolicy)
                }
                Text("RID2Caltopo contains no advertising, does not track people across apps or websites, and does not sell personal data.")
                Text("This app contains no analytics SDKs.")
                Text("Remote ID observations, track archives, and diagnostic logs remain on this device unless you enable CalTopo publishing, load a configuration that enables eligible team-track upload or tracker peer coordination, or explicitly share a file.")
                Text("The CalTopo credential secret and tracker device key are stored in Apple Keychain.")
                Text("CalTopo and organization credentials stay saved on this device and are reused across app restarts and updates. You do not need to enter them for each connection.")
                Text("Settings backups contain supported preferences and aircraft mappings, without saved CalTopo, tracker or FAA credentials. Restoring a settings backup does not erase credentials already saved on that device or set up access on another device.")
                Text("If you enable iCloud configuration backup, operational settings and aircraft mappings, excluding access credentials, are encrypted with your passphrase before they are saved to the app's iCloud Drive container. The automatic-backup passphrase remains in this device's Keychain.")
                Text("Local video and mapping analysis run on this device. Original photos, controller recordings, clue images, and exported maps can contain coordinates, capture times, aircraft identifiers, and other embedded metadata.")
            }

            Section("Permissions and network use") {
                Label("Bluetooth receives nearby ASTM Remote ID broadcasts.", systemImage: "antenna.radiowaves.left.and.right")
                Label("Location places the operator relative to aircraft and supports configured incident coordination. An active monitoring session can continue location updates in the background.", systemImage: "location")
                Text("The system shows a location indicator during background location updates.")
                Label("Local-network access exchanges controller video, mapping missions and original photos, map tile packages, and optional external Remote ID observations.", systemImage: "network")
                Label("Camera scans configuration codes and aircraft/account identifiers.", systemImage: "camera")
                Label("Sending microphone audio is separately enabled for authorized VoIP; controller media may already contain source audio.", systemImage: "mic")
                Text("When enabled by the operator, CalTopo receives aircraft positions and telemetry for the selected map. Configured tracker peer coordination receives the app-install zone identifier, device zone name, operator position, confirmed drone identity, and aircraft sightings needed to coordinate ownership. Nearby NOTAM monitoring sends the operator location and selected radius to the configured tracker, which queries FAA without exposing FAA credentials to this app.")
                Text("Map layers can contact their configured providers to load map content.")
                Text("Apple MapKit may contact Apple's map service to load map content.")
                Text("When protected-land checks are enabled, public NPS, USFWS, USFS, and Colorado Parks and Wildlife services receive a small geographic search area around the operator location. Returned boundaries are cached locally; these requests do not include aircraft tracks or an operator identity.")
                Text("Controller and local-browser connections can exchange flight plans, settings, aircraft and controller identifiers, telemetry, and original photographs. CalTopo sign-in uses its provider pages and a retained local browser session. Organizations and shared/public maps control who can see information sent to their services.")
            }

            Section("Video sharing and recording") {
                Text("Configured tracker coordination can publish stream/recording catalogs and preview thumbnails before full-session approval. Thumbnails and links can also appear on a configured CalTopo map.")
                Text("Full viewing and recording-file transfers use per-request approval by default. Enabling Remote Video Control authorizes supported requests without a prompt each time. It does not unmute outgoing voice, and turning it off does not stop catalog/thumbnail sharing. Saving this setting as off ends sessions and transfers using advance authorization and cancels queued automatic grants. Requests you approved individually remain approved.")
                Text("The microphone switch controls whether your voice is sent to the other participant. You can still hear incoming sound while your voice is muted.")
                Text("During an active voice connection, this device's system audio session can keep the microphone active even when your outgoing voice is muted.")
                Text("WebRTC setup and link tests exchange network/connection information and performance estimates before media starts. Media can pass through configured relays. The tracker can retain access, session, and usage records. Downloaded recordings can include source audio; recipients can retain copies.")
                Text("The app uses MediaMTX, a media server running on this device, to receive incoming RTMP streams from controllers. Capture Streams controls whether those streams are recorded locally and is initially enabled. Restrict media server access is initially enabled; turning it off permits direct local-network viewing outside tracker approval.")
            }

            Section("Logs and deletion") {
                Text("When sending diagnostics to the developer, you choose which log days to package. Diagnostic log messages omit location details. JSON track files contain aircraft positions and are excluded unless you explicitly include them.")
                Text("Log sharing sends nothing until you send the prepared message or choose a share destination. The diagnostic bundle contains selected logs and optional track files, not saved-configuration files. Bundles can contain operational records and performance/connection diagnostics. CalTopo publishing, tracker coordination, and video sharing operate separately, according to their own settings.")
                Text("Remove local files through the app's library or system file manager. Local deletion does not recall recipient copies or delete service records.")
                Text("Clear login removes the retained CalTopo browser session.")
                Text("Reset persisted app state clears saved settings and app-accessible Keychain secrets while retaining operational files; quit and reopen afterward. Do not rely on uninstalling alone to erase Keychain credentials.")
                Text("Ask the configured service operator about server retention, access revocation, and deletion.")
                Text("Turning automatic backup off stops future automatic backups, but retains its saved passphrase and cloud file. Remove the backup from iCloud Drive separately.")
            }

            Section("Services & data") {
                NavigationLink("Service & data acknowledgments") {
                    BundledInformationView(document: .serviceAcknowledgments)
                }
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

private struct BundledInformationView: View {
    let document: ApplicationInformationDocument
    @State private var sections: [String]?
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                if let sections {
                    ForEach(sections.indices, id: \.self) { index in
                        Text(sections[index]).frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                } else { ProgressView("Loading…") }
            }
            .padding()
        }
        .navigationTitle(document.title)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: document.rawValue) {
            let selected = document
            let loaded = await Task.detached(priority: .userInitiated) {
                (try? selected.sections()) ?? ["This document could not be loaded. Please contact help@uas4sar.com."]
            }.value
            if !Task.isCancelled { sections = loaded }
        }
    }
}
