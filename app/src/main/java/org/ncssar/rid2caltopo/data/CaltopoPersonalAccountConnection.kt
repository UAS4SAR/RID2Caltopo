/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
/**
 * Personal CalTopo account credential obtained from the Desktop activation flow.
 *
 * Kept apart from Teams service-account credentials and from the cookie-based
 * personal login. The feature flag defaults off. While it is off, callers that
 * ask for signing material receive the cookie path and this store is not read.
 *
 * Enrollment, exchange, and secure-store reads must run off the main thread.
 * A single in-memory cache lookup after the first worker read is the only
 * fast path; it does not touch disk.
 */
object CaltopoPersonalAccountConnection {
    const val FEATURE_PREFERENCE = "caltopo-personal-account"
    const val FEATURE_ENABLED_KEY = "feature_enabled"
    const val LOGIN_MODE_KEY = "login_mode"
    const val DEFAULT_DEVICE_NAME = "RID2Caltopo app"
    const val CREDENTIAL_TITLE = "Last synced by RID2Caltopo app"
    const val RETURN_SCHEME = "rid2caltopo"
    const val AUTH_LOG_TAG = "CaltopoPersonalAuth"
    /** Map-node grant for a stored HMAC credential. It is not a browser-session token. */
    const val SIGNED_ACCOUNT_GRANT = "signed-account"
    const val ACTIVATION_PROMPT = "Check the box, then tap Sync Account."
    const val LOG_OUT_PROMPT = "Remove the personal CalTopo account from this device and return to the team account?"

    fun personalMenuLabel(storedDisplayName: String?): String =
        if (storedDisplayName.isNullOrBlank()) "Personal: Log In" else "Personal: $storedDisplayName"

    fun teamMenuLabel(teamName: String): String = "Team: $teamName"
    private const val SIGNATURE_TTL_MS = 2 * 60 * 1000L
    private const val PENDING_TTL_MS = 5 * 60 * 1000L
    private const val MAX_ACTIVATION_BODY = 65_536
    private const val MAX_HTTP_HEADER = 8_192

    @Volatile var featureEnabled: Boolean = false
    @Volatile var loginMode: CaltopoPersonalLoginMode = CaltopoPersonalLoginMode.CREDENTIALS
    @Volatile var authorizationGeneration: Long = 0L
        private set

    private val lock = Any()
    private var store: CaltopoPersonalAccountStore = CaltopoPersonalAccountMemoryStore()
    private var cached: CaltopoPersonalAccountRecord? = null
    private var cacheValid = false
    private var pending: PendingActivation? = null
    var clock: () -> Long = { System.currentTimeMillis() }
    var titleClient: (CredentialTitleRequest) -> CaltopoActivationHttp = { CaltopoActivationHttp(200, "") }
    var titleFailureLog: (String) -> Unit = {}
    var onEnrollmentAbandoned: (() -> Unit)? = null
    var authLog: (String) -> Unit = {}
    var randomBytes: (Int) -> ByteArray = { count -> ByteArray(count).also { SecureRandom().nextBytes(it) } }
    private var listening: ServerSocket? = null
    private var enrollmentCancelled = false
    private val redeemedActivationCodes = mutableSetOf<String>()
    private var catalogAccountId: String? = null
    private var catalogBody: String? = null

    @JvmStatic fun install(store: CaltopoPersonalAccountStore, featureEnabled: Boolean = this.featureEnabled) {
        synchronized(lock) {
            this.store = store
            this.featureEnabled = featureEnabled
            this.loginMode = if (featureEnabled) CaltopoPersonalLoginMode.CREDENTIALS else CaltopoPersonalLoginMode.SESSION_COOKIES
            cached = null
            cacheValid = false
            pending = null
            catalogAccountId = null
            catalogBody = null
        }
    }

    fun applyLoginMode(mode: CaltopoPersonalLoginMode) {
        loginMode = mode
        featureEnabled = mode == CaltopoPersonalLoginMode.CREDENTIALS
    }

    /** Test isolation. Production code uses [install]. */
    fun resetForTests() {
        install(CaltopoPersonalAccountMemoryStore(), featureEnabled = false)
        titleClient = { CaltopoActivationHttp(200, "") }
        titleFailureLog = {}
        onEnrollmentAbandoned = null
        authLog = {}
        synchronized(lock) {
            enrollmentCancelled = false
            listening = null
            redeemedActivationCodes.clear()
        }
        clock = { System.currentTimeMillis() }
        randomBytes = { count -> ByteArray(count).also { SecureRandom().nextBytes(it) } }
    }

    fun status(host: String): CaltopoPersonalAccountStatus? {
        val record = stored() ?: return null
        if (!record.host.equals(normalizeHost(host), ignoreCase = true)) return null
        return record.status()
    }

    /** Stored binding for this host when it has not been marked revoked. */
    fun reconnect(host: String): CaltopoPersonalAccountStatus? {
        val current = status(host) ?: return null
        return if (current.revoked) null else current
    }

