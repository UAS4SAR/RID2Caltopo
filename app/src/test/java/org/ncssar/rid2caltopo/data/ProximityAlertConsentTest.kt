package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class ProximityAlertConsentTest {
    @Test fun missingOrOldConsentIsOffRegardlessOfSpacing() {
        assertFalse(ProximityAlertConsentState().enabled)
        assertFalse(ProximityAlertConsentState.restore(0).enabled)
        assertFalse(ProximityAlertConsentState.restore(-1).enabled)
        assertFalse(ProximityAlertConsentState.restore(99).enabled)
        assertFalse(ProximityAlertConsentState.restore(1).enabled)
        assertTrue(ProximityAlertConsentState.restore(2).enabled)
    }
    @Test fun version1AcceptanceRestoresOffAndNeedsOneReacknowledgment() {
        assertEquals(2, ProximityAlertConsentState.NOTICE_VERSION)
        assertFalse(ProximityAlertConsentState.restore(1).enabled)
        assertFalse(ProximityAlertConsentState.restore(1).noticePending)
        assertTrue(ProximityAlertConsentState.needsReacknowledgment(1))
        assertFalse(ProximityAlertConsentState.needsReacknowledgment(0)) // never enabled: no prompt
        assertFalse(ProximityAlertConsentState.needsReacknowledgment(99)) // newer notice from a downgrade
    }
    @Test fun currentAcceptancePersistsWithoutFurtherPromptsAcrossRestarts() {
        // Launch after update: stored v1 -> prompt; user checks all four and enables.
        var stored = 1
        assertTrue(ProximityAlertConsentState.needsReacknowledgment(stored))
        stored = 0 // beginReacknowledgment clears the stale acceptance before prompting
        val accepted = ProximityAlertConsentState.restore(stored).requestEnable().acknowledgeAll().confirmEnable()
        assertTrue(accepted.enabled)
        stored = ProximityAlertConsentState.NOTICE_VERSION // what confirmEnable persists
        repeat(3) {
            assertTrue(ProximityAlertConsentState.restore(stored).enabled)
            assertFalse(ProximityAlertConsentState.restore(stored).noticePending)
            assertFalse(ProximityAlertConsentState.needsReacknowledgment(stored))
        }
        // Declining the one-time prompt leaves nothing stored, so later launches stay quiet and off.
        assertFalse(ProximityAlertConsentState.needsReacknowledgment(0))
        assertFalse(ProximityAlertConsentState.restore(0).enabled)
    }
    @Test fun onlyAnExplicitPendingAcknowledgmentCanEnable() {
        val initial = ProximityAlertConsentState()
        assertFalse(initial.confirmEnable().enabled)
        val pending = initial.requestEnable()
        assertFalse(pending.enabled)
        assertTrue(pending.noticePending)
        assertFalse(pending.cancel().confirmEnable().enabled)
        val enabled = pending.acknowledgeAll().confirmEnable()
        assertTrue(enabled.enabled)
        assertFalse(enabled.noticePending)
        val disabled = enabled.disable()
        assertFalse(disabled.enabled)
        assertFalse(disabled.confirmEnable().enabled)
        assertTrue(disabled.requestEnable().noticePending)
    }
    @Test fun enableRequiresEveryParagraphCheckedAndChecksResetOnEachOpen() {
        var state = ProximityAlertConsentState().requestEnable()
        assertTrue(state.acknowledged.isEmpty())
        assertEquals("Confirm each paragraph", state.confirmLabel)
        for (index in 0 until ProximityAlertConsentState.NOTICE_PARAGRAPH_COUNT - 1) {
            state = state.toggleAcknowledgment(index)
            assertFalse(state.canConfirm)
            assertEquals("Confirm each paragraph", state.confirmLabel)
            assertFalse(state.confirmEnable().enabled)
        }
        state = state.toggleAcknowledgment(ProximityAlertConsentState.NOTICE_PARAGRAPH_COUNT - 1)
        assertTrue(state.canConfirm)
        assertEquals("Enable alerts", state.confirmLabel)
        assertEquals("Confirm each paragraph", state.toggleAcknowledgment(2).confirmLabel)
        assertFalse(state.toggleAcknowledgment(0).canConfirm)
        assertEquals(state, state.toggleAcknowledgment(-1).toggleAcknowledgment(ProximityAlertConsentState.NOTICE_PARAGRAPH_COUNT))
        assertTrue(state.cancel().acknowledged.isEmpty())
        assertTrue(state.cancel().requestEnable().acknowledged.isEmpty())
        assertFalse(state.cancel().requestEnable().canConfirm)
        assertTrue(ProximityAlertConsentState().toggleAcknowledgment(0).acknowledged.isEmpty())
        val enabled = state.confirmEnable()
        assertTrue(enabled.enabled)
        assertTrue(enabled.acknowledged.isEmpty())
        assertTrue(enabled.disable().requestEnable().acknowledged.isEmpty())
    }
    @Test fun noticeHasOneCheckboxParagraphPerStatementWithUnchangedText() {
        val paragraphs = ProximityAlertConsent.noticeParagraphs
        assertEquals(ProximityAlertConsentState.NOTICE_PARAGRAPH_COUNT, paragraphs.size)
        assertEquals(ProximityAlertConsent.notice, paragraphs.joinToString("\n\n"))
        assertTrue(paragraphs[0].startsWith("Proximity alerts provide supplemental situational awareness."))
        assertTrue(paragraphs[3].endsWith("Do not rely on these alerts to avoid a collision."))
        paragraphs.forEach { assertEquals(it.trim(), it) }
    }
    private fun ProximityAlertConsentState.acknowledgeAll() =
        (0 until ProximityAlertConsentState.NOTICE_PARAGRAPH_COUNT).fold(this) { state, index -> state.toggleAcknowledgment(index) }
    @Test fun disclosureRetainsEssentialCaveats() {
        assertTrue(ProximityAlertConsent.notice.contains("No alert does not mean the airspace is clear."))
        assertTrue(ProximityAlertConsent.notice.contains("DJI video telemetry accuracy has not been verified"))
        assertTrue(ProximityAlertConsent.notice.contains("Do not rely on these alerts to avoid a collision."))
    }
}
