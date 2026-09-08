package org.ncssar.rid2caltopo.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R2CActivityLogArchiveTest {
    @Test
    fun trackerReauthenticationCallbackIsConsumedWithoutRecreatingDisclaimer() {
        val activitySource = projectSource(
            "app/src/main/java/org/ncssar/rid2caltopo/app/R2CActivity.kt"
        )
        val handlerStart = activitySource.indexOf("if (uri.scheme == \"r2creauth\")")
        val handlerEnd = activitySource.indexOf(
            "TrackerEnrollmentClient.normalizedEnrollmentUrl",
            startIndex = handlerStart,
        )
        assertTrue(handlerStart >= 0 && handlerEnd > handlerStart)
        val handler = activitySource.substring(handlerStart, handlerEnd)

        assertTrue(handler.contains("setIntent(Intent(this, R2CActivity::class.java)"))
        assertTrue(handler.contains("reconcileDeviceAuthorizationAfterReauthentication()"))
        assertFalse(handler.contains(".resumeAfterReauthentication()"))
        assertFalse(handler.contains("recreate()"))
    }

    @Test
    fun temporaryLocationOverrideClearsForNewTaskButNotConfigurationRecreation() {
        assertTrue(shouldClearTemporaryLocationOverrideOnCreate(hasSavedInstanceState = false))
        assertFalse(shouldClearTemporaryLocationOverrideOnCreate(hasSavedInstanceState = true))
    }

    @Test
    fun buildLogArchiveEntryName_keepsExistingTxtSuffix() {
        assertEquals("Log_081054Apr13.txt", buildLogArchiveEntryName("Log_081054Apr13.txt"))
    }

    @Test
    fun buildLogArchiveEntryName_addsMissingTxtSuffix() {
        assertEquals("Log_081054Apr13.txt", buildLogArchiveEntryName("Log_081054Apr13"))
    }

    @Test
    fun buildLogArchiveEntryName_collapsesLegacyDuplicateTxtSuffix() {
        assertEquals("Log_081054Apr13.txt", buildLogArchiveEntryName("Log_081054Apr13.txt.txt"))
    }

    @Test
    fun buildLogArchiveEntryName_defaultsBlankNames() {
        assertEquals("log_unknown.txt", buildLogArchiveEntryName(" "))
    }

    @Test
    fun diagnosticBundleIncludesTextLogsAndJsonTracksOnly() {
        assertTrue(isDiagnosticBundleFile("Log_22Aug.txt", "text/plain"))
        assertTrue(isDiagnosticBundleFile("1SAR7-track.json", "application/json"))
        assertFalse(isDiagnosticBundleFile("flight.mp4", "video/mp4"))
        assertFalse(isDiagnosticBundleFile("clues.kmz", "application/vnd.google-earth.kmz"))
    }

    @Test
    fun diagnosticBundlePreservesTrackPathAndNormalizesLogSuffix() {
        assertEquals(
            "1sar7/track.json",
            buildDiagnosticArchiveEntryName("1sar7/track.json", "application/json")
        )
        assertEquals(
            "Log_22Aug.txt",
            buildDiagnosticArchiveEntryName("Log_22Aug.txt.txt", "text/plain")
        )
    }

    @Test
    fun diagnosticBundleRedactsCoordinatesFromHistoricalTextLogs() {
        val text = """
            INFO Location Published lat=39.153017 lng=-121.133046 accuracy=7
            INFO Location authorization changed
            DEBUG NOTAM geometry={"coordinates":[-121.72,38.21]}
        """.trimIndent() + "\n"

        assertEquals(
            "[location details redacted]\n" +
                "INFO Location authorization changed\n" +
                "[location details redacted]\n",
            redactDiagnosticLogText(text),
        )
    }

    @Test
    fun trackerReauthenticationOpensOnlyOneBrowserAttemptAtATime() {
        assertTrue(shouldOpenTrackerReauthentication(browserAlreadyOpen = false))
        assertFalse(shouldOpenTrackerReauthentication(browserAlreadyOpen = true))
    }

    @Test
    fun returningFromTrackerBrowserRetriesOnlyWhenReauthenticationIsPending() {
        assertTrue(
            shouldRetryTrackerReauthenticationAfterBrowserReturn(
                browserWasOpen = true,
                pendingUrl = "https://r2c-tracker.com/reauth",
            )
        )
        assertFalse(
            shouldRetryTrackerReauthenticationAfterBrowserReturn(
                browserWasOpen = false,
                pendingUrl = "https://r2c-tracker.com/reauth",
            )
        )
        assertFalse(
            shouldRetryTrackerReauthenticationAfterBrowserReturn(
                browserWasOpen = true,
                pendingUrl = null,
            )
        )
        assertFalse(
            shouldRetryTrackerReauthenticationAfterBrowserReturn(
                browserWasOpen = true,
                pendingUrl = "",
            )
        )
    }

    @Test
    fun trackerReauthenticationPresentsAnExplicitSignInAction() {
        val activitySource = projectSource(
            "app/src/main/java/org/ncssar/rid2caltopo/app/R2CActivity.kt"
        )

        assertTrue(activitySource.contains("Tracker sign-in required"))
        assertTrue(activitySource.contains("Text(\"Sign in\")"))
        assertTrue(activitySource.contains("Text(\"Continue offline\")"))
        assertTrue(activitySource.contains("onSignIn = ::openPendingTrackerReauthentication"))
    }

    @Test
    fun archivePickerUsesTrustedTransitionAndScreenLockRevokesAuthentication() {
        val activitySource = projectSource(
            "app/src/main/java/org/ncssar/rid2caltopo/app/R2CActivity.kt"
        )
        val mainScreenSource = projectSource(
            "app/src/main/java/org/ncssar/rid2caltopo/ui/MainScreen.kt"
        )

        assertTrue(activitySource.contains("Intent.ACTION_SCREEN_OFF"))
        assertTrue(activitySource.contains("Intent.ACTION_USER_PRESENT"))
        assertTrue(
            activitySource.contains(
                "organizationAccessSession.invalidateForScreenLock(SystemClock.elapsedRealtime())"
            )
        )
        assertTrue(activitySource.contains("acceptSystemAuthenticationAfterScreenUnlock()"))
        assertTrue(activitySource.contains("onArchiveDirPickerStarted = ::beginArchiveDirectoryPicker"))
        assertTrue(activitySource.contains("onArchiveDirPickerFinished = ::finishArchiveDirectoryPicker"))
        assertTrue(mainScreenSource.contains("onArchiveDirPickerStarted()"))
        assertTrue(mainScreenSource.contains("onArchiveDirPickerFinished()"))
        assertFalse(activitySource.contains("ORGANIZATION_ACCESS_BACKGROUND_GRACE_MSEC"))
    }

    private fun projectSource(relativePath: String): String {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(
            File(workingDirectory, relativePath),
            File(workingDirectory.parentFile ?: workingDirectory, relativePath),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate $relativePath from $workingDirectory"
        }.readText()
    }
}