    fun disconnect() {
        synchronized(lock) {
            authorizationGeneration++
            store.clear()
            cached = null
            cacheValid = false
            pending = null
            catalogAccountId = null
            catalogBody = null
        }
    }

    /**
     * Best-effort signed DELETE of the CalTopo credential, then the local record is always removed.
     * The web object DAO documents DELETE on this URL. A non-200 or a transport error does not keep the key.
     */
    fun logOut(delete: (String) -> Int = { url -> CaltopoPersonalAccountHttps.delete(url).status }) {
        val record = stored()
        if (record != null && !record.revoked) {
            val path = "/api/v1/acct/${record.accountId}/UserCredential/${record.credentialId}"
            try {
                val status = delete(credentialDeleteUrl(record))
                logAuth("credential DELETE $path HTTP $status")
            } catch (error: Exception) {
                logAuth("credential DELETE $path failed: ${error.javaClass.simpleName}")
            }
        }
        disconnect()
        logAuth("logged out")
    }

    fun credentialDeleteUrl(record: CaltopoPersonalAccountRecord, nowMilliseconds: Long = clock()): String {
        val path = "/api/v1/acct/${record.accountId}/UserCredential/${record.credentialId}"
        val expires = nowMilliseconds + SIGNATURE_TTL_MS
        val signature = sign("DELETE", path, expires, "", record.signingKey)
        return "https://${record.host}$path?id=${encode(record.credentialId)}&expires=$expires&signature=${encode(signature)}"
    }

    fun markRevoked() {
        val current = stored() ?: return
        write(current.copy(revoked = true))
    }

    /**
     * Session Cookies leaves personal requests on the browser session.
     * Credentials never falls back to that session or to a Teams secret.
     */
    @JvmStatic fun decide(accountId: String?): PersonalSigningDecision {
        if (!featureEnabled) return PersonalSigningDecision(null, null)
        val record = try {
            stored()
        } catch (error: Exception) {
            return PersonalSigningDecision(null, "Personal CalTopo credential could not be read. Team credentials were not used.")
        } ?: return PersonalSigningDecision(null, "Sign in to connect your CalTopo account. Session cookies are not used.")
        if (record.host != "caltopo.com") {
            return PersonalSigningDecision(null, "The saved CalTopo account credential is for a different server. Team credentials were not used.")
        }
        val account = accountId?.trim().orEmpty()
        if (account.isEmpty() || account != record.accountId) {
            return PersonalSigningDecision(null, "This map is not covered by the connected CalTopo account. Team credentials were not used.")
        }
        if (record.revoked) {
            return PersonalSigningDecision(null, "Personal CalTopo credential was revoked. Reconnect the account. Team credentials were not used.")
        }
        return PersonalSigningDecision(
            CaltopoCredentials(record.accountId, record.credentialId, record.signingKey),
            null
        )
    }

    @JvmStatic fun usesSignedCredential(token: String?): Boolean = token == SIGNED_ACCOUNT_GRANT

    /** Browser-session grants are checked with the cookie probe. The signed-account grant is not a session. */
    @JvmStatic fun requiresCookieValidation(token: String?): Boolean =
        !token.isNullOrEmpty() && !usesSignedCredential(token)

    /**
     * A signed-account grant never falls through to session cookies.
     * A cookie grant keeps [decide]'s result, including the cookie path while Credentials is off.
     */
    @JvmStatic fun signingForMap(accountId: String?, grantToken: String?): PersonalSigningDecision {
        val decision = decide(accountId)
        if (usesSignedCredential(grantToken) && decision.credentials == null && decision.failure == null) {
            return PersonalSigningDecision(null, "Sign in to connect your CalTopo account. Session cookies are not used.")
        }
        return decision
    }

    @JvmStatic fun requestLogLine(method: String, path: String, status: Int, credential: String): String {
        val safePath = path.substringBefore('?')
        val kind = if (credential == "personal" || credential == "session" || credential == "team" || credential == "unsigned") {
            credential
        } else {
            "unknown"
        }
        return "$method $safePath HTTP $status credential=$kind"
    }

    @JvmStatic fun logRequest(method: String, path: String, status: Int, credential: String) {
        logAuth(requestLogLine(method, path, status, credential))
    }

    @JvmStatic fun grantKind(token: String?): String = when {
        token.isNullOrEmpty() -> "none"
        usesSignedCredential(token) -> "signed-account"
        else -> "session"
    }

    /** State transitions only. Callers must not pass a secret, signature, or query. */
    @JvmStatic fun logState(message: String) {
        logAuth(message.substringBefore('?'))
    }

