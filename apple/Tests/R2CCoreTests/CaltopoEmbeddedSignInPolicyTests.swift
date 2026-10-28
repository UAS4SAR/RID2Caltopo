import Foundation
import Testing
@testable import R2CCore
#if canImport(WebKit)
import WebKit
#endif

private let googleSignInURLs = [
    "https://accounts.google.com/o/oauth2/v2/auth?client_id=test",
    "https://ACCOUNTS.GOOGLE.COM./v3/signin/identifier",
    "https://user@accounts.google.com/ServiceLogin",
    "https://accounts.google.com:443/gsi/iframe",
    "https://oauth2.googleapis.com/token",
    "https://www.googleapis.com/oauth2/v4/token",
    "https://www.google.com/accounts/ServiceLogin",
    "https://google.com/o/oauth2/auth",
    "https://caltopo.com/account/oidclogin?domain=google",
    "https://caltopo.com/account/oidclogin?url=%2F&domain=%67oogle",
    "https://maps.agency.example/base/account/oidclogin?domain=google"
]
private let permittedBrowserURLs = [
    "https://caltopo.com/account/login", "https://caltopo.com/join",
    "https://caltopo.com/api/v0/acct/login", "https://caltopo.com/map?id=ABC123",
    "https://caltopo.com/account/oidclogin?domain=apple",
    "https://caltopo.com/account/oidclogin?domain=msft",
    "https://maps.agency.example:8443/private/incident",
    "https://www.google.com/maps", "https://fonts.googleapis.com/css?family=test",
    "https://accounts.google.com.example.org/agency", "about:blank"
]

@Test func embeddedGoogleNavigationAndProviderEntryAreBlocked() {
    for raw in googleSignInURLs {
        #expect(CaltopoEmbeddedSignInPolicy.isGoogleSignIn(URL(string: raw)!))
        #expect(!CaltopoEmbeddedSignInPolicy.allowsEmbedded(URL(string: raw)!))
    }
}
@Test func emailPasswordAndPrivateAgencyNavigationRemainAvailable() {
    for raw in permittedBrowserURLs {
        #expect(!CaltopoEmbeddedSignInPolicy.isGoogleSignIn(URL(string: raw)!))
        #expect(CaltopoEmbeddedSignInPolicy.allowsEmbedded(URL(string: raw)!))
    }
    for raw in ["http://agency.example", "file:///tmp/page", "javascript:alert(1)", "r2cmapkg1:test"] {
        #expect(!CaltopoEmbeddedSignInPolicy.allowsEmbedded(URL(string: raw)!))
    }
}
@Test func googleRequestRulesMatchKnownAuthorizationAndProviderRoutes() throws {
    let rules = try JSONSerialization.jsonObject(with: Data(CaltopoEmbeddedSignInPolicy.contentRulesJSON.utf8)) as! [[String: Any]]
    let filters = try rules.map { rule -> NSRegularExpression in
        #expect((rule["action"] as? [String: String])?["type"] == "block")
        return try NSRegularExpression(pattern: (rule["trigger"] as! [String: Any])["url-filter"] as! String, options: [.caseInsensitive])
    }
    for raw in googleSignInURLs where !raw.contains("%67oogle") {
        #expect(filters.contains { $0.firstMatch(in: raw, range: NSRange(raw.startIndex..., in: raw)) != nil })
    }
    for raw in permittedBrowserURLs {
        #expect(!filters.contains { $0.firstMatch(in: raw, range: NSRange(raw.startIndex..., in: raw)) != nil })
    }
}
@Test func setupUsesCalTopoOwnedHttpsPagesAndNeverGoogleCredentials() {
    #expect(CaltopoEmbeddedSignInPolicy.setupURL.absoluteString == "https://caltopo.com/join")
    #expect(CaltopoEmbeddedSignInPolicy.loginURL.absoluteString == "https://caltopo.com/account/login")
    #expect(CaltopoEmbeddedSignInPolicy.setupInstructions.contains("exact email"))
    #expect(CaltopoEmbeddedSignInPolicy.setupInstructions.contains("does not ask for your Google password"))
}
#if canImport(WebKit)
@Test @MainActor func webKitCompilesTheActualGoogleRequestRulesAndKeepsExistingCookies() async throws {
    let identifier = "r2c-login-test-" + UUID().uuidString
    let store = WKContentRuleListStore.default()!
    let rule = try await store.compileContentRuleList(forIdentifier: identifier, encodedContentRuleList: CaltopoEmbeddedSignInPolicy.contentRulesJSON)
    let configuration = WKWebViewConfiguration()
    configuration.websiteDataStore = .nonPersistent()
    let cookie = HTTPCookie(properties: [.domain: "caltopo.com", .path: "/", .name: "test-existing-session", .value: "fake-unit-test-only", .secure: "TRUE"])!
    await configuration.websiteDataStore.httpCookieStore.setCookie(cookie)
    configuration.userContentController.add(rule!)
    let cookies = await configuration.websiteDataStore.httpCookieStore.allCookies()
    #expect(cookies.contains { $0.name == cookie.name && $0.value == cookie.value })
    try await store.removeContentRuleList(forIdentifier: identifier)
}
#endif
