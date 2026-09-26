import SwiftUI
import MessageUI

struct DiagnosticLogView: View {
    @ObservedObject var diagnostics: AppleDiagnosticsCenter
    @State private var showingMailComposer = false

    var body: some View {
        List {
            Section {
                Text("Today is selected automatically. The compressed ZIP includes device and app details and each selected log. Track files are optional and off by default because they contain aircraft locations.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text("Ordinary diagnostic messages omit location details; enabled research captures can include aircraft positions. The bundle can contain Remote IDs, the app-install coordination identifier, local network addresses, and operational status. It never includes the CalTopo credential secret.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("DJI SEI Research") {
                Toggle(
                    "Capture video telemetry samples",
                    isOn: Binding(
                        get: { AppleSEIHexDiagnostics.enabled },
                        set: { enabled in
                            AppleSEIHexDiagnostics.enabled = enabled
                            AppleLog.info(
                                "DjiSeiHex",
                                "DJI SEI hex capture \(enabled ? "enabled" : "disabled")"
                            )
                        }
                    )
                )
                Text("Captures up to 20 complete samples per message layout, with a 2 MiB text budget. Turn off for a summary; turn on again to sample arriving traffic. Research logs may contain aircraft positions. Original recordings are also preserved until the app restarts; this uses additional storage.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Select Log Days") {
                if diagnostics.days.isEmpty {
                    ContentUnavailableView("No logs", systemImage: "doc.text.magnifyingglass")
                } else {
                    ForEach(diagnostics.days) { day in
                        Button {
                            if diagnostics.selectedDays.contains(day.name) {
                                diagnostics.selectedDays.remove(day.name)
                            } else {
                                diagnostics.selectedDays.insert(day.name)
                            }
                        } label: {
                            HStack {
                                Image(systemName: diagnostics.selectedDays.contains(day.name) ? "checkmark.circle.fill" : "circle")
                                VStack(alignment: .leading) {
                                    Text(day.name)
                                    Text("\(day.logCount) log file\(day.logCount == 1 ? "" : "s")\(day.isToday ? " • today" : "")")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }

                Toggle("Include JSON track files", isOn: $diagnostics.includeTracks)
                Text("Off by default. Track files contain aircraft positions.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section {
                Button("Package Logs") {
                    Task { await diagnostics.prepareSelectedBundle() }
                }
                .disabled(diagnostics.selectedDays.isEmpty || diagnostics.isPreparing)

                if diagnostics.isPreparing {
                    ProgressView("Preparing log bundle…")
                }
                if let bundleURL = diagnostics.bundleURL {
                    if MFMailComposeViewController.canSendMail() {
                        Button {
                            showingMailComposer = true
                        } label: {
                            Label("Email diagnostics to developer", systemImage: "envelope")
                        }
                        .sheet(isPresented: $showingMailComposer) {
                            DiagnosticMailComposer(attachmentURL: bundleURL)
                        }
                    } else {
                        ShareLink(
                            item: bundleURL,
                            subject: Text("RID2Caltopo Diagnostics"),
                            message: Text("Please send these diagnostics to help@uas4sar.com")
                        ) {
                            Label("Share diagnostics…", systemImage: "square.and.arrow.up")
                        }
                    }
                }
                Text(diagnostics.status)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section {
                NavigationLink("About diagnostic data and privacy") {
                    AboutPrivacyView()
                }
            }
        }
        .navigationTitle("Send Diagnostics")
        .task { await diagnostics.beginPackagingSession() }
    }
}

private struct DiagnosticMailComposer: UIViewControllerRepresentable {
    let attachmentURL: URL

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let composer = MFMailComposeViewController()
        composer.mailComposeDelegate = context.coordinator
        composer.setToRecipients(["help@uas4sar.com"])
        composer.setSubject("RID2Caltopo Diagnostics")
        composer.setMessageBody("RID2Caltopo diagnostic bundle attached.", isHTML: false)
        if let data = try? Data(contentsOf: attachmentURL) {
            composer.addAttachmentData(
                data,
                mimeType: "application/zip",
                fileName: attachmentURL.lastPathComponent
            )
        }
        return composer
    }

    func updateUIViewController(_ uiViewController: MFMailComposeViewController, context: Context) {}

    final class Coordinator: NSObject, @MainActor MFMailComposeViewControllerDelegate {
        @MainActor
        func mailComposeController(
            _ controller: MFMailComposeViewController,
            didFinishWith result: MFMailComposeResult,
            error: Error?
        ) {
            controller.dismiss(animated: true)
        }
    }
}