    /** Same HMAC construction as [CaltopoSession] and the Apple request signer. */
    @JvmStatic fun sign(method: String, path: String, expiresMilliseconds: Long, payload: String, credentialSecretBase64: String): String {
        val key = try {
            java.util.Base64.getDecoder().decode(credentialSecretBase64)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("CalTopo credential secret is not valid Base64.")
        }
        if (key.isEmpty()) throw IllegalArgumentException("CalTopo credential secret is not valid Base64.")
        val message = "$method $path\n$expiresMilliseconds\n$payload"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return java.util.Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    fun accountCatalogRequest(nowMilliseconds: Long = clock()): SignedGet? {
        if (!featureEnabled) return null
        val record = stored() ?: return null
        if (record.revoked) return null
        val path = "/api/v1/acct/${record.accountId}/since/0"
        val expires = nowMilliseconds + SIGNATURE_TTL_MS
        val signature = sign("GET", path, expires, "", record.signingKey)
        val query = "id=${encode(record.credentialId)}&expires=$expires&signature=${encode(signature)}"
        return SignedGet("https://${record.host}$path?$query", record.accountId)
    }

    /** Signed account catalog. The existing personal-catalog normalizer interprets the body. */
    fun fetchAccountCatalog(http: CaltopoActivationExchange): String? {
        val request = accountCatalogRequest() ?: return null
        val path = runCatching { URI(request.url).path }.getOrDefault("/api/v1/acct/${request.accountId}/since/0")
        synchronized(lock) {
            val cached = catalogBody
            if (catalogAccountId == request.accountId && cached != null) {
                logAuth("catalog cache hit account=${request.accountId} bytes=${cached.length}")
                return cached
            }
        }
        logAuth("catalog GET $path")
        val response = try {
            http.get(request.url)
        } catch (error: Exception) {
            val reason = if (error is CaltopoPersonalAccountException) error.message ?: "rejected" else error.javaClass.simpleName
            logAuth("catalog GET $path failed: $reason")
            throw CaltopoPersonalAccountException("CalTopo account catalog could not be loaded.")
        }
        logAuth("catalog HTTP ${response.status} bytes=${response.body.length}")
        if (response.status != 200) throw CaltopoPersonalAccountException("CalTopo account catalog failed (HTTP ${response.status}).", response.status)
        synchronized(lock) {
            catalogAccountId = request.accountId
            catalogBody = response.body
        }
        return response.body
    }

    /**
     * Binds 127.0.0.1, opens the system browser, exchanges the activation code,
     * and stores the result. Does not read or write Teams credentials.
     */
    fun enroll(
        host: String = "caltopo.com",
        deviceName: String = DEFAULT_DEVICE_NAME,
        browser: CaltopoSystemBrowser,
        http: CaltopoActivationExchange,
        timeoutMilliseconds: Long = PENDING_TTL_MS
    ): CaltopoPersonalAccountStatus {
        val normalizedHost = normalizeHost(host)
        val name = normalizeDeviceName(deviceName)
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val pending = begin(normalizedHost, name, server.localPort)
        server.soTimeout = 1_000
        val deadline = clock() + timeoutMilliseconds
        val generation = authorizationGeneration
        try {
            synchronized(lock) {
                enrollmentCancelled = false
                listening = server
            }
            logAuth("listener start port=${server.localPort}")
            val accept = Thread({
                pending.acceptedReturn = awaitCode(server, pending, deadline)
            }, "caltopo-personal-activate")
            accept.isDaemon = true
            accept.start()
            browser.open(pending.activationUrl)
            accept.join(timeoutMilliseconds + 2_000)
            val returned = pending.acceptedReturn ?: throw CaltopoPersonalAccountException(
                if (enrollmentWasCancelled()) "CalTopo sign-in was cancelled." else "CalTopo sign-in timed out."
            ).also { logAuth(if (enrollmentWasCancelled()) "listener cancelled" else "listener timed out") }
            if (generation != authorizationGeneration) throw CaltopoPersonalAccountException("Personal CalTopo sign-in was cancelled.")
            val record = exchange(pending, returned, http)
            if (generation != authorizationGeneration) throw CaltopoPersonalAccountException("Personal CalTopo sign-in was cancelled.")
            write(record)
            logAuth("stored account=${record.accountId} credential=${record.credentialId}")
            clearPending()
            if (generation == authorizationGeneration) publishCredentialTitle(record)
            return record.status()
        } finally {
            val abandoned = pending.acceptedReturn == null
            server.close()
            synchronized(lock) { if (listening === server) listening = null }
            if (abandoned) runCatching { onEnrollmentAbandoned?.invoke() }
        }
    }

    /** Closes the loopback listener. The in-app browser calls this when the operator cancels. */
    fun cancelEnrollment() {
        synchronized(lock) {
            if (pending?.acceptedReturn != null) {
                logAuth("cancel ignored; activation code already accepted")
                return
            }
            enrollmentCancelled = true
            runCatching { listening?.close() }
            logAuth("listener cancelled")
        }
    }

    fun logAuth(message: String) {
        runCatching { authLog(message) }
    }

    private fun rejectActivation(reason: String): Nothing {
        logAuth("activation rejected: $reason")
        throw CaltopoPersonalAccountException("CalTopo activation response was rejected.")
    }

    private fun decodeSigningKey(signingKey: String): ByteArray? {
        val standard = runCatching { java.util.Base64.getDecoder().decode(signingKey) }.getOrNull()
        if (standard != null) return standard
        return runCatching { java.util.Base64.getUrlDecoder().decode(signingKey) }.getOrNull()
    }

    fun enrollmentWasCancelled(): Boolean = synchronized(lock) { enrollmentCancelled }

    fun appReturnUrl(nonce: String): String {
        if (!NONCE.matches(nonce)) throw CaltopoPersonalAccountException("CalTopo activation return was rejected.")
        return "$RETURN_SCHEME://caltopo-return/$nonce"
    }

    /** Shown in the browser after CalTopo redirects to loopback. The activation code is not in this page. */
    fun activationSuccessPage(nonce: String): String {
        val url = appReturnUrl(nonce)
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"refresh\" content=\"0;url=$url\">" +
            "<title>RID2Caltopo</title></head><body>" +
            "<p>CalTopo account connected. Returning to RID2Caltopo.</p>" +
            "<p><a href=\"$url\">Return to RID2Caltopo</a></p></body></html>"
    }

    fun begin(host: String, deviceName: String, port: Int, now: Long = clock()): PendingActivation {
        val normalizedHost = normalizeHost(host)
        require(port in 1..65535) { "Activation listener port is invalid." }
        val pathToken = hex(randomBytes(16))
        // CalTopo appends "/client/finish-activate?code&name" onto this string.
        // A query here would swallow that suffix. The nonce is the whole path.
        val loopback = "http://127.0.0.1:$port/$pathToken"
        val activation = activationUrl(normalizedHost, loopback, normalizeDeviceName(deviceName))
        val created = PendingActivation(
            host = normalizedHost,
            deviceName = normalizeDeviceName(deviceName),
            port = port,
            pathToken = pathToken,
            activationUrl = activation,
            expiresAtMilliseconds = now + PENDING_TTL_MS
        )
        synchronized(lock) { pending = created }
        return created
    }

    fun acceptCallback(requestTarget: String, now: Long = clock()): CaltopoActivationReturn {
        val current = synchronized(lock) { pending } ?: throw CaltopoPersonalAccountException("CalTopo activation is not waiting for a code.")
        if (now > current.expiresAtMilliseconds) throw CaltopoPersonalAccountException("CalTopo activation expired. Start it again.")
        val returned = callbackCode(requestTarget, current)
        clearPending()
        return returned
    }

    fun completeExchange(rawBody: String, code: String, host: String, deviceName: String, now: Long = clock()): CaltopoPersonalAccountRecord {
        val parsed = parseActivationResponse(rawBody)
        return CaltopoPersonalAccountRecord(
            host = normalizeHost(host),
            accountId = parsed.accountId,
            credentialId = parsed.credentialId,
            signingKey = parsed.signingKey,
            deviceName = normalizeDeviceName(deviceName),
            enrolledAtEpochMs = now,
            revoked = false,
            displayName = parsed.displayName
        )
    }

    fun activationUrl(host: String, loopbackUrl: String, deviceName: String): String {
        require(LOOPBACK_REDIRECT.matches(loopbackUrl)) { "Activation return must be a loopback URL with the nonce in the path." }
        return "https://${normalizeHost(host)}/app/activate/offline?redirect=${encode(loopbackUrl)}&name=${encode(deviceName)}"
    }

    /** Same append rule as loopback: nonce is a path segment and the prefix has no query. */
    fun customSchemePrefix(scheme: String, nonce: String): String {
        if (!SCHEME.matches(scheme) || scheme == "http" || scheme == "https" || !NONCE.matches(nonce)) {
            throw CaltopoPersonalAccountException("CalTopo activation return was rejected.")
        }
        return "$scheme://return/$nonce"
    }

    fun isActivationUrl(value: String): Boolean {
        val url = runCatching { URI(value) }.getOrNull() ?: return false
        if (url.scheme != "https" || url.userInfo != null || url.port !in listOf(-1, 443)) return false
        if (url.host?.lowercase(Locale.US) !in ALLOWED_HOSTS || url.path != "/app/activate/offline") return false
        val query = url.rawQuery ?: return false
        val redirect = queryParameters(query)["redirect"] ?: return false
        return LOOPBACK_REDIRECT.matches(redirect)
    }

    fun parseActivationResponse(rawBody: String): ParsedActivation {
        if (rawBody.length > MAX_ACTIVATION_BODY) rejectActivation("body length")
        val json = try {
            JSONObject(rawBody)
        } catch (error: Exception) {
            rejectActivation("json")
        }
        if (json.optString("status") == "error") throw CaltopoPersonalAccountException("CalTopo activation was rejected.")
        val source = json.optJSONObject("result") ?: json
        val credentialId = source.optString("code")
        val signingKey = source.optString("key")
        val account = source.optJSONObject("account") ?: rejectActivation("account missing")
        val accountId = account.optString("id")
        val accountType = account.optString("type")
        val properties = account.optJSONObject("properties") ?: rejectActivation("properties missing")
        if (properties.optString("class") != "UserAccount") {
            logAuth("activation rejected: properties.class")
            throw CaltopoPersonalAccountException("CalTopo activation did not return a personal account.")
        }
        // CalTopo account objects are GeoJSON features. type is a short word such as
        // Feature (7 characters), not the properties.class value.
        if (!ACCOUNT_TYPE.matches(accountType)) rejectActivation("account.type length=${accountType.length}")
        if (!ACCOUNT_ID.matches(accountId)) rejectActivation("account.id length=${accountId.length}")
        if (!CREDENTIAL_ID.matches(credentialId)) rejectActivation("credential id length=${credentialId.length}")
        if (signingKey.length != 44) rejectActivation("key length=${signingKey.length}")
        if (properties.has("subscriptionExpires")) {
            val expires = properties.opt("subscriptionExpires")
            if (expires != null && expires != JSONObject.NULL && expires !is Number) rejectActivation("subscriptionExpires")
        }
        val keyBytes = decodeSigningKey(signingKey) ?: rejectActivation("key decode")
        if (keyBytes.size != 32) rejectActivation("key bytes=${keyBytes.size}")
        logAuth("activation accepted type=$accountType")
        val person = listOf(properties.optString("firstName"), properties.optString("lastName"))
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val display = person.ifBlank { null }
            ?: properties.optString("title").takeIf { it.isNotBlank() }
            ?: properties.optString("email").takeIf { it.isNotBlank() }
            ?: accountId
        return ParsedActivation(accountId, credentialId, signingKey, display, accountType)
    }

    private fun exchange(pending: PendingActivation, returned: CaltopoActivationReturn, http: CaltopoActivationExchange): CaltopoPersonalAccountRecord {
        synchronized(lock) {
            if (!redeemedActivationCodes.add(returned.code)) {
                logAuth("exchange skipped; activation code already redeemed")
                val existing = cached
                if (existing != null && !existing.revoked) return existing
                throw CaltopoPersonalAccountException("CalTopo activation code was already used.")
            }
        }
        val url = "https://${pending.host}/api/v1/activate?code=${encode(returned.code)}&name=${encode(returned.deviceName)}"
        val response = try {
            http.get(url)
        } catch (error: Exception) {
            synchronized(lock) { redeemedActivationCodes.remove(returned.code) }
            logAuth("exchange request failed")
            throw CaltopoPersonalAccountException("CalTopo activation exchange failed.")
        }
        logAuth("exchange HTTP ${response.status}")
        if (response.status != 200) {
            synchronized(lock) { redeemedActivationCodes.remove(returned.code) }
            throw CaltopoPersonalAccountException("CalTopo activation exchange failed (HTTP ${response.status}).")
        }
        return completeExchange(response.body, returned.code, pending.host, returned.deviceName)
    }

    /**
     * Sync Account inserts a UserCredential before the one-time code is redeemed. That row stays
     * "Generated on User Request" with no Last Used, because the HMAC credential we keep is the
     * one returned by GET /api/v1/activate and then retitled. Delete only when the catalog shows
     * exactly one other credential matching that unused, just-created shape.
     */
    fun spareActivationCredentialId(rawBody: String, keepCredentialId: String, enrolledAtEpochMs: Long): String? {
        val json = try {
            JSONObject(rawBody)
        } catch (error: Exception) {
            return null
        }
        val source = json.optJSONObject("result") ?: json
        val matches = mutableListOf<String>()
        for (key in listOf("features", "credentials")) {
            val array = source.optJSONArray(key) ?: continue
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val properties = item.optJSONObject("properties") ?: continue
                if (properties.optString("class") != "UserCredential") continue
                val id = item.optString("id")
                if (id.isEmpty() || id == keepCredentialId) continue
                if (properties.optString("title") != "Generated on User Request") continue
                val lastUsed = if (properties.has("lastUsed")) properties.opt("lastUsed") else null
                val unused = lastUsed == null || lastUsed == JSONObject.NULL || (lastUsed is Number && lastUsed.toLong() == 0L)
                if (!unused) continue
                val updated = properties.opt("updated")
                if (updated !is Number) continue
                if (kotlin.math.abs(updated.toLong() - enrolledAtEpochMs) > 3 * 60 * 1000L) continue
                matches += id
            }
        }
        return if (matches.size == 1) matches[0] else null.also {
            if (matches.size > 1) logAuth("spare activation credential ambiguous count=${matches.size}; not deleted")
        }
    }

