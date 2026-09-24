package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveDirPromptStateTest {
    @Test
    fun persistentResetImmediatelyRequestsRequiredSetupAndSafetyRefresh() {
        val calls = mutableListOf<String>()

        resetPersistedStateAndRequestRequiredSetup(
            resetState = { calls += "reset" },
            resetNotamRuntimeState = { calls += "notam-reset" },
            requestArchiveSelection = { calls += "archive" },
            requestNotamRefresh = { calls += "notam" },
            requestAirspaceRefresh = { calls += "airspace" }
        )

        assertTrue(calls == listOf("reset", "notam-reset", "notam", "airspace", "archive"))
    }

    @Test
    fun forcedArchiveSetupDoesNotWaitForDriveRestoreEligibility() {
        assertTrue(
            shouldLaunchArchiveDirPicker(
                archiveUriMissing = true,
                sessionArchiveDirAvailable = false,
                forceArchiveDirPrompt = true,
                driveRestoreEligibilityLoaded = false,
                showDriveRestoreDialog = false,
                driveSyncInProgress = false,
                archiveDirPickerOpen = false
            )
        )
    }

    @Test
    fun missingArchiveUriDoesNotLaunchAgainWhilePickerIsOpen() {
        assertTrue(
            shouldLaunchArchiveDirPicker(
                archiveUriMissing = true,
                sessionArchiveDirAvailable = false,
                forceArchiveDirPrompt = false,
                driveRestoreEligibilityLoaded = true,
                showDriveRestoreDialog = false,
                driveSyncInProgress = false,
                archiveDirPickerOpen = false
            )
        )

        assertFalse(
            shouldLaunchArchiveDirPicker(
                archiveUriMissing = true,
                sessionArchiveDirAvailable = false,
                forceArchiveDirPrompt = false,
                driveRestoreEligibilityLoaded = true,
                showDriveRestoreDialog = false,
                driveSyncInProgress = false,
                archiveDirPickerOpen = true
            )
        )
    }

    @Test
    fun missingArchiveUriDoesNotPromptWhenSessionArchiveDirIsAvailable() {
        assertFalse(
            shouldLaunchArchiveDirPicker(
                archiveUriMissing = false,
                sessionArchiveDirAvailable = true,
                forceArchiveDirPrompt = false,
                driveRestoreEligibilityLoaded = true,
                showDriveRestoreDialog = false,
                driveSyncInProgress = false,
                archiveDirPickerOpen = false
            )
        )
    }

    @Test
    fun explicitArchiveStorageRequestOverridesTemporarySessionStorage() {
        assertTrue(
            shouldLaunchArchiveDirPicker(
                archiveUriMissing = true,
                sessionArchiveDirAvailable = true,
                forceArchiveDirPrompt = true,
                driveRestoreEligibilityLoaded = true,
                showDriveRestoreDialog = false,
                driveSyncInProgress = false,
                archiveDirPickerOpen = false
            )
        )
    }

    @Test
    fun cancelledOrFailedPickerDoesNotAutomaticallyTrapUserInAnotherPrompt() {
        assertFalse(
            shouldLaunchArchiveDirPicker(
                archiveUriMissing = true,
                sessionArchiveDirAvailable = false,
                forceArchiveDirPrompt = false,
                driveRestoreEligibilityLoaded = true,
                showDriveRestoreDialog = false,
                driveSyncInProgress = false,
                archiveDirPickerOpen = false,
                archiveDirPromptSuppressed = true
            )
        )
    }

    @Test
    fun archiveDirPromptMessagesExplainWhyTheSystemPickerWillOpen() {
        val initial = archiveDirPromptMessage(permissionMissing = false)
        assertTrue(initial.contains("archive folder", ignoreCase = true))
        assertTrue(initial.contains("drone tracks", ignoreCase = true))
        assertTrue(initial.contains("logs", ignoreCase = true))
        assertTrue(initial.contains("map data", ignoreCase = true))

        val expired = archiveDirPromptMessage(
            permissionMissing = true,
            previousArchivePath = "sdcard:/Documents/DroneTrax"
        )
        assertTrue(expired.contains("uninstalled", ignoreCase = true))
        assertTrue(expired.contains("existing files are still there", ignoreCase = true))
        assertTrue(expired.contains("reauthorize", ignoreCase = true))
        assertTrue(expired.contains("sdcard:/Documents/DroneTrax", ignoreCase = true))
        assertTrue(expired.contains("drone tracks", ignoreCase = true))
        assertTrue(expired.contains("logs", ignoreCase = true))
        assertTrue(expired.contains("map data", ignoreCase = true))
    }

    @Test
    fun archiveDirDisplayPathMakesAndroidTreeUriOperatorReadable() {
        assertEquals(
            "sdcard:/Drone Trax",
            archiveDirDisplayPath(
                "content://com.android.externalstorage.documents/tree/primary%3ADrone%20Trax"
            )
        )
        assertEquals(
            "1234-5678:/R2C Archive",
            archiveDirDisplayPath(
                "content://com.android.externalstorage.documents/tree/1234-5678%3AR2C%20Archive"
            )
        )
        assertNull(archiveDirDisplayPath(null))
    }

    @Test
    fun expiredArchiveGrantOffersOnlyPersistentFolderChoices() {
        val expired = archiveDirPromptActions(
            permissionMissing = true,
            previousArchivePath = "sdcard:/Drone Trax"
        )
        assertEquals("Reauthorize existing folder", expired.continueLabel)
        assertEquals("Choose another folder", expired.chooseDifferentLabel)

        val initialSetup = archiveDirPromptActions(
            permissionMissing = false,
            previousArchivePath = null
        )
        assertEquals("Choose archive folder", initialSetup.continueLabel)
        assertEquals("Set up later", initialSetup.chooseDifferentLabel)

        val configured = archiveDirPromptActions(
            permissionMissing = false,
            previousArchivePath = "sdcard:/Documents/DroneTrax"
        )
        assertEquals("Choose another folder", configured.continueLabel)
        assertEquals("Keep current folder", configured.chooseDifferentLabel)

        val configuredMessage = archiveDirPromptMessage(
            permissionMissing = false,
            previousArchivePath = "sdcard:/Documents/DroneTrax"
        )
        assertTrue(configuredMessage.contains("currently saved", ignoreCase = true))
        assertTrue(configuredMessage.contains("sdcard:/Documents/DroneTrax"))
    }

    @Test
    fun archiveSelectionIsAcceptedOnlyAfterPermissionActivationAndVerification() {
        val calls = mutableListOf<String>()

        val result = persistArchiveDirSelection(
            selection = "archive",
            persistPermission = { calls += "persist:$it" },
            activateSelection = { calls += "activate:$it" },
            selectionIsUsable = {
                calls += "verify:$it"
                true
            }
        )

        assertEquals(ArchiveDirSelectionResult.Selected, result)
        assertEquals(listOf("persist:archive", "activate:archive", "verify:archive"), calls)
    }

    @Test
    fun cancelledArchiveSelectionHasAnExplicitNonLoopingOutcome() {
        val result = persistArchiveDirSelection<String>(
            selection = null,
            persistPermission = { throw AssertionError("must not persist") },
            activateSelection = { throw AssertionError("must not activate") },
            selectionIsUsable = { throw AssertionError("must not verify") }
        )

        assertEquals(ArchiveDirSelectionResult.Cancelled, result)
    }

    @Test
    fun rejectedArchiveGrantBecomesARecoverableFailure() {
        val rejected = persistArchiveDirSelection(
            selection = "archive",
            persistPermission = {},
            activateSelection = {},
            selectionIsUsable = { false }
        )
        assertTrue(rejected is ArchiveDirSelectionResult.Failed)

        val exception = persistArchiveDirSelection(
            selection = "archive",
            persistPermission = { throw SecurityException("grant rejected") },
            activateSelection = {},
            selectionIsUsable = { true }
        )
        assertEquals(
            ArchiveDirSelectionResult.Failed("grant rejected"),
            exception
        )
    }
    @Test
    fun keepingRetainedFolderRestoresSelectionAfterResetAndSuppressesNextStartupPrompt() {
        val retainedHint = "sdcard:/DroneTrax"
        var persistedSelection: String? = null
        var activeSelection: String? = null
        val result = keepArchiveDirSelection(
            selection = retainedHint,
            selectionIsAuthorized = { it == retainedHint },
            activateSelection = { activeSelection = it; persistedSelection = it },
            selectionIsActive = { activeSelection == it }
        )
        assertEquals(ArchiveDirSelectionResult.Selected, result)
        // A new session loads the durable choice, rather than relying on dialog dismissal.
        activeSelection = persistedSelection
        assertEquals(retainedHint, activeSelection)
        assertFalse(shouldLaunchArchiveDirPicker(
            archiveUriMissing = activeSelection == null,
            sessionArchiveDirAvailable = activeSelection != null,
            forceArchiveDirPrompt = false,
            driveRestoreEligibilityLoaded = true,
            showDriveRestoreDialog = false,
            driveSyncInProgress = false,
            archiveDirPickerOpen = false
        ))
    }

    @Test
    fun retainedHintWithoutAndroidPermissionCannotBeActivated() {
        var activated = false
        val result = keepArchiveDirSelection(
            selection = "old folder",
            selectionIsAuthorized = { false },
            activateSelection = { activated = true },
            selectionIsActive = { true }
        )
        assertTrue(result is ArchiveDirSelectionResult.Failed)
        assertFalse(activated)
    }

}
