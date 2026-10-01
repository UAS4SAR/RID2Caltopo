import Foundation
import SwiftUI
import WebKit
import VisionKit
import AVFoundation
import R2CCore

private final class PersonalProbeRedirectBlocker: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

@MainActor
final class PersonalProbeModel: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    // A dedicated persistent WebKit profile retains site login/trust across
    // app launches. Only explicit Clear login removes the profile's website data.
    static let shared = PersonalProbeModel()
    let web: WKWebView
    @Published var link = ""
    @Published var map = ""
    @Published var status = "Sign in if needed. Your login is retained until you clear it or it expires."
    @Published var origin = CaltopoPersonalProbe.origin
    @Published var busy = false
    @Published var catalogFailed = false
    @Published var pendingMarker: UUID?
    private static let pendingURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("personal-probe-pending-marker.txt")
    private var accountID: String?
    private var catalogCompletion: ((String, [CaltopoTeamMapNode]) -> Void)?
    private var pickerSelection: ((CaltopoTeamMap) -> Void)?
    private var pickerReady = false
    private var generation = 0
    private var sessionGeneration = UUID()
    private var clearingLogin = false
    private let redirectBlocker = PersonalProbeRedirectBlocker()
    private var requestTask: Task<Void, Never>?

    private override init() {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = WKWebsiteDataStore(forIdentifier: UUID(uuidString: "4DCD198B-FE86-4AC4-A487-E419FECC42A8")!)
        web = WKWebView(frame: .zero, configuration: configuration)
        web.isInspectable = false
        super.init()
        if let saved = try? String(contentsOf: Self.pendingURL, encoding: .utf8) {
            pendingMarker = UUID(uuidString: saved)
        }
        applyStoragePolicy()
        web.navigationDelegate = self
        web.uiDelegate = self
        web.load(URLRequest(url: URL(string: CaltopoPersonalProbe.origin)!))
    }

    /// Best effort: this WebKit-owned layout is observed, not a public path contract.
    /// No cookie contents or filenames are exported. Background flight uploads remain
    /// available after first unlock; browser trust state stays persistent locally.
    private func applyStoragePolicy() {
        let manager = FileManager.default
        let storeRoot = manager.urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("WebKit/WebsiteDataStore", isDirectory: true)
        let identifier = UUID(uuidString: "4DCD198B-FE86-4AC4-A487-E419FECC42A8")!
        let profile = (try? manager.contentsOfDirectory(at: storeRoot, includingPropertiesForKeys: [.isSymbolicLinkKey]))?
            .first { UUID(uuidString: $0.lastPathComponent) == identifier }
            ?? storeRoot.appendingPathComponent(identifier.uuidString.lowercased(), isDirectory: true)
        var checked = 0, protected = 0, excluded = 0, failures = 0
        if manager.fileExists(atPath: profile.path) {
            let descendants = (manager.enumerator(at: profile, includingPropertiesForKeys: [.isSymbolicLinkKey], options: [])?.allObjects as? [URL]) ?? []
            for var url in [profile] + descendants {
                do {
                    guard try url.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else { continue }
                    var values = URLResourceValues(); values.isExcludedFromBackup = true
                    try url.setResourceValues(values)
                    let existing = try manager.attributesOfItem(atPath: url.path)[.protectionKey] as? FileProtectionType
                    if existing == nil || existing == FileProtectionType.none {
                        try manager.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
                    }
                    checked += 1
                    if let protection = try manager.attributesOfItem(atPath: url.path)[.protectionKey] as? FileProtectionType,
                       protection != .none { protected += 1 }
                    if try url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true { excluded += 1 }
                } catch { failures += 1 }
            }
        }
        let report = "Personal browser storage metadata: checked=\(checked), protectedFiles=\(protected), excludedFromBackup=\(excluded), failures=\(failures). Missing profile or incomplete counts requires device verification; backup restore not tested."
        let output = manager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("personal-browser-storage-metadata.txt")
        try? manager.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? report.write(to: output, atomically: true, encoding: .utf8)
    }

    func loadNativeCatalog() async throws -> (String, [CaltopoTeamMapNode]) {
        guard !clearingLogin else { throw CancellationError() }
        let started = sessionGeneration
        // The retained browser starts loading in init. Give cookie restoration a
        // chance to finish offscreen before offering interactive sign-in.
        for _ in 0..<75 {
            guard web.isLoading else { break }
            try await Task.sleep(for: .milliseconds(200))
        }
        guard web.url?.scheme == "https", web.url?.host == "caltopo.com",
              let encoded = try await web.evaluateJavaScript(CaltopoPersonalCatalog.identityScript) as? String,
              let identity = try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: String],
              let id = identity["id"], id.range(of: "^[A-Za-z0-9]+$", options: .regularExpression) != nil
        else { throw CaltopoLiveClientError.httpStatus(401, "Open CalTopo to sign in and load personal maps.") }
        guard !clearingLogin, sessionGeneration == started else { throw CancellationError() }
        if accountID != id {
            accountID = id
            sessionGeneration = UUID()
            await CaltopoPersonalSessions.shared.clear()
        }
        let sessionEpoch = sessionGeneration
        let registryEpoch = await CaltopoPersonalSessions.shared.epoch()
        guard accountID == id, sessionGeneration == sessionEpoch else { throw CancellationError() }
        let url = URL(string: "https://caltopo.com/sideload/account/\(id).json?json=%7Bfull%3A%20true%7D")!
        guard let cookie = await boundCookie(url, account: id, epoch: sessionEpoch) else { throw CaltopoLiveClientError.httpStatus(401, "Personal login expired.") }
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil; config.httpShouldSetCookies = false; config.urlCache = nil
        let session = URLSession(configuration: config, delegate: redirectBlocker, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: url)
        request.httpShouldHandleCookies = false
        request.setValue(cookie, forHTTPHeaderField: "Cookie")
        let (data, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw CaltopoLiveClientError.httpStatus(401, "Unable to load personal maps. Check your login.") }
        let account = try CaltopoPersonalCatalog.account(data)
        guard let username = account["username"] as? String, !username.isEmpty,
              accountID == id, sessionGeneration == sessionEpoch else { throw CaltopoLiveClientError.invalidConfiguration }
        let normalized = try CaltopoPersonalCatalog.normalize(data, expectedID: id, username: username)
        let catalog = try JSONSerialization.jsonObject(with: normalized) as! [String: Any]
        let mediaOwners = catalog["mediaOwners"] as? [String: String] ?? [:]
        let decoded = try CaltopoTeamMapDecoder.decode(data: normalized).sorted {
            func rank(_ node: CaltopoTeamMapNode) -> Int {
                node.id == "virtual_recents" ? 0 : (node.id == id ? 1 : 2)
            }
            return rank($0) < rank($1)
        }
        func grant(_ node: CaltopoTeamMapNode) async -> CaltopoTeamMapNode {
            switch node {
            case let .directory(id, title, children):
                var converted = [CaltopoTeamMapNode]()
                for child in children { converted.append(await grant(child)) }
                return .directory(id: id, title: title, children: converted)
            case let .map(map):
                let token = UUID()
                await CaltopoPersonalSessions.shared.register(token, epoch: registryEpoch) { [weak self] url in
                    guard (url.path.hasPrefix("/api/v1/map/\(map.id)/") || CaltopoPersonalProbe.mediaID(url.path) != nil) else { return nil }
                    return await self?.boundCookie(url, account: id, epoch: sessionEpoch)
                }
                return .map(CaltopoTeamMap(id: map.id, title: map.title, updatedMilliseconds: map.updatedMilliseconds, personalSessionID: token, personalAccountID: id, personalMediaOwnerID: mediaOwners[map.id] ?? id))
            }
        }
        var result = [CaltopoTeamMapNode]()
        for node in decoded { result.append(await grant(node)) }
        guard accountID == id, sessionGeneration == sessionEpoch else { throw CancellationError() }
        return (username, result)
    }
    func beginCatalogLogin(_ completion: @escaping (String, [CaltopoTeamMapNode]) -> Void) {
        catalogCompletion = completion
        status = "Sign in to load your maps in RID2Caltopo."
        web.load(URLRequest(url: URL(string: CaltopoPersonalProbe.origin)!))
    }
    func completeCatalogLoginIfReady() {
        guard let completion = catalogCompletion, !busy else { return }
        busy = true
        catalogFailed = false
        status = "Loading your personal maps…"
        Task {
            defer { busy = false }
            if let (username, maps) = try? await loadNativeCatalog(), catalogCompletion != nil {
                catalogCompletion = nil
                completion(username, maps)
            } else if catalogCompletion != nil {
                catalogFailed = true
                status = "Personal maps could not load. Sign in below, then tap Retry maps. Your existing credentials have not changed."
            }
        }
    }
    func loadMaps(_ completion: @escaping (String, [CaltopoTeamMapNode]) -> Void) {
        catalogCompletion = completion
        completeCatalogLoginIfReady()
    }
    func beginPicker(_ onSelect: @escaping (CaltopoTeamMap) -> Void) {
        pickerSelection = onSelect
        pickerReady = false
        status = "Open a map from CalTopo’s Your Data to select your Incident Map."
        web.load(URLRequest(url: URL(string: CaltopoPersonalProbe.origin)!))
    }
    func endPicker() {
        catalogCompletion = nil
        pickerSelection = nil
        pickerReady = false
        generation += 1
    }
    func selectIncidentMap(_ onSelect: @escaping (CaltopoTeamMap) -> Void) {
        guard let id = CaltopoPersonalProbe.mapID(map) else { status = "Open a map below, or enter its map ID/link."; return }
        guard !busy else { return }
        let attempt = generation
        busy = true
        status = "Connecting to personal incident map \(id)…"
        Task {
            defer { busy = false }
            // Resolve through the same account-bound catalog grants as native selection.
            guard let (_, nodes) = try? await loadNativeCatalog(), generation == attempt else { return }
            func find(_ nodes: [CaltopoTeamMapNode]) -> CaltopoTeamMap? {
                for node in nodes {
                    switch node {
                    case let .map(map): if map.id == id { return map }
                    case let .directory(_, _, children): if let map = find(children) { return map }
                    }
                }
                return nil
            }
            guard let selected = find(nodes) else {
                status = "This map is not in your personal account catalog. Use Select Map to choose an available map."
                return
            }
            onSelect(selected)
        }
    }
    private func boundCookie(_ url: URL, account: String, epoch: UUID) async -> String? {
        guard !clearingLogin, !web.isLoading, accountID == account, sessionGeneration == epoch else { return nil }
        let raw = try? await web.evaluateJavaScript(CaltopoPersonalCatalog.identityScript) as? String
        let identity = raw.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: String] }
        guard identity?["id"] == account, accountID == account, sessionGeneration == epoch else { return nil }
        let cookie = await sessionCookie(url)
        let finalRaw = try? await web.evaluateJavaScript(CaltopoPersonalCatalog.identityScript) as? String
        let finalIdentity = finalRaw.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: String] }
        guard !clearingLogin, !web.isLoading, finalIdentity?["id"] == account,
              accountID == account, sessionGeneration == epoch, !Task.isCancelled else { return nil }
        return cookie
    }
    private func sessionCookie(_ url: URL) async -> String? {
        let cookies = await web.configuration.websiteDataStore.httpCookieStore.allCookies()
        return CaltopoPersonalProbe.cookieHeader(cookies, for: url)
    }

    func openLink() {
        guard let url = CaltopoPersonalProbe.browserURL(link) else {
            status = "Enter an https://caltopo.com invitation or map link."; return
        }
        pickerReady = true
        web.load(URLRequest(url: url))
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void) {
        let url = navigationAction.request.url
        decisionHandler(url?.scheme == "https" || url?.absoluteString == "about:blank" ? .allow : .cancel)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        applyStoragePolicy()
        guard let url = webView.url else { return }
        origin = "\(url.scheme ?? "")://\(url.host ?? "")"
        let observedGeneration = sessionGeneration
        Task {
            if url.scheme == "https", url.host == "caltopo.com", url.port == nil {
                let raw = try? await web.evaluateJavaScript(CaltopoPersonalCatalog.identityScript) as? String
                let identity = raw.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: String] }
                guard !clearingLogin, sessionGeneration == observedGeneration, web.url == url else { return }
                let nextID = identity?["id"]
                if accountID != nextID {
                    accountID = nextID
                    sessionGeneration = UUID()
                    await CaltopoPersonalSessions.shared.clear()
                }
            }
            completeCatalogLoginIfReady()
        }
        if let id = CaltopoPersonalProbe.mapID(url.absoluteString) {
            map = id
            if pickerReady, let onSelect = pickerSelection { selectIncidentMap(onSelect) }
        }
        pickerReady = true
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil, navigationAction.request.url?.scheme == "https" {
            webView.load(navigationAction.request)
        }
        return nil
    }

    func clear(reload: Bool = true) {
        clearingLogin = true
        accountID = nil
        sessionGeneration = UUID()
        generation += 1
        requestTask?.cancel()
        requestTask = nil
        busy = true
        web.stopLoading()
        web.loadHTMLString("", baseURL: nil)
        let attempt = sessionGeneration
        Task {
            await CaltopoPersonalSessions.shared.clear()
            await web.configuration.websiteDataStore.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast)
            guard sessionGeneration == attempt else { return }
            busy = false
            clearingLogin = false
            status = "Local login cleared."
            if reload { web.load(URLRequest(url: URL(string: CaltopoPersonalProbe.origin + "/account/login")!)) }
        }
    }

    func check() {
        guard let id = CaltopoPersonalProbe.mapID(map), let url = CaltopoPersonalProbe.endpoint(id) else {
            status = "Enter a map ID or CalTopo map link."; return
        }
        busy = true
        status = "Comparing personal and anonymous read requests…"
        let attempt = generation
        requestTask = Task { [weak self] in
            guard let self else { return }
            let cookies = await web.configuration.websiteDataStore.httpCookieStore.allCookies()
            guard !Task.isCancelled, generation == attempt else { return }
            guard let cookie = CaltopoPersonalProbe.cookieHeader(cookies, for: url) else {
                status = "No CalTopo browser cookies. Sign in first."; busy = false; return
            }
            let config = URLSessionConfiguration.ephemeral
            config.httpCookieStorage = nil
            config.httpShouldSetCookies = false
            config.urlCache = nil
            config.timeoutIntervalForRequest = 30
            config.timeoutIntervalForResource = 30
            let session = URLSession(configuration: config, delegate: redirectBlocker, delegateQueue: nil)
            defer { session.invalidateAndCancel() }
            do {
                let personal = try await fetch(session: session, url: url, cookie: cookie)
                let anonymous = try await fetch(session: session, url: url, cookie: nil)
                guard !Task.isCancelled, generation == attempt else { return }
                status = "Personal: \(personal.summary)\nAnonymous: \(anonymous.summary)\n\(CaltopoPersonalProbe.comparison(personal: personal, anonymous: anonymous))"
            } catch {
                guard !Task.isCancelled, generation == attempt else { return }
                status = "Map check could not complete. Check the connection and retry. No credentials were logged."
            }
            busy = false
        }
    }

    func testMarker() {
        guard CaltopoPersonalProbe.mapID(map) == CaltopoPersonalMarkerTest.mapID else {
            status = "Enter G00CPSS, the designated test map."; return
        }
        let cleanup = pendingMarker != nil
        let id = pendingMarker ?? UUID()
        let attempt = generation
        busy = true
        status = "Testing marker publishing on G00CPSS…"
        requestTask = Task { [weak self] in
            guard let self else { return }
            let cookies = await web.configuration.websiteDataStore.httpCookieStore.allCookies()
            guard !Task.isCancelled, generation == attempt else { return }
            let config = URLSessionConfiguration.ephemeral
            config.httpCookieStorage = nil
            config.httpShouldSetCookies = false
            config.urlCache = nil
            config.timeoutIntervalForRequest = 30
            config.timeoutIntervalForResource = 30
            let session = URLSession(configuration: config, delegate: redirectBlocker, delegateQueue: nil)
            defer { session.invalidateAndCancel() }
            do {
                status = try await CaltopoPersonalMarkerTest.run(id: id, cleanupOnly: cleanup, send: { method, path, payload in
                    try Task.checkCancellation()
                    guard self.generation == attempt else { throw CancellationError() }
                    let url = URL(string: CaltopoPersonalProbe.origin + path)!
                    guard let cookie = CaltopoPersonalProbe.cookieHeader(cookies, for: url) else {
                        throw CaltopoPersonalMarkerTest.Failure(message: "Sign in to CalTopo first.")
                    }
                    var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
                    request.httpShouldHandleCookies = false
                    request.httpMethod = method
                    request.setValue(cookie, forHTTPHeaderField: "Cookie")
                    request.setValue("application/json", forHTTPHeaderField: "Accept")
                    request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
                    request.setValue(CaltopoPersonalProbe.origin, forHTTPHeaderField: "Origin")
                    request.setValue(CaltopoPersonalProbe.origin + "/m/" + CaltopoPersonalMarkerTest.mapID, forHTTPHeaderField: "Referer")
                    request.setValue("RID2Caltopo-PersonalSession-Experiment", forHTTPHeaderField: "User-Agent")
                    if let payload {
                        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
                        var encoded = URLComponents()
                        encoded.queryItems = [URLQueryItem(name: "json", value: payload)]
                        request.httpBody = encoded.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B").data(using: .utf8)
                    }
                    let (data, response) = try await session.data(for: request)
                    return .init(code: (response as? HTTPURLResponse)?.statusCode ?? -1, data: data)
                }, savePending: { markerID in
                    try FileManager.default.createDirectory(at: Self.pendingURL.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try Data(markerID.uuidString.utf8).write(to: Self.pendingURL, options: .atomic)
                    self.pendingMarker = markerID
                }, clearPending: {
                    if FileManager.default.fileExists(atPath: Self.pendingURL.path) {
                        try FileManager.default.removeItem(at: Self.pendingURL)
                    }
                    self.pendingMarker = nil
                })
            } catch let error as CaltopoPersonalMarkerTest.Failure {
                if generation == attempt { status = error.message }
            } catch {
                if generation == attempt { status = "Test interrupted or connection failed. Use cleanup if a marker is pending." }
            }
            guard !Task.isCancelled, generation == attempt else { return }
            if let pendingMarker { status += "\nPending marker: \(pendingMarker.uuidString.lowercased())" }
            busy = false
        }
    }

    private func fetch(session: URLSession, url: URL, cookie: String?) async throws -> CaltopoPersonalProbe.Result {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.httpShouldHandleCookies = false
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.setValue("RID2Caltopo-PersonalSession-Experiment", forHTTPHeaderField: "User-Agent")
        request.setValue(cookie, forHTTPHeaderField: "Cookie")
        let (data, response) = try await session.data(for: request)
        return CaltopoPersonalProbe.result(code: (response as? HTTPURLResponse)?.statusCode ?? -1, data: data)
    }
}

