package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class CaltopoPersonalAccountConnectionTest {
    @Test fun loginModeDefaultsToCredentialsUnlessACookieSessionAlreadyExists() {
        assertEquals(CaltopoPersonalLoginMode.CREDENTIALS, CaltopoPersonalLoginMode.resolve(null, cookieSessionAlreadyInUse = false))
        assertEquals(CaltopoPersonalLoginMode.SESSION_COOKIES, CaltopoPersonalLoginMode.resolve(null, cookieSessionAlreadyInUse = true))
        assertEquals(CaltopoPersonalLoginMode.CREDENTIALS, CaltopoPersonalLoginMode.resolve("credentials", cookieSessionAlreadyInUse = true))
        assertEquals(CaltopoPersonalLoginMode.SESSION_COOKIES, CaltopoPersonalLoginMode.resolve("sessionCookies", cookieSessionAlreadyInUse = false))
    }

    @Test fun credentialsModeDoesNotFallBackToCookiesWhenNothingIsStored() {
        CaltopoPersonalAccountConnection.applyLoginMode(CaltopoPersonalLoginMode.CREDENTIALS)
        val decision = CaltopoPersonalAccountConnection.decide("USER01")
        assertNull(decision.credentials)
        assertTrue(decision.failure!!.contains("Session cookies are not used"))
    }

    @Before fun reset() { CaltopoPersonalAccountConnection.resetForTests() }
    @After fun clear() { CaltopoPersonalAccountConnection.resetForTests() }

    @Test fun signatureMatchesTheSharedCalTopoVector() {
        val secret = Base64.getEncoder().encodeToString("team-secret-1".toByteArray())
        val signature = CaltopoPersonalAccountConnection.sign(
            "GET", "/api/v1/acct/team-42/since/0", 1_700_000_120_000, "", secret
        )
        assertEquals("uwNMwY6YwT7qcXhOp1yBhFPXFk779c+rP83s3O/SWjI=", signature)
    }

    @Test fun flagOffIgnoresAStoredCredentialAndDoesNotReadTheStore() {
        CaltopoPersonalAccountConnection.install(object : CaltopoPersonalAccountStore {
            override fun load(): CaltopoPersonalAccountRecord? = error("store should stay unread")
            override fun save(record: CaltopoPersonalAccountRecord) = error("store should stay unread")
            override fun clear() = error("store should stay unread")
        }, featureEnabled = false)
        val decision = CaltopoPersonalAccountConnection.decide("USER01")
        assertNull(decision.credentials)
        assertNull(decision.failure)
        assertEquals("PersonalSigningDecision(cookie)", decision.toString())
        assertNull(CaltopoPersonalAccountConnection.accountCatalogRequest())
    }

    @Test fun activationUrlUsesLoopbackAndRejectsOtherBrowsingTargets() {
        val pending = CaltopoPersonalAccountConnection.begin("caltopo.com", "RID2Caltopo", 9)
        assertTrue(pending.activationUrl.startsWith("https://caltopo.com/app/activate/offline?redirect="))
        assertTrue(CaltopoPersonalAccountConnection.isActivationUrl(pending.activationUrl))
        val redirect = URLDecoder.decode(pending.activationUrl.substringAfter("redirect=").substringBefore("&"), StandardCharsets.UTF_8)
        assertTrue(redirect.matches(Regex("http://127\\.0\\.0\\.1:9/[0-9a-f]{32}")))
        assertFalse(redirect.contains("?"))
        assertFalse(pending.toString().contains(pending.pathToken))
        assertFalse(CaltopoPersonalAccountConnection.isActivationUrl("https://accounts.google.com/o/oauth2/auth"))
        assertFalse(CaltopoPersonalAccountConnection.isActivationUrl("https://caltopo.com/account/login"))
        assertFalse(CaltopoPersonalAccountConnection.isActivationUrl("javascript:alert(1)"))
    }

    @Test fun callbackMatchesCalTopoStringAppendOnLoopbackAndCustomScheme() {
        val pending = CaltopoPersonalAccountConnection.begin("CalTopo.com", "RID2Caltopo", 4242)
        val appended = pending.activationUrl.let {
            val redirect = URLDecoder.decode(it.substringAfter("redirect=").substringBefore("&"), StandardCharsets.UTF_8)
            redirect + "/client/finish-activate?code=abcd1234&name=R2C+probe"
        }
        val returned = CaltopoPersonalAccountConnection.acceptCallback(
            "/${pending.pathToken}/client/finish-activate?code=abcd1234&name=R2C+probe"
        )
        assertEquals("abcd1234", returned.code)
        assertEquals("R2C probe", returned.deviceName)
        assertTrue(appended.endsWith("/${pending.pathToken}/client/finish-activate?code=abcd1234&name=R2C+probe"))
        val custom = CaltopoPersonalAccountConnection.begin("caltopo.com", "RID2Caltopo", 4242)
        val prefix = CaltopoPersonalAccountConnection.customSchemePrefix("caltopoapp", custom.pathToken)
        assertFalse(prefix.contains("?"))
        val customReturn = CaltopoPersonalAccountConnection.acceptCallback(
            prefix + "/client/finish-activate?code=abcd1234&name=R2C+probe"
        )
        assertEquals("abcd1234", customReturn.code)
        assertEquals("R2C probe", customReturn.deviceName)
        val broken = CaltopoPersonalAccountConnection.begin("caltopo.com", "RID2Caltopo", 4242)
        assertTrue(runCatching {
            CaltopoPersonalAccountConnection.acceptCallback(
                "/${broken.pathToken}?state=r2ctest123/client/finish-activate?code=abcd1234&name=R2C+probe"
            )
        }.isFailure)
        assertTrue(runCatching {
            CaltopoPersonalAccountConnection.acceptCallback("https://evil.test/${broken.pathToken}/client/finish-activate?code=abcd1234&name=R2C+probe")
        }.isFailure)
    }

    @Test fun exchangeParsesDesktopShapeAndBindsHostPlusAccount() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val body = """{"code":"cred12345678","key":"$key","account":{"id":"USER01","type":"account","properties":{"class":"UserAccount","email":"pilot@example.com","firstName":"Test","lastName":"Pilot","title":"Pilot","subscriptionType":"pro","subscriptionStatus":"active","subscriptionExpires":1790000000000,"subscriptionRenew":true,"guest":false,"featuresId":"pro","provider":"google","updated":1790000000000}}}"""
        val record = CaltopoPersonalAccountConnection.completeExchange(body, "abcd1234", "caltopo.com", "RID2Caltopo", now = 50)
        assertEquals("USER01", record.accountId)
        assertEquals("cred12345678", record.credentialId)
        assertEquals("Test Pilot", record.displayName)
        assertFalse(record.toString().contains(key))
        assertFalse(record.toString().contains("pilot@example.com"))
        val wrapped = """{"status":"ok","result":{"code":"cred12345678","key":"$key","account":{"id":"USER01","type":"account","properties":{"class":"UserAccount"}}}}"""
        assertEquals("USER01", CaltopoPersonalAccountConnection.parseActivationResponse(wrapped).accountId)
        assertEquals("account", CaltopoPersonalAccountConnection.parseActivationResponse(wrapped).accountType)
        assertTrue(runCatching { CaltopoPersonalAccountConnection.parseActivationResponse("""{"status":"error","message":"no"}""") }.isFailure)
        assertTrue(runCatching {
            CaltopoPersonalAccountConnection.parseActivationResponse("""{"code":"cred12345678","key":"$key","account":{"id":"USER01","properties":{"class":"TeamAccount"}}}""")
        }.exceptionOrNull()!!.message!!.contains("personal account"))
        val teams = CaltopoCredentials("TEAM01", "teamcred1234", "dGVhbQ==")
        CaltopoPersonalAccountConnection.install(CaltopoPersonalAccountMemoryStore(), featureEnabled = true)
        val saved = CaltopoPersonalAccountConnection.completeExchange(body, "abcd1234", "caltopo.com", "RID2Caltopo", now = 50)
        // completeExchange does not store. Enrollment does. Persist through the public reconnect path by enrolling via the store API.
        val store = CaltopoPersonalAccountMemoryStore()
        store.save(saved)
        CaltopoPersonalAccountConnection.install(store, featureEnabled = true)
        assertEquals("TEAM01", teams.teamId)
        assertEquals("teamcred1234", teams.credentialId)
        val signed = CaltopoPersonalAccountConnection.decide("USER01")
        assertEquals("cred12345678", signed.credentials!!.credentialId)
        assertEquals("USER01", signed.credentials!!.teamId)
        assertNull(CaltopoPersonalAccountConnection.decide("OTHER1").credentials)
        assertTrue(CaltopoPersonalAccountConnection.decide("OTHER1").failure!!.contains("not covered"))
        val catalog = CaltopoPersonalAccountConnection.accountCatalogRequest(1_700_000_000_000)
        assertTrue(catalog!!.url.startsWith("https://caltopo.com/api/v1/acct/USER01/since/0?"))
        assertTrue(catalog.url.contains("id=cred12345678"))
        assertFalse(catalog.url.contains(key))
    }

    @Test fun revokedCredentialDoesNotFallBackToCookiesAndDisconnectClearsIt() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
        val record = CaltopoPersonalAccountConnection.completeExchange(
            """{"code":"cred12345678","key":"$key","account":{"id":"USER01","type":"account","properties":{"class":"UserAccount"}}}""",
            "abcd1234", "sartopo.com", "RID2Caltopo", 10
        )
        val store = CaltopoPersonalAccountMemoryStore()
        store.save(record.copy(host = "caltopo.com"))
        CaltopoPersonalAccountConnection.install(store, featureEnabled = true)
        assertEquals("USER01", CaltopoPersonalAccountConnection.reconnect("caltopo.com")!!.accountId)
        CaltopoPersonalAccountConnection.markRevoked()
        val revoked = CaltopoPersonalAccountConnection.decide("USER01")
        assertNull(revoked.credentials)
        assertTrue(revoked.failure!!.contains("revoked"))
        assertFalse(revoked.failure!!.contains(key))
        assertNull(CaltopoPersonalAccountConnection.reconnect("caltopo.com"))
        CaltopoPersonalAccountConnection.disconnect()
        assertNull(CaltopoPersonalAccountConnection.status("caltopo.com"))
        val signedOut = CaltopoPersonalAccountConnection.decide("USER01")
        assertNull(signedOut.credentials)
        assertTrue(signedOut.failure!!.contains("Session cookies are not used"))
        CaltopoPersonalAccountConnection.applyLoginMode(CaltopoPersonalLoginMode.SESSION_COOKIES)
        assertNull(CaltopoPersonalAccountConnection.decide("USER01").failure)
    }

    @Test fun credentialTitleRequestMatchesTheVerifiedFormPost() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        val record = CaltopoPersonalAccountConnection.completeExchange(
            """{"code":"cred12345678","key":"$key","account":{"id":"USER01","type":"account","properties":{"class":"UserAccount"}}}""",
            "abcd1234", "caltopo.com", "RID2Caltopo", 50
        )
        val request = CaltopoPersonalAccountConnection.credentialTitleRequest(record, 1_700_000_000_000)
        val path = "/api/v1/acct/USER01/UserCredential/cred12345678"
        val json = """{"id":"cred12345678","properties":{"class":"UserCredential","title":"Last synced by RID2Caltopo app"}}"""
        assertEquals("https://caltopo.com$path", request.url)
        assertEquals(json, request.json)
        assertEquals(record.credentialId, request.fields["id"])
        assertEquals("1700000120000", request.fields["expires"])
        assertEquals(json, request.fields["json"])
        assertEquals(CaltopoPersonalAccountConnection.sign("POST", path, 1_700_000_120_000, json, key), request.fields["signature"])
        assertFalse(request.fields["signature"]!!.contains(key))
    }

    @Test fun enrollAcceptsOnlyTheLoopbackCallbackAndStoresTheExchange() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        val seen = mutableListOf<String>()
        val titles = mutableListOf<String>()
        CaltopoPersonalAccountConnection.titleClient = { request ->
            titles += request.json
            CaltopoActivationHttp(500, "no")
        }
        var warned = false
        CaltopoPersonalAccountConnection.titleFailureLog = { warned = true }
        val http = CaltopoActivationExchange { url ->
            seen += url
            assertFalse(url.contains(key))
            CaltopoActivationHttp(200, """{"code":"cred12345678","key":"$key","account":{"id":"USER01","type":"account","properties":{"class":"UserAccount","firstName":"Test","lastName":"Pilot"}}}""")
        }
        val browser = CaltopoSystemBrowser { activation ->
            val redirect = URI(URLDecoder.decode(activation.substringAfter("redirect=").substringBefore("&"), StandardCharsets.UTF_8))
            check(redirect.rawQuery == null)
            val request = "GET ${redirect.rawPath}/client/finish-activate?code=abcd1234&name=R2C+probe HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
            val started = System.currentTimeMillis()
            var accepted = false
            while (System.currentTimeMillis() - started < 5_000 && !accepted) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", redirect.port), 200)
                        socket.soTimeout = 2_000
                        socket.getOutputStream().write(request.toByteArray())
                        socket.getOutputStream().flush()
                        val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                        assertTrue(response.startsWith("HTTP/1.1 200"))
                        assertFalse(response.contains(key))
                        assertFalse(response.contains("abcd1234"))
                        accepted = true
                    }
                } catch (_: Exception) {
                    Thread.sleep(20)
                }
            }
            check(accepted) { "loopback listener did not accept the activation callback" }
        }
        val teams = CaltopoCredentials("TEAM01", "teamcred1234", "dGVhbQ==")
        val status = CaltopoPersonalAccountConnection.enroll(browser = browser, http = http)
        assertEquals("USER01", status.accountId)
        assertEquals("caltopo.com", status.host)
        assertEquals(listOf("https://caltopo.com/api/v1/activate?code=abcd1234&name=R2C%20probe"), seen)
        assertEquals(listOf("""{"id":"cred12345678","properties":{"class":"UserCredential","title":"Last synced by RID2Caltopo app"}}"""), titles)
        assertTrue(warned)
        assertEquals("cred12345678", CaltopoPersonalAccountConnection.status("caltopo.com")!!.credentialId)
        assertEquals("TEAM01", teams.credentialId.let { teams.teamId }.let { "TEAM01" })
        assertEquals("teamcred1234", teams.credentialId)
        CaltopoPersonalAccountConnection.featureEnabled = true
        assertEquals("cred12345678", CaltopoPersonalAccountConnection.decide("USER01").credentials!!.credentialId)
        val workspace = """{"status":"ok","result":{"accounts":[{"id":"USER01","properties":{"class":"UserAccount","firstName":"Test","lastName":"Pilot"}},{"id":"WORK01","properties":{"class":"UserAccount","alias":"Incident Workspace"}}],"features":[{"id":"GHI789","properties":{"class":"CollaborativeMap","accountId":"WORK01","title":"Restricted","updated":1}}],"groups":[{"properties":{"class":"UserAccountGroupRel","accountId":"USER01","referenceAccountId":"WORK01","type":16}}],"rels":[]}}"""
        val body = CaltopoPersonalAccountConnection.fetchAccountCatalog(CaltopoActivationExchange { url ->
            assertTrue(url.contains("/api/v1/acct/USER01/since/0"))
            CaltopoActivationHttp(200, workspace)
        })
        val normalized = CaltopoPersonalCatalog.normalize(body!!, "USER01")
        assertTrue(normalized.getJSONArray("accounts").toString().contains("WORK01"))
        assertEquals("Incident Workspace", JSONObject(workspace).getJSONObject("result").getJSONArray("accounts").getJSONObject(1).getJSONObject("properties").getString("alias"))
    }

    @Test fun expiredActivationAndFailedExchangeDoNotKeepTheCodeOrKey() {
        var now = 1_000L
        CaltopoPersonalAccountConnection.clock = { now }
        val pending = CaltopoPersonalAccountConnection.begin("caltopo.com", "RID2Caltopo", 9)
        now += 5 * 60 * 1000L + 1
        val error = runCatching {
            CaltopoPersonalAccountConnection.acceptCallback("/${pending.pathToken}/client/finish-activate?code=abcd1234&name=RID2Caltopo", now)
        }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("expired"))
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        val exchanged = runCatching {
            CaltopoPersonalAccountConnection.completeExchange("""{"key":"$key","code":"nope","account":{"id":"USER01","type":"account","properties":{"class":"UserAccount"}}}""", "abcd1234", "caltopo.com", "RID2Caltopo")
        }.exceptionOrNull()!!
        assertFalse(exchanged.message!!.contains(key))
    }
}
