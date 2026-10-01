package org.ncssar.rid2caltopo.ui

import android.app.Activity
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.os.Bundle
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import org.ncssar.rid2caltopo.data.CaltopoPersonalMarkerTest as MarkerTest
import org.ncssar.rid2caltopo.data.CaltopoPersonalProbe as Probe
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs in its own process and WebView profile. Scoped cookies cross the private provider
 * to native clients within this application; neither component is exported.
 * The manifest handles rotation/window-size changes in place so the live WebView,
 * cookies, form fields, and pending read check survive without triggering cleanup.
 * Closing the screen detaches the browser; only Clear login clears its session.
 * The isolated browser profile persists across launches; Clear login removes its data.
 */
class CaltopoPersonalProbeActivity : Activity() {
    private object Session {
        val grants = org.ncssar.rid2caltopo.data.PersonalSessionGrants()
        val accountID: String? get() = grants.snapshot().accountID
        @Volatile var username: String? = null
        var web: WebView? = null
        var context: MutableContextWrapper? = null
        @Volatile var token: String? = null
        var ready = false
        var onReady: (() -> Unit)? = null
        var map = ""
        var link = ""
        var status = "Enter your login directly in CalTopo."
    }
    companion object {
        /** Cold-process cookie recovery must not launch a visible Activity. Called on a provider worker. */
        fun prepareCatalog(context: android.content.Context) {
            if (Session.accountID != null) return
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            val identity = Session.grants.snapshot()
            val observation = Session.grants.beginObservation()
            val finished = java.util.concurrent.CountDownLatch(1)
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val active = java.util.concurrent.atomic.AtomicBoolean(true)
            val loadFailed = java.util.concurrent.atomic.AtomicBoolean(false)
            main.post {
                // Never navigate an already displayed login or replace its callbacks.
                if (Session.web?.parent != null) { finished.countDown(); return@post }
                val browser = Session.web ?: run {
                    val wrapper = MutableContextWrapper(context.applicationContext)
                    Session.context = wrapper
                    WebView(wrapper).also { Session.web = it }
                }
                Session.ready = true
                browser.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                browser.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) { loadFailed.set(true); finished.countDown() }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme != "https"
                }
                browser.loadUrl(Probe.ORIGIN)
                val discover = object : Runnable {
                    override fun run() {
                        if (!active.get()) return
                        browser.evaluateJavascript(org.ncssar.rid2caltopo.data.CaltopoPersonalCatalog.IDENTITY_SCRIPT) { encoded ->
                            if (!active.get()) return@evaluateJavascript
                            val id = runCatching {
                                org.json.JSONObject(org.json.JSONArray("[$encoded]").getString(0)).optString("id")
                            }.getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
                            if (id != null) {
                                Session.grants.observe(id, identity, observation)
                                CookieManager.getInstance().flush()
                                finished.countDown()
                            } else if (browser.progress == 100) {
                                finished.countDown()
                            } else main.postDelayed(this, 200)
                        }
                    }
                }
                main.postDelayed(discover, 200)
            }
            try {
                check(finished.await(15, TimeUnit.SECONDS) && !loadFailed.get()) {
                    "CalTopo session check could not finish. Check the connection and retry."
                }
            } finally { active.set(false) }
        }

        fun catalog(): String {
            if (!verifyBrowserIdentity()) throw org.ncssar.rid2caltopo.data.PersonalCaltopoLoginRequired()
            val identity = Session.grants.snapshot()
            val observation = Session.grants.observationTicket()
            val accountID = identity.accountID ?: throw org.ncssar.rid2caltopo.data.PersonalCaltopoLoginRequired()
            require(accountID.matches(Regex("[A-Za-z0-9]+")))
            val url = "https://caltopo.com/sideload/account/$accountID.json?json=%7Bfull%3A%20true%7D"
            val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
            if (cookie.isBlank()) throw org.ncssar.rid2caltopo.data.PersonalCaltopoLoginRequired()
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .addNetworkInterceptor(org.ncssar.rid2caltopo.data.PersonalSessionDispatchInterceptor {
                    check(Session.grants.current(identity, observation)) { "Personal account changed during loading." }
                }).build()
            check(Session.grants.current(identity, observation)) { "Personal account changed during loading." }
            val body = client.newCall(Request.Builder().url(url).header("Cookie", cookie).header("Cache-Control", "no-store").build()).execute().use {
                if (it.code == 401 || it.code == 403) throw org.ncssar.rid2caltopo.data.PersonalCaltopoLoginRequired()
                check(it.isSuccessful) { "Personal maps request returned HTTP ${it.code}." }
                it.body?.string() ?: error("Empty account response")
            }
            val username = org.ncssar.rid2caltopo.data.CaltopoPersonalCatalog.account(body).optString("username").trim()
            check(username.isNotEmpty()) { "CalTopo account response has no username." }
            val json = org.ncssar.rid2caltopo.data.CaltopoPersonalCatalog.normalize(body, accountID, username)
            val features = json.getJSONArray("features")
            val ids = (0 until features.length()).map { features.getJSONObject(it) }
                .filter { it.getJSONObject("properties").optString("class") == "CollaborativeMap" }
                .map { it.getString("id") }
            val grants = org.json.JSONObject(Session.grants.publish(identity, ids, observation))
            json.put("grants", grants)
            return json.toString()
        }

        fun authorizeMedia(token: String?, mediaID: String?): Boolean = Session.grants.authorizeMedia(token, mediaID)
        fun validSession(token: String?, url: String?): Boolean = Session.grants.valid(token, url)
        /** Refresh the live browser identity before copying any cookie, not only on navigation callbacks. */
        private fun verifyBrowserIdentity(): Boolean {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return false
            val identity = Session.grants.snapshot()
            if (!Session.grants.current(identity)) return false
            val observation = Session.grants.observationTicket()
            val finished = java.util.concurrent.CountDownLatch(1)
            val valid = java.util.concurrent.atomic.AtomicBoolean(false)
            val active = java.util.concurrent.atomic.AtomicBoolean(true)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val browser = Session.web
                if (browser == null || !Session.grants.current(identity)) { finished.countDown(); return@post }
                browser.evaluateJavascript(org.ncssar.rid2caltopo.data.CaltopoPersonalCatalog.IDENTITY_SCRIPT) { encoded ->
                    if (!active.get()) return@evaluateJavascript
                    val id = runCatching {
                        org.json.JSONObject(org.json.JSONArray("[$encoded]").getString(0)).optString("id")
                    }.getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
                    valid.set(Session.grants.observe(id, identity, observation) && id != null && id == identity.accountID)
                    finished.countDown()
                }
            }
            return try { finished.await(5, TimeUnit.SECONDS) && valid.get() }
            finally { active.set(false) }
        }
        fun cookieForSession(token: String?, url: String?): String? {
            if (!Session.grants.valid(token, url) || !verifyBrowserIdentity()) return null
            return Session.grants.cookie(token, url) { CookieManager.getInstance().getCookie(it) }
        }

    }

    private lateinit var mapField: EditText
    private lateinit var linkField: EditText
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var check: Button
    private lateinit var publish: Button
    private var publishing = false
    private var catalogLoading = false
    private lateinit var loadMaps: Button
    private var selecting = false
    private var selectionArmed = false
    private val isPicker by lazy { intent.getBooleanExtra("picker", false) }
    private val pending by lazy { getSharedPreferences("personal-caltopo-probe", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    @Volatile private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(16, 20, 16, 12)
            fitsSystemWindows = true
        }
        fun label(text: String) = TextView(this).apply { this.text = text; setTextColor(Color.BLACK); root.addView(this) }
        label("Personal CalTopo login")
        label("Sign in or open a CalTopo invitation. Load maps returns to RID2Caltopo with your available maps. Close keeps your login.")
        val linkRow = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; root.addView(this) }
        val link = EditText(this).apply {
            hint = "CalTopo invitation or map link"; setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.BLACK); setHintTextColor(Color.DKGRAY)
            linkRow.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        linkRow.addView(ImageButton(this).apply {
            setImageResource(org.ncssar.rid2caltopo.R.drawable.ic_personal_qr)
            contentDescription = "Scan CalTopo QR code"
            setOnClickListener {
                com.google.zxing.integration.android.IntentIntegrator(this@CaltopoPersonalProbeActivity)
                    .setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
                    .setPrompt("Scan a CalTopo invitation or map QR code")
                    .setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false).initiateScan()
            }
        }, LinearLayout.LayoutParams((48 * resources.displayMetrics.density).toInt(), (48 * resources.displayMetrics.density).toInt()))
        linkField = link
        link.setText(Session.link)
        val open = Button(this).apply { text = "Open link"; root.addView(this) }
        val map = EditText(this).apply { hint = "Incident map ID or link"; setSingleLine(true); root.addView(this) }
        mapField = map
        map.setText(Session.map)
        check = Button(this).apply { text = "Check personal map access"; isEnabled = false; root.addView(this) }
        publish = Button(this).apply { text = "Test marker publishing on G00CPSS"; isEnabled = false; root.addView(this) }
        if (isPicker) {
            map.visibility = android.view.View.GONE
            check.visibility = android.view.View.GONE
            publish.visibility = android.view.View.GONE
        }
        status = label("Choose a map in CalTopo’s Your Data, or open its link.")
        publish.setOnClickListener {
            val cleanup = pending.contains("markerID")
            if (Probe.mapID(map.text.toString()) != MarkerTest.MAP) {
                status.text = "Enter G00CPSS, the designated test map."; return@setOnClickListener
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(if (cleanup) "Remove pending test marker?" else "Test publishing on G00CPSS?")
                .setMessage(if (cleanup) "Checks and removes only this experiment's pending marker." else
                    "Creates a labeled test marker at 0,0, reads it back, removes it, and checks removal. Other map objects are left alone.")
                .setPositiveButton(if (cleanup) "Clean up" else "Run test") { _, _ -> runMarkerTest(cleanup) }
                .setNegativeButton("Cancel", null).show()
        }
        val origin = label("https://caltopo.com")
        val controls = LinearLayout(this)
        root.addView(controls)
        controls.addView(Button(this).apply {
            text = "Back"; setOnClickListener { if (web.canGoBack()) web.goBack() }
        })
        controls.addView(Button(this).apply {
            text = "Clear login"; setOnClickListener { clearSession { web.loadUrl(Probe.ORIGIN + "/account/login") } }
        })
        loadMaps = Button(this).apply {
            text = "Load maps"; setOnClickListener { loadCatalogFromPage() }
        }
        controls.addView(loadMaps)
        controls.addView(Button(this).apply { text = "Close"; setOnClickListener { finish() } })
        val newSession = Session.web == null
        web = (Session.web ?: run {
            val wrapper = MutableContextWrapper(this)
            Session.context = wrapper
            WebView(wrapper).also { Session.web = it }
        }).apply {
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) selectionArmed = true
                publishing || selecting
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    Session.grants.beginObservation()
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.scheme != "https"
                override fun onPageFinished(view: WebView, url: String) {
                    origin.text = runCatching { val u = java.net.URI(url); "${u.scheme}://${u.host ?: ""}" }.getOrDefault("")
                    CookieManager.getInstance().flush()
                    observeAccountChange()
                    if (intent.getBooleanExtra("catalog", false)) loadCatalogFromPage()
                    Probe.mapID(url)?.let { map.setText(it); selectBrowserMap(it) }
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    Probe.mapID(url)?.let { map.setText(it); selectBrowserMap(it) }
                }
            }
        }
        Session.context?.baseContext = this
        (web.parent as? ViewGroup)?.removeView(web)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        open.setOnClickListener {
            if (publishing) return@setOnClickListener
            val url = Probe.browserURL(link.text.toString())
            if (url == null) status.text = "Enter an https://caltopo.com invitation or map link."
            else { selectionArmed = true; web.loadUrl(url) }
        }
        check.setOnClickListener {
            val id = Probe.mapID(map.text.toString())
            if (id == null) { status.text = "Enter a map ID or CalTopo map link."; return@setOnClickListener }
            val url = Probe.endpoint(id)
            // CookieManager scopes cookies to the exact destination, including HttpOnly cookies.
            val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
            if (cookie.isBlank()) { status.text = "No CalTopo browser cookies. Sign in first."; return@setOnClickListener }
            val attempt = generation
            check.isEnabled = false
            publish.isEnabled = false
            status.text = "Comparing personal and anonymous read requests…"
            worker.execute {
                val message = runCatching {
                    if (attempt != generation) throw InterruptedException()
                    val personal = fetch(url, cookie)
                    if (attempt != generation) throw InterruptedException()
                    val anonymous = fetch(url, null)
                    "Personal: ${personal.summary}\nAnonymous: ${anonymous.summary}\n${Probe.comparison(personal, anonymous)}"
                }.getOrElse { "Map check could not complete. Check the connection and retry. No credentials were logged." }
                runOnUiThread {
                    if (!isDestroyed && attempt == generation) { status.text = message; check.isEnabled = true; updatePublishButton() }
                }
            }
        }
        Session.onReady = {
            if (!isDestroyed) {
                status.text = Session.status
                check.isEnabled = true
                updatePublishButton()
            }
        }
        if (newSession) {
            Session.ready = true
            Session.status = if (intent.getBooleanExtra("catalog", false)) "Sign in if needed. Loading your personal maps…" else "Sign in if needed, then open a map from Your Data."
            Session.onReady?.invoke()
            web.loadUrl(Probe.ORIGIN)
        } else if (Session.ready) {
            Session.onReady?.invoke()
            val url = web.url.orEmpty()
            origin.text = runCatching { val u = java.net.URI(url); "${u.scheme}://${u.host ?: ""}" }.getOrDefault(Probe.ORIGIN)
            if (isPicker) {
                selectionArmed = false
                web.loadUrl(Probe.ORIGIN)
            }
        }
    }

    @Deprecated("Activity result compatibility with the existing QR scanner")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        val result = com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) { super.onActivityResult(requestCode, resultCode, data); return }
        val scanned = result.contents ?: return
        val url = Probe.browserURL(scanned)
        if (url == null) status.text = "This QR code is not a CalTopo invitation or map link."
        else {
            linkField.setText(url)
            Session.link = url
            status.text = "Link scanned. Tap Open link to continue."
        }
    }

    private fun observeAccountChange() {
        val location = web.url?.let { android.net.Uri.parse(it) }
        if (location?.scheme != "https" || location.host != "caltopo.com" || location.port != -1) return
        val identitySnapshot = Session.grants.snapshot()
        val observation = Session.grants.observationTicket()
        web.evaluateJavascript(org.ncssar.rid2caltopo.data.CaltopoPersonalCatalog.IDENTITY_SCRIPT) { encoded ->
            val id = runCatching { org.json.JSONObject(org.json.JSONArray("[$encoded]").getString(0)).optString("id") }
                .getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
            Session.grants.observe(id, identitySnapshot, observation)

        }
    }

    private fun loadCatalogFromPage() {
        val location = web.url?.let { android.net.Uri.parse(it) }
        if (catalogLoading || isFinishing || location?.scheme != "https" || location.host != "caltopo.com" || location.port != -1) return
        val identitySnapshot = Session.grants.snapshot()
        val observation = Session.grants.observationTicket()
        web.evaluateJavascript(org.ncssar.rid2caltopo.data.CaltopoPersonalCatalog.IDENTITY_SCRIPT) { encoded ->
            val identity = runCatching { org.json.JSONObject(org.json.JSONArray("[$encoded]").getString(0)) }.getOrNull()
            val id = identity?.optString("id").orEmpty()
            if (!id.matches(Regex("[A-Za-z0-9]+")) || catalogLoading) {
                status.text = "Sign in to CalTopo below. Waiting for your account before opening personal maps."
                return@evaluateJavascript
            }
            if (!Session.grants.observe(id, identitySnapshot, observation)) return@evaluateJavascript
            catalogLoading = true
            loadMaps.isEnabled = false
            loadMaps.text = "Loading…"
            status.text = "Loading your personal maps…"
            worker.execute {
                val result = runCatching { catalog() }
                android.util.Log.i("PersonalCatalog", if (result.isSuccess) "Catalog loaded" else "Catalog failed: ${result.exceptionOrNull()?.javaClass?.simpleName}")
                runOnUiThread {
                    catalogLoading = false
                    loadMaps.isEnabled = true
                    loadMaps.text = if (result.isFailure) "Retry maps" else "Load maps"
                    if (!isDestroyed && result.isSuccess) { setResult(RESULT_OK); finish() }
                    else if (!isDestroyed) status.text = "Personal maps could not load (${result.exceptionOrNull()?.javaClass?.simpleName ?: "request failed"}). Tap Retry maps. Your existing credentials have not changed."
                }
            }
        }
    }

    private fun selectBrowserMap(id: String) {
        if (intent.getBooleanExtra("catalog", false) || intent.getBooleanExtra("webOnly", false)) return
        if (!isPicker || !selectionArmed || selecting || publishing || !Session.ready || isFinishing) return
        selecting = true
        val url = Probe.endpoint(id)
        val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
        val attempt = generation
        val identitySnapshot = Session.grants.snapshot()
        status.text = "Connecting to personal incident map $id…"
        worker.execute {
            val readable = cookie.isNotBlank() && runCatching { fetch(url, cookie).readable }.getOrDefault(false)
            runOnUiThread {
                if (!isDestroyed && attempt == generation) {
                    if (readable && identitySnapshot.accountID != null && Session.grants.current(identitySnapshot)) {
                        CookieManager.getInstance().flush()
                        Session.token = Session.grants.publish(identitySnapshot, listOf(id)).getValue(id)
                        setResult(RESULT_OK, android.content.Intent().putExtra("map", id).putExtra("session", Session.token))
                        finish()
                    } else {
                        selecting = false
                        status.text = "This login cannot read the map. Sign in or choose another map in Your Data."
                    }
                }
            }
        }
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        super.onStop()
    }

    private fun updatePublishButton() {
        publish.text = if (pending.contains("markerID")) "Clean up pending test marker" else "Test marker publishing on G00CPSS"
        publish.isEnabled = true
    }

    private fun runMarkerTest(cleanup: Boolean) {
        val id = pending.getString("markerID", null) ?: java.util.UUID.randomUUID().toString()
        val paths = listOf(MarkerTest.readPath, MarkerTest.markerPath(id))
        val cookies = paths.associateWith { CookieManager.getInstance().getCookie(Probe.ORIGIN + it).orEmpty() }
        if (cookies.values.any { it.isBlank() }) { status.text = "Sign in to CalTopo first."; return }
        val attempt = generation
        publishing = true
        check.isEnabled = false
        publish.isEnabled = false
        status.text = "Testing marker publishing on G00CPSS…"
        worker.execute {
            val message = try {
                MarkerTest.run(id, cleanup, send = { method, path, payload ->
                    if (attempt != generation || Thread.currentThread().isInterrupted) throw InterruptedException()
                    val request = Request.Builder().url(Probe.ORIGIN + path)
                        .header("Cookie", cookies.getValue(path)).header("Accept", "application/json")
                        .header("Cache-Control", "no-store").header("Origin", Probe.ORIGIN)
                        .header("Referer", Probe.ORIGIN + "/m/" + MarkerTest.MAP)
                        .header("User-Agent", "RID2Caltopo-PersonalSession-Experiment")
                    val body = payload?.let { FormBody.Builder().add("json", it).build() }
                    request.method(method, body)
                    client.newCall(request.build()).execute().use { reply ->
                        MarkerTest.Reply(reply.code, reply.body?.string().orEmpty())
                    }
                }, savePending = { markerID ->
                    kotlin.check(pending.edit().putString("markerID", markerID).commit()) { "Unable to save cleanup record; no marker was sent." }
                }, clearPending = {
                    kotlin.check(pending.edit().remove("markerID").commit()) { "Removal was verified but the local cleanup record could not be cleared." }
                })
            } catch (error: IllegalStateException) {
                error.message ?: "Test could not complete."
            } catch (_: Exception) {
                "Test interrupted or connection failed. Reopen this experiment and use cleanup if a marker is pending."
            }
            runOnUiThread {
                publishing = false
                if (!isDestroyed && attempt == generation) {
                    status.text = message + if (pending.contains("markerID")) "\nPending marker: $id" else ""
                    check.isEnabled = true
                    updatePublishButton()
                }
            }
        }
    }

    private fun fetch(url: String, cookie: String?): Probe.Result {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("Cache-Control", "no-store").header("User-Agent", "RID2Caltopo-PersonalSession-Experiment")
        cookie?.let { request.header("Cookie", it) }
        return client.newCall(request.build()).execute().use { response ->
            Probe.result(response.code, response.body?.string().orEmpty())
        }
    }

    private fun clearSession(ready: () -> Unit) {
        generation++
        selecting = false
        selectionArmed = false
        Session.grants.clear()
        Session.username = null
        Session.token = null
        Session.ready = false
        client.dispatcher.cancelAll()
        check.isEnabled = false
        publish.isEnabled = false
        web.stopLoading()
        web.loadUrl("about:blank")
        web.clearCache(true)
        web.clearHistory()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            Session.ready = true
            Session.status = "Fresh session. Enter your login directly in CalTopo."
            Session.onReady?.invoke()
            // Even if the screen was closed during clearing, keep the retained
            // browser at a usable login page for its next attachment.
            if (!isDestroyed) ready() else Session.web?.loadUrl(Probe.ORIGIN + "/account/login")
        }
    }

    override fun onDestroy() {
        generation++
        client.dispatcher.cancelAll()
        worker.shutdownNow()
        Session.map = mapField.text.toString()
        Session.link = linkField.text.toString()
        Session.status = if (!check.isEnabled) "Previous check interrupted. Login retained; retry the check or pending cleanup." else status.text.toString()
        Session.onReady = null
        (web.parent as? ViewGroup)?.removeView(web)
        // Detach every Activity reference, but retain page/JS state and cookies.
        web.webViewClient = WebViewClient()
        web.setOnTouchListener(null)
        Session.context?.baseContext = applicationContext
        super.onDestroy()
    }
}
