package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class ProximityAlertConsentTest {
    @Test fun missingOrOldConsentIsOffRegardlessOfSpacing() {
        assertFalse(ProximityAlertConsentState().enabled)
        assertFalse(ProximityAlertConsentState.restore(0).enabled)
        assertFalse(ProximityAlertConsentState.restore(-1).enabled)
        assertFalse(ProximityAlertConsentState.restore(99).enabled)
        assertTrue(ProximityAlertConsentState.restore(1).enabled)
    }
    @Test fun onlyAnExplicitPendingAcknowledgmentCanEnable() {
        val initial = ProximityAlertConsentState()
        assertFalse(initial.confirmEnable().enabled)
        val pending = initial.requestEnable()
        assertFalse(pending.enabled)
        assertTrue(pending.noticePending)
        assertFalse(pending.cancel().confirmEnable().enabled)
        val enabled = pending.confirmEnable()
        assertTrue(enabled.enabled)
        assertFalse(enabled.noticePending)
        val disabled = enabled.disable()
        assertFalse(disabled.enabled)
        assertFalse(disabled.confirmEnable().enabled)
        assertTrue(disabled.requestEnable().noticePending)
    }
    @Test fun disclosureRetainsEssentialCaveats() {
        assertTrue(ProximityAlertConsent.notice.contains("No alert does not mean the airspace is clear."))
        assertTrue(ProximityAlertConsent.notice.contains("DJI video telemetry accuracy has not been verified"))
        assertTrue(ProximityAlertConsent.notice.contains("Do not rely on these alerts to avoid a collision."))
    }
}
