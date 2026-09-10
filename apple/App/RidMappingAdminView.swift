import R2CCore
import SwiftUI
import Foundation
import VisionKit

private struct AppleRidMappingDraft: Identifiable, Codable, Equatable {
    var id = UUID()
    var remoteID: String
    var ownerName: String
    var ownerCallsign: String
    var model: String

    init(identity: RidAircraftIdentity? = nil) {
        remoteID = identity?.remoteID ?? ""
        ownerName = identity?.ownerName ?? ""
        ownerCallsign = identity?.pilotCallsign ?? ""
        model = identity?.droneDescription ?? ""
    }
}

struct AppleTrackerEnrollmentResult: Sendable {
    let organization: String
    let trackerBaseURL: String
    let deviceToken: String
    let faaProxyURL: String
    let reauthenticationURL: URL?
}

struct AppleManagedOrganizationConfigResult: @unchecked Sendable {
    let snapshot: [String: Any]
    let versionMs: Int64
}

enum AppleTrackerEnrollmentClient {
    static let appLinkScheme = "r2cenroll"
    private static let enrollmentSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        configuration.waitsForConnectivity = false
        configuration.httpMaximumConnectionsPerHost = 2
        return URLSession(configuration: configuration)
    }()

    static func normalizedEnrollmentURL(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if isEnrollmentURL(trimmed) { return trimmed }
        guard let wrapper = URLComponents(string: trimmed),
              wrapper.scheme?.lowercased() == appLinkScheme,
              let nested = wrapper.queryItems?.first(where: { $0.name == "url" })?.value,
              isEnrollmentURL(nested)
        else { return nil }
        return nested.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func isEnrollmentURL(_ value: String) -> Bool {
        guard let components = URLComponents(
            string: value.trimmingCharacters(in: .whitespacesAndNewlines)
        ),
        components.scheme?.lowercased() == "https",
        let host = components.host?.lowercased(),
        host == "r2c-tracker.com" || host.hasSuffix(".r2c-tracker.com"),
        components.path.hasSuffix("/enroll"),
        components.queryItems?.first(where: { $0.name == "token" })?.value?.isEmpty == false
        else { return false }
        return true
    }

    static func enrollmentOrganization(_ value: String) -> String? {
        guard isEnrollmentURL(value),
              let components = URLComponents(
                  string: value.trimmingCharacters(in: .whitespacesAndNewlines)
              )
        else { return nil }
        let segments = components.path.split(separator: "/").map(String.init)
        guard segments.count >= 2 else { return nil }
        return segments[segments.count - 2]
    }

    static func redeem(
        _ value: String,
        deviceName: String,
        previousDeviceToken: String? = nil
    ) async throws -> AppleTrackerEnrollmentResult {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isEnrollmentURL(trimmed),
              let enrollment = URLComponents(string: trimmed),
              let token = enrollment.queryItems?.first(where: { $0.name == "token" })?.value,
              let scheme = enrollment.scheme,
              let host = enrollment.host
        else { throw EnrollmentError.invalidURL }
        var endpoint = URLComponents()
        endpoint.scheme = scheme
        endpoint.host = host
        endpoint.port = enrollment.port
        endpoint.path = "/api/v1/device-enrollment/redeem"
        guard let url = endpoint.url else { throw EnrollmentError.invalidURL }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(
            String(TrackerCoordinationClient.trackerFunctionalityRelease),
            forHTTPHeaderField: "X-R2C-Functionality-Release"
        )
        if let previousDeviceToken = previousDeviceToken?
            .trimmingCharacters(in: .whitespacesAndNewlines),
           !previousDeviceToken.isEmpty {
            request.setValue(
                previousDeviceToken,
                forHTTPHeaderField: "X-R2C-Previous-Device-Token"
            )
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "token": token,
            "device_name": deviceName,
            "device_model": AppleDeviceIdentity.modelName,
            "platform": "ios",
            "installation_id": AppleDeviceIdentity.installationID(),
            "functionality_release": TrackerCoordinationClient.trackerFunctionalityRelease,
        ])
        let requestStartedAt = ProcessInfo.processInfo.systemUptime
        AppleLog.info("OrgConfig", "Starting managed tracker enrollment network request")
        let (data, response) = try await enrollmentSession.data(for: request)
        let durationMs = Int(
            ((ProcessInfo.processInfo.systemUptime - requestStartedAt) * 1_000).rounded()
        )
        guard let http = response as? HTTPURLResponse else {
            throw EnrollmentError.invalidResponse
        }
        AppleLog.info(
            "OrgConfig",
            "Managed tracker enrollment response status=\(http.statusCode) durationMs=\(durationMs)"
        )
        guard (200 ..< 300).contains(http.statusCode) else {
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            throw EnrollmentError.server(
                object?["detail"] as? String ?? "Enrollment failed (\(http.statusCode))."
            )
        }
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let organization = object["organization"] as? [String: Any],
              let tracker = object["tracker"] as? [String: Any],
              let designator = organization["designator"] as? String,
              let baseURL = tracker["base_url"] as? String,
              let apiKey = tracker["api_key"] as? String,
              let faaProxyURL = tracker["faa_proxy_url"] as? String
        else { throw EnrollmentError.invalidResponse }
        return AppleTrackerEnrollmentResult(
            organization: designator,
            trackerBaseURL: baseURL,
            deviceToken: apiKey,
            faaProxyURL: faaProxyURL,
            reauthenticationURL: TrackerReauthenticationChallenge.url(
                fromEnrollmentResponse: data
            )
        )
    }

    static func fetchManagedOrganizationConfig(
        trackerBaseURL: String,
        deviceToken: String
    ) async throws -> AppleManagedOrganizationConfigResult? {
        guard let baseURL = URL(string: trackerBaseURL) else {
            throw EnrollmentError.invalidResponse
        }
        var request = URLRequest(
            url: baseURL.appendingPathComponent("api/v1/organization-config/current")
        )
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 60
        request.setValue(deviceToken, forHTTPHeaderField: "X-SAR-Token")
        request.setValue(
            String(TrackerCoordinationClient.trackerFunctionalityRelease),
            forHTTPHeaderField: "X-R2C-Functionality-Release"
        )
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw EnrollmentError.invalidResponse
        }
        if http.statusCode == 204 { return nil }
        guard (200 ..< 300).contains(http.statusCode),
              let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let versionMs = (root["versionMs"] as? NSNumber)?.int64Value,
              let snapshot = root["config"] as? [String: Any]
        else { throw EnrollmentError.invalidResponse }
        return AppleManagedOrganizationConfigResult(snapshot: snapshot, versionMs: versionMs)
    }

    enum EnrollmentError: LocalizedError {
        case invalidURL
        case invalidResponse
        case server(String)

        var errorDescription: String? {
            switch self {
            case .invalidURL: "Enrollment QR is not an r2c-tracker.com enrollment URL."
            case .invalidResponse: "The tracker returned an invalid enrollment response."
            case let .server(message): message
            }
        }
    }
}

