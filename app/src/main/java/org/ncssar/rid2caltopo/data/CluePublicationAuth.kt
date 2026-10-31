package org.ncssar.rid2caltopo.data

/**
 * Personal clue uploads share the live-track grant. A signed-account token is HMAC;
 * only a browser-session grant needs the cookie media probe.
 */
object CluePublicationAuth {
    @JvmStatic
    fun usesSignedPersonalCredential(personal: CaltopoPersonalSession.Authorization?): Boolean =
        personal != null && CaltopoPersonalAccountConnection.usesSignedCredential(personal.token)

    /** Cookie media grants are only for browser-session maps. */
    @JvmStatic
    fun requiresCookieMediaAuthorization(personal: CaltopoPersonalSession.Authorization?): Boolean =
        personal != null && !usesSignedPersonalCredential(personal)

    @JvmStatic
    fun loginExpiredMessage(responseCode: Int): String? =
        if (responseCode == 401 || responseCode == 403) {
            "Personal login expired; photo remains queued."
        } else {
            null
        }
}
