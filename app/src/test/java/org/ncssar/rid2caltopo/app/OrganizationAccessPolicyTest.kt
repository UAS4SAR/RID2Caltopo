package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationAccessPolicyTest {
    @Test
    fun freshInstallCanOpenSetupFlowsBeforeAnOrganizationAccountExists() {
        var authenticatedFlowAttempted = false

        assertTrue(
            beginTrustedExternalFlowWhenRequired(authenticationRequired = false) {
                authenticatedFlowAttempted = true
                false
            }
        )
        assertFalse(authenticatedFlowAttempted)
    }

    @Test
    fun configuredInstallStillRequiresAnAuthenticatedExternalFlow() {
        assertFalse(
            beginTrustedExternalFlowWhenRequired(authenticationRequired = true) { false }
        )
        assertTrue(
            beginTrustedExternalFlowWhenRequired(authenticationRequired = true) { true }
        )
    }

    @Test
    fun organizationOrCaltopoTeamsConfigurationRequiresDeviceOwnerAuthentication() {
        assertFalse(organizationAccessAuthenticationRequired(null, false, false))
        assertFalse(organizationAccessAuthenticationRequired("", false, false))
        assertFalse(organizationAccessAuthenticationRequired("  \n", false, false))
        assertTrue(organizationAccessAuthenticationRequired("NCSSAR", false, false))
        assertTrue(organizationAccessAuthenticationRequired("", true, false))
        assertTrue(organizationAccessAuthenticationRequired("", false, true))
        assertTrue(organizationAccessAuthenticationRequired("NCSSAR", true, true))
    }

    @Test
    fun authenticatedSessionSurvivesTrustedArchivePickerUntilItsResult() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()

        assertTrue(session.beginTrustedExternalFlow(OrganizationExternalFlow.ARCHIVE_DIRECTORY_PICKER))
        assertTrue(session.activityStopped(isChangingConfigurations = false))
        assertTrue(session.isAuthenticated())

        session.completeTrustedExternalFlow(OrganizationExternalFlow.ARCHIVE_DIRECTORY_PICKER)
        assertTrue(session.isAuthenticated())
    }

    @Test
    fun authenticatedSessionSurvivesConfigQrScannerUntilItsResult() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()

        assertTrue(session.beginTrustedExternalFlow(OrganizationExternalFlow.CONFIG_QR_SCANNER))
        assertTrue(session.activityStopped(isChangingConfigurations = false))
        assertTrue(session.isAuthenticated())

        session.completeTrustedExternalFlow(OrganizationExternalFlow.CONFIG_QR_SCANNER)
        assertTrue(session.isAuthenticated())
    }

    @Test
    fun capturedVideoPickerPreservesAccessUntilSelectionOrCancellation() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()
        assertTrue(session.beginTrustedExternalFlow(OrganizationExternalFlow.CAPTURED_VIDEO_PICKER))
        assertTrue(session.activityStopped(isChangingConfigurations = false))
        session.completeTrustedExternalFlow(OrganizationExternalFlow.CAPTURED_VIDEO_PICKER)
        assertTrue(session.isAuthenticated())
        // Completing or cancelling the picker still preserves access while the device
        // remains unlocked; a real screen lock invalidates it.
        assertTrue(session.activityStopped(isChangingConfigurations = false))
    }

    @Test
    fun capturedVideoPickerResultCannotUnlockARealScreenLock() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()
        assertTrue(session.beginTrustedExternalFlow(OrganizationExternalFlow.CAPTURED_VIDEO_PICKER))
        assertFalse(session.activityStopped(false, screenOffElapsedRealtimeMs = 1_000L))
        session.completeTrustedExternalFlow(OrganizationExternalFlow.CAPTURED_VIDEO_PICKER)
        assertFalse(session.isAuthenticated())
        assertTrue(session.authenticateFromSystemUnlock(1_100L, deviceLocked = false))
        // A fresh selection can be launched after returning from the lock.
        assertTrue(session.beginTrustedExternalFlow(OrganizationExternalFlow.CAPTURED_VIDEO_PICKER))
    }

    @Test
    fun authenticatedSessionSurvivesTrackerReauthenticationBrowserUntilReturn() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()

        assertTrue(
            session.beginTrustedExternalFlow(
                OrganizationExternalFlow.TRACKER_REAUTHENTICATION_BROWSER
            )
        )
        assertTrue(session.activityStopped(isChangingConfigurations = false))
        assertTrue(session.isAuthenticated())

        session.completeTrustedExternalFlow(
            OrganizationExternalFlow.TRACKER_REAUTHENTICATION_BROWSER
        )
        assertTrue(session.isAuthenticated())
    }

    @Test
    fun ordinaryBackgroundingPreservesAuthenticatedSessionUntilDeviceLock() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()

        assertTrue(session.activityStopped(isChangingConfigurations = false))
        assertTrue(session.isAuthenticated())
    }

    @Test
    fun screenLockHandoffSurvivesWhenActivityStopsBeforeScreenOffBroadcast() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()

        assertFalse(
            session.activityStopped(
                isChangingConfigurations = false,
                screenOffElapsedRealtimeMs = 1_000L,
            )
        )
        assertTrue(session.isAwaitingSystemUnlock())
        assertTrue(session.authenticateFromUserPresent())
        assertTrue(session.isAuthenticated())
    }

    @Test
    fun screenOffAfterActivityStopPreservesSystemUnlockHandoff() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()
        assertFalse(session.activityStopped(false, screenOffElapsedRealtimeMs = 1_000L))
        session.invalidateForScreenLock(screenOffElapsedRealtimeMs = 1_010L)

        assertFalse(session.isAuthenticated())
        assertTrue(session.isAwaitingSystemUnlock())
        assertTrue(session.authenticateFromUserPresent())
        assertTrue(session.isAuthenticated())
    }

    @Test
    fun repeatedScreenOffPreservesOriginalAuthenticationBoundary() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()
        session.invalidateForScreenLock(screenOffElapsedRealtimeMs = 1_000L)
        session.invalidateForScreenLock(screenOffElapsedRealtimeMs = 1_020L)
        assertFalse(session.activityStopped(false, screenOffElapsedRealtimeMs = 1_030L))

        assertFalse(session.authenticateFromSystemUnlock(999L, deviceLocked = false))
        assertFalse(session.authenticateFromSystemUnlock(1_010L, deviceLocked = true))
        assertTrue(session.authenticateFromSystemUnlock(1_010L, deviceLocked = false))
    }

    @Test
    fun explicitInvalidationClearsPendingSystemUnlockHandoff() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()
        session.invalidateForScreenLock(screenOffElapsedRealtimeMs = 1_000L)
        session.invalidate()
        session.invalidateForScreenLock(screenOffElapsedRealtimeMs = 1_020L)

        assertFalse(session.authenticateFromUserPresent())
        assertFalse(session.authenticateFromSystemUnlock(1_100L, deviceLocked = false))
    }

    @Test
    fun screenLockInvalidatesAuthenticationWhileRetainingPickerCompletion() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()
        assertTrue(session.beginTrustedExternalFlow(OrganizationExternalFlow.ARCHIVE_DIRECTORY_PICKER))

        session.invalidateForScreenLock(screenOffElapsedRealtimeMs = 1_000L)
        assertFalse(session.isAuthenticated())

        session.completeTrustedExternalFlow(OrganizationExternalFlow.ARCHIVE_DIRECTORY_PICKER)
        assertFalse(session.isAuthenticated())
    }

    @Test
    fun systemAuthenticationAfterScreenLockUnlocksTheProtectedSession() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()
        session.invalidateForScreenLock(screenOffElapsedRealtimeMs = 1_000L)

        assertTrue(session.isAwaitingSystemUnlock())
        assertTrue(
            session.authenticateFromSystemUnlock(
                authenticationElapsedRealtimeMs = 1_100L,
                deviceLocked = false,
            )
        )
        assertTrue(session.isAuthenticated())
        assertFalse(session.isAwaitingSystemUnlock())
    }

    @Test
    fun staleOrStillLockedSystemAuthenticationDoesNotUnlockTheProtectedSession() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()
        session.invalidateForScreenLock(screenOffElapsedRealtimeMs = 1_000L)

        assertFalse(session.authenticateFromSystemUnlock(999L, deviceLocked = false))
        assertFalse(session.authenticateFromSystemUnlock(1_100L, deviceLocked = true))
        assertFalse(session.isAuthenticated())
    }

    @Test
    fun userPresentOnlyUnlocksAConversationThatStartedWithScreenOff() {
        val session = OrganizationAccessSession()
        assertFalse(session.authenticateFromUserPresent())

        session.markAuthenticated()
        session.invalidateForScreenLock(screenOffElapsedRealtimeMs = 1_000L)

        assertTrue(session.isAwaitingSystemUnlock())
        assertTrue(session.authenticateFromUserPresent())
        assertTrue(session.isAuthenticated())
        assertFalse(session.isAwaitingSystemUnlock())
        assertFalse(session.authenticateFromUserPresent())
    }

    @Test
    fun screenOffDoesNotCreateAnUnlockHandoffForAnAlreadyLockedSession() {
        val session = OrganizationAccessSession()
        session.invalidateForScreenLock(screenOffElapsedRealtimeMs = 1_000L)

        assertFalse(session.authenticateFromUserPresent())
        assertFalse(
            session.authenticateFromSystemUnlock(
                authenticationElapsedRealtimeMs = 1_100L,
                deviceLocked = false,
            )
        )
        assertFalse(session.isAuthenticated())
    }

    @Test
    fun configurationChangePreservesAuthenticatedSession() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()

        assertTrue(session.activityStopped(isChangingConfigurations = true))
        assertTrue(session.isAuthenticated())
    }

    @Test
    fun processSessionCannotStartOverlappingTrustedFlows() {
        val session = OrganizationAccessSession()
        assertFalse(session.beginTrustedExternalFlow(OrganizationExternalFlow.ARCHIVE_DIRECTORY_PICKER))

        session.markAuthenticated()
        assertTrue(session.beginTrustedExternalFlow(OrganizationExternalFlow.ARCHIVE_DIRECTORY_PICKER))
        assertFalse(session.beginTrustedExternalFlow(OrganizationExternalFlow.ARCHIVE_DIRECTORY_PICKER))
    }

    @Test
    fun completedDeviceUnlockAfterScreenLockUnlocksWithoutSecondPrompt() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()
        session.invalidateForScreenLock(1_000L)
        // Still on the lock screen: nothing granted, handoff kept.
        assertFalse(session.authenticateFromCompletedSystemUnlock(deviceSecure = true, deviceLocked = true))
        assertTrue(session.isAwaitingSystemUnlock())
        // OS reports the device unlocked: accepted once, handoff consumed.
        assertTrue(session.authenticateFromCompletedSystemUnlock(deviceSecure = true, deviceLocked = false))
        assertTrue(session.isAuthenticated())
        assertFalse(session.isAwaitingSystemUnlock())
        assertFalse(session.authenticateFromCompletedSystemUnlock(deviceSecure = true, deviceLocked = false))
    }

    @Test
    fun completedDeviceUnlockNeverAuthenticatesWithoutAScreenLockHandoff() {
        // Fresh process (never authenticated) or explicit lock: the unlocked device alone grants nothing.
        val fresh = OrganizationAccessSession()
        assertFalse(fresh.authenticateFromCompletedSystemUnlock(deviceSecure = true, deviceLocked = false))
        assertFalse(fresh.isAuthenticated())
        val locked = OrganizationAccessSession()
        locked.markAuthenticated()
        locked.invalidateForScreenLock(1_000L)
        locked.invalidate()
        assertFalse(locked.authenticateFromCompletedSystemUnlock(deviceSecure = true, deviceLocked = false))
        // A device without a secure lock screen cannot hand off authentication.
        val insecure = OrganizationAccessSession()
        insecure.markAuthenticated()
        insecure.invalidateForScreenLock(1_000L)
        assertFalse(insecure.authenticateFromCompletedSystemUnlock(deviceSecure = false, deviceLocked = false))
    }

    @Test
    fun promptCancelledByScreenLockKeepsTheUnlockHandoff() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()
        session.invalidateForScreenLock(1_000L)
        session.authenticationPromptAbandoned(cancelledByScreenLock = true)
        assertTrue(session.isAwaitingSystemUnlock())
        assertTrue(session.authenticateFromCompletedSystemUnlock(deviceSecure = true, deviceLocked = false))

        val declined = OrganizationAccessSession()
        declined.markAuthenticated()
        declined.invalidateForScreenLock(1_000L)
        declined.authenticationPromptAbandoned(cancelledByScreenLock = false)
        assertFalse(declined.isAwaitingSystemUnlock())
        assertFalse(declined.authenticateFromCompletedSystemUnlock(deviceSecure = true, deviceLocked = false))
    }

    @Test
    fun accessRequestPromptsOnlyWhenNoSessionOrUnlockHandoffApplies() {
        fun action(authenticated: Boolean, awaiting: Boolean, secure: Boolean = true, locked: Boolean, user: Boolean = false) =
            organizationAccessRequestAction(authenticated, awaiting, secure, locked, user)
        // Already authenticated (e.g. a recreated activity): show content, never prompt.
        assertEquals(OrganizationAccessRequestAction.SHOW_UNLOCKED, action(authenticated = true, awaiting = false, locked = false))
        // Handoff armed and the OS says unlocked: accept the device unlock.
        assertEquals(OrganizationAccessRequestAction.ACCEPT_SYSTEM_UNLOCK, action(authenticated = false, awaiting = true, locked = false))
        assertEquals(OrganizationAccessRequestAction.ACCEPT_SYSTEM_UNLOCK, action(authenticated = false, awaiting = true, locked = false, user = true))
        // Handoff armed but the OS still reports locked: automatic requests wait behind the cover...
        assertEquals(OrganizationAccessRequestAction.WAIT_FOR_SYSTEM_UNLOCK, action(authenticated = false, awaiting = true, locked = true))
        // ...while an explicit Unlock tap still prompts, so the operator is never stuck.
        assertEquals(OrganizationAccessRequestAction.PROMPT, action(authenticated = false, awaiting = true, locked = true, user = true))
        // No handoff (fresh start, explicit lock): prompt.
        assertEquals(OrganizationAccessRequestAction.PROMPT, action(authenticated = false, awaiting = false, locked = false))
        assertEquals(OrganizationAccessRequestAction.PROMPT, action(authenticated = false, awaiting = true, secure = false, locked = false, user = true))
    }
}