struct RidMappingAdminView: View {
    @ObservedObject var organization: AppleOrgConfigSettings
    @ObservedObject var identities: AppleDroneConfirmationStore
    @Environment(\.dismiss) private var dismiss
    @State private var organizationName: String
    @State private var mappings: [AppleRidMappingDraft]
    @State private var errors: [String] = []
    @State private var showingValidationErrors = false

    private static let draftOrganizationKey = "org.ridMappingDraft.organization"
    private static let draftsKey = "org.ridMappingDraft.entries"
    private static let draftBaselineRemoteIDsKey = "org.ridMappingDraft.baselineRemoteIDs"

    init(
        organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore
    ) {
        self.organization = organization
        self.identities = identities
        let defaults = UserDefaults.standard
        let importedMappings = identities.importedMappings
        let importedRemoteIDs = Set(importedMappings.map { Self.normalizedRemoteID($0.remoteID) })
        let savedDrafts = defaults.data(forKey: Self.draftsKey)
            .flatMap { try? JSONDecoder().decode([AppleRidMappingDraft].self, from: $0) }
        let savedBaseline = (defaults.array(forKey: Self.draftBaselineRemoteIDsKey) as? [String])
            .map { Set($0.map(Self.normalizedRemoteID)) }
        let savedRemoteIDs = savedDrafts.map {
            Set($0.map { Self.normalizedRemoteID($0.remoteID) })
        }
        let draftMatchesImportedMappings = savedDrafts != nil && (
            savedBaseline == importedRemoteIDs
                || (savedBaseline == nil && savedRemoteIDs == importedRemoteIDs)
        )

        _organizationName = State(
            initialValue: draftMatchesImportedMappings
                ? (defaults.string(forKey: Self.draftOrganizationKey)
                    ?? organization.organizationName)
                : organization.organizationName
        )
        _mappings = State(
            initialValue: draftMatchesImportedMappings ? savedDrafts! : importedMappings.map {
                AppleRidMappingDraft(identity: $0)
            }
        )
        if savedDrafts != nil && !draftMatchesImportedMappings {
            defaults.removeObject(forKey: Self.draftOrganizationKey)
            defaults.removeObject(forKey: Self.draftsKey)
            defaults.removeObject(forKey: Self.draftBaselineRemoteIDsKey)
        }
    }