    fun deleteSpareActivationCredential(rawBody: String, delete: (String) -> Int = { url -> CaltopoPersonalAccountHttps.delete(url).status }) {
        val record = stored() ?: return
        val spareId = spareActivationCredentialId(rawBody, record.credentialId, record.enrolledAtEpochMs) ?: return
        val path = "/api/v1/acct/${record.accountId}/UserCredential/$spareId"
        val expires = clock() + SIGNATURE_TTL_MS
        val signature = sign("DELETE", path, expires, "", record.signingKey)
        val url = "https://${record.host}$path?id=${encode(record.credentialId)}&expires=$expires&signature=${encode(signature)}"
        try {
            val status = delete(url)
            logAuth("spare DELETE $path HTTP $status")
        } catch (error: Exception) {
            logAuth("spare DELETE $path failed: ${error.javaClass.simpleName}")
        }
    }

    fun credentialTitleRequest(record: CaltopoPersonalAccountRecord, nowMilliseconds: Long = clock()): CredentialTitleRequest {
        val path = "/api/v1/acct/${record.accountId}/UserCredential/${record.credentialId}"
        val json = """{"id":"${record.credentialId}","properties":{"class":"UserCredential","title":"$CREDENTIAL_TITLE"}}"""
        val expires = nowMilliseconds + SIGNATURE_TTL_MS
        val signature = sign("POST", path, expires, json, record.signingKey)
        return CredentialTitleRequest(
            "https://${record.host}$path",
            json,
            linkedMapOf(
                "id" to record.credentialId,
                "expires" to expires.toString(),
                "signature" to signature,
                "json" to json
            )
        )
    }

