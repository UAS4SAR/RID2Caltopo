package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.ui.ActiveScreen

class R2CActivityShutdownPolicyTest {
    @Test
    fun systemBackRequestsExitFromUnifiedWorkspace() {
        assertEquals(
            AppBackAction.REQUEST_EXIT_CONFIRMATION,
            appBackAction(ActiveScreen.MAIN),
        )
        assertEquals(AppBackAction.REQUEST_EXIT_CONFIRMATION, appBackAction(ActiveScreen.STREAMS))
        listOf(ActiveScreen.SETTINGS, ActiveScreen.SCANNER).forEach {
            assertEquals(AppBackAction.RETURN_TO_MAIN, appBackAction(it))
        }
    }

    @Test
    fun taskRemovalShutsDownWithoutExplicitQuitRequest() {
        assertTrue(
            shouldShutdownOnActivityDestroy(
                isPrimaryActivity = true,
                isFinishing = true,
                isChangingConfigurations = false,
            )
        )
    }

    @Test
    fun configurationRecreationDoesNotShutDownApplication() {
        assertFalse(
            shouldShutdownOnActivityDestroy(
                isPrimaryActivity = true,
                isFinishing = true,
                isChangingConfigurations = true,
            )
        )
    }

    @Test
    fun transientOrSecondaryActivityDestructionDoesNotShutDownApplication() {
        assertFalse(
            shouldShutdownOnActivityDestroy(
                isPrimaryActivity = true,
                isFinishing = false,
                isChangingConfigurations = false,
            )
        )
        assertFalse(
            shouldShutdownOnActivityDestroy(
                isPrimaryActivity = false,
                isFinishing = true,
                isChangingConfigurations = false,
            )
        )
    }
}
