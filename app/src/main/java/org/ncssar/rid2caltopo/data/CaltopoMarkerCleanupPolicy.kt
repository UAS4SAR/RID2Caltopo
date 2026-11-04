package org.ncssar.rid2caltopo.data

/**
 * Interprets device-marker DELETE results for journal cleanup.
 *
 * A DELETE 400 is not proof the marker is gone (field logs showed success=true/400 while the
 * marker remained). Only 404 means already absent; 200 and 400 require an absence verify.
 */
object CaltopoMarkerCleanupPolicy {
    data class AfterDelete(
        /** Journal may clear without a since-GET (404 already gone, or terminal auth). */
        val terminal: Boolean,
        /** Treat as verified success (404). */
        val success: Boolean,
        /** Run since-GET absence verification. */
        val needsVerify: Boolean,
        val terminalAuth: Boolean,
    )

    @JvmStatic
    fun afterDelete(deleteCode: Int, deleteAcceptedAsHttpSuccess: Boolean): AfterDelete {
        if (deleteCode == 401 || deleteCode == 403) {
            return AfterDelete(terminal = true, success = false, needsVerify = false, terminalAuth = true)
        }
        if (deleteCode == 404) {
            return AfterDelete(terminal = true, success = true, needsVerify = false, terminalAuth = false)
        }
        if (deleteCode == 400 || deleteAcceptedAsHttpSuccess) {
            return AfterDelete(terminal = false, success = false, needsVerify = true, terminalAuth = false)
        }
        return AfterDelete(terminal = false, success = false, needsVerify = false, terminalAuth = false)
    }

    /** Final success after a verify GET: only confirmed absence counts. */
    @JvmStatic
    fun afterVerify(absent: Boolean): Boolean = absent
}
