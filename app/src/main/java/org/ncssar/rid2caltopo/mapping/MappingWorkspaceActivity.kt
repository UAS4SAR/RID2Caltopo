package org.ncssar.rid2caltopo.mapping

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayInputStream
import java.net.URI

/** One mapping workspace inside R2C; only the paired station may navigate here. */
class MappingWorkspaceActivity : ComponentActivity() {
    private var chooser: android.webkit.ValueCallback<Array<Uri>>? = null
    private var web: WebView? = null
    private var workToken:String?=null
    private val files = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val values = if (result.resultCode == RESULT_OK) {
            data?.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }
                ?: data?.data?.let { arrayOf(it) }
        } else null
        chooser?.onReceiveValue(values); chooser = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        org.ncssar.rid2caltopo.app.SettingsStartup.showWhenReady(this) { createReadyWorkspace() }
    }

    private fun createReadyWorkspace() {
        val url = intent.getStringExtra("workspaceUrl") ?: run { finish(); return }
        val parsed = runCatching { URI(url) }.getOrNull() ?: run { finish(); return }
        val base = runCatching { MappingStationURL.parse("${parsed.scheme}://${parsed.rawAuthority}") }
            .getOrNull() ?: run { finish(); return }
        fun sameStation(value: String): Boolean = runCatching {
            val target = URI(value)
            target.scheme == base.scheme && target.host == base.host && target.port == base.port && target.userInfo == null
        }.getOrDefault(false)
        workToken=MappingWorkRuntime.begin { runOnUiThread { finish() } }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL;keepScreenOn=true }
        layout.addView(Button(this).apply { text = "Back to incident map"; setOnClickListener { finish() } })
        val view = WebView(this); web = view
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportMultipleWindows(false)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !sameStation(request.url.toString())
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (sameStation(request.url.toString())) null else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                chooser?.onReceiveValue(null); chooser = callback
                val types = params.acceptTypes.filter { it.isNotBlank() }
                val json = types.any { it.contains("json") }
                files.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (json) "application/json" else "image/jpeg"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                })
                return true
            }
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) { request.deny() }
        }
        view.setDownloadListener { address, _, _, _, _ ->
            if (sameStation(address) && URI(address).path.startsWith("/download/")) {
                val request = DownloadManager.Request(Uri.parse(address))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "R2C-Mapping-${System.currentTimeMillis()}")
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            }
        }
        layout.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
        view.loadUrl(url)
    }

    override fun onDestroy() {
        chooser?.onReceiveValue(null); chooser = null
        web?.stopLoading(); web?.destroy(); web = null
        val token=workToken;workToken=null;token?.let(MappingWorkRuntime::finish)
        super.onDestroy()
    }
}