    private fun publishCredentialTitle(record: CaltopoPersonalAccountRecord) {
        try {
            val response = titleClient(credentialTitleRequest(record))
            logAuth(if (response.status == 200) "title HTTP 200" else "title HTTP ${response.status}")
            if (response.status != 200) titleFailureLog("CalTopo credential title update failed (HTTP ${response.status}).")
        } catch (error: Exception) {
            logAuth("title request failed")
            titleFailureLog("CalTopo credential title update failed.")
        }
    }

    private fun awaitCode(server: ServerSocket, pending: PendingActivation, deadline: Long): CaltopoActivationReturn? {
        while (clock() < deadline) {
            val socket = try {
                server.accept()
            } catch (timeout: java.net.SocketTimeoutException) {
                continue
            } catch (closed: Exception) {
                return null
            }
            socket.use { client ->
                if (!client.inetAddress.isLoopbackAddress) return@use
                client.soTimeout = 2_000
                val target = readRequestTarget(client) ?: return@use
                val code = runCatching { callbackCode(target, pending) }.getOrNull()
                if (code != null) {
                    pending.acceptedReturn = code
                    logAuth("code received")
                }
                val body = if (code != null) {
                    activationSuccessPage(pending.pathToken)
                } else {
                    "<html><body>RID2Caltopo did not accept this activation request.</body></html>"
                }
                val status = if (code != null) "200 OK" else "404 Not Found"
                val bytes = body.toByteArray(Charsets.UTF_8)
                val writer = BufferedOutputStream(client.getOutputStream())
                writer.write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                writer.write(bytes)
                writer.flush()
                if (code != null) return code
            }
        }
        return null
    }

