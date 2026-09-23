package org.ncssar.rid2caltopo.notam

import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.airspace.AirspaceChipSeverity
import org.ncssar.rid2caltopo.airspace.AirspaceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotamPolicyTest {
    @Test
    fun expiryDoesNotPrecedeTheConfiguredRefreshInterval() {
        assertFalse(NotamPolicy.isStale(1_000L, 181_001L, 1_800))
        assertFalse(NotamPolicy.isStale(1_000L, 3_601_000L, 1_800))
        assertTrue(NotamPolicy.isStale(1_000L, 3_601_001L, 1_800))
        assertTrue(NotamPolicy.isStale(1_000L, 601_001L, 30))
        assertFalse(NotamPolicy.isStale(0L, 9_999_999L, 30))
    }

    @Test
    fun compactLabelsPreserveQueryResultsAndFailureInsteadOfClaimingClear() {
        assertEquals("NOTAMs: 2 nearby", conciseSafetyStatusLabel(false, NotamChipSeverity.Normal, "NOTAMs: 2 nearby"))
        assertEquals("NOTAMs stale • review", conciseSafetyStatusLabel(false, NotamChipSeverity.Danger, "NOTAMs stale • review"))
        assertEquals("NOTAMs updating...", conciseSafetyStatusLabel(false, NotamChipSeverity.Normal, "NOTAMs updating..."))
        assertEquals("No notices returned", NotamPolicy.chipLabel(emptyList(), true, false, false))
        assertEquals("NOTAMs unavailable", NotamPolicy.chipLabel(emptyList(), true, false, true))
        val cached = NearbyNotam(id = "tfr", title = "TFR", summary = "Restriction", distanceNm = 0.0, intersectsPilotBubble = true, severity = NotamChipSeverity.Danger)
        assertEquals(NotamChipSeverity.Danger, NotamPolicy.effectiveChipSeverity(listOf(cached), true, true))
        assertEquals("NOTAMs stale • review", NotamPolicy.chipLabel(listOf(cached), true, false, true))
    }

    @Test
    fun runtimeResetClearsNotamResultAndRequiresConfigurationAgain() {
        CaltopoClient.ResetPersistedClientState()

        NotamCenter.resetRuntimeState()

        assertEquals("NOTAMs not configured", NotamCenter.uiState.value.chipLabel)
        assertFalse(NotamCenter.uiState.value.configured)
        assertTrue(NotamCenter.uiState.value.notices.isEmpty())
        assertEquals(0L, CaltopoClient.GetNotamLastUpdatedEpochMs())
    }

    @Test
    fun toolbarUsesBriefAirspaceLabelWhileStateRetainsAirportDetails() {
        assertEquals(
            "Authorization required",
            conciseSafetyStatusLabel(
                useAirspaceLabel = true,
                severity = NotamChipSeverity.Danger,
                detailedLabel = "Airspace: Authorization required - KBOI Class C 100 ft grid"
            )
        )
        assertEquals(
            "Airspace nearby",
            conciseSafetyStatusLabel(
                useAirspaceLabel = true,
                severity = NotamChipSeverity.Caution,
                detailedLabel = "Airspace nearby - Gowen Field Class C 0.4 mi"
            )
        )
    }

    @Test
    fun pendingAirspaceStatusOverridesClearNotamLabel() {
        assertTrue(
            shouldUseAirspaceStatus(
                notamVisible = true,
                airspaceState = AirspaceUiState(
                    chipSeverity = AirspaceChipSeverity.Neutral,
                    chipLabel = "Airspace updating..."
                )
            )
        )
        assertFalse(
            shouldUseAirspaceStatus(
                notamVisible = true,
                airspaceState = AirspaceUiState(
                    chipSeverity = AirspaceChipSeverity.Normal,
                    chipLabel = "No facility grids returned"
                )
            )
        )
    }

    @Test
    fun normalAirspaceDoesNotMaskVisibleDisabledNotamStatus() {
        assertFalse(
            shouldUseAirspaceStatus(
                notamVisible = true,
                airspaceState = AirspaceUiState(
                    chipSeverity = AirspaceChipSeverity.Normal,
                    chipLabel = "No facility grids returned"
                )
            )
        )
    }

    @Test
    fun intersectingNonRestrictiveNoticeIsCautionNoticeNotRedRestriction() {
        val notice = NearbyNotam(
            id = "KZSE-6-7414",
            title = "Airspace service notice",
            summary = "Service unusable below 8000 ft.",
            distanceNm = 0.0,
            intersectsPilotBubble = true,
            severity = NotamChipSeverity.Normal
        )

        assertEquals(
            NotamChipSeverity.Caution,
            NotamPolicy.effectiveChipSeverity(
                notices = listOf(notice),
                configured = true,
                hasError = false
            )
        )
        assertEquals(
            "NOTAMs: NOTICE 0.0 mi",
            NotamPolicy.chipLabel(
                notices = listOf(notice),
                configured = true,
                loading = false,
                hasError = false
            )
        )
    }

    @Test
    fun intersectingDangerNoticeKeepsRedRestrictedLabel() {
        val notice = NearbyNotam(
            id = "restricted",
            title = "UAS airspace restriction",
            summary = "Restriction from surface to 8000 ft.",
            distanceNm = 0.0,
            intersectsPilotBubble = true,
            severity = NotamChipSeverity.Danger
        )

        assertEquals(
            NotamChipSeverity.Danger,
            NotamPolicy.effectiveChipSeverity(
                notices = listOf(notice),
                configured = true,
                hasError = false
            )
        )
        assertEquals(
            "NOTAMs: RESTRICTED 0.0 mi",
            NotamPolicy.chipLabel(
                notices = listOf(notice),
                configured = true,
                loading = false,
                hasError = false
            )
        )
    }

    @Test
    fun pendingProxyPlaceholderIsNotCountedAsNearbyNotam() {
        val placeholder = NearbyNotam(
            id = "pending_credentials",
            title = "FAA NOTAM proxy pending",
            summary = "Waiting for configuration",
            distanceNm = null,
            severity = NotamChipSeverity.Neutral
        )

        assertEquals(
            "NOTAMs not configured",
            NotamPolicy.chipLabel(
                notices = listOf(placeholder),
                configured = false,
                loading = false,
                hasError = false
            )
        )
    }

    @Test
    fun hiddenAirspaceDoesNotOverrideDisabledNotamStatus() {
        assertFalse(
            shouldUseAirspaceStatus(
                notamVisible = true,
                airspaceState = AirspaceUiState(
                    visible = false,
                    chipSeverity = AirspaceChipSeverity.Danger,
                    chipLabel = "Airspace warning"
                )
            )
        )
    }

    @Test
    fun oneStatuteMileRadiusDoesNotIncludeOneNauticalMileNotice() {
        val inside = NearbyNotam(
            id = "inside",
            title = "Inside",
            summary = "",
            distanceNm = 0.86
        )
        val outside = NearbyNotam(
            id = "outside",
            title = "Outside",
            summary = "",
            distanceNm = 1.0
        )

        val (visible, suppressed) = NotamPolicy.filterWithinRadius(
            notices = listOf(inside, outside),
            radiusStatuteMiles = 1
        )

        assertEquals(listOf(inside), visible)
        assertEquals(1, suppressed)
    }
}