    var body: some View {
        Form {
            Section("Organization") {
                TextField("Organization designator", text: $organizationName)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                Text("Stored once and applied to every aircraft.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach($mappings) { $mapping in
                Section("Aircraft") {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Remote ID")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        AppleScannableTextField(
                            title: "Remote ID",
                            text: $mapping.remoteID,
                            mode: .remoteID
                        )
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Owner name")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        TextField("Owner name", text: $mapping.ownerName)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Owner callsign (for example 1SAR7)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        TextField("Owner callsign", text: $mapping.ownerCallsign)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Model")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        TextField("Model", text: $mapping.model)
                    }
                    LabeledContent("Drone designator", value: identity(for: mapping).mappedID)
                    Button("Remove aircraft", role: .destructive) {
                        mappings.removeAll { $0.id == mapping.id }
                    }
                }
            }
            Section {
                Button("Add aircraft", systemImage: "plus") {
                    mappings.append(AppleRidMappingDraft())
                }
            }
            if !errors.isEmpty {
                Section("Please correct") {
                    ForEach(errors, id: \.self) {
                        Text($0).foregroundStyle(.red)
                    }
                }
            }
        }
        .onChange(of: organizationName) { _, value in
            UserDefaults.standard.set(value, forKey: Self.draftOrganizationKey)
        }
        .onChange(of: mappings) { _, value in
            persistDrafts(value)
        }
        .alert("RID entries were not saved", isPresented: $showingValidationErrors) {
            Button("Review Fields", role: .cancel) {}
        } message: {
            Text(errors.joined(separator: "\n"))
        }
        .navigationTitle("RID Map Entries")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") { save() }
            }
        }
    }

    private func identity(for mapping: AppleRidMappingDraft) -> RidAircraftIdentity {
        RidAircraftIdentity(
            remoteID: mapping.remoteID.uppercased(),
            organization: organizationName,
            ownerName: mapping.ownerName,
            pilotCallsign: mapping.ownerCallsign,
            droneDescription: mapping.model
        )
    }

    private func save() {
        errors = validate()
        guard errors.isEmpty else {
            persistDrafts(mappings)
            showingValidationErrors = true
            return
        }
        organization.setOrganizationNameForRidMappings(organizationName)
        identities.replacePersistedMappings(mappings.map { identity(for: $0) })
        UserDefaults.standard.removeObject(forKey: Self.draftOrganizationKey)
        UserDefaults.standard.removeObject(forKey: Self.draftsKey)
        UserDefaults.standard.removeObject(forKey: Self.draftBaselineRemoteIDsKey)
        dismiss()
    }

    private func persistDrafts(_ drafts: [AppleRidMappingDraft]) {
        guard let data = try? JSONEncoder().encode(drafts) else { return }
        UserDefaults.standard.set(data, forKey: Self.draftsKey)
        UserDefaults.standard.set(
            identities.importedMappings.map { Self.normalizedRemoteID($0.remoteID) },
            forKey: Self.draftBaselineRemoteIDsKey
        )
    }

    private static func normalizedRemoteID(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    private func validate() -> [String] {
        var result: [String] = []
        if organizationName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            result.append("Organization is required.")
        }
        var remoteIDs: Set<String> = []
        var ownerModels: Set<String> = []
        for (offset, mapping) in mappings.enumerated() {
            let row = offset + 1
            let remoteID = mapping.remoteID
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .uppercased()
            let callsign = mapping.ownerCallsign.trimmingCharacters(in: .whitespacesAndNewlines)
            let model = mapping.model.trimmingCharacters(in: .whitespacesAndNewlines)
            if remoteID.range(of: #"^[A-Z0-9]+$"#, options: .regularExpression) == nil {
                result.append("Aircraft \(row): Remote ID must contain only A-Z and 0-9.")
            } else if !remoteIDs.insert(remoteID).inserted {
                result.append("Aircraft \(row): Remote ID is already listed.")
            }
            if callsign.range(
                of: #"^[0-9]+[A-Za-z]+[0-9]+(?:-[0-9]+)?$"#,
                options: .regularExpression
            ) == nil {
                result.append("Aircraft \(row): Owner callsign must look like 1SAR7 or 1SAR7-2.")
            }
            if model.isEmpty {
                result.append("Aircraft \(row): Model is required.")
            } else if !ownerModels.insert("\(callsign.lowercased())\u{0}\(model.lowercased())").inserted {
                result.append("Aircraft \(row): Model must be unique for this owner callsign.")
            }
        }
        return result
    }
}

enum AppleScannedFieldMode {
    case remoteID
    case credential
}

struct AppleScannableTextField: View {
    let title: String
    @Binding var text: String
    let mode: AppleScannedFieldMode
    var secure = false
    @State private var showingScanner = false