    private fun readRequestTarget(socket: Socket): String? {
        val input = BufferedInputStream(socket.getInputStream())
        val header = ByteArray(MAX_HTTP_HEADER)
        var used = 0
        while (used < header.size) {
            val read = input.read(header, used, header.size - used)
            if (read < 0) break
            used += read
            val text = String(header, 0, used, Charsets.ISO_8859_1)
            val end = text.indexOf("\r\n")
            if (end >= 0) {
                val line = text.substring(0, end)
                if (!line.startsWith("GET ")) return null
                val parts = line.split(' ')
                return parts.getOrNull(1)
            }
        }
        return null
    }

    fun callbackCode(requestTarget: String, pending: PendingActivation): CaltopoActivationReturn {
        val raw = requestTarget.substringBefore('#')
        if (raw.contains('\\') || raw.contains('?') && raw.substringAfter('?', "").contains('?')) {
            throw CaltopoPersonalAccountException("CalTopo activation return was rejected.")
        }
        val path: String
        val query: String
        if (raw.startsWith("/")) {
            path = raw.substringBefore('?')
            query = raw.substringAfter('?', "")
        } else {
            val uri = try {
                URI(raw)
            } catch (error: Exception) {
                throw CaltopoPersonalAccountException("CalTopo activation return was rejected.")
            }
            val scheme = uri.scheme?.lowercase(Locale.US)
            if (scheme == null || scheme == "http" || scheme == "https" || uri.userInfo != null) {
                throw CaltopoPersonalAccountException("CalTopo activation return was rejected.")
            }
            path = uri.path ?: ""
            query = uri.rawQuery ?: ""
        }
        if (path != "/${pending.pathToken}/client/finish-activate") {
            throw CaltopoPersonalAccountException("CalTopo activation return was rejected.")
        }
        val params = queryParameters(query)
        val code = params["code"] ?: throw CaltopoPersonalAccountException("CalTopo activation return was rejected.")
        val deviceName = params["name"] ?: throw CaltopoPersonalAccountException("CalTopo activation return was rejected.")
        if (!ACTIVATION_CODE.matches(code)) throw CaltopoPersonalAccountException("CalTopo activation code was rejected.")
        return CaltopoActivationReturn(code, normalizeDeviceName(deviceName))
    }

