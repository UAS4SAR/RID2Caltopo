import Foundation

/// Blocks Google authorization without restricting ordinary HTTPS agency browsing.
/// This policy has no access to cookies, account grants or credential storage.
public enum CaltopoEmbeddedSignInPolicy {
    public static let loginURL = URL(string: "https://caltopo.com/account/login")!
    public static let setupURL = URL(string: "https://caltopo.com/join")!
    public static let instructionsURL = URL(string: "https://blog.caltopo.com/2026/08/05/introducing-first-party-logins-for-caltopo/")!
    public static let blockedMessage = "Google sign-in is unavailable in this embedded browser. Use CalTopo email/password. Saved CalTopo sessions are kept."
    public static let setupInstructions = """
    Already signed in here? You can continue using your saved CalTopo session.

    To add email/password to an existing Google-backed CalTopo account:
    1. Open setup in your regular browser. CalTopo may ask you to sign out there to reach Sign up.
    2. Choose email/password and use the exact email on your existing CalTopo account. CalTopo links matching accounts. Check that your maps and subscription are present.
    3. Return here and choose Email/password login. Enter your CalTopo credentials on CalTopo’s page.

    Browser sign-in does not transfer into this app. This app does not ask for your Google password. Setup and blocking do not clear this app’s saved session. Use Clear login only when you intend to remove it.
    """

    public static func isGoogleSignIn(_ url: URL) -> Bool {
        let host = (url.host ?? "").lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        let path = url.path.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if host == "accounts.google.com" || host.hasSuffix(".accounts.google.com") || host == "oauth2.googleapis.com" { return true }
        if ["google.com", "www.google.com"].contains(host),
           ["accounts", "signin", "o/oauth2"].contains(where: { path == $0 || path.hasPrefix($0 + "/") }) { return true }
        if host == "www.googleapis.com", path == "oauth2" || path.hasPrefix("oauth2/") { return true }
        // Block CalTopo's actual provider-selection route before its redirect starts.
        if path == "account/oidclogin" || path.hasSuffix("/account/oidclogin") {
            return (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).contains {
                $0.name.lowercased() == "domain" && ["google", "google.com", "accounts.google.com"].contains(($0.value ?? "").lowercased())
            }
        }
        return false
    }

    public static func allowsEmbedded(_ url: URL) -> Bool {
        (url.scheme?.lowercased() == "https" || url.absoluteString == "about:blank") && !isGoogleSignIn(url)
    }

    /// WebKit request-level protection also covers frames, POST and fetch requests.
    public static let requestFilters: [String] = {
        // WebKit's rule compiler does not support regex disjunctions. Keep
        // endpoint and end-of-string variants as separate blocking rules.
        let credentials = #"^https?://([^/?#]*@)?"#
        var rules: [String] = []
        for host in [#"([^/?#]+\.)?accounts\.google\.com\.*"#, #"oauth2\.googleapis\.com\.*"#] {
            rules += [credentials + host + "[:/]", credentials + host + "$"]
        }
        for path in ["accounts", "signin", "o/oauth2"] {
            let base = credentials + #"(www\.)?google\.com\.*(:[0-9]+)?/"# + path
            rules += [base + #"[/\?#]"#, base + "$"]
        }
        let api = credentials + #"www\.googleapis\.com\.*(:[0-9]+)?/oauth2"#
        rules += [api + #"[/\?#]"#, api + "$"]
        for provider in ["google", #"google\.com"#, #"accounts\.google\.com"#] {
            let base = #"^https?://[^/?#]+(/[^?#]*)?/account/oidclogin/?\?([^#]*&)?domain="# + provider
            rules += [base + "[&#]", base + "$"]
        }
        return rules
    }()
    public static var contentRulesJSON: String {
        let rules: [[String: Any]] = requestFilters.map {
            ["trigger": ["url-filter": $0, "url-filter-is-case-sensitive": false], "action": ["type": "block"]]
        }
        return String(data: try! JSONSerialization.data(withJSONObject: rules), encoding: .utf8)!
    }
}