    var body: some View {
        HStack {
            Group {
                if secure {
                    SecureField(title, text: $text)
                } else {
                    TextField(title, text: $text)
                }
            }
            .textInputAutocapitalization(mode == .remoteID ? .characters : .never)
            .autocorrectionDisabled()

            Button {
                showingScanner = true
            } label: {
                Image(systemName: "viewfinder")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Scan \(title)")
            .disabled(!DataScannerViewController.isSupported || !DataScannerViewController.isAvailable)
        }
        .sheet(isPresented: $showingScanner) {
            AppleAlphanumericScannerSheet(title: title, mode: mode) { value in
                text = value
                showingScanner = false
            }
        }
    }
}

private struct AppleAlphanumericScannerSheet: View {
    let title: String
    let mode: AppleScannedFieldMode
    let onSelected: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var candidateCounts: [String: Int] = [:]

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                AppleAlphanumericDataScanner(mode: mode) { value, barcode in
                    let increment = barcode ? 3 : 1
                    candidateCounts[value, default: 0] += increment
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text(
                        mode == .remoteID
                            ? "Center the barcode or printed serial number. Confirm every character before saving."
                            : "Center one tuple value at a time. Confirm every character before saving."
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    if candidates.isEmpty {
                        Text("Looking for text or a barcode…")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(candidates, id: \.value) { candidate in
                            Button {
                                onSelected(candidate.value)
                            } label: {
                                HStack {
                                    Text(candidate.value)
                                        .font(.system(.body, design: .monospaced))
                                        .lineLimit(2)
                                    Spacer()
                                    if candidate.count >= 2 {
                                        Image(systemName: "checkmark.circle.fill")
                                            .foregroundStyle(.green)
                                    }
                                }
                            }
                            .buttonStyle(.borderedProminent)
                        }
                    }
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.regularMaterial)
            }
            .navigationTitle("Scan \(title)")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private var candidates: [(value: String, count: Int)] {
        candidateCounts
            .map { (value: $0.key, count: $0.value) }
            .sorted {
                if ($0.count >= 2) != ($1.count >= 2) { return $0.count >= 2 }
                if $0.count != $1.count { return $0.count > $1.count }
                return $0.value.count > $1.value.count
            }
            .prefix(5)
            .map { $0 }
    }
}

private struct AppleAlphanumericDataScanner: UIViewControllerRepresentable {
    let mode: AppleScannedFieldMode
    let onCandidate: (String, Bool) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(mode: mode, onCandidate: onCandidate)
    }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [
                .barcode(symbologies: [.qr, .code128, .code39, .code93, .dataMatrix, .pdf417, .aztec]),
                .text(languages: ["en-US"]),
            ],
            qualityLevel: .accurate,
            recognizesMultipleItems: true,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true
        )
        controller.delegate = context.coordinator
        try? controller.startScanning()
        return controller
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {
        if !controller.isScanning { try? controller.startScanning() }
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let mode: AppleScannedFieldMode
        let onCandidate: (String, Bool) -> Void

        init(mode: AppleScannedFieldMode, onCandidate: @escaping (String, Bool) -> Void) {
            self.mode = mode
            self.onCandidate = onCandidate
        }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            didAdd addedItems: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            process(addedItems)
        }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            didUpdate updatedItems: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            process(updatedItems)
        }

        private func process(_ items: [RecognizedItem]) {
            for item in items {
                switch item {
                case let .barcode(barcode):
                    guard let value = barcode.payloadStringValue else { continue }
                    emitCandidates(from: value, barcode: true)
                case let .text(text):
                    emitCandidates(from: text.transcript, barcode: false)
                @unknown default:
                    continue
                }
            }
        }

        private func emitCandidates(from raw: String, barcode: Bool) {
            for candidate in Self.candidates(from: raw, mode: mode) {
                onCandidate(candidate, barcode)
            }
        }

        private static func candidates(
            from raw: String,
            mode: AppleScannedFieldMode
        ) -> [String] {
            let upper = raw.uppercased()
            let pattern = mode == .remoteID
                ? #"[A-Z0-9]{8,24}"#
                : #"[A-Za-z0-9+/_=-]{4,256}"#
            guard let expression = try? NSRegularExpression(pattern: pattern) else { return [] }
            let source = mode == .remoteID ? upper : raw
            let range = NSRange(source.startIndex..., in: source)
            let ignored = ["SERIAL", "NUMBER", "REMOTEID", "CREDENTIAL"]
            var values: [String] = []
            for match in expression.matches(in: source, range: range) {
                guard let matchRange = Range(match.range, in: source) else { continue }
                let value = String(source[matchRange])
                guard !ignored.contains(value.uppercased()), !values.contains(value) else { continue }
                values.append(value)
            }
            return values
        }
    }
}
