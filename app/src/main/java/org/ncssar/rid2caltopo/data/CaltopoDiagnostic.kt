package org.ncssar.rid2caltopo.data

/** Deliberately excludes URLs, bodies, exception messages and headers, including on failures. */
object CaltopoDiagnostic {
    @JvmStatic fun operation(method: String?, status: Int, durationMillis: Long): String {
        val verb = method?.takeIf { it in setOf("GET", "POST", "DELETE", "PUT", "PATCH") } ?: "REQUEST"
        return "CalTopo $verb HTTP $status durationMs=${durationMillis.coerceAtLeast(0)}"
    }
}
