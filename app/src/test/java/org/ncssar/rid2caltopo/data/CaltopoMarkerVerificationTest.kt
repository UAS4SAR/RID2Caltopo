package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CaltopoMarkerVerificationTest {
    @Test fun emptyDeltaCannotForgetPendingOwnership() {
        assertFalse(CaltopoMarkerVerification.confirmsAbsent(JSONObject("""{"state":{"features":[]}}"""), "m"))
        assertFalse(CaltopoMarkerVerification.confirmsAbsent(JSONObject("""{"state":{"features":[],"ids":{"Folder":[]}}}"""), "m"))
    }
    @Test fun explicitTombstoneOrMarkerIDListCanConfirmDeletion() {
        assertTrue(CaltopoMarkerVerification.confirmsAbsent(JSONObject("""{"state":{"features":[{"id":"m","deleted":true}]}}"""), "m"))
        assertTrue(CaltopoMarkerVerification.confirmsAbsent(JSONObject("""{"state":{"features":[]},"ids":{"Marker":[]}}"""), "m"))
    }
    @Test fun liveDuplicateOrMalformedIDListCannotConfirmDeletion() {
        assertFalse(CaltopoMarkerVerification.confirmsAbsent(JSONObject("""{"state":{"features":[{"id":"m","deleted":true},{"id":"m","geometry":{}}]}}"""), "m"))
        assertFalse(CaltopoMarkerVerification.confirmsAbsent(JSONObject("""{"state":{"features":[]},"ids":{"Marker":["m"]}}"""), "m"))
        assertFalse(CaltopoMarkerVerification.confirmsAbsent(JSONObject("""{"state":{"features":[]},"ids":{"Marker":[10]}}"""), "m"))
    }
}
