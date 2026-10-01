package org.ncssar.rid2caltopo.data

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Network interceptor runs after connection/queue waits, immediately before writing the request. */
class PersonalSessionDispatchInterceptor(
    private val validate: () -> Unit
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        try { validate() } catch (_: Exception) {
            throw IOException("Personal login revoked; Team credentials were not used.")
        }
        return chain.proceed(chain.request())
    }
}
