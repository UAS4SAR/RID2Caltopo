package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageNavigationSwipeTest {
    @Test fun returnSwipeClaimsBeforeTheHeaderScrollerButNotOnATapOrVerticalDrag() {
        assertTrue(shouldClaimPageNavigationSwipe(20f, 2f, 8f, false))
        assertFalse(isPageNavigationSwipe(20f, 2f, false))
        assertFalse(shouldClaimPageNavigationSwipe(4f, 0f, 8f, false))
        assertFalse(shouldClaimPageNavigationSwipe(-20f, 0f, 8f, false))
        assertFalse(shouldClaimPageNavigationSwipe(12f, 20f, 8f, false))
        assertTrue(shouldClaimPageNavigationSwipe(-20f, 2f, 8f, true))
    }

    @Test fun navigationRequiresDeliberateHorizontalMotionInTheCorrectDirection() {
        assertTrue(isPageNavigationSwipe(-100f, 10f, true))
        assertTrue(isPageNavigationSwipe(100f, -10f, false))
        assertFalse(isPageNavigationSwipe(100f, 0f, true))
        assertFalse(isPageNavigationSwipe(-100f, 0f, false))
        assertFalse(isPageNavigationSwipe(-60f, 0f, true))
        assertFalse(isPageNavigationSwipe(100f, 70f, false))
        assertFalse(isPageNavigationSwipe(0f, 150f, false))
    }
}