private struct PersonalProbeBrowser: UIViewRepresentable {
    let web: WKWebView
    func makeUIView(context: Context) -> WKWebView { web }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

struct AppleCaltopoPersonalProbeView: View {
    @ObservedObject private var model = PersonalProbeModel.shared
    var onSelect: ((CaltopoTeamMap) -> Void)? = nil
    var onCatalog: ((String, [CaltopoTeamMapNode]) -> Void)? = nil
    var diagnostics = false
    @Environment(\.dismiss) private var dismiss
    @State private var showScanner = false
    @State private var started = false
    @State private var confirmMarker = false
    var body: some View {
        VStack(spacing: 8) {
            Text("Sign in or open a CalTopo invitation. Load maps returns to RID2Caltopo with your available maps. Close keeps your login.")
                .font(.footnote)
            HStack {
                TextField("CalTopo invitation or map link", text: $model.link)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                Button { scanQR() } label: { Image(systemName: "qrcode.viewfinder") }
                    .accessibilityLabel("Scan CalTopo QR code").disabled(model.busy)
                Button("Open link") { model.openLink() }.disabled(model.busy)
            }
            if diagnostics {
                TextField("Test map ID or link", text: $model.map)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Check personal map access") { model.check() }.disabled(model.busy)
                Button(model.pendingMarker == nil ? "Test marker publishing on G00CPSS" : "Clean up pending test marker") {
                    confirmMarker = true
                }.disabled(model.busy)
            }
            Text(model.status).font(.footnote).textSelection(.enabled)
            Text(model.origin).font(.caption).lineLimit(1)
            HStack {
                Button("Back") { model.web.goBack() }.disabled(!model.web.canGoBack)
                Button("Clear login") { model.clear() }.disabled(model.busy)
                Button(model.busy ? "Loading…" : (model.catalogFailed ? "Retry maps" : "Load maps")) {
                    model.loadMaps { username, maps in
                        if let onCatalog { onCatalog(username, maps) } else { dismiss() }
                    }
                }.disabled(model.busy)
                Button("Close") { dismiss() }
            }
            PersonalProbeBrowser(web: model.web).allowsHitTesting(!model.busy)
        }
        .padding(.horizontal)
        .onAppear {
            guard !started else { return }
            started = true
            model.catalogFailed = false
            if let onCatalog { model.beginCatalogLogin(onCatalog) } else if let onSelect { model.beginPicker(onSelect) }
        }
        .onDisappear { if !showScanner { model.endPicker() } }
        .sheet(isPresented: $showScanner) {
            NavigationStack {
                QRCodeScannerView { value in
                    showScanner = false
                    if let url = CaltopoPersonalProbe.browserURL(value) {
                        model.link = url.absoluteString
                        model.status = "Link scanned. Tap Open link to continue."
                    } else { model.status = "This QR code is not a CalTopo invitation or map link." }
                }
                .navigationTitle("Scan CalTopo QR")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { showScanner = false } } }
            }
        }
        .navigationTitle("Personal CalTopo login")
        .navigationBarTitleDisplayMode(.inline)
        .alert("Test marker on G00CPSS", isPresented: $confirmMarker) {
            Button(model.pendingMarker == nil ? "Run test" : "Clean up") { model.testMarker() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(model.pendingMarker == nil ? "Creates a labeled marker at 0,0, reads it back, removes it, and checks removal. Other map objects are left alone." : "Checks and removes only this experiment's pending marker.")
        }
    }
    private func scanQR() {
        Task { @MainActor in
            guard DataScannerViewController.isSupported else {
                model.status = "QR scanning is unavailable on this device. Paste the CalTopo link instead."; return
            }
            let allowed = await AVCaptureDevice.requestAccess(for: .video)
            guard allowed, DataScannerViewController.isAvailable else {
                model.status = "Camera access is unavailable. Allow camera access in Settings or paste the link."; return
            }
            showScanner = true
        }
    }

}