    private fun stored(): CaltopoPersonalAccountRecord? {
        synchronized(lock) {
            if (!cacheValid) {
                cached = store.load()
                cacheValid = true
            }
            return cached
        }
    }

    private fun write(record: CaltopoPersonalAccountRecord) {
        synchronized(lock) {
            store.save(record)
            cached = record
            cacheValid = true
        }
    }

    private fun clearPending() {
        synchronized(lock) { pending = null }
    }

    private fun normalizeHost(host: String): String {
        val value = host.trim().lowercase(Locale.US)
        if (value !in ALLOWED_HOSTS) throw CaltopoPersonalAccountException("CalTopo account activation only supports caltopo.com.")
        return value
    }

    private fun normalizeDeviceName(name: String): String {
        val value = name.trim()
        if (!DEVICE_NAME.matches(value)) throw CaltopoPersonalAccountException("CalTopo device name is invalid.")
        return value
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun queryParameters(query: String): Map<String, String> =
        query.split('&').mapNotNull { part ->
            if (part.isEmpty()) return@mapNotNull null
            val name = java.net.URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8.name())
            val value = java.net.URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8.name())
            name to value
        }.toMap()

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private val ALLOWED_HOSTS = setOf("caltopo.com", "sartopo.com")
    private val ACCOUNT_ID = Regex("^[A-Za-z0-9]{6}$")
    private val ACCOUNT_TYPE = Regex("^[A-Za-z]{1,32}$")
    private val CREDENTIAL_ID = Regex("^[A-Za-z0-9]{12}$")
    private val ACTIVATION_CODE = Regex("^[A-Za-z0-9]{8}$")
    private val NONCE = Regex("^[0-9a-f]{32}$")
    private val SCHEME = Regex("^[a-z][a-z0-9+.-]{0,31}$")
    private val LOOPBACK_REDIRECT = Regex("^http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/[0-9a-f]{32}$")
    private val DEVICE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9 ._-]{0,39}$")
}

fun interface CaltopoSystemBrowser {
    /** System browser or Custom Tab. Must not be an embedded WebView. */
    fun open(activationUrl: String)
}

fun interface CaltopoActivationExchange {
    fun get(url: String): CaltopoActivationHttp
}

data class CaltopoActivationHttp(val status: Int, val body: String)

data class SignedGet(val url: String, val accountId: String)

enum class CaltopoPersonalLoginMode {
    CREDENTIALS,
    SESSION_COOKIES;

    fun wire(): String = if (this == CREDENTIALS) "credentials" else "sessionCookies"

    companion object {
        fun resolve(stored: String?, cookieSessionAlreadyInUse: Boolean): CaltopoPersonalLoginMode = when (stored) {
            "credentials" -> CREDENTIALS
            "sessionCookies" -> SESSION_COOKIES
            else -> if (cookieSessionAlreadyInUse) SESSION_COOKIES else CREDENTIALS
        }
    }
}

data class CredentialTitleRequest(val url: String, val json: String, val fields: Map<String, String>)

data class CaltopoActivationReturn(val code: String, val deviceName: String) {
    override fun toString(): String = "CaltopoActivationReturn(codeLength=${code.length})"
}

data class ParsedActivation(
    val accountId: String,
    val credentialId: String,
    val signingKey: String,
    val displayName: String,
    val accountType: String
) {
    override fun toString(): String = "ParsedActivation(accountId=$accountId, credentialId=$credentialId, accountType=$accountType)"
}

data class PendingActivation(
    val host: String,
    val deviceName: String,
    val port: Int,
    val pathToken: String,
    val activationUrl: String,
    val expiresAtMilliseconds: Long,
    @Volatile var acceptedReturn: CaltopoActivationReturn? = null
) {
    override fun toString(): String = "PendingActivation(host=$host, port=$port)"
}

data class CaltopoPersonalAccountRecord(
    val host: String,
    val accountId: String,
    val credentialId: String,
    val signingKey: String,
    val deviceName: String,
    val enrolledAtEpochMs: Long,
    val revoked: Boolean,
    val displayName: String
) {
    fun status() = CaltopoPersonalAccountStatus(host, accountId, credentialId, deviceName, enrolledAtEpochMs, revoked, displayName)
    override fun toString(): String = "CaltopoPersonalAccountRecord(host=$host, accountId=$accountId, credentialId=$credentialId, revoked=$revoked)"
    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("host", host)
        .put("accountId", accountId)
        .put("credentialId", credentialId)
        .put("signingKey", signingKey)
        .put("deviceName", deviceName)
        .put("enrolledAtEpochMs", enrolledAtEpochMs)
        .put("revoked", revoked)
        .put("displayName", displayName)

    companion object {
        fun fromJson(raw: String): CaltopoPersonalAccountRecord {
            val json = JSONObject(raw)
            return CaltopoPersonalAccountRecord(
                host = json.getString("host"),
                accountId = json.getString("accountId"),
                credentialId = json.getString("credentialId"),
                signingKey = json.getString("signingKey"),
                deviceName = json.getString("deviceName"),
                enrolledAtEpochMs = json.getLong("enrolledAtEpochMs"),
                revoked = json.optBoolean("revoked"),
                displayName = json.optString("displayName")
            )
        }
    }
}

