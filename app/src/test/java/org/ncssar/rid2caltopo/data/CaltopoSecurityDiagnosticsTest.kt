package org.ncssar.rid2caltopo.data

import okhttp3.Interceptor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

class CaltopoSecurityDiagnosticsTest {
    @Test fun operationDiagnosticsNeverSerializePayloadResponseOrCapabilityUrl() {
        val secret = "FAKE_COOKIE_AND_CAPABILITY_SECRET"
        val op = CaltopoOp(null).apply {
            method = CaltopoSession.CtsMethod_t.POST
            url = "https://caltopo.com/api/v1/position/$secret?signature=$secret"
            payload = JSONObject().put("Cookie", secret).put("private-location", secret)
            response = "Redirect Location: https://caltopo.com/$secret Cookie: $secret"
            responseJson = JSONObject().put("result", secret)
            responseCode = 302
        }
        for (message in listOf(op.toString(), op.responseString(), CaltopoDiagnostic.operation(secret, 403, -1))) {
            assertFalse(message, message.contains(secret))
            assertFalse(message, message.contains("https://"))
        }
        assertTrue(op.toString().contains("302"))
    }
    @Test fun queuedRequestRevocationPreventsDispatchAndDoesNotExposeExceptionSecrets() {
        val ledger = PersonalSessionGrants().apply { observe("A", snapshot()) }
        val token = ledger.publish(ledger.snapshot(), listOf("ABC123")).getValue("ABC123")
        val url = "https://caltopo.com/api/v1/map/ABC123/since/0"
        assertEquals("fake-cookie", ledger.cookie(token, url) { "fake-cookie" })
        ledger.clear()
        var proceeded = false
        val chain = Proxy.newProxyInstance(Interceptor.Chain::class.java.classLoader, arrayOf(Interceptor.Chain::class.java)) { _, method, _ ->
            if (method.name == "proceed") proceeded = true
            error("No chain method should run after revocation")
        } as Interceptor.Chain
        val interceptor = PersonalSessionDispatchInterceptor { check(ledger.valid(token, url)) { "fake-cookie https://caltopo.com/secret" } }
        val failure = assertThrows(IOException::class.java) { interceptor.intercept(chain) }
        assertFalse(proceeded)
        assertFalse(failure.toString().contains("fake-cookie"))
        assertNull(failure.cause)
    }
}
