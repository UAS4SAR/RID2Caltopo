package org.ncssar.rid2caltopo.ui

import org.junit.Assert.*
import org.junit.Test

class TermsAcceptanceTest {
    @Test fun firstLaunchAndDamagedRecordRequireAcceptance() {
        assertNull(TermsAcceptance.decode(null))
        assertNull(TermsAcceptance.decode("not json"))
        assertNull(TermsAcceptance.decode("{\"version\":\"2026-09-24\"}"))
        assertFalse(TermsAcceptance(ApplicationTerms.version, LAUNCH_DISCLAIMER_TEXT, 0).isCurrent())
    }

    @Test fun savedRecordSurvivesReloadButRejectsChangedTerms() {
        val accepted = TermsAcceptance(ApplicationTerms.version, LAUNCH_DISCLAIMER_TEXT, 1_790_000_000_000)
        val restored = TermsAcceptance.decode(accepted.encode())!!
        assertEquals(accepted, restored)
        assertTrue(restored.isCurrent())
        assertFalse(restored.isCurrent(currentVersion = "future"))
        assertFalse(restored.isCurrent(currentText = LAUNCH_DISCLAIMER_TEXT + " Changed."))
        assertTrue(restored.logMessage("TermsAccepted").contains("acceptedAt="))
        assertTrue(restored.logMessage("TermsAcceptanceRestored").startsWith("TermsAcceptanceRestored "))
    }

    @Test fun fingerprintUsesStandardSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ApplicationTerms.fingerprint("abc"))
    }
}