data class CaltopoPersonalAccountStatus(
    val host: String,
    val accountId: String,
    val credentialId: String,
    val deviceName: String,
    val enrolledAtEpochMs: Long,
    val revoked: Boolean,
    val displayName: String
) {
    override fun toString(): String = "CaltopoPersonalAccountStatus(host=$host, accountId=$accountId, credentialId=$credentialId, revoked=$revoked)"
}

class PersonalSigningDecision(
    @JvmField val credentials: CaltopoCredentials?,
    @JvmField val failure: String?
) {
    override fun toString(): String = when {
        failure != null -> "PersonalSigningDecision(failure)"
        credentials != null -> "PersonalSigningDecision(signed)"
        else -> "PersonalSigningDecision(cookie)"
    }
}

class CaltopoPersonalAccountException(message: String, val httpStatus: Int? = null) : IllegalStateException(message) {
    fun rejectsStoredCredential(): Boolean = httpStatus == 401 || httpStatus == 403
}

interface CaltopoPersonalAccountStore {
    fun load(): CaltopoPersonalAccountRecord?
    fun save(record: CaltopoPersonalAccountRecord)
    fun clear()
}

class CaltopoPersonalAccountMemoryStore : CaltopoPersonalAccountStore {
    private var record: CaltopoPersonalAccountRecord? = null
    override fun load(): CaltopoPersonalAccountRecord? = record
    override fun save(record: CaltopoPersonalAccountRecord) { this.record = record }
    override fun clear() { record = null }
}

/** HTTPS exchange that does not follow redirects, so an activation code cannot be sent to another host. */
object CaltopoPersonalAccountHttps : CaltopoActivationExchange {
    const val MAX_ACTIVATION_BODY = 65_536
    /** A personal since/0 dump includes every workspace feature, so it is far larger than the credential JSON. */
    const val MAX_CATALOG_BODY = 16 * 1024 * 1024

    override fun get(url: String): CaltopoActivationHttp {
        val parsed = URI(url)
        require(parsed.scheme == "https" && parsed.host?.lowercase(Locale.US) in setOf("caltopo.com", "sartopo.com"))
        require(parsed.path == "/api/v1/activate" || parsed.path.matches(Regex("^/api/v1/acct/[A-Za-z0-9]{6}/since/0$")))
        val limit = if (parsed.path.endsWith("/since/0")) MAX_CATALOG_BODY else MAX_ACTIVATION_BODY
        return exchange(parsed, "GET", null, limit)
    }

    fun postForm(url: String, fields: Map<String, String>): CaltopoActivationHttp {
        val parsed = URI(url)
        require(parsed.scheme == "https" && parsed.host?.lowercase(Locale.US) in setOf("caltopo.com", "sartopo.com"))
        require(parsed.path.matches(Regex("^/api/v1/acct/[A-Za-z0-9]{6}/UserCredential/[A-Za-z0-9]{12}$")))
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${formEncode(key)}=${formEncode(value)}"
        }
        return exchange(parsed, "POST", body, MAX_ACTIVATION_BODY)
    }

    fun delete(url: String): CaltopoActivationHttp {
        val parsed = URI(url)
        require(parsed.scheme == "https" && parsed.host?.lowercase(Locale.US) in setOf("caltopo.com", "sartopo.com"))
        require(parsed.path.matches(Regex("^/api/v1/acct/[A-Za-z0-9]{6}/UserCredential/[A-Za-z0-9]{12}$")))
        return exchange(parsed, "DELETE", null, MAX_ACTIVATION_BODY)
    }

    fun readResponseBody(input: java.io.InputStream, maxBytes: Int, status: Int): String {
        val buffer = ByteArray(4096)
        val output = java.io.ByteArrayOutputStream()
        while (output.size() <= maxBytes) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        if (output.size() > maxBytes) throw CaltopoPersonalAccountException("HTTP $status response too large")
        return output.toString(Charsets.UTF_8.name())
    }

    private fun exchange(parsed: URI, method: String, body: String?, maxBytes: Int): CaltopoActivationHttp {
        val connection = parsed.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "RID2Caltopo/0.2")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream?.use { input -> readResponseBody(input, maxBytes, status) }.orEmpty()
        return CaltopoActivationHttp(status, responseBody)
    }
}

private fun formEncode(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
