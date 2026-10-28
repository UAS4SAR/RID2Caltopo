package org.ncssar.rid2caltopo.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.ncssar.rid2caltopo.data.CaltopoEmbeddedSignInPolicy as SignInPolicy
import java.io.ByteArrayInputStream

/** Shared by visible sign-in and cold session restoration; no cookie/session mutation. */
internal open class CaltopoSignInWebViewClient(
    private val blocked: (WebView) -> Unit = {},
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (SignInPolicy.isGoogleSignIn(url) && request.isForMainFrame) blocked(view)
        return !SignInPolicy.allowsEmbedded(url)
    }
    @Deprecated("Compatibility callback")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        if (SignInPolicy.isGoogleSignIn(url)) blocked(view)
        return !SignInPolicy.allowsEmbedded(url)
    }
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (!SignInPolicy.isGoogleSignIn(request.url.toString())) return null
        if (request.isForMainFrame) view.post { blocked(view) }
        return denied()
    }
    @Deprecated("Compatibility callback")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
        if (SignInPolicy.isGoogleSignIn(url)) denied() else null

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Embedded Google sign-in blocked",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
}
