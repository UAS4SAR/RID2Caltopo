package org.ncssar.rid2caltopo.data

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Pure URL policy: never reads or clears cookies, account grants or credentials. */
object CaltopoEmbeddedSignInPolicy {
    const val LOGIN_URL = "https://caltopo.com/account/login"
    const val SETUP_URL = "https://caltopo.com/join"
    const val INSTRUCTIONS_URL = "https://blog.caltopo.com/2026/08/05/introducing-first-party-logins-for-caltopo/"
    const val BLOCKED_MESSAGE = "Google sign-in is unavailable in this embedded browser. Use CalTopo email/password. Saved CalTopo sessions are kept."
    val SETUP_INSTRUCTIONS = """
        Already signed in here? You can continue using your saved CalTopo session.

        To add email/password to an existing Google-backed CalTopo account:
        1. Open setup in your regular browser. CalTopo may ask you to sign out there to reach Sign up.
        2. Choose email/password and use the exact email on your existing CalTopo account. CalTopo links matching accounts. Check that your maps and subscription are present.
        3. Return here and choose Email/password login. Enter your CalTopo credentials on CalTopo’s page.

        Browser sign-in does not transfer into this app. This app does not ask for your Google password. Setup and blocking do not clear this app’s saved session. Use Clear login only when you intend to remove it.
    """.trimIndent()

    fun isGoogleSignIn(raw: String): Boolean {
        val url = runCatching { URI(raw) }.getOrNull() ?: return false
        val host = url.host.orEmpty().lowercase(Locale.ROOT).trimEnd('.')
        val path = url.path.orEmpty().lowercase(Locale.ROOT).trim('/')
        if (host == "accounts.google.com" || host.endsWith(".accounts.google.com") || host == "oauth2.googleapis.com") return true
        if (host in setOf("google.com", "www.google.com") && listOf("accounts", "signin", "o/oauth2").any { path == it || path.startsWith("$it/") }) return true
        if (host == "www.googleapis.com" && (path == "oauth2" || path.startsWith("oauth2/"))) return true
        if (path == "account/oidclogin" || path.endsWith("/account/oidclogin")) {
            return url.rawQuery.orEmpty().split('&').any { field ->
                val pair = field.split('=', limit = 2)
                val key = runCatching { URLDecoder.decode(pair[0], "UTF-8") }.getOrDefault("")
                val value = runCatching { URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8") }.getOrDefault("")
                key.equals("domain", true) && value.lowercase(Locale.ROOT) in setOf("google", "google.com", "accounts.google.com")
            }
        }
        return false
    }
    fun allowsEmbedded(raw: String): Boolean {
        val url = runCatching { URI(raw) }.getOrNull() ?: return false
        return (url.scheme.equals("https", true) || raw == "about:blank") && !isGoogleSignIn(raw)
    }
}
